#!/usr/bin/env python3
"""Repository-level iteration, packaging, and deployment orchestration.

The component tools remain authoritative.  This module only composes them into
repeatable stages, writes one run report, and keeps deliverables in an owned
output directory.  Subprocesses always receive argv arrays with ``shell=False``.
"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import datetime as dt
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence


SCHEMA_VERSION = 1
OWNER_MARKER = ".sweetpet-run"
PACK_ID_RE = re.compile(r"[a-z0-9][a-z0-9_-]{0,63}\Z")
RUN_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,95}\Z")
INTAKE_ID_RE = re.compile(r"[a-z0-9][a-z0-9_-]{0,63}\Z")
STABLE_VERSION_RE = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
FIXED_ZIP_TIME = (2020, 1, 1, 0, 0, 0)


class PipelineError(RuntimeError):
    """A user-facing, fail-closed pipeline error."""


@dataclasses.dataclass(frozen=True)
class StageDefinition:
    name: str
    component: str
    dependencies: tuple[str, ...]
    handler: Callable[["PipelineContext"], None]


@dataclasses.dataclass
class StageResult:
    name: str
    status: str
    started_at: str | None = None
    duration_seconds: float = 0.0
    message: str = ""


class Executor:
    """Injectable subprocess boundary used by the pipeline and unit tests."""

    def run(
        self,
        argv: Sequence[str],
        *,
        cwd: Path,
        log_path: Path,
        env: Mapping[str, str] | None = None,
    ) -> int:
        raise NotImplementedError


class SubprocessExecutor(Executor):
    def run(
        self,
        argv: Sequence[str],
        *,
        cwd: Path,
        log_path: Path,
        env: Mapping[str, str] | None = None,
    ) -> int:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        command = [str(value) for value in argv]
        print(f"\n$ {format_command(command)}")
        print(f"  cwd: {cwd}")
        merged_env = os.environ.copy()
        merged_env["PYTHONUTF8"] = "1"
        if env:
            merged_env.update(env)
        with log_path.open("w", encoding="utf-8", newline="\n") as log:
            process = subprocess.Popen(
                command,
                cwd=str(cwd),
                env=merged_env,
                shell=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            assert process.stdout is not None
            for line in process.stdout:
                print(line, end="")
                log.write(line)
            return process.wait()


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def default_run_id() -> str:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{stamp}-{os.getpid()}"


def repository_root() -> Path:
    return Path(__file__).resolve().parents[1]


def format_command(argv: Sequence[str]) -> str:
    if os.name == "nt":
        return subprocess.list2cmdline(list(argv))
    return shlex.join(list(argv))


def atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_write_json(path: Path, payload: Any) -> None:
    atomic_write_text(
        path,
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def portable_relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def load_config(root: Path, path: Path | None = None) -> dict[str, Any]:
    config_path = path or root / "sweetpet.pipeline.json"
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PipelineError(f"cannot read pipeline config {config_path}: {exc}") from exc
    if config.get("schemaVersion") != SCHEMA_VERSION:
        raise PipelineError(
            f"unsupported pipeline schemaVersion {config.get('schemaVersion')!r}"
        )
    for key in ("android", "desktop", "petpack", "outputRoot"):
        value = config.get("paths", {}).get(key)
        if not isinstance(value, str) or not value.strip():
            raise PipelineError(f"paths.{key} must be a non-empty string")
    profiles = config.get("profiles")
    if not isinstance(profiles, dict):
        raise PipelineError("profiles must be an object")
    for profile_name in ("quick", "ci", "full", "release"):
        stages = profiles.get(profile_name)
        if not isinstance(stages, list) or not stages or not all(
            isinstance(item, str) and item for item in stages
        ):
            raise PipelineError(f"profiles.{profile_name} must be a non-empty stage list")
        unknown = sorted(set(stages) - set(STAGES))
        if unknown:
            raise PipelineError(
                f"profiles.{profile_name} contains unknown stage(s): {', '.join(unknown)}"
            )
    versions = config.get("versions")
    if not isinstance(versions, dict):
        raise PipelineError("versions must be an object")
    for component in ("android", "desktop"):
        value = versions.get(component)
        if not isinstance(value, str) or not STABLE_VERSION_RE.fullmatch(value):
            raise PipelineError(f"versions.{component} must be a stable x.y.z version")
    packs = config.get("packs")
    if not isinstance(packs, dict) or not packs:
        raise PipelineError("packs must be an object")
    for pack_id, entry in packs.items():
        if not isinstance(pack_id, str) or not PACK_ID_RE.fullmatch(pack_id):
            raise PipelineError(f"invalid configured pack id: {pack_id!r}")
        if not isinstance(entry, dict):
            raise PipelineError(f"packs.{pack_id} must be an object")
        pack_path = entry.get("path")
        if not isinstance(pack_path, str) or not pack_path.strip():
            raise PipelineError(f"packs.{pack_id}.path must be a non-empty string")
        warnings = entry.get("acceptedWarnings", [])
        if not isinstance(warnings, list):
            raise PipelineError(f"packs.{pack_id}.acceptedWarnings must be a list")
        keys: list[tuple[str, str]] = []
        for item in warnings:
            if not isinstance(item, dict):
                raise PipelineError(
                    f"packs.{pack_id}.acceptedWarnings entries must be objects"
                )
            code = item.get("code")
            location = item.get("location")
            if not isinstance(code, str) or not code or not isinstance(location, str) or not location:
                raise PipelineError(
                    f"packs.{pack_id}.acceptedWarnings requires non-empty code and location"
                )
            keys.append((code, location))
        if len(keys) != len(set(keys)):
            raise PipelineError(f"packs.{pack_id}.acceptedWarnings contains duplicates")
    return config


def resolve_project_path(root: Path, config: Mapping[str, Any], key: str) -> Path:
    candidate = Path(config["paths"][key])
    return candidate if candidate.is_absolute() else root / candidate


def warning_key(diagnostic: Mapping[str, Any]) -> tuple[str, str]:
    return str(diagnostic.get("code", "")), str(diagnostic.get("location", ""))


def accepted_warning_keys(
    config: Mapping[str, Any], pack_id: str
) -> set[tuple[str, str]]:
    entry = config.get("packs", {}).get(pack_id, {})
    warnings = entry.get("acceptedWarnings", []) if isinstance(entry, dict) else []
    return {
        (str(item.get("code", "")), str(item.get("location", "")))
        for item in warnings
        if isinstance(item, dict)
    }


def verify_qa_report(
    report_path: Path, config: Mapping[str, Any], pack_id: str
) -> None:
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PipelineError(f"cannot read QA report {report_path}: {exc}") from exc
    diagnostics = report.get("diagnostics", [])
    if not isinstance(diagnostics, list):
        raise PipelineError(f"QA report has invalid diagnostics: {report_path}")
    errors = [item for item in diagnostics if item.get("severity") == "error"]
    if errors:
        raise PipelineError(f"{pack_id} QA contains {len(errors)} error(s)")
    actual = {
        warning_key(item)
        for item in diagnostics
        if item.get("severity") == "warning"
    }
    accepted = accepted_warning_keys(config, pack_id)
    unexpected = sorted(actual - accepted)
    stale = sorted(accepted - actual)
    if unexpected or stale:
        detail = {
            "unexpectedWarnings": unexpected,
            "staleAllowlistEntries": stale,
        }
        raise PipelineError(
            f"{pack_id} QA warning allowlist mismatch: "
            + json.dumps(detail, ensure_ascii=False)
        )


def build_artifact_manifest(run_dir: Path) -> dict[str, Any]:
    artifact_root = run_dir / "artifacts"
    entries: list[dict[str, Any]] = []
    if artifact_root.exists():
        for path in sorted(
            (item for item in artifact_root.rglob("*") if item.is_file()),
            key=lambda item: item.relative_to(run_dir).as_posix(),
        ):
            entries.append(
                {
                    "path": portable_relative(path, run_dir),
                    "bytes": path.stat().st_size,
                    "sha256": sha256_file(path),
                }
            )
    return {
        "schemaVersion": 1,
        "artifactCount": len(entries),
        "totalBytes": sum(item["bytes"] for item in entries),
        "artifacts": entries,
    }


def deterministic_zip_tree(source: Path, output: Path) -> None:
    if not source.is_dir():
        raise PipelineError(f"desktop package directory not found: {source}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.{uuid.uuid4().hex}.tmp")
    try:
        with zipfile.ZipFile(
            temporary,
            "w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
            allowZip64=True,
        ) as archive:
            for path in sorted(source.rglob("*"), key=lambda item: item.as_posix()):
                if path.is_symlink():
                    raise PipelineError(f"symlink is not allowed in package: {path}")
                if not path.is_file():
                    continue
                relative = path.relative_to(source.parent).as_posix()
                info = zipfile.ZipInfo(relative, FIXED_ZIP_TIME)
                info.create_system = 0
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0
                archive.writestr(info, path.read_bytes(), compresslevel=9)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def write_sha_sidecar(path: Path) -> Path:
    sidecar = Path(f"{path}.sha256")
    atomic_write_text(sidecar, f"{sha256_file(path)} *{path.name}\n")
    return sidecar


@dataclasses.dataclass
class PipelineContext:
    root: Path
    config: dict[str, Any]
    run_id: str
    run_dir: Path
    dry_run: bool
    keep_going: bool
    selected_packs: tuple[str, ...]
    serial: str | None
    adb_override: str | None
    allow_physical_device: bool
    promote: bool
    archive: Path | None
    executor: Executor
    results: dict[str, StageResult] = dataclasses.field(default_factory=dict)

    @property
    def android_dir(self) -> Path:
        return resolve_project_path(self.root, self.config, "android")

    @property
    def desktop_dir(self) -> Path:
        return resolve_project_path(self.root, self.config, "desktop")

    @property
    def petpack_dir(self) -> Path:
        return resolve_project_path(self.root, self.config, "petpack")

    def command(
        self,
        stage: str,
        label: str,
        argv: Sequence[str | Path],
        *,
        cwd: Path,
        env: Mapping[str, str] | None = None,
    ) -> None:
        normalized = [str(value) for value in argv]
        if self.dry_run:
            print(f"[dry-run:{stage}] {format_command(normalized)}")
            print(f"  cwd: {cwd}")
            return
        safe_label = re.sub(r"[^A-Za-z0-9_.-]+", "-", label).strip("-") or "command"
        log_path = self.run_dir / "logs" / f"{stage}-{safe_label}.log"
        exit_code = self.executor.run(normalized, cwd=cwd, log_path=log_path, env=env)
        if exit_code != 0:
            raise PipelineError(
                f"stage {stage} command {label} failed with exit code {exit_code}"
            )


def configured_pack_path(context: PipelineContext, pack_id: str) -> Path:
    if not PACK_ID_RE.fullmatch(pack_id):
        raise PipelineError(f"invalid pack id: {pack_id!r}")
    configured = context.config.get("packs", {}).get(pack_id)
    relative = configured.get("path") if isinstance(configured, dict) else None
    path = context.petpack_dir / (relative or f"packs/{pack_id}")
    if not path.is_dir() or not (path / "pack.json").is_file():
        raise PipelineError(f"pack source not found: {path}")
    return path


def selected_pack_ids(context: PipelineContext) -> list[str]:
    if context.selected_packs:
        return list(context.selected_packs)
    defaults = [
        pack_id
        for pack_id, entry in context.config.get("packs", {}).items()
        if isinstance(entry, dict) and entry.get("qaByDefault") is True
    ]
    if not defaults:
        raise PipelineError("no pack selected and no qaByDefault pack is configured")
    return sorted(defaults)


def all_source_pack_ids(context: PipelineContext) -> list[str]:
    pack_root = context.petpack_dir / "packs"
    return sorted(
        path.name
        for path in pack_root.iterdir()
        if path.is_dir() and (path / "pack.json").is_file()
    )


def pack_version(pack_dir: Path) -> str:
    try:
        payload = json.loads((pack_dir / "pack.json").read_text(encoding="utf-8"))
        version = payload["version"]
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        raise PipelineError(f"cannot read pack version from {pack_dir}: {exc}") from exc
    if not isinstance(version, str) or not version:
        raise PipelineError(f"invalid pack version in {pack_dir}")
    return version


def source_declared_version(path: Path, pattern: str, label: str) -> str:
    try:
        content = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise PipelineError(f"cannot read {label} version source {path}: {exc}") from exc
    match = re.search(pattern, content, flags=re.MULTILINE)
    if not match:
        raise PipelineError(f"cannot find {label} version in {path}")
    return match.group(1)


def verify_component_version(context: PipelineContext, component: str) -> str:
    configured = str(context.config["versions"][component])
    if component == "android":
        actual = source_declared_version(
            context.android_dir / "app" / "build.gradle.kts",
            r'^\s*versionName\s*=\s*"([^"]+)"',
            "Android",
        )
    elif component == "desktop":
        actual = source_declared_version(
            context.desktop_dir / "app.py",
            r'^APP_VERSION\s*=\s*"([^"]+)"',
            "desktop",
        )
    else:  # pragma: no cover - caller-owned invariant
        raise PipelineError(f"unsupported versioned component: {component}")
    if actual != configured:
        raise PipelineError(
            f"{component} version drift: config={configured}, source={actual}"
        )
    return actual


def petpack_tool(context: PipelineContext) -> Path:
    return context.petpack_dir / "tools" / "petpack.py"


def gradle_argv(context: PipelineContext, *tasks: str) -> list[str]:
    if os.name == "nt":
        prefix = [str(context.android_dir / "gradlew.bat")]
    else:
        prefix = ["sh", str(context.android_dir / "gradlew")]
    return prefix + ["--no-daemon", "--console=plain", *tasks]


def find_adb(context: PipelineContext) -> str:
    candidates: list[Path] = []
    if context.adb_override:
        candidates.append(Path(context.adb_override))
    located = shutil.which("adb")
    if located:
        candidates.append(Path(located))
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(variable)
        if value:
            candidates.append(Path(value) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        candidates.append(
            Path(os.environ["LOCALAPPDATA"])
            / "Android"
            / "Sdk"
            / "platform-tools"
            / "adb.exe"
        )
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise PipelineError("adb was not found; set ANDROID_SDK_ROOT or pass --adb")


def stage_bootstrap(context: PipelineContext) -> None:
    venv = context.root / ".venv"
    venv_python = (
        venv / "Scripts" / "python.exe"
        if os.name == "nt"
        else venv / "bin" / "python"
    )
    if context.dry_run or not venv_python.is_file():
        context.command(
            "bootstrap",
            "venv",
            [sys.executable, "-m", "venv", venv],
            cwd=context.root,
        )
    context.command(
        "bootstrap",
        "dependencies",
        [
            venv_python,
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "-r",
            context.root / "requirements-iteration.txt",
        ],
        cwd=context.root,
    )


def stage_tooling_test(context: PipelineContext) -> None:
    context.command(
        "tooling-test",
        "repo-tools",
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            "scripts",
            "-p",
            "test_*.py",
            "-v",
        ],
        cwd=context.root,
    )
    context.command(
        "tooling-test",
        "petpack-tools",
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            "tools",
            "-p",
            "test_*.py",
            "-v",
        ],
        cwd=context.petpack_dir,
    )


def stage_petpack_validate(context: PipelineContext) -> None:
    for pack_id in all_source_pack_ids(context):
        context.command(
            "petpack-validate",
            pack_id,
            [sys.executable, petpack_tool(context), "validate", configured_pack_path(context, pack_id)],
            cwd=context.petpack_dir,
        )


def stage_petpack_qa(context: PipelineContext) -> None:
    for pack_id in selected_pack_ids(context):
        report_dir = context.run_dir / "reports" / "petpack" / pack_id / "qa"
        context.command(
            "petpack-qa",
            pack_id,
            [
                sys.executable,
                petpack_tool(context),
                "qa",
                configured_pack_path(context, pack_id),
                "--reports",
                report_dir,
                "--normalization",
                "safe",
            ],
            cwd=context.petpack_dir,
        )
        if not context.dry_run:
            verify_qa_report(report_dir / "qa-report.json", context.config, pack_id)


def stage_petpack_candidate(context: PipelineContext) -> None:
    for pack_id in selected_pack_ids(context):
        source = configured_pack_path(context, pack_id)
        version = pack_version(source)
        output = (
            context.run_dir
            / "artifacts"
            / "petpacks"
            / f"{pack_id}-{version}.candidate.petpack"
        )
        report_dir = context.run_dir / "reports" / "petpack" / pack_id / "candidate"
        argv: list[str | Path] = [
            sys.executable,
            petpack_tool(context),
            "release",
            source,
            "--output",
            output,
            "--reports",
            report_dir,
            "--normalization",
            "safe",
        ]
        if not accepted_warning_keys(context.config, pack_id):
            argv.append("--strict")
        context.command(
            "petpack-candidate", pack_id, argv, cwd=context.petpack_dir
        )
        if not context.dry_run:
            verify_qa_report(report_dir / "qa-report.json", context.config, pack_id)


def stage_android_test(context: PipelineContext) -> None:
    context.command(
        "android-test",
        "jvm-lint",
        gradle_argv(
            context,
            ":content-pack:testDebugUnitTest",
            ":overlay-host:testDebugUnitTest",
            ":pet-runtime:testDebugUnitTest",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
        ),
        cwd=context.android_dir,
    )
    context.command(
        "android-test",
        "bundled-pack-assets",
        [sys.executable, context.root / "scripts" / "test_bundled_pack_assets.py"],
        cwd=context.root,
        env={"SWEETPET_REQUIRE_CAMPUS_BUNDLE": "1"},
    )


def locate_android_apk(context: PipelineContext, android_test: bool) -> Path:
    if android_test:
        relative = Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        pattern = "*debug-androidTest.apk"
    else:
        relative = Path("app/build/outputs/apk/debug/app-debug.apk")
        pattern = "app-debug.apk"
    direct = context.android_dir / relative
    if direct.is_file():
        return direct
    matches = sorted(context.android_dir.rglob(pattern))
    if len(matches) != 1:
        raise PipelineError(
            f"expected one {'test ' if android_test else ''}APK, found {len(matches)}"
        )
    return matches[0]


def copy_artifact(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.tmp")
    try:
        shutil.copy2(source, temporary)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    write_sha_sidecar(destination)


def _path_present(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def _remove_owned_path(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink(missing_ok=True)
    elif path.is_dir():
        shutil.rmtree(path)


def _move_promoted_path(source: Path, destination: Path) -> None:
    """Move one promotion item; split out for fault-injection tests."""
    os.replace(source, destination)


def _copy_promotion_path(source: Path, destination: Path) -> None:
    if source.is_symlink():
        raise PipelineError(f"promotion path cannot be a symlink: {source}")
    if source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    elif source.is_dir():
        shutil.copytree(source, destination)
    else:
        raise PipelineError(f"promotion source is missing: {source}")


def _replace_file_from_copy(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.restore")
    try:
        shutil.copy2(source, temporary)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def _replace_directory_from_copy(source: Path, destination: Path, workspace: Path) -> None:
    prepared = workspace / f"restore-{uuid.uuid4().hex}"
    displaced = workspace / f"displaced-{uuid.uuid4().hex}"
    shutil.copytree(source, prepared)
    moved_old = False
    try:
        if _path_present(destination):
            _move_promoted_path(destination, displaced)
            moved_old = True
        _move_promoted_path(prepared, destination)
        if moved_old and _path_present(displaced):
            _remove_owned_path(displaced)
    except Exception:
        if not _path_present(destination) and moved_old and _path_present(displaced):
            _move_promoted_path(displaced, destination)
        raise
    finally:
        if _path_present(prepared):
            _remove_owned_path(prepared)


@contextlib.contextmanager
def promotion_lock(lock_path: Path):
    """Acquire a process-level exclusive lock for one canonical PetPack path."""
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    stream = lock_path.open("a+b")
    acquired = False
    try:
        if stream.seek(0, os.SEEK_END) == 0:
            stream.write(b"\0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            acquired = True
        except OSError as exc:
            raise PipelineError(
                f"another PetPack promotion holds the lock: {lock_path}"
            ) from exc
        yield
    finally:
        try:
            if acquired:
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt

                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        finally:
            stream.close()


def sanitize_promoted_reports(
    reports: Path, *, artifact_name: str, runtime_version: str
) -> dict[str, Any]:
    """Remove machine-local gate details from the canonical public report set."""
    qa_path = reports / "qa-report.json"
    try:
        qa_report = json.loads(qa_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PipelineError(f"cannot sanitize promoted QA report: {exc}") from exc
    gate = qa_report.pop("androidInstallGate", None)
    if not isinstance(gate, dict) or gate.get("result") != "pass":
        raise PipelineError("promoted QA report has no passing Android install gate")
    public_gate = {
        key: gate.get(key)
        for key in (
            "schemaVersion",
            "result",
            "archiveSha256",
            "archiveSizeBytes",
            "deviceType",
            "physicalDeviceExplicitlyAllowed",
            "vivoScannerCompatibility",
            "packId",
            "version",
            "actions",
            "tasks",
        )
    }
    public_gate["archive"] = artifact_name
    public_gate["deviceSerial"] = (
        "dedicated-emulator"
        if gate.get("deviceType") == "emulator"
        else "explicit-physical-device"
    )
    atomic_write_json(qa_path, qa_report)
    atomic_write_json(
        reports / f"android-install-gate-v{runtime_version}.json", public_gate
    )
    return public_gate


PROMOTION_STATE = ".sweetpet-promotion.json"


def _promotion_targets(
    canonical_output: Path, canonical_reports: Path
) -> tuple[Path, Path, Path]:
    return canonical_output, Path(f"{canonical_output}.sha256"), canonical_reports


def _restore_promotion(
    staging_root: Path,
    canonical_output: Path,
    canonical_reports: Path,
    originally_present: Mapping[str, bool],
) -> None:
    canonical_output, canonical_sidecar, canonical_reports = _promotion_targets(
        canonical_output, canonical_reports
    )
    backups = staging_root / "backups"
    targets = {
        "output": (backups / "output", canonical_output),
        "sidecar": (backups / "sidecar", canonical_sidecar),
        "reports": (backups / "reports", canonical_reports),
    }
    for key in ("output", "sidecar"):
        backup, target = targets[key]
        if originally_present.get(key, False):
            if not backup.is_file():
                raise PipelineError(f"promotion recovery backup is missing: {backup}")
            _replace_file_from_copy(backup, target)
        elif _path_present(target):
            _remove_owned_path(target)
    report_backup, report_target = targets["reports"]
    if originally_present.get("reports", False):
        if not report_backup.is_dir():
            raise PipelineError(
                f"promotion recovery report backup is missing: {report_backup}"
            )
        _replace_directory_from_copy(report_backup, report_target, staging_root)
    elif _path_present(report_target):
        _remove_owned_path(report_target)


def recover_stale_promotions(
    canonical_output: Path, canonical_reports: Path
) -> None:
    """Recover an interrupted promotion before starting a new one."""
    prefix = f".{canonical_output.name}.promote-"
    for staging_root in sorted(canonical_output.parent.glob(f"{prefix}*")):
        if not staging_root.is_dir():
            continue
        state_path = staging_root / PROMOTION_STATE
        if not state_path.is_file():
            _remove_owned_path(staging_root)
            continue
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PipelineError(
                f"cannot recover malformed promotion workspace {staging_root}: {exc}"
            ) from exc
        if (
            state.get("schemaVersion") != 1
            or state.get("canonicalOutput") != str(canonical_output.resolve())
            or state.get("canonicalReports") != str(canonical_reports.resolve())
        ):
            raise PipelineError(
                f"promotion workspace ownership mismatch; inspect manually: {staging_root}"
            )
        status = state.get("status")
        if status == "committed" or status == "prepared":
            _remove_owned_path(staging_root)
            continue
        if status != "committing":
            raise PipelineError(
                f"unknown promotion recovery state in {staging_root}: {status!r}"
            )
        try:
            _restore_promotion(
                staging_root,
                canonical_output,
                canonical_reports,
                state.get("originallyPresent", {}),
            )
        except Exception as exc:
            raise PipelineError(
                f"automatic promotion recovery failed; backups preserved at {staging_root}: {exc}"
            ) from exc
        _remove_owned_path(staging_root)


def promote_petpack_publish_set(
    source_output: Path,
    source_reports: Path,
    canonical_output: Path,
    canonical_reports: Path,
    *,
    runtime_version: str,
) -> None:
    """Atomically promote an already gated and exact-warning-verified publish set."""
    source_sidecar = Path(f"{source_output}.sha256")
    canonical_sidecar = Path(f"{canonical_output}.sha256")
    required = (source_output, source_sidecar, source_reports / "qa-report.json")
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise PipelineError("verified publish set is incomplete: " + ", ".join(missing))

    artifact_hash = sha256_file(source_output)
    artifact_size = source_output.stat().st_size
    expected_sidecar = source_sidecar.read_text(encoding="utf-8")
    try:
        report = json.loads((source_reports / "qa-report.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PipelineError(f"cannot read verified publish report: {exc}") from exc
    expected_artifact = report.get("artifact")
    if not isinstance(expected_artifact, dict):
        raise PipelineError("verified publish report has no artifact metadata")
    if (
        expected_artifact.get("sha256") != artifact_hash
        or expected_artifact.get("sizeBytes") != artifact_size
        or expected_artifact.get("name") != source_output.name
    ):
        raise PipelineError("verified publish report does not match its PetPack")
    expected_sidecar_text = f"{artifact_hash}  {source_output.name}\n"
    if expected_sidecar != expected_sidecar_text:
        raise PipelineError("verified publish sidecar does not match its PetPack")

    canonical_output.parent.mkdir(parents=True, exist_ok=True)
    canonical_reports.parent.mkdir(parents=True, exist_ok=True)
    if os.stat(canonical_output.parent).st_dev != os.stat(canonical_reports.parent).st_dev:
        raise PipelineError("canonical PetPack and reports must be on the same filesystem")
    lock_path = canonical_output.with_name(f".{canonical_output.name}.promote.lock")
    with promotion_lock(lock_path):
        recover_stale_promotions(canonical_output, canonical_reports)
        staging_root = Path(
            tempfile.mkdtemp(
                prefix=f".{canonical_output.name}.promote-", dir=canonical_output.parent
            )
        )
        staged_output = staging_root / canonical_output.name
        staged_sidecar = staging_root / canonical_sidecar.name
        staged_reports = staging_root / "reports"
        backups = staging_root / "backups"
        state_path = staging_root / PROMOTION_STATE
        originally_present = {
            "output": _path_present(canonical_output),
            "sidecar": _path_present(canonical_sidecar),
            "reports": _path_present(canonical_reports),
        }
        commit_started = False
        rollback_complete = False
        try:
            shutil.copy2(source_output, staged_output)
            shutil.copy2(source_sidecar, staged_sidecar)
            shutil.copytree(source_reports, staged_reports)
            public_gate = sanitize_promoted_reports(
                staged_reports,
                artifact_name=canonical_output.name,
                runtime_version=runtime_version,
            )
            if sha256_file(staged_output) != artifact_hash:
                raise PipelineError("staged promotion PetPack hash changed")
            backups.mkdir()
            for key, target in (
                ("output", canonical_output),
                ("sidecar", canonical_sidecar),
                ("reports", canonical_reports),
            ):
                if originally_present[key]:
                    _copy_promotion_path(target, backups / key)
            state = {
                "schemaVersion": 1,
                "status": "prepared",
                "canonicalOutput": str(canonical_output.resolve()),
                "canonicalReports": str(canonical_reports.resolve()),
                "originallyPresent": originally_present,
            }
            atomic_write_json(state_path, state)
            state["status"] = "committing"
            atomic_write_json(state_path, state)
            commit_started = True

            _move_promoted_path(staged_output, canonical_output)
            _move_promoted_path(staged_sidecar, canonical_sidecar)
            displaced_reports = staging_root / "displaced-reports"
            if _path_present(canonical_reports):
                _move_promoted_path(canonical_reports, displaced_reports)
            try:
                _move_promoted_path(staged_reports, canonical_reports)
            except Exception:
                if not _path_present(canonical_reports) and _path_present(displaced_reports):
                    _move_promoted_path(displaced_reports, canonical_reports)
                raise
            if _path_present(displaced_reports):
                _remove_owned_path(displaced_reports)

            if sha256_file(canonical_output) != artifact_hash:
                raise PipelineError("promoted PetPack hash changed")
            if canonical_sidecar.read_text(encoding="utf-8") != expected_sidecar:
                raise PipelineError("promoted PetPack sidecar changed")
            committed_report = json.loads(
                (canonical_reports / "qa-report.json").read_text(encoding="utf-8")
            )
            if committed_report.get("artifact") != expected_artifact:
                raise PipelineError("promoted PetPack report changed")
            committed_gate = json.loads(
                (
                    canonical_reports
                    / f"android-install-gate-v{runtime_version}.json"
                ).read_text(encoding="utf-8")
            )
            if committed_gate != public_gate:
                raise PipelineError("promoted Android install gate report changed")
            state["status"] = "committed"
            atomic_write_json(state_path, state)
        except Exception as commit_error:
            if commit_started:
                try:
                    _restore_promotion(
                        staging_root,
                        canonical_output,
                        canonical_reports,
                        originally_present,
                    )
                    rollback_complete = True
                except Exception as rollback_error:
                    raise PipelineError(
                        "PetPack promotion and automatic rollback failed; "
                        f"backups preserved at {staging_root}: {commit_error}; {rollback_error}"
                    ) from commit_error
            else:
                rollback_complete = True
            raise PipelineError(
                f"PetPack promotion failed; previous canonical release restored: {commit_error}"
            ) from commit_error
        else:
            rollback_complete = True
        finally:
            if rollback_complete and staging_root.exists():
                shutil.rmtree(staging_root)


def stage_android_build(context: PipelineContext) -> None:
    version = verify_component_version(context, "android")
    context.command(
        "android-build",
        "debug-apks",
        gradle_argv(context, ":app:assembleDebug", ":app:assembleDebugAndroidTest"),
        cwd=context.android_dir,
    )
    if context.dry_run:
        return
    artifact_dir = context.run_dir / "artifacts" / "android"
    copy_artifact(
        locate_android_apk(context, False),
        artifact_dir / f"SweetGirlfriendPet-Android-v{version}-debug.apk",
    )
    copy_artifact(
        locate_android_apk(context, True),
        artifact_dir / f"SweetGirlfriendPet-Android-v{version}-debug-androidTest.apk",
    )


def stage_desktop_test(context: PipelineContext) -> None:
    context.command(
        "desktop-test",
        "unit",
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            "tests",
            "-p",
            "test_*.py",
            "-v",
        ],
        cwd=context.desktop_dir,
    )


def stage_desktop_audit(context: PipelineContext) -> None:
    context.command(
        "desktop-audit",
        "animation-quality",
        [sys.executable, "audit_animation_quality.py"],
        cwd=context.desktop_dir,
    )


def stage_desktop_build(context: PipelineContext) -> None:
    version = verify_component_version(context, "desktop")
    if not context.dry_run and platform.system() != "Windows":
        raise PipelineError("desktop-build must run on Windows")
    if not context.dry_run and importlib.util.find_spec("PyInstaller") is None:
        raise PipelineError(
            "PyInstaller is unavailable; install requirements-iteration.txt"
        )
    staging = context.run_dir / "staging" / "desktop"
    dist = staging / "dist"
    work = staging / "build"
    context.command(
        "desktop-build",
        "pyinstaller",
        [
            sys.executable,
            "-m",
            "PyInstaller",
            "--clean",
            "--noconfirm",
            "--distpath",
            dist,
            "--workpath",
            work,
            "SweetGirlfriendDesktopPet.spec",
        ],
        cwd=context.desktop_dir,
    )
    if context.dry_run:
        return
    package_dir = dist / "SweetGirlfriendDesktopPet"
    for name in ("README.md", "THIRD_PARTY_NOTICES.txt"):
        source = context.desktop_dir / name
        if source.is_file():
            shutil.copy2(source, package_dir / name)
    licenses = context.desktop_dir / "licenses"
    if licenses.is_dir():
        shutil.copytree(licenses, package_dir / "licenses", dirs_exist_ok=True)
    output = (
        context.run_dir
        / "artifacts"
        / "desktop"
        / f"SweetGirlfriendDesktopPet-v{version}-windows.zip"
    )
    deterministic_zip_tree(package_dir, output)
    write_sha_sidecar(output)


def stage_petpack_publish(context: PipelineContext) -> None:
    if not context.serial:
        raise PipelineError("petpack-publish requires --serial")
    for pack_id in selected_pack_ids(context):
        source = configured_pack_path(context, pack_id)
        version = pack_version(source)
        output = (
            context.run_dir
            / "artifacts"
            / "petpacks"
            / f"{pack_id}-{version}.petpack"
        )
        report_dir = context.run_dir / "reports" / "petpack" / pack_id / "publish"
        argv: list[str | Path] = [
            sys.executable,
            petpack_tool(context),
            "publish",
            source,
            "--output",
            output,
            "--reports",
            report_dir,
            "--normalization",
            "safe",
            "--android-project",
            context.android_dir,
            "--serial",
            context.serial,
        ]
        if accepted_warning_keys(context.config, pack_id):
            argv.append("--allow-warnings")
        if context.allow_physical_device:
            argv.append("--allow-physical-device")
        if context.adb_override:
            argv.extend(("--adb", context.adb_override))
        context.command(
            "petpack-publish", pack_id, argv, cwd=context.petpack_dir
        )
        if context.dry_run:
            continue
        verify_qa_report(report_dir / "qa-report.json", context.config, pack_id)
        if context.promote:
            promote_petpack_publish_set(
                output,
                report_dir,
                context.petpack_dir / "dist" / f"{pack_id}-{version}.petpack",
                context.petpack_dir / "reports" / f"{pack_id}-{version}",
                runtime_version=str(context.config["versions"]["android"]),
            )


def stage_android_deploy(context: PipelineContext) -> None:
    if not context.serial:
        raise PipelineError("android-deploy requires --serial")
    adb = context.adb_override or "adb" if context.dry_run else find_adb(context)
    version = context.config.get("versions", {}).get("android", "dev")
    apk = (
        context.run_dir
        / "artifacts"
        / "android"
        / f"SweetGirlfriendPet-Android-v{version}-debug.apk"
    )
    context.command(
        "android-deploy",
        "install",
        [adb, "-s", context.serial, "install", "-r", apk],
        cwd=context.root,
    )
    context.command(
        "android-deploy",
        "launch",
        [
            adb,
            "-s",
            context.serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "com.sweetgirlfriend.pet/com.sweetgirlfriend.pet.app.MainActivity",
        ],
        cwd=context.root,
    )


def stage_petpack_stage(context: PipelineContext) -> None:
    if not context.serial:
        raise PipelineError("petpack-stage requires --serial")
    if context.archive is None or (not context.dry_run and not context.archive.is_file()):
        raise PipelineError("petpack-stage requires an existing --archive")
    adb = context.adb_override or "adb" if context.dry_run else find_adb(context)
    remote_dir = "/sdcard/Download/PetPacks"
    remote_path = f"{remote_dir}/{context.archive.name}"
    context.command(
        "petpack-stage",
        "mkdir",
        [adb, "-s", context.serial, "shell", "mkdir", "-p", remote_dir],
        cwd=context.root,
    )
    context.command(
        "petpack-stage",
        "push",
        [adb, "-s", context.serial, "push", context.archive, remote_path],
        cwd=context.root,
    )
    if not context.dry_run:
        report = {
            "schemaVersion": 1,
            "serial": context.serial,
            "localPath": str(context.archive.resolve()),
            "remotePath": remote_path,
            "bytes": context.archive.stat().st_size,
            "sha256": sha256_file(context.archive),
            "note": "Open the app and explicitly confirm this local PetPack import.",
        }
        atomic_write_json(context.run_dir / "reports" / "petpack-stage.json", report)


STAGES: dict[str, StageDefinition] = {
    "bootstrap": StageDefinition("bootstrap", "tooling", (), stage_bootstrap),
    "tooling-test": StageDefinition("tooling-test", "petpack", (), stage_tooling_test),
    "petpack-validate": StageDefinition(
        "petpack-validate", "petpack", ("tooling-test",), stage_petpack_validate
    ),
    "petpack-qa": StageDefinition(
        "petpack-qa", "petpack", ("petpack-validate",), stage_petpack_qa
    ),
    "petpack-candidate": StageDefinition(
        "petpack-candidate",
        "petpack",
        ("petpack-validate",),
        stage_petpack_candidate,
    ),
    "android-test": StageDefinition("android-test", "android", (), stage_android_test),
    "android-build": StageDefinition(
        "android-build", "android", ("android-test",), stage_android_build
    ),
    "desktop-test": StageDefinition("desktop-test", "desktop", (), stage_desktop_test),
    "desktop-audit": StageDefinition(
        "desktop-audit", "desktop", ("desktop-test",), stage_desktop_audit
    ),
    "desktop-build": StageDefinition(
        "desktop-build", "desktop", ("desktop-audit",), stage_desktop_build
    ),
    "petpack-publish": StageDefinition(
        "petpack-publish",
        "petpack",
        ("petpack-qa", "android-test"),
        stage_petpack_publish,
    ),
    "android-deploy": StageDefinition(
        "android-deploy", "android", ("android-build",), stage_android_deploy
    ),
    "petpack-stage": StageDefinition(
        "petpack-stage", "petpack", (), stage_petpack_stage
    ),
}


def dependency_order(requested: Iterable[str]) -> list[str]:
    ordered: list[str] = []
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(name: str) -> None:
        if name not in STAGES:
            raise PipelineError(f"unknown stage: {name}")
        if name in visiting:
            raise PipelineError(f"stage dependency cycle at {name}")
        if name in visited:
            return
        visiting.add(name)
        for dependency in STAGES[name].dependencies:
            visit(dependency)
        visiting.remove(name)
        visited.add(name)
        ordered.append(name)

    for stage in requested:
        visit(stage)
    return ordered


class Pipeline:
    def __init__(self, context: PipelineContext) -> None:
        self.context = context

    def _summary_payload(self, overall: str) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "runId": self.context.run_id,
            "overall": overall,
            "updatedAt": utc_now(),
            "stages": [dataclasses.asdict(result) for result in self.context.results.values()],
        }

    def _write_summary(self, overall: str) -> None:
        if self.context.dry_run:
            return
        payload = self._summary_payload(overall)
        atomic_write_json(self.context.run_dir / "summary.json", payload)
        lines = [
            f"# SweetPet pipeline {self.context.run_id}",
            "",
            f"Overall: **{overall}**",
            "",
            "| Stage | Status | Seconds | Message |",
            "|---|---:|---:|---|",
        ]
        for result in self.context.results.values():
            message = result.message.replace("|", "\\|").replace("\n", " ")
            lines.append(
                f"| {result.name} | {result.status} | "
                f"{result.duration_seconds:.3f} | {message} |"
            )
        atomic_write_text(self.context.run_dir / "summary.md", "\n".join(lines) + "\n")
        atomic_write_json(self.context.run_dir / "state.json", payload)

    def run(self, requested: Sequence[str]) -> int:
        order = dependency_order(requested)
        if self.context.dry_run:
            print("Execution order: " + " -> ".join(order))
        else:
            if self.context.run_dir.exists():
                raise PipelineError(f"run directory already exists: {self.context.run_dir}")
            self.context.run_dir.mkdir(parents=True)
            atomic_write_text(
                self.context.run_dir / OWNER_MARKER,
                json.dumps(
                    {"schemaVersion": 1, "runId": self.context.run_id},
                    ensure_ascii=False,
                )
                + "\n",
            )
            self._write_summary("running")

        failed = False
        for index, name in enumerate(order):
            definition = STAGES[name]
            blocked = [
                dependency
                for dependency in definition.dependencies
                if self.context.results.get(dependency)
                and self.context.results[dependency].status != "passed"
            ]
            if blocked:
                self.context.results[name] = StageResult(
                    name=name,
                    status="skipped",
                    message="blocked by " + ", ".join(blocked),
                )
                continue
            started = time.monotonic()
            result = StageResult(name=name, status="running", started_at=utc_now())
            self.context.results[name] = result
            try:
                definition.handler(self.context)
            except Exception as exc:  # The report must survive unexpected tool errors.
                result.status = "failed"
                result.message = f"{type(exc).__name__}: {exc}"
                print(f"ERROR [{name}]: {result.message}", file=sys.stderr)
                failed = True
            else:
                result.status = "passed"
            finally:
                result.duration_seconds = round(time.monotonic() - started, 3)
                self._write_summary("failed" if failed else "running")
            if failed and not self.context.keep_going:
                for remaining in order[index + 1 :]:
                    if remaining not in self.context.results:
                        self.context.results[remaining] = StageResult(
                            name=remaining,
                            status="skipped",
                            message=f"fail-fast after {name}",
                        )
                break

        if not self.context.dry_run:
            manifest = build_artifact_manifest(self.context.run_dir)
            atomic_write_json(self.context.run_dir / "artifacts-manifest.json", manifest)
        overall = "failed" if any(
            result.status == "failed" for result in self.context.results.values()
        ) else "passed"
        self._write_summary(overall)
        if not self.context.dry_run:
            print(f"\nRun report: {self.context.run_dir / 'summary.md'}")
            print(f"Artifact manifest: {self.context.run_dir / 'artifacts-manifest.json'}")
        return 1 if overall == "failed" else 0


def create_intake(
    root: Path,
    config: Mapping[str, Any],
    intake_id: str,
    *,
    title: str | None,
    pack_class: str,
) -> Path:
    if not INTAKE_ID_RE.fullmatch(intake_id):
        raise PipelineError(f"invalid intake id: {intake_id!r}")
    if pack_class != "game-compatible":
        raise PipelineError(
            "only the fully parameterized game-compatible intake is currently supported"
        )
    petpack_dir = resolve_project_path(root, config, "petpack")
    template = petpack_dir / "templates" / "intake-v1"
    destination = petpack_dir / "work" / "intake" / intake_id
    if not template.is_dir():
        raise PipelineError(f"intake template is missing: {template}")
    if destination.exists():
        raise PipelineError(f"intake already exists: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.partial")
    replacements = {
        "{{INTAKE_ID}}": intake_id,
        "{{TITLE_JSON}}": json.dumps(title, ensure_ascii=False),
        "{{PACK_CLASS}}": pack_class,
        "{{UPDATED_AT}}": utc_now(),
    }
    try:
        shutil.copytree(template, temporary)
        for path in temporary.rglob("*"):
            if path.is_file() and path.suffix.lower() in {".json", ".md", ".txt"}:
                content = path.read_text(encoding="utf-8")
                for placeholder, value in replacements.items():
                    content = content.replace(placeholder, value)
                path.write_text(content, encoding="utf-8", newline="\n")
        for name in (
            "brief.json",
            "sources.manifest.json",
            "actions.manifest.json",
            "content-plan.json",
        ):
            manifest_path = temporary / name
            if not manifest_path.is_file():
                raise PipelineError(f"intake template is missing {name}")
            payload = json.loads(manifest_path.read_text(encoding="utf-8"))
            payload["intakeId"] = intake_id
            payload["intakeTitle"] = title
            payload["updatedAt"] = replacements["{{UPDATED_AT}}"]
            payload["status"] = "collecting"
            if name == "brief.json":
                baseline = payload.setdefault("technicalBaseline", {})
                baseline["packClass"] = (
                    "full-motion" if pack_class == "game-compatible" else pack_class
                )
                baseline["gameCompatible"] = pack_class == "game-compatible"
            elif name == "actions.manifest.json":
                baseline = payload.setdefault("baseline", {})
                baseline["packClass"] = (
                    "full-motion" if pack_class == "game-compatible" else pack_class
                )
                baseline["gameCompatible"] = pack_class == "game-compatible"
            atomic_write_json(manifest_path, payload)
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return destination


def doctor(root: Path, config: Mapping[str, Any], *, strict: bool, as_json: bool) -> int:
    checks: list[dict[str, Any]] = []

    def add(name: str, ok: bool, value: str, required: bool = True) -> None:
        checks.append(
            {"name": name, "ok": ok, "required": required, "value": value}
        )

    add("python", sys.version_info >= (3, 11), sys.version.split()[0])
    for key in ("android", "desktop", "petpack"):
        path = resolve_project_path(root, config, key)
        add(f"path.{key}", path.is_dir(), str(path))
    expected_versions: dict[str, str] = {}
    requirements = root / "requirements-iteration.txt"
    try:
        for line in requirements.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and "==" in stripped:
                distribution, version = stripped.split("==", 1)
                expected_versions[distribution.lower()] = version
    except OSError:
        expected_versions = {}
    module_distributions = {
        "PIL": "Pillow",
        "numpy": "numpy",
        "PyInstaller": "pyinstaller",
        "PySide6": "PySide6",
    }
    for module, distribution in module_distributions.items():
        found = importlib.util.find_spec(module) is not None
        actual = None
        if found:
            try:
                actual = importlib.metadata.version(distribution)
            except importlib.metadata.PackageNotFoundError:
                found = False
        expected = expected_versions.get(distribution.lower())
        matches = found and (expected is None or actual == expected)
        value = "missing" if not found else str(actual or "available")
        if expected:
            value += f" (expected {expected})"
        add(f"python.{module}", matches, value)
    java_home = os.environ.get("JAVA_HOME")
    java_candidate = (
        Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if java_home
        else None
    )
    java = str(java_candidate) if java_candidate and java_candidate.is_file() else shutil.which("java")
    java_major = None
    if java:
        try:
            version_result = subprocess.run(
                [java, "-version"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=10,
                check=False,
            )
            match = re.search(r'version\s+"([0-9]+)', version_result.stdout)
            java_major = int(match.group(1)) if match else None
        except (OSError, subprocess.TimeoutExpired):
            java_major = None
    add(
        "java",
        java is not None and java_major is not None and java_major >= 17,
        f"{java or 'missing'} (major {java_major if java_major is not None else 'unknown'}, requires >=17)",
    )
    adb = shutil.which("adb")
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    sdk_path = Path(sdk) if sdk else None
    add(
        "android.sdk",
        sdk_path is not None and sdk_path.is_dir(),
        str(sdk_path) if sdk_path else "missing",
    )
    if not adb and sdk:
        candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        adb = str(candidate) if candidate.is_file() else None
    add("adb", adb is not None, adb or "missing")
    payload = {
        "schemaVersion": 1,
        "platform": platform.platform(),
        "root": str(root),
        "checks": checks,
    }
    if as_json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        for item in checks:
            marker = "OK" if item["ok"] else ("WARN" if not item["required"] else "FAIL")
            print(f"[{marker:4}] {item['name']}: {item['value']}")
    failures = [item for item in checks if item["required"] and not item["ok"]]
    return 1 if strict and failures else 0


def add_pipeline_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--pack", action="append", default=[])
    parser.add_argument("--serial")
    parser.add_argument("--adb")
    parser.add_argument("--allow-physical-device", action="store_true")
    parser.add_argument("--promote", action="store_true")
    parser.add_argument("--run-id")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--keep-going", action="store_true")


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="SweetPet repository iteration, packaging, and deployment automation"
    )
    parser.add_argument("--config", type=Path)
    subparsers = parser.add_subparsers(dest="command", required=True)

    doctor_parser = subparsers.add_parser("doctor", help="inspect local toolchain")
    doctor_parser.add_argument("--strict", action="store_true")
    doctor_parser.add_argument("--json", action="store_true")

    bootstrap = subparsers.add_parser(
        "bootstrap", help="create .venv and install pinned iteration dependencies"
    )
    bootstrap.add_argument("--run-id")
    bootstrap.add_argument("--dry-run", action="store_true")
    bootstrap.add_argument("--keep-going", action="store_true")
    bootstrap.add_argument("--pack", action="append", default=[])
    bootstrap.add_argument("--serial")
    bootstrap.add_argument("--adb")
    bootstrap.add_argument("--allow-physical-device", action="store_true")
    bootstrap.add_argument("--promote", action="store_true")

    iterate = subparsers.add_parser("iterate", help="run a named iteration profile")
    iterate.add_argument("--profile", choices=("quick", "ci", "full", "release"), default="quick")
    iterate.add_argument(
        "--component",
        action="append",
        choices=("all", "petpack", "android", "desktop"),
        default=[],
    )
    iterate.add_argument("--stage", action="append", default=[])
    add_pipeline_arguments(iterate)

    pack = subparsers.add_parser("pack", help="QA, build, or publish one PetPack")
    pack.add_argument("action", choices=("qa", "candidate", "publish"))
    pack.add_argument("pack_ids", nargs="+")
    add_pipeline_arguments(pack)

    deploy = subparsers.add_parser("deploy", help="deploy an APK or stage a PetPack")
    deploy.add_argument("target", choices=("android", "petpack"))
    deploy.add_argument("--archive", type=Path)
    add_pipeline_arguments(deploy)

    intake = subparsers.add_parser(
        "intake", help="create a pre-scaffold workspace for the next character"
    )
    intake.add_argument("intake_id")
    intake.add_argument("--title")
    intake.add_argument(
        "--pack-class",
        choices=("game-compatible",),
        default="game-compatible",
        help="currently only the fully parameterized game-compatible intake is supported",
    )

    status = subparsers.add_parser("status", help="show a previous run summary")
    status.add_argument("run_id")
    return parser.parse_args(argv)


def context_from_args(
    args: argparse.Namespace,
    root: Path,
    config: dict[str, Any],
    executor: Executor | None = None,
) -> PipelineContext:
    run_id = args.run_id or default_run_id()
    if not RUN_ID_RE.fullmatch(run_id):
        raise PipelineError(f"invalid run id: {run_id!r}")
    output_root = resolve_project_path(root, config, "outputRoot")
    packs = tuple(getattr(args, "pack", []) or [])
    if getattr(args, "command", None) == "pack":
        packs = tuple(args.pack_ids)
    archive = getattr(args, "archive", None)
    if archive is not None and not archive.is_absolute():
        archive = root / archive
    return PipelineContext(
        root=root,
        config=config,
        run_id=run_id,
        run_dir=output_root / run_id,
        dry_run=bool(args.dry_run),
        keep_going=bool(args.keep_going),
        selected_packs=packs,
        serial=args.serial,
        adb_override=args.adb,
        allow_physical_device=bool(args.allow_physical_device),
        promote=bool(args.promote),
        archive=archive,
        executor=executor or SubprocessExecutor(),
    )


def requested_stages(args: argparse.Namespace, config: Mapping[str, Any]) -> list[str]:
    if args.command == "bootstrap":
        return ["bootstrap"]
    if args.command == "iterate":
        roots = list(args.stage or config["profiles"][args.profile])
        unknown = sorted(set(roots) - set(STAGES))
        if unknown:
            raise PipelineError("unknown stage(s): " + ", ".join(unknown))
        components = set(args.component or ["all"])
        if "all" not in components:
            roots = [name for name in roots if STAGES[name].component in components]
        if not roots:
            raise PipelineError("component filter selected no stages")
        return roots
    if args.command == "pack":
        return {
            "qa": ["petpack-qa"],
            "candidate": ["petpack-candidate"],
            "publish": ["petpack-publish"],
        }[args.action]
    if args.command == "deploy":
        return ["android-deploy" if args.target == "android" else "petpack-stage"]
    raise PipelineError(f"command {args.command} has no pipeline stages")


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = repository_root()
    try:
        config = load_config(root, args.config)
        if args.command == "doctor":
            return doctor(root, config, strict=args.strict, as_json=args.json)
        if args.command == "intake":
            destination = create_intake(
                root,
                config,
                args.intake_id,
                title=args.title,
                pack_class=args.pack_class,
            )
            print(destination)
            print("Intake created. Do not run petpack.py new until identity and rights are locked.")
            return 0
        if args.command == "status":
            output_root = resolve_project_path(root, config, "outputRoot")
            if not RUN_ID_RE.fullmatch(args.run_id):
                raise PipelineError(f"invalid run id: {args.run_id!r}")
            summary = output_root / args.run_id / "summary.json"
            print(summary.read_text(encoding="utf-8"))
            return 0
        context = context_from_args(args, root, config)
        return Pipeline(context).run(requested_stages(args, config))
    except PipelineError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
