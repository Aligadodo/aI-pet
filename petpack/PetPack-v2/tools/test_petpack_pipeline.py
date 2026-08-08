#!/usr/bin/env python3
"""Regression tests for the reusable PetPack authoring pipeline."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from PIL import Image

import petpack
import petpack_pipeline


PROJECT_ROOT = Path(__file__).resolve().parents[1]
JK_PACK = PROJECT_ROOT / "packs" / "jk-beach-summer"
JK_RELEASE = PROJECT_ROOT / "dist" / "jk-beach-summer-1.0.0.petpack"
REPOSITORY_RELEASE_MANIFEST = PROJECT_ROOT.parents[1] / "docs" / "release-manifest.json"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_file():
            digest.update(path.relative_to(root).as_posix().encode("utf-8"))
            digest.update(path.read_bytes())
    return digest.hexdigest()


def expected_jk_release() -> tuple[str, int]:
    if REPOSITORY_RELEASE_MANIFEST.is_file():
        manifest = json.loads(REPOSITORY_RELEASE_MANIFEST.read_text(encoding="utf-8-sig"))
        for artifact in manifest.get("artifacts", []):
            if artifact.get("assetName") == "jk-beach-summer-1.0.0.petpack":
                return str(artifact["sha256"]), int(artifact["bytes"])
        raise AssertionError("JK release is missing from docs/release-manifest.json")
    return sha256(JK_RELEASE), JK_RELEASE.stat().st_size


class PetPackPipelineTest(unittest.TestCase):
    def temporary_path(self, name: str) -> Path:
        temporary = tempfile.TemporaryDirectory(prefix="sweetpet-pipeline-test-")
        self.addCleanup(temporary.cleanup)
        return Path(temporary.name) / name

    def initialized_pack(self) -> Path:
        pack = self.temporary_path("pack")
        petpack_pipeline.initialize_pack(
            pack,
            pack_id="pipeline-test",
            name='流水线 "测试"',
            version="0.1.0",
        )
        return pack

    def test_new_template_is_immediately_valid(self) -> None:
        pack = self.initialized_pack()
        manifest, errors = petpack.validate_pack(pack, verify_checksums=True)
        self.assertEqual([], errors)
        self.assertEqual("pipeline-test", manifest["id"])
        self.assertEqual('流水线 "测试"', manifest["name"])

    def test_qa_report_marks_deterministic_build_as_not_run(self) -> None:
        pack = self.initialized_pack()
        reports = self.temporary_path("qa-reports")
        report = petpack_pipeline.run_qa(pack, reports=reports, strict=True)
        self.assertIsNone(report["deterministicBuild"])
        markdown = (reports / "qa-report.md").read_text(encoding="utf-8")
        self.assertIn("确定性复建：未运行", markdown)
        self.assertNotIn("确定性复建：未通过", markdown)

    def test_build_is_deterministic_atomic_and_does_not_mutate_source(self) -> None:
        pack = self.initialized_pack()
        before = tree_hash(pack)
        first = self.temporary_path("first.petpack")
        second = self.temporary_path("second.petpack")
        petpack.build_pack(pack, first)
        petpack.build_pack(pack, second)
        self.assertEqual(before, tree_hash(pack))
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_generated_checksums_have_canonical_crlf_bytes(self) -> None:
        pack = self.initialized_pack()
        checksum_bytes = (pack / "checksums.json").read_bytes()
        self.assertIn(b"\r\n", checksum_bytes)
        self.assertNotIn(b"\r\r\n", checksum_bytes)
        self.assertEqual(0, checksum_bytes.replace(b"\r\n", b"").count(b"\n"))

    def test_archive_metadata_uses_canonical_host_system(self) -> None:
        pack = self.initialized_pack()
        output = self.temporary_path("metadata.petpack")

        real_zip_info = zipfile.ZipInfo

        class UnixDefaultZipInfo(real_zip_info):
            def __init__(self, *args: object, **kwargs: object) -> None:
                super().__init__(*args, **kwargs)
                self.create_system = 3

        # Reproduce ZipInfo's Linux default even when this test runs on Windows.
        with mock.patch.object(zipfile, "ZipInfo", UnixDefaultZipInfo):
            petpack.build_pack(pack, output)
        with zipfile.ZipFile(output) as archive:
            self.assertTrue(archive.infolist())
            for info in archive.infolist():
                self.assertEqual(0, info.create_system, info.filename)
                self.assertEqual((2026, 1, 1, 0, 0, 0), info.date_time, info.filename)
                self.assertEqual(0o644 << 16, info.external_attr, info.filename)

    def test_failed_build_preserves_existing_output(self) -> None:
        pack = self.initialized_pack()
        manifest_path = pack / "pack.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["version"] = "not-a-version"
        manifest_path.write_text(json.dumps(manifest) + "\n", encoding="utf-8")
        output = self.temporary_path("existing.petpack")
        output.write_bytes(b"previous-good-build")
        with self.assertRaises(ValueError):
            petpack.build_pack(pack, output)
        self.assertEqual(b"previous-good-build", output.read_bytes())

    def test_safe_normalization_repairs_only_the_snapshot(self) -> None:
        source = self.initialized_pack()
        source_frame = source / "character" / "animations" / "idle" / "frame_00.png"
        with Image.open(source_frame) as image:
            image.resize((240, 300)).save(source_frame, format="PNG")
        source_hash = sha256(source_frame)
        snapshot = self.temporary_path("snapshot")
        shutil.copytree(source, snapshot)
        manifest = petpack.load_json(snapshot / "pack.json")
        changed = petpack_pipeline.normalize_frames(snapshot, manifest, "safe")
        self.assertEqual(["character/animations/idle/frame_00.png"], changed)
        with Image.open(snapshot / "character" / "animations" / "idle" / "frame_00.png") as normalized:
            self.assertEqual((512, 512), normalized.size)
            self.assertEqual("RGBA", normalized.mode)
        self.assertEqual(source_hash, sha256(source_frame))

    def test_semantic_lint_rejects_unknown_placeholder_and_setting(self) -> None:
        pack = self.initialized_pack()
        dialogue_path = pack / "dialogue" / "zh-CN.json"
        dialogue = json.loads(dialogue_path.read_text(encoding="utf-8"))
        dialogue["tap"] = ["未知变量 {secret_token}"]
        dialogue_path.write_text(json.dumps(dialogue, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (pack / "settings").mkdir()
        (pack / "settings" / "schema.json").write_text(
            json.dumps({"schemaVersion": 1, "settings": []}) + "\n",
            encoding="utf-8",
        )
        (pack / "dialogue" / "rules.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "rules": [
                        {
                            "id": "bad-setting",
                            "event": "idle",
                            "when": {"settingKey": "missing", "settingEquals": "true"},
                            "lines": ["测试规则"],
                        }
                    ],
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        manifest_path = pack / "pack.json"
        manifest = petpack.load_json(manifest_path)
        manifest["extensions"] = [
            {
                "id": "io.sweetpet.pack-settings",
                "apiVersion": 1,
                "entrypoint": "settings/schema.json",
                "required": False,
            },
            {
                "id": "io.sweetpet.dialogue-rules",
                "apiVersion": 1,
                "entrypoint": "dialogue/rules.json",
                "required": False,
            },
        ]
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False) + "\n", encoding="utf-8")
        diagnostics = petpack_pipeline.semantic_lint(pack, manifest)
        self.assertTrue(any(item.code == "copy.unknown-placeholder" for item in diagnostics))
        self.assertTrue(any(item.code == "reference.setting" for item in diagnostics))

    def test_release_cli_returns_nonzero_and_does_not_publish_on_lint_error(self) -> None:
        pack = self.initialized_pack()
        dialogue_path = pack / "dialogue" / "zh-CN.json"
        dialogue = json.loads(dialogue_path.read_text(encoding="utf-8"))
        dialogue["tap"] = ["未知变量 {secret_token}"]
        dialogue_path.write_text(json.dumps(dialogue, ensure_ascii=False) + "\n", encoding="utf-8")
        output = self.temporary_path("must-not-exist.petpack")
        reports = self.temporary_path("failed-reports")
        result = subprocess.run(
            [
                sys.executable,
                str(PROJECT_ROOT / "tools" / "petpack.py"),
                "release",
                str(pack),
                "--output",
                str(output),
                "--reports",
                str(reports),
            ],
            check=False,
            capture_output=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())
        self.assertTrue((reports / "qa-report.json").is_file())

    def test_jk_release_is_lossless_reproducible_and_non_mutating(self) -> None:
        before = tree_hash(JK_PACK)
        output = self.temporary_path("jk-beach-summer-1.0.0.petpack")
        reports = self.temporary_path("reports")
        report = petpack_pipeline.run_pipeline(JK_PACK, output=output, reports=reports)
        expected_hash, expected_size = expected_jk_release()
        self.assertEqual(before, tree_hash(JK_PACK))
        self.assertEqual(expected_size, output.stat().st_size)
        self.assertEqual(expected_hash, sha256(output))
        self.assertEqual(expected_hash, report["artifact"]["sha256"])
        self.assertTrue(report["deterministicBuild"])
        self.assertEqual(0, report["summary"]["errors"])
        self.assertEqual(0, report["summary"]["normalizedFrames"])
        self.assertTrue((reports / "qa-report.json").is_file())
        self.assertTrue((reports / "qa-report.md").is_file())
        self.assertTrue((reports / "contact-sheet.png").is_file())
        self.assertTrue(output.with_name(output.name + ".sha256").is_file())

    def test_release_default_is_a_work_candidate_not_a_dist_publish(self) -> None:
        pack = self.initialized_pack()
        isolated_project = self.temporary_path("isolated-project")
        isolated_project.mkdir()
        with mock.patch.object(petpack_pipeline, "PROJECT_ROOT", isolated_project):
            report = petpack_pipeline.run_pipeline(pack, strict=True)

        candidate = (
            isolated_project
            / "work"
            / "pipeline-test"
            / "pipeline-test-0.1.0.candidate.petpack"
        )
        self.assertTrue(candidate.is_file())
        self.assertFalse((isolated_project / "dist").exists())
        self.assertEqual(candidate.name, report["artifact"]["name"])

    def test_publish_runs_gate_on_private_candidate_before_replacing_output(self) -> None:
        pack = self.initialized_pack()
        output = self.temporary_path("published.petpack")
        output.write_bytes(b"previous-release")
        sidecar = output.with_name(output.name + ".sha256")
        sidecar.write_text("previous-sidecar\n", encoding="utf-8")
        reports = self.temporary_path("publish-reports")
        reports.mkdir()
        (reports / "previous-report.txt").write_text("previous-report\n", encoding="utf-8")
        previous_reports = tree_hash(reports)
        observed: dict[str, object] = {}

        def passing_gate(candidate: Path) -> dict[str, object]:
            observed["candidateExists"] = candidate.is_file()
            observed["candidateStagedBesideOutput"] = candidate.parent.parent == output.parent
            observed["outputDuringGate"] = output.read_bytes()
            observed["sidecarDuringGate"] = sidecar.read_text(encoding="utf-8")
            observed["reportsDuringGate"] = tree_hash(reports)
            return {
                "schemaVersion": 1,
                "result": "pass",
                "archiveSha256": sha256(candidate),
                "archiveSizeBytes": candidate.stat().st_size,
                "packId": "pipeline-test",
                "version": "0.1.0",
            }

        report = petpack_pipeline.run_publish(
            pack,
            install_gate=passing_gate,
            output=output,
            reports=reports,
            strict=True,
        )

        self.assertTrue(observed["candidateExists"])
        self.assertTrue(observed["candidateStagedBesideOutput"])
        self.assertEqual(b"previous-release", observed["outputDuringGate"])
        self.assertEqual("previous-sidecar\n", observed["sidecarDuringGate"])
        self.assertEqual(previous_reports, observed["reportsDuringGate"])
        self.assertNotEqual(b"previous-release", output.read_bytes())
        self.assertEqual(
            f"{sha256(output)}  {output.name}\n",
            sidecar.read_text(encoding="utf-8"),
        )
        self.assertFalse((reports / "previous-report.txt").exists())
        self.assertEqual("pass", report["androidInstallGate"]["result"])
        self.assertEqual(sha256(output), report["artifact"]["sha256"])

    def test_failed_android_gate_preserves_previous_published_output(self) -> None:
        pack = self.initialized_pack()
        output = self.temporary_path("published.petpack")
        output.write_bytes(b"previous-release")
        sidecar = output.with_name(output.name + ".sha256")
        sidecar.write_text("previous-sidecar\n", encoding="utf-8")
        reports = self.temporary_path("failed-gate-reports")
        reports.mkdir()
        (reports / "previous-report.txt").write_text("previous-report\n", encoding="utf-8")
        previous_reports = tree_hash(reports)

        def failing_gate(candidate: Path) -> dict[str, object]:
            self.assertTrue(candidate.is_file())
            raise RuntimeError("simulated device install failure")

        with self.assertRaisesRegex(ValueError, "Android install gate failed"):
            petpack_pipeline.run_publish(
                pack,
                install_gate=failing_gate,
                output=output,
                reports=reports,
                strict=True,
            )

        self.assertEqual(b"previous-release", output.read_bytes())
        self.assertEqual("previous-sidecar\n", sidecar.read_text(encoding="utf-8"))
        self.assertEqual(previous_reports, tree_hash(reports))

    def assert_publish_commit_failure_restores_old_set(self, failing_member: str) -> None:
        pack = self.initialized_pack()
        output = self.temporary_path(f"{failing_member}-failure.petpack")
        output.write_bytes(b"previous-release")
        sidecar = output.with_name(output.name + ".sha256")
        sidecar.write_text("previous-sidecar\n", encoding="utf-8")
        reports = self.temporary_path(f"{failing_member}-failure-reports")
        reports.mkdir()
        (reports / "qa-report.json").write_text('{"release": "previous"}\n', encoding="utf-8")
        nested = reports / "nested"
        nested.mkdir()
        (nested / "sentinel.txt").write_text("previous-report-tree\n", encoding="utf-8")
        previous_reports = tree_hash(reports)

        def passing_gate(candidate: Path) -> dict[str, object]:
            return {
                "schemaVersion": 1,
                "result": "pass",
                "archiveSha256": sha256(candidate),
                "archiveSizeBytes": candidate.stat().st_size,
                "packId": "pipeline-test",
                "version": "0.1.0",
            }

        failing_target = sidecar if failing_member == "sidecar" else reports
        real_move = petpack_pipeline._move_publish_path
        failure_injected = False

        def fail_first_commit(source: Path, destination: Path) -> None:
            nonlocal failure_injected
            if destination == failing_target and not failure_injected:
                failure_injected = True
                raise OSError(f"simulated {failing_member} commit failure")
            real_move(source, destination)

        with mock.patch.object(
            petpack_pipeline,
            "_move_publish_path",
            side_effect=fail_first_commit,
        ):
            with self.assertRaisesRegex(ValueError, "Publish commit failed; previous release restored"):
                petpack_pipeline.run_publish(
                    pack,
                    install_gate=passing_gate,
                    output=output,
                    reports=reports,
                    strict=True,
                )

        self.assertTrue(failure_injected)
        self.assertEqual(b"previous-release", output.read_bytes())
        self.assertEqual("previous-sidecar\n", sidecar.read_text(encoding="utf-8"))
        self.assertEqual(previous_reports, tree_hash(reports))
        self.assertEqual(
            [],
            list(output.parent.glob(f".{output.name}.publish-staging-*")),
        )

    def test_sidecar_commit_failure_restores_old_publish_set(self) -> None:
        self.assert_publish_commit_failure_restores_old_set("sidecar")

    def test_report_commit_failure_restores_old_publish_set(self) -> None:
        self.assert_publish_commit_failure_restores_old_set("reports")

    def test_nonpassing_or_mismatched_gate_report_never_publishes(self) -> None:
        pack = self.initialized_pack()
        for label, gate_report in (
            ("reported-failure", {"result": "fail"}),
            (
                "wrong-hash",
                {
                    "result": "pass",
                    "archiveSha256": "0" * 64,
                    "archiveSizeBytes": 1,
                    "packId": "pipeline-test",
                    "version": "0.1.0",
                },
            ),
        ):
            with self.subTest(label=label):
                output = self.temporary_path(f"{label}.petpack")
                output.write_bytes(b"known-good-release")
                reports = self.temporary_path(f"{label}-reports")
                with self.assertRaisesRegex(ValueError, "Android install gate failed"):
                    petpack_pipeline.run_publish(
                        pack,
                        install_gate=lambda _candidate, value=gate_report: value,
                        output=output,
                        reports=reports,
                        strict=True,
                    )
                self.assertEqual(b"known-good-release", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
