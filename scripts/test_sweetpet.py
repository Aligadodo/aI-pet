from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("sweetpet.py")
SPEC = importlib.util.spec_from_file_location("sweetpet_automation", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
sweetpet = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sweetpet
SPEC.loader.exec_module(sweetpet)


class FakeExecutor(sweetpet.Executor):
    def __init__(self, exit_codes: list[int] | None = None) -> None:
        self.calls: list[tuple[list[str], Path, Path]] = []
        self.envs: list[dict[str, str] | None] = []
        self.exit_codes = list(exit_codes or [])

    def run(self, argv, *, cwd, log_path, env=None):
        self.calls.append((list(argv), cwd, log_path))
        self.envs.append(dict(env) if env is not None else None)
        return self.exit_codes.pop(0) if self.exit_codes else 0


def minimal_config(root: Path) -> dict:
    return {
        "schemaVersion": 1,
        "paths": {
            "android": "android project",
            "desktop": "desktop project",
            "petpack": "petpack project",
            "outputRoot": "outputs/pipeline",
        },
        "versions": {"android": "0.0.0", "desktop": "0.0.0"},
        "profiles": {
            "quick": ["petpack-qa"],
            "ci": ["petpack-qa"],
            "full": ["petpack-candidate"],
            "release": ["petpack-publish"],
        },
        "packs": {
            "sample": {
                "path": "packs/sample",
                "qaByDefault": True,
                "acceptedWarnings": [],
            }
        },
    }


class SweetPetAutomationTest(unittest.TestCase):
    def test_android_test_always_verifies_complete_bundled_pack_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            context = sweetpet.PipelineContext(
                root=root,
                config=minimal_config(root),
                run_id="android-bundle-check",
                run_dir=root / "outputs" / "android-bundle-check",
                dry_run=False,
                keep_going=False,
                selected_packs=(),
                serial=None,
                adb_override=None,
                allow_physical_device=False,
                promote=False,
                archive=None,
                executor=FakeExecutor(),
            )

            sweetpet.stage_android_test(context)

            self.assertEqual(len(context.executor.calls), 2)
            self.assertEqual(
                context.executor.calls[1][0],
                [
                    sys.executable,
                    str(root / "scripts" / "test_bundled_pack_assets.py"),
                ],
            )
            self.assertEqual(
                context.executor.envs[1],
                {"SWEETPET_REQUIRE_CAMPUS_BUNDLE": "1"},
            )

    def test_dependency_order_closes_and_deduplicates(self) -> None:
        order = sweetpet.dependency_order(
            ["petpack-qa", "petpack-validate", "petpack-qa"]
        )
        self.assertEqual(
            order, ["tooling-test", "petpack-validate", "petpack-qa"]
        )

    def test_unknown_stage_is_rejected(self) -> None:
        with self.assertRaisesRegex(sweetpet.PipelineError, "unknown stage"):
            sweetpet.dependency_order(["not-a-stage"])

    def test_component_filter_reports_unknown_stage_without_traceback(self) -> None:
        args = mock.Mock(
            command="iterate",
            stage=["not-a-stage"],
            profile="quick",
            component=["petpack"],
        )
        with self.assertRaisesRegex(sweetpet.PipelineError, "unknown stage"):
            sweetpet.requested_stages(args, minimal_config(Path.cwd()))

    def test_load_config_rejects_unknown_profile_stage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = minimal_config(root)
            config["profiles"]["ci"] = ["not-a-stage"]
            path = root / "pipeline.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            with self.assertRaisesRegex(sweetpet.PipelineError, "unknown stage"):
                sweetpet.load_config(root, path)

    def test_pack_version_uses_protocol_v2_top_level_field(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            pack = Path(directory)
            (pack / "pack.json").write_text(
                json.dumps({"schemaVersion": 2, "id": "sample", "version": "1.2.3"}),
                encoding="utf-8",
            )
            self.assertEqual(sweetpet.pack_version(pack), "1.2.3")

    def test_warning_allowlist_must_match_exactly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "qa-report.json"
            report.write_text(
                json.dumps(
                    {
                        "diagnostics": [
                            {
                                "severity": "warning",
                                "code": "frame.size-pop",
                                "location": "photo_pose",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            config = minimal_config(root)
            config["packs"]["sample"]["acceptedWarnings"] = [
                {"code": "frame.size-pop", "location": "photo_pose"}
            ]
            sweetpet.verify_qa_report(report, config, "sample")
            config["packs"]["sample"]["acceptedWarnings"] = []
            with self.assertRaisesRegex(sweetpet.PipelineError, "allowlist mismatch"):
                sweetpet.verify_qa_report(report, config, "sample")

    def test_stale_warning_allowlist_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "qa-report.json"
            report.write_text('{"diagnostics": []}', encoding="utf-8")
            config = minimal_config(Path(directory))
            config["packs"]["sample"]["acceptedWarnings"] = [
                {"code": "copy.duplicate", "location": "tasks[0]"}
            ]
            with self.assertRaisesRegex(sweetpet.PipelineError, "staleAllowlistEntries"):
                sweetpet.verify_qa_report(report, config, "sample")

    def test_artifact_manifest_is_sorted_and_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run / "artifacts" / "z").mkdir(parents=True)
            (run / "artifacts" / "z" / "b.bin").write_bytes(b"b")
            (run / "artifacts" / "a.bin").write_bytes(b"alpha")
            first = sweetpet.build_artifact_manifest(run)
            second = sweetpet.build_artifact_manifest(run)
            self.assertEqual(first, second)
            self.assertEqual(
                [item["path"] for item in first["artifacts"]],
                ["artifacts/a.bin", "artifacts/z/b.bin"],
            )
            self.assertEqual(first["totalBytes"], 6)
            self.assertRegex(first["artifacts"][0]["sha256"], r"^[0-9a-f]{64}$")

    def test_deterministic_zip_tree_is_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / "Package"
            package.mkdir()
            (package / "中文.txt").write_text("hello", encoding="utf-8")
            (package / "nested").mkdir()
            (package / "nested" / "data.bin").write_bytes(b"\x00\x01")
            left = root / "left.zip"
            right = root / "right.zip"
            sweetpet.deterministic_zip_tree(package, left)
            sweetpet.deterministic_zip_tree(package, right)
            self.assertEqual(left.read_bytes(), right.read_bytes())

    def test_petpack_promotion_is_transactional_and_restores_old_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "run" / "sample-1.0.0.petpack"
            reports = root / "run" / "reports"
            source.parent.mkdir(parents=True)
            reports.mkdir()
            source.write_bytes(b"new-pack")
            digest = sweetpet.sha256_file(source)
            Path(f"{source}.sha256").write_text(
                f"{digest}  {source.name}\n", encoding="utf-8"
            )
            artifact = {
                "name": source.name,
                "sizeBytes": source.stat().st_size,
                "sha256": digest,
            }
            gate = {
                "schemaVersion": 1,
                "result": "pass",
                "archive": r"D:\private\candidate.petpack",
                "archiveSha256": digest,
                "archiveSizeBytes": source.stat().st_size,
                "deviceSerial": "emulator-5554",
                "deviceType": "emulator",
                "physicalDeviceExplicitlyAllowed": False,
                "vivoScannerCompatibility": "pass",
                "packId": "sample",
                "version": "1.0.0",
                "actions": 1,
                "tasks": 0,
            }
            (reports / "qa-report.json").write_text(
                json.dumps({"artifact": artifact, "androidInstallGate": gate}),
                encoding="utf-8",
            )

            canonical = root / "dist" / source.name
            canonical_reports = root / "reports" / "sample-1.0.0"
            canonical.parent.mkdir()
            canonical_reports.mkdir(parents=True)
            canonical.write_bytes(b"old-pack")
            old_digest = sweetpet.sha256_file(canonical)
            canonical_sidecar = Path(f"{canonical}.sha256")
            canonical_sidecar.write_text(
                f"{old_digest}  {canonical.name}\n", encoding="utf-8"
            )
            (canonical_reports / "qa-report.json").write_text(
                '{"artifact":"old"}', encoding="utf-8"
            )

            real_move = sweetpet._move_promoted_path

            def fail_on_new_sidecar(move_source, destination):
                if (
                    destination == canonical_sidecar
                    and move_source.parent.name != "backups"
                ):
                    raise OSError("injected sidecar move failure")
                real_move(move_source, destination)

            with mock.patch.object(
                sweetpet, "_move_promoted_path", side_effect=fail_on_new_sidecar
            ):
                with self.assertRaisesRegex(sweetpet.PipelineError, "previous canonical"):
                    sweetpet.promote_petpack_publish_set(
                        source,
                        reports,
                        canonical,
                        canonical_reports,
                        runtime_version="0.0.0",
                    )
            self.assertEqual(canonical.read_bytes(), b"old-pack")
            self.assertEqual(
                canonical_sidecar.read_text(encoding="utf-8"),
                f"{old_digest}  {canonical.name}\n",
            )
            self.assertEqual(
                (canonical_reports / "qa-report.json").read_text(encoding="utf-8"),
                '{"artifact":"old"}',
            )

            sweetpet.promote_petpack_publish_set(
                source,
                reports,
                canonical,
                canonical_reports,
                runtime_version="0.0.0",
            )
            self.assertEqual(canonical.read_bytes(), b"new-pack")
            self.assertEqual(
                json.loads(
                    (canonical_reports / "qa-report.json").read_text(encoding="utf-8")
                )["artifact"],
                artifact,
            )
            public_gate = json.loads(
                (canonical_reports / "android-install-gate-v0.0.0.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(public_gate["archive"], source.name)
            self.assertEqual(public_gate["deviceSerial"], "dedicated-emulator")
            self.assertNotIn("androidInstallGate", json.loads(
                (canonical_reports / "qa-report.json").read_text(encoding="utf-8")
            ))

    def test_promotion_lock_contention_preserves_the_owner_lock(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / ".sample.petpack.promote.lock"
            with sweetpet.promotion_lock(lock_path):
                with self.assertRaisesRegex(
                    sweetpet.PipelineError, "another PetPack promotion holds the lock"
                ):
                    with sweetpet.promotion_lock(lock_path):
                        self.fail("a competing promotion unexpectedly acquired the lock")
                with self.assertRaises(sweetpet.PipelineError):
                    with sweetpet.promotion_lock(lock_path):
                        self.fail("the failed contender released the owner's lock")

            with sweetpet.promotion_lock(lock_path):
                pass

    def test_failed_promotion_recovery_preserves_backups(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "run" / "sample-1.0.0.petpack"
            reports = root / "run" / "reports"
            source.parent.mkdir(parents=True)
            reports.mkdir()
            source.write_bytes(b"new-pack")
            digest = sweetpet.sha256_file(source)
            Path(f"{source}.sha256").write_text(
                f"{digest}  {source.name}\n", encoding="utf-8"
            )
            gate = {
                "schemaVersion": 1,
                "result": "pass",
                "archive": "private",
                "archiveSha256": digest,
                "archiveSizeBytes": source.stat().st_size,
                "deviceSerial": "emulator-5554",
                "deviceType": "emulator",
                "physicalDeviceExplicitlyAllowed": False,
                "vivoScannerCompatibility": "pass",
                "packId": "sample",
                "version": "1.0.0",
                "actions": 1,
                "tasks": 0,
            }
            (reports / "qa-report.json").write_text(
                json.dumps(
                    {
                        "artifact": {
                            "name": source.name,
                            "sizeBytes": source.stat().st_size,
                            "sha256": digest,
                        },
                        "androidInstallGate": gate,
                    }
                ),
                encoding="utf-8",
            )
            canonical = root / "dist" / source.name
            canonical_reports = root / "reports" / "sample-1.0.0"
            canonical.parent.mkdir()
            canonical_reports.mkdir(parents=True)
            canonical.write_bytes(b"old-pack")
            old_digest = sweetpet.sha256_file(canonical)
            Path(f"{canonical}.sha256").write_text(
                f"{old_digest}  {canonical.name}\n", encoding="utf-8"
            )
            (canonical_reports / "qa-report.json").write_text(
                '{"artifact":"old"}', encoding="utf-8"
            )

            real_move = sweetpet._move_promoted_path

            def fail_commit_sidecar(move_source, destination):
                if destination == Path(f"{canonical}.sha256"):
                    raise OSError("injected commit failure")
                real_move(move_source, destination)

            with mock.patch.object(
                sweetpet, "_move_promoted_path", side_effect=fail_commit_sidecar
            ), mock.patch.object(
                sweetpet,
                "_restore_promotion",
                side_effect=OSError("injected restore failure"),
            ):
                with self.assertRaisesRegex(sweetpet.PipelineError, "backups preserved"):
                    sweetpet.promote_petpack_publish_set(
                        source,
                        reports,
                        canonical,
                        canonical_reports,
                        runtime_version="0.0.0",
                    )
            workspaces = list(canonical.parent.glob(f".{canonical.name}.promote-*"))
            self.assertEqual(len(workspaces), 1)
            self.assertEqual((workspaces[0] / "backups" / "output").read_bytes(), b"old-pack")
            state = json.loads(
                (workspaces[0] / sweetpet.PROMOTION_STATE).read_text(encoding="utf-8")
            )
            self.assertEqual(state["status"], "committing")
            sweetpet.recover_stale_promotions(canonical, canonical_reports)
            self.assertEqual(canonical.read_bytes(), b"old-pack")
            self.assertFalse(workspaces[0].exists())

    def test_component_versions_must_match_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = minimal_config(root)
            android = root / "android project" / "app"
            desktop = root / "desktop project"
            android.mkdir(parents=True)
            desktop.mkdir(parents=True)
            (android / "build.gradle.kts").write_text(
                'versionName = "0.0.0"\n', encoding="utf-8"
            )
            (desktop / "app.py").write_text(
                'APP_VERSION = "9.9.9"\n', encoding="utf-8"
            )
            context = sweetpet.PipelineContext(
                root=root,
                config=config,
                run_id="version-check",
                run_dir=root / "outputs" / "version-check",
                dry_run=True,
                keep_going=False,
                selected_packs=(),
                serial=None,
                adb_override=None,
                allow_physical_device=False,
                promote=False,
                archive=None,
                executor=FakeExecutor(),
            )
            self.assertEqual(sweetpet.verify_component_version(context, "android"), "0.0.0")
            with self.assertRaisesRegex(sweetpet.PipelineError, "version drift"):
                sweetpet.verify_component_version(context, "desktop")

    def test_dry_run_has_no_executor_or_filesystem_side_effect(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = minimal_config(root)
            run_dir = root / "outputs" / "dry-run"
            executor = FakeExecutor()

            def handler(context):
                context.command(
                    "sample-stage",
                    "path-test",
                    ["tool", root / "目录 with spaces" / "file.bin"],
                    cwd=root,
                )

            stage = sweetpet.StageDefinition("sample-stage", "tooling", (), handler)
            context = sweetpet.PipelineContext(
                root=root,
                config=config,
                run_id="dry-run",
                run_dir=run_dir,
                dry_run=True,
                keep_going=False,
                selected_packs=(),
                serial=None,
                adb_override=None,
                allow_physical_device=False,
                promote=False,
                archive=None,
                executor=executor,
            )
            with mock.patch.dict(sweetpet.STAGES, {"sample-stage": stage}, clear=True):
                self.assertEqual(sweetpet.Pipeline(context).run(["sample-stage"]), 0)
            self.assertEqual(executor.calls, [])
            self.assertFalse(run_dir.exists())

    def test_first_command_failure_stops_dependents_and_writes_summary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = minimal_config(root)
            executor = FakeExecutor([7])

            def failing(context):
                context.command("first", "fail", ["fake", "arg"], cwd=root)

            def dependent(context):
                context.command("second", "must-not-run", ["fake"], cwd=root)

            stages = {
                "first": sweetpet.StageDefinition("first", "tooling", (), failing),
                "second": sweetpet.StageDefinition(
                    "second", "tooling", ("first",), dependent
                ),
            }
            context = sweetpet.PipelineContext(
                root=root,
                config=config,
                run_id="failure-run",
                run_dir=root / "outputs" / "failure-run",
                dry_run=False,
                keep_going=False,
                selected_packs=(),
                serial=None,
                adb_override=None,
                allow_physical_device=False,
                promote=False,
                archive=None,
                executor=executor,
            )
            with mock.patch.dict(sweetpet.STAGES, stages, clear=True):
                self.assertEqual(sweetpet.Pipeline(context).run(["second"]), 1)
            self.assertEqual(len(executor.calls), 1)
            summary = json.loads(
                (context.run_dir / "summary.json").read_text(encoding="utf-8")
            )
            self.assertEqual(summary["overall"], "failed")
            statuses = {item["name"]: item["status"] for item in summary["stages"]}
            self.assertEqual(statuses, {"first": "failed", "second": "skipped"})

    def test_executor_receives_chinese_space_path_as_one_argument(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executor = FakeExecutor()
            context = sweetpet.PipelineContext(
                root=root,
                config=minimal_config(root),
                run_id="path-run",
                run_dir=root / "outputs" / "path-run",
                dry_run=False,
                keep_going=False,
                selected_packs=(),
                serial=None,
                adb_override=None,
                allow_physical_device=False,
                promote=False,
                archive=None,
                executor=executor,
            )
            argument = root / "中文 path" / "pack.petpack"

            def handler(current):
                current.command("path", "one", ["fake", argument], cwd=root)

            stages = {
                "path": sweetpet.StageDefinition("path", "tooling", (), handler)
            }
            with mock.patch.dict(sweetpet.STAGES, stages, clear=True):
                self.assertEqual(sweetpet.Pipeline(context).run(["path"]), 0)
            self.assertEqual(executor.calls[0][0], ["fake", str(argument)])

    def test_create_intake_sets_identity_without_creating_pack_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = minimal_config(root)
            template = root / "petpack project" / "templates" / "intake-v1"
            template.mkdir(parents=True)
            base = {
                "schemaVersion": 1,
                "intakeId": None,
                "revision": 1,
                "status": "collecting",
            }
            for name in (
                "brief.json",
                "sources.manifest.json",
                "actions.manifest.json",
                "content-plan.json",
            ):
                payload = dict(base)
                if name == "brief.json":
                    payload["technicalBaseline"] = {}
                if name == "actions.manifest.json":
                    payload["baseline"] = {}
                (template / name).write_text(json.dumps(payload), encoding="utf-8")
            (template / "README.md").write_text("intake", encoding="utf-8")
            (template / "acceptance-checklist.md").write_text(
                "Intake: {{INTAKE_ID}}", encoding="utf-8"
            )
            output = sweetpet.create_intake(
                root,
                config,
                "next-pack-001",
                title="待定角色",
                pack_class="game-compatible",
            )
            brief = json.loads((output / "brief.json").read_text(encoding="utf-8"))
            actions = json.loads(
                (output / "actions.manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(brief["intakeId"], "next-pack-001")
            self.assertEqual(brief["intakeTitle"], "待定角色")
            self.assertEqual(brief["technicalBaseline"]["packClass"], "full-motion")
            self.assertTrue(brief["technicalBaseline"]["gameCompatible"])
            self.assertTrue(actions["baseline"]["gameCompatible"])
            self.assertEqual(
                (output / "acceptance-checklist.md").read_text(encoding="utf-8"),
                "Intake: next-pack-001",
            )
            self.assertFalse((output / "pack.json").exists())
            with self.assertRaisesRegex(sweetpet.PipelineError, "already exists"):
                sweetpet.create_intake(
                    root,
                    config,
                    "next-pack-001",
                    title=None,
                    pack_class="game-compatible",
                )
            with self.assertRaisesRegex(sweetpet.PipelineError, "only.*game-compatible"):
                sweetpet.create_intake(
                    root,
                    config,
                    "static-pack",
                    title=None,
                    pack_class="static",
                )


if __name__ == "__main__":
    unittest.main()
