#!/usr/bin/env python3
"""Regression tests for the host-side Android PetPack install gate."""

from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import android_install_gate


class AndroidInstallGateTest(unittest.TestCase):
    def temporary_root(self) -> Path:
        temporary = tempfile.TemporaryDirectory(prefix="sweetpet-android-gate-test-")
        self.addCleanup(temporary.cleanup)
        return Path(temporary.name)

    def gate_fixture(self) -> tuple[Path, Path, Path, str]:
        root = self.temporary_root()
        archive = root / "pipeline-smoke.petpack"
        archive.write_bytes(b"exact staged PetPack bytes")
        expected_sha256 = hashlib.sha256(archive.read_bytes()).hexdigest()

        adb = root / "adb.exe"
        adb.write_bytes(b"test stub")
        android_project = root / "AndroidProject"
        android_project.mkdir()
        (android_project / "gradle.properties").write_text(
            "externalBuildRoot=build-output\n",
            encoding="utf-8",
        )
        apk_root = android_project / "build-output" / "app" / "outputs" / "apk"
        app_apk = apk_root / "debug" / "app-debug.apk"
        test_apk = apk_root / "androidTest" / "debug" / "app-debug-androidTest.apk"
        app_apk.parent.mkdir(parents=True)
        test_apk.parent.mkdir(parents=True)
        app_apk.write_bytes(b"app")
        test_apk.write_bytes(b"test")
        return archive, adb, android_project, expected_sha256

    @staticmethod
    def fake_runner(
        commands: list[list[str]],
        *,
        qemu_property: str = "1",
        scanner_output: str | Exception = "VIVO_SCANNER_COMPAT_PASS snapshots=1\n",
    ):
        def fake_run(command, *, timeout, cwd=None):
            del timeout, cwd
            rendered = list(command)
            commands.append(rendered)
            if "ro.kernel.qemu" in rendered:
                return f"{qemu_property}\n"
            if "vivoScannerCompatOnly" in rendered:
                if isinstance(scanner_output, Exception):
                    raise scanner_output
                return scanner_output
            if "prepareOnly" in rendered:
                return "PETPACK_GATE_STAGING=/sdcard/gate-staging\n"
            if "packPath" in rendered:
                return (
                    "PETPACK_GATE_PASS id=pipeline-smoke version=0.1.0 "
                    "actions=1 tasks=0\n"
                )
            return ""

        return fake_run

    def test_scanner_pass_precedes_staging_and_exact_sha256_is_forwarded(self) -> None:
        archive, adb, android_project, expected_sha256 = self.gate_fixture()
        commands: list[list[str]] = []

        with (
            mock.patch.object(
                android_install_gate,
                "_run",
                side_effect=self.fake_runner(commands),
            ),
            mock.patch.object(android_install_gate.subprocess, "run") as cleanup_run,
        ):
            report = android_install_gate.run_android_install_gate(
                archive,
                android_project=android_project,
                adb=adb,
                serial="emulator-test",
                skip_build=True,
            )

        scanner_index = next(
            index for index, command in enumerate(commands) if "vivoScannerCompatOnly" in command
        )
        prepare_index = next(
            index for index, command in enumerate(commands) if "prepareOnly" in command
        )
        push_index = next(index for index, command in enumerate(commands) if "push" in command)
        self.assertLess(scanner_index, prepare_index)
        self.assertLess(prepare_index, push_index)

        gate_command = next(command for command in commands if "packPath" in command)
        expected_index = gate_command.index("expectedSha256")
        self.assertEqual("-e", gate_command[expected_index - 1])
        self.assertEqual(expected_sha256, gate_command[expected_index + 1])
        self.assertEqual(64, len(gate_command[expected_index + 1]))
        self.assertEqual(expected_sha256, report["archiveSha256"])
        self.assertEqual("emulator", report["deviceType"])
        self.assertFalse(report["physicalDeviceExplicitlyAllowed"])
        self.assertEqual("pass", report["vivoScannerCompatibility"])

        pushed_path = commands[push_index][-1]
        self.assertIn(expected_sha256[:16], pushed_path)
        cleanup_run.assert_called_once()

    def assert_scanner_blocks_before_staging(self, scanner_output: str | Exception) -> None:
        archive, adb, android_project, _ = self.gate_fixture()
        commands: list[list[str]] = []
        with mock.patch.object(
            android_install_gate,
            "_run",
            side_effect=self.fake_runner(commands, scanner_output=scanner_output),
        ):
            with self.assertRaises(android_install_gate.GateFailure):
                android_install_gate.run_android_install_gate(
                    archive,
                    android_project=android_project,
                    adb=adb,
                    serial="emulator-test",
                    skip_build=True,
                )
        self.assertTrue(any("vivoScannerCompatOnly" in command for command in commands))
        self.assertFalse(any("prepareOnly" in command for command in commands))
        self.assertFalse(any("push" in command for command in commands))

    def test_scanner_missing_pass_marker_blocks_gate(self) -> None:
        self.assert_scanner_blocks_before_staging("INSTRUMENTATION_CODE: -1\n")

    def test_scanner_fail_marker_blocks_gate(self) -> None:
        self.assert_scanner_blocks_before_staging(
            "PETPACK_GATE_FAIL IllegalStateException: scanner regression\n"
        )

    def test_scanner_nonzero_command_blocks_gate(self) -> None:
        self.assert_scanner_blocks_before_staging(
            android_install_gate.GateFailure("Command failed (1): scanner gate")
        )

    def test_rejects_physical_device_before_build_or_install_by_default(self) -> None:
        archive, adb, android_project, _ = self.gate_fixture()
        commands: list[list[str]] = []
        with mock.patch.object(
            android_install_gate,
            "_run",
            side_effect=self.fake_runner(commands, qemu_property="0"),
        ):
            with self.assertRaisesRegex(
                android_install_gate.GateFailure,
                "--allow-physical-device",
            ):
                android_install_gate.run_android_install_gate(
                    archive,
                    android_project=android_project,
                    adb=adb,
                    serial="physical-test",
                    skip_build=True,
                )
        self.assertEqual(1, len(commands))
        self.assertIn("ro.kernel.qemu", commands[0])

    def test_marks_explicitly_allowed_physical_device_in_report(self) -> None:
        archive, adb, android_project, _ = self.gate_fixture()
        commands: list[list[str]] = []
        with (
            mock.patch.object(
                android_install_gate,
                "_run",
                side_effect=self.fake_runner(commands, qemu_property="0"),
            ),
            mock.patch.object(android_install_gate.subprocess, "run"),
        ):
            report = android_install_gate.run_android_install_gate(
                archive,
                android_project=android_project,
                adb=adb,
                serial="physical-test",
                skip_build=True,
                allow_physical_device=True,
            )

        self.assertEqual("physical", report["deviceType"])
        self.assertTrue(report["physicalDeviceExplicitlyAllowed"])
        self.assertIn("explicitly allowed", android_install_gate._device_summary(report))


if __name__ == "__main__":
    unittest.main()
