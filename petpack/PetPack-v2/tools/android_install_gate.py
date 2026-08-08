#!/usr/bin/env python3
"""Run a real PetPack through the Android production installer on a test device."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Sequence


APP_PACKAGE = "com.sweetgirlfriend.pet"
TEST_PACKAGE = f"{APP_PACKAGE}.test"
INSTRUMENTATION = (
    f"{TEST_PACKAGE}/com.sweetgirlfriend.pet.app.PetPackInstallGateInstrumentation"
)
PASS_PATTERN = re.compile(
    r"PETPACK_GATE_PASS id=(?P<id>[a-z0-9][a-z0-9._-]*) "
    r"version=(?P<version>\d+\.\d+\.\d+) actions=(?P<actions>\d+) tasks=(?P<tasks>\d+)"
)
STAGING_PATTERN = re.compile(r"PETPACK_GATE_STAGING=(?P<path>\S+)")
VIVO_SCANNER_COMPAT_PASS = "VIVO_SCANNER_COMPAT_PASS"


class GateFailure(RuntimeError):
    pass


def _run(command: Sequence[str], *, timeout: int, cwd: Path | None = None) -> str:
    completed = subprocess.run(
        list(command),
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )
    if completed.returncode != 0:
        rendered = subprocess.list2cmdline(list(command))
        raise GateFailure(
            f"Command failed ({completed.returncode}): {rendered}\n{completed.stdout.strip()}"
        )
    return completed.stdout


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _default_android_project() -> Path:
    repository_root = Path(__file__).resolve().parents[3]
    return repository_root / "android" / "SweetGirlfriendPetAndroid"


def _find_adb(explicit: Path | None) -> Path:
    if explicit:
        candidate = explicit.expanduser().resolve()
    else:
        executable = "adb.exe" if os.name == "nt" else "adb"
        from_path = shutil.which(executable)
        sdk_root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        candidates = [
            Path(from_path) if from_path else None,
            Path(sdk_root) / "platform-tools" / executable if sdk_root else None,
        ]
        candidate = next((item for item in candidates if item and item.is_file()), Path())
    if not candidate.is_file():
        raise GateFailure("adb was not found; pass --adb or configure ANDROID_SDK_ROOT")
    return candidate


def _select_device(adb: Path, serial: str | None) -> str:
    if serial:
        return serial
    output = _run([str(adb), "devices"], timeout=15)
    devices = [
        line.split("\t", 1)[0]
        for line in output.splitlines()
        if "\tdevice" in line
    ]
    if len(devices) != 1:
        raise GateFailure(
            f"Install gate requires exactly one online device; found {len(devices)}. Pass --serial."
        )
    return devices[0]


def _device_type(adb_command: Sequence[str]) -> str:
    qemu_property = _run(
        list(adb_command) + ["shell", "getprop", "ro.kernel.qemu"],
        timeout=15,
    ).strip()
    return "emulator" if qemu_property == "1" else "physical"


def _device_summary(report: dict[str, object]) -> str:
    device_type = str(report["deviceType"])
    serial = str(report["deviceSerial"])
    if device_type == "physical":
        return f"physical device {serial} (explicitly allowed)"
    return f"emulator {serial}"


def _run_vivo_scanner_compatibility_gate(adb_command: Sequence[str]) -> None:
    output = _run(
        list(adb_command)
        + [
            "shell",
            "am",
            "instrument",
            "-w",
            "-e",
            "vivoScannerCompatOnly",
            "true",
            INSTRUMENTATION,
        ],
        timeout=120,
    )
    if "FAIL" in output:
        failure = next(
            (line.strip() for line in output.splitlines() if "FAIL" in line),
            "scanner compatibility instrumentation reported FAIL",
        )
        raise GateFailure(f"vivo scanner compatibility gate failed: {failure}")
    if VIVO_SCANNER_COMPAT_PASS not in output:
        raise GateFailure(
            "vivo scanner compatibility gate did not report "
            f"{VIVO_SCANNER_COMPAT_PASS}:\n{output.strip()}"
        )


def _app_apk_root(android_project: Path) -> Path:
    """Resolve app APK output for both redirected and standard Gradle layouts."""
    properties = android_project / "gradle.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("externalBuildRoot="):
            external_root = (android_project / line.split("=", 1)[1].strip()).resolve()
            return external_root / "app" / "outputs" / "apk"
    return (android_project / "app" / "build" / "outputs" / "apk").resolve()


def _build_test_apks(android_project: Path) -> tuple[Path, Path]:
    wrapper = android_project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise GateFailure(f"Gradle wrapper not found: {wrapper}")
    wrapper_command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    _run(
        wrapper_command
        + [
            "--no-daemon",
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            "--console=plain",
        ],
        timeout=600,
        cwd=android_project,
    )
    root = _app_apk_root(android_project)
    app_apk = root / "debug" / "app-debug.apk"
    test_apk = root / "androidTest" / "debug" / "app-debug-androidTest.apk"
    if not app_apk.is_file() or not test_apk.is_file():
        raise GateFailure(f"Android gate APKs were not generated below {root}")
    return app_apk, test_apk


def run_android_install_gate(
    archive: Path,
    *,
    android_project: Path | None = None,
    adb: Path | None = None,
    serial: str | None = None,
    skip_build: bool = False,
    allow_physical_device: bool = False,
) -> dict[str, object]:
    archive = archive.expanduser().resolve()
    if not archive.is_file():
        raise GateFailure(f"PetPack archive not found: {archive}")
    android_project = (android_project or _default_android_project()).expanduser().resolve()
    adb = _find_adb(adb)
    serial = _select_device(adb, serial)
    adb_command = [str(adb), "-s", serial]
    device_type = _device_type(adb_command)
    if device_type == "physical" and not allow_physical_device:
        raise GateFailure(
            "Android install gate defaults to an emulator "
            "(ro.kernel.qemu must equal 1). This target appears to be a physical device; "
            "pass --allow-physical-device to authorize it explicitly."
        )

    build_root = _app_apk_root(android_project)
    if skip_build:
        app_apk = build_root / "debug" / "app-debug.apk"
        test_apk = build_root / "androidTest" / "debug" / "app-debug-androidTest.apk"
        if not app_apk.is_file() or not test_apk.is_file():
            raise GateFailure("--skip-build requested but gate APKs do not exist")
    else:
        app_apk, test_apk = _build_test_apks(android_project)

    _run(adb_command + ["install", "-r", str(app_apk)], timeout=120)
    _run(adb_command + ["install", "-r", str(test_apk)], timeout=120)
    _run_vivo_scanner_compatibility_gate(adb_command)
    prepare_output = _run(
        adb_command
        + ["shell", "am", "instrument", "-w", "-r", "-e", "prepareOnly", "true", INSTRUMENTATION],
        timeout=60,
    )
    staging_match = STAGING_PATTERN.search(prepare_output)
    if not staging_match:
        raise GateFailure(f"Device gate did not return a staging path:\n{prepare_output.strip()}")
    staging = staging_match.group("path")
    archive_hash = _sha256(archive)
    device_archive = f"{staging}/candidate-{archive_hash[:16]}.petpack"
    try:
        _run(adb_command + ["push", str(archive), device_archive], timeout=180)
        gate_output = _run(
            adb_command
            + [
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "expectedSha256",
                archive_hash,
                "-e",
                "packPath",
                device_archive,
                INSTRUMENTATION,
            ],
            timeout=300,
        )
        if "PETPACK_GATE_FAIL" in gate_output:
            failure = next(
                (line.strip() for line in gate_output.splitlines() if "PETPACK_GATE_FAIL" in line),
                "PETPACK_GATE_FAIL",
            )
            raise GateFailure(failure)
        passed = PASS_PATTERN.search(gate_output)
        if not passed:
            raise GateFailure(f"Device gate did not report PASS:\n{gate_output.strip()}")
        return {
            "schemaVersion": 1,
            "result": "pass",
            "archive": str(archive),
            "archiveSha256": archive_hash,
            "archiveSizeBytes": archive.stat().st_size,
            "deviceSerial": serial,
            "deviceType": device_type,
            "physicalDeviceExplicitlyAllowed": (
                device_type == "physical" and allow_physical_device
            ),
            "vivoScannerCompatibility": "pass",
            "packId": passed.group("id"),
            "version": passed.group("version"),
            "actions": int(passed.group("actions")),
            "tasks": int(passed.group("tasks")),
        }
    finally:
        subprocess.run(
            adb_command + ["shell", "rm", "-f", device_archive],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )


def _write_report(path: Path, report: dict[str, object]) -> None:
    path = path.expanduser().resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=path.parent, prefix=f".{path.name}.", delete=False
    ) as stream:
        temporary = Path(stream.name)
        stream.write(payload)
    os.replace(temporary, path)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify a real .petpack with the production Android inspector/installer/loader",
    )
    parser.add_argument("archive", type=Path)
    parser.add_argument("--android-project", type=Path)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--serial")
    parser.add_argument(
        "--allow-physical-device",
        action="store_true",
        help="Explicitly authorize running the install gate on a physical device",
    )
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = run_android_install_gate(
            args.archive,
            android_project=args.android_project,
            adb=args.adb,
            serial=args.serial,
            skip_build=args.skip_build,
            allow_physical_device=args.allow_physical_device,
        )
        if args.report:
            _write_report(args.report, report)
        print(
            "Android install gate PASS: "
            f"{report['packId']} v{report['version']} "
            f"({report['actions']} actions, {report['tasks']} tasks)"
        )
        print(f"Gate device: {_device_summary(report)}")
        return 0
    except (GateFailure, subprocess.TimeoutExpired) as error:
        print(f"Android install gate FAILED: {error}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
