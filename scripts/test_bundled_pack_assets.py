"""Verify every released Android bundle is byte-identical to its PetPack source.

The campus pack can exist as an authoring scaffold while its sprites are still being
created.  Local runs defer that one comparison until it declares the complete
``idle``/``walk``/``run`` action set.  CI and release gates set
``SWEETPET_REQUIRE_CAMPUS_BUNDLE=1`` so a missing or partial campus bundle can never
reach a published APK.
"""

from __future__ import annotations

import hashlib
import json
import os
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = REPOSITORY_ROOT / "petpack" / "PetPack-v2" / "packs"
BUNDLED_ROOT = (
    REPOSITORY_ROOT
    / "android"
    / "SweetGirlfriendPetAndroid"
    / "app"
    / "src"
    / "main"
    / "assets"
    / "packs"
)
EXPECTED_PACK_IDS = [
    "girlfriend-classic",
    "jk-beach-summer",
    "nju-campus-girlfriend",
]
CORE_CAMPUS_ACTIONS = {"idle", "walk", "run"}


def file_hashes(root: Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def campus_source_is_complete() -> bool:
    animations = SOURCE_ROOT / "nju-campus-girlfriend" / "character" / "animations.json"
    if not animations.is_file():
        return False
    payload = json.loads(animations.read_text(encoding="utf-8"))
    actions = payload.get("actions")
    return isinstance(actions, dict) and CORE_CAMPUS_ACTIONS <= set(actions)


class BundledPackAssetTest(unittest.TestCase):
    def test_index_has_all_three_packs_in_stable_order(self) -> None:
        index_path = BUNDLED_ROOT / "index.json"
        payload = json.loads(index_path.read_text(encoding="utf-8"))
        self.assertEqual(payload.get("schemaVersion"), 1)
        self.assertEqual(payload.get("packs"), EXPECTED_PACK_IDS)

    def test_completed_sources_match_android_assets_file_for_file(self) -> None:
        require_campus = os.environ.get("SWEETPET_REQUIRE_CAMPUS_BUNDLE") == "1"
        campus_complete = campus_source_is_complete()
        if require_campus:
            self.assertTrue(
                campus_complete,
                "campus source must declare idle, walk, and run before CI/release",
            )

        for pack_id in EXPECTED_PACK_IDS:
            with self.subTest(pack_id=pack_id):
                if pack_id == "nju-campus-girlfriend" and not campus_complete:
                    continue

                source = SOURCE_ROOT / pack_id
                bundled = BUNDLED_ROOT / pack_id
                self.assertTrue(source.is_dir(), f"missing authoritative source: {source}")
                self.assertTrue(bundled.is_dir(), f"missing Android bundle: {bundled}")

                source_hashes = file_hashes(source)
                bundled_hashes = file_hashes(bundled)
                self.assertTrue(source_hashes, f"source pack is empty: {source}")
                self.assertEqual(
                    set(bundled_hashes),
                    set(source_hashes),
                    "bundled file set differs from authoritative source",
                )
                self.assertEqual(
                    bundled_hashes,
                    source_hashes,
                    "bundled content hash differs from authoritative source",
                )


if __name__ == "__main__":
    unittest.main(verbosity=2)
