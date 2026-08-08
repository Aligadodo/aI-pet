#!/usr/bin/env python3
"""Pack-specific regression gates for the Nanjing campus character alignment."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import numpy as np
from PIL import Image


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PACK_ROOT = PROJECT_ROOT / "packs" / "nju-campus-girlfriend"
ANIMATION_ROOT = PACK_ROOT / "character" / "animations"


def load_frame(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGBA"), dtype=np.float64)


def alpha_bounds(frame: np.ndarray) -> tuple[int, int, int, int]:
    ys, xs = np.nonzero(frame[:, :, 3] > 16)
    if not len(xs):
        raise AssertionError("frame has no visible pixels")
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def upper_body_center(frame: np.ndarray) -> float:
    alpha = frame[:, :, 3]
    _, top, _, bottom = alpha_bounds(frame)
    height = bottom - top
    region_top = int(top + height * 0.12)
    region_bottom = int(top + height * 0.50)
    region = alpha[region_top:region_bottom]
    _, xs = np.nonzero(region > 32)
    weights = region[region > 32]
    return float(np.average(xs, weights=weights))


def trouser_root_center(frame: np.ndarray) -> float:
    """Track the burgundy trouser mass, a stable locomotion root proxy."""
    alpha = frame[:, :, 3]
    red, green, blue = frame[:, :, 0], frame[:, :, 1], frame[:, :, 2]
    _, top, _, bottom = alpha_bounds(frame)
    row = np.arange(frame.shape[0])[:, None]
    mask = (
        (alpha > 32)
        & (red > 45)
        & (red > green * 1.22)
        & (red > blue * 1.05)
        & (row > top + (bottom - top) * 0.38)
    )
    ys, xs = np.nonzero(mask)
    if len(xs) < 500:
        raise AssertionError("not enough trouser pixels to locate locomotion root")
    return float(np.average(xs, weights=alpha[ys, xs]))


def action_frames(action_id: str) -> list[np.ndarray]:
    paths = sorted((ANIMATION_ROOT / action_id).glob("frame_*.png"))
    if not paths:
        raise AssertionError(f"missing frames for {action_id}")
    return [load_frame(path) for path in paths]


class NjuCampusAlignmentTest(unittest.TestCase):
    def test_pack_version_marks_the_corrected_release(self) -> None:
        manifest = json.loads((PACK_ROOT / "pack.json").read_text(encoding="utf-8"))
        self.assertEqual("1.0.1", manifest["version"])

    def test_stationary_actions_do_not_jump_sideways_between_frames(self) -> None:
        # Thresholds intentionally leave room for a natural lean while rejecting
        # the old 20-52 px root jumps that were visible during action playback.
        maximum_ranges = {
            "idle": 3.0,
            "sleep": 8.0,
            "wave": 8.0,
            "glasses_adjust": 2.0,
            "chalk_explain": 14.0,
            "flower_spot": 18.0,
        }
        for action_id, maximum_range in maximum_ranges.items():
            with self.subTest(action=action_id):
                centers = [upper_body_center(frame) for frame in action_frames(action_id)]
                self.assertLessEqual(max(centers) - min(centers), maximum_range)

    def test_walk_and_run_keep_the_pelvis_on_the_same_root_axis(self) -> None:
        for action_id in ("walk", "run"):
            with self.subTest(action=action_id):
                centers = [trouser_root_center(frame) for frame in action_frames(action_id)]
                self.assertLessEqual(max(centers) - min(centers), 1.25)
                self.assertAlmostEqual(float(np.median(centers)), 256.0, delta=0.75)

    def test_grounded_actions_keep_visible_feet_near_the_shared_baseline(self) -> None:
        for action_id in (
            "idle",
            "walk",
            "wave",
            "photo_pose",
            "autumn_leaf",
            "chalk_explain",
            "gift_hold",
            "grass_rest",
            "glasses_adjust",
            "flower_spot",
            "sleep",
        ):
            with self.subTest(action=action_id):
                bottoms = [alpha_bounds(frame)[3] - 1 for frame in action_frames(action_id)]
                self.assertTrue(all(476 <= bottom <= 481 for bottom in bottoms), bottoms)


if __name__ == "__main__":
    unittest.main()
