#!/usr/bin/env python3
"""Regression tests for the offline SweetPet pack validator."""

from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import petpack


SOURCE_PACK = Path(__file__).resolve().parents[1] / "packs" / "girlfriend-classic"


class PetPackValidationTest(unittest.TestCase):
    def copied_pack(self):
        temporary = tempfile.TemporaryDirectory(prefix="sweetpet-bad-pack-")
        self.addCleanup(temporary.cleanup)
        destination = Path(temporary.name) / "pack"
        shutil.copytree(SOURCE_PACK, destination)
        return destination

    @staticmethod
    def mutate_json(root: Path, relative: str, mutate) -> None:
        path = root / relative
        document = json.loads(path.read_text(encoding="utf-8"))
        mutate(document)
        path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    @staticmethod
    def errors(root: Path) -> list[str]:
        return petpack.validate_pack(root, verify_checksums=False)[1]

    def assert_rejected(self, root: Path, fragment: str) -> None:
        errors = self.errors(root)
        self.assertTrue(any(fragment in error for error in errors), "\n".join(errors))

    def test_reference_pack_is_valid(self) -> None:
        self.assertEqual([], self.errors(SOURCE_PACK))

    def test_pack_version_requires_strict_stable_three_part_numbers(self) -> None:
        for invalid in ("1.2.0-rc.1", "1.2", "1.x.0", "2147483648.0.0"):
            with self.subTest(version=invalid):
                pack = self.copied_pack()
                self.mutate_json(pack, "pack.json", lambda document: document.__setitem__("version", invalid))
                self.assert_rejected(pack, "pack.version must be a stable numeric x.y.z version")

    def test_protocol_version_requires_major_two_and_numeric_minor(self) -> None:
        pack = self.copied_pack()
        self.mutate_json(
            pack,
            "pack.json",
            lambda document: document["protocol"].__setitem__("version", "2.foo"),
        )
        self.assert_rejected(pack, "Only io.sweetpet.pack protocol 2.x is supported")

    def test_behavior_profiles_weights_fallback_and_rest_are_validated(self) -> None:
        pack = self.copied_pack()
        self.mutate_json(
            pack,
            "behavior/default.json",
            lambda document: document["profiles"]["daily"].__setitem__("idleWeight", -1),
        )
        self.assert_rejected(pack, "Behavior weight")

        pack = self.copied_pack()
        self.mutate_json(
            pack,
            "behavior/default.json",
            lambda document: document.__setitem__("fallbackAction", "missing_action"),
        )
        self.assert_rejected(pack, "fallbackAction references unknown action")

        pack = self.copied_pack()
        self.mutate_json(
            pack,
            "behavior/default.json",
            lambda document: document.__setitem__("manualPlacementRestSeconds", 2),
        )
        self.assert_rejected(pack, "manualPlacementRestSeconds")

    def test_motion_enums_and_ground_anchor_are_validated(self) -> None:
        pack = self.copied_pack()

        def break_motion(document):
            document["actions"]["walk"]["motion"]["rotationPolicy"] = "spin-randomly"
            document["actions"]["walk"]["motion"]["groundAnchor"] = [0.5, 1.2]

        self.mutate_json(pack, "character/animations.json", break_motion)
        errors = self.errors(pack)
        self.assertTrue(any("rotationPolicy" in error for error in errors), "\n".join(errors))
        self.assertTrue(any("groundAnchor" in error for error in errors), "\n".join(errors))

    def test_game_kit_avatar_and_supported_modes_are_validated(self) -> None:
        pack = self.copied_pack()

        def break_game_kit(document):
            document["avatar"]["crop"] = [0.7, 0.1, 0.3, 0.8]
            document["supportedModes"].append("TELEPORT")

        self.mutate_json(pack, "character/game-kit.json", break_game_kit)
        errors = self.errors(pack)
        self.assertTrue(any("avatar crop" in error for error in errors), "\n".join(errors))
        self.assertTrue(any("Unsupported game mode" in error for error in errors), "\n".join(errors))

    def test_task_action_and_play_mode_references_are_validated(self) -> None:
        pack = self.copied_pack()

        def break_task(document):
            document["tasks"][0]["action"] = "missing_action"
            document["tasks"][0]["options"][0]["playMode"] = "GRAVITY"

        def remove_mode(document):
            document["supportedModes"].remove("GRAVITY")

        self.mutate_json(pack, "tasks/tasks.json", break_task)
        self.mutate_json(pack, "character/game-kit.json", remove_mode)
        errors = self.errors(pack)
        self.assertTrue(any("action references unknown action" in error for error in errors), "\n".join(errors))
        self.assertTrue(any("playMode is not declared" in error for error in errors), "\n".join(errors))

    def test_dialogue_rule_references_and_conditions_are_validated(self) -> None:
        pack = self.copied_pack()

        def break_rule(document):
            document["rules"][0]["event"] = "missing_event"
            document["rules"][0]["when"]["chance"] = 1.5

        self.mutate_json(pack, "dialogue/rules.json", break_rule)
        errors = self.errors(pack)
        self.assertTrue(any("unknown dialogue event" in error for error in errors), "\n".join(errors))
        self.assertTrue(any("chance" in error for error in errors), "\n".join(errors))

    def test_corrupt_bitmap_is_rejected_after_pixel_decode(self) -> None:
        pack = self.copied_pack()
        frame = pack / "character" / "animations" / "idle" / "frame_00.png"
        frame.write_bytes(frame.read_bytes()[:64])
        self.assert_rejected(pack, "Unable to decode bitmap")

    def test_bitmap_budgets_are_enforced(self) -> None:
        with mock.patch.object(petpack, "MAX_SINGLE_BITMAP_PIXELS", 1), mock.patch.object(
            petpack, "MAX_CLIP_BITMAP_PIXELS", 1
        ), mock.patch.object(petpack, "MAX_PACK_BITMAP_PIXELS", 1):
            errors = self.errors(SOURCE_PACK)
        self.assertTrue(any("single-image pixel budget" in error for error in errors), "\n".join(errors))
        self.assertTrue(any("decoded pixel budget" in error for error in errors), "\n".join(errors))
        self.assertTrue(any("Pack exceeds decoded pixel budget" in error for error in errors), "\n".join(errors))


if __name__ == "__main__":
    unittest.main()
