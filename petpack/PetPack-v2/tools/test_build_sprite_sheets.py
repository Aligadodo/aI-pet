#!/usr/bin/env python3
"""Regression tests for the generic sprite-sheet post-processor."""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image, ImageDraw


TOOLS_ROOT = Path(__file__).resolve().parent
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

import build_sprite_sheets as sprites


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class SpriteSheetBuilderTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="sweetpet-sprite-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def colored_grid(self, *, rows: int, cols: int, width: int, height: int) -> tuple[Image.Image, list[tuple[int, int, int, int]]]:
        image = Image.new("RGBA", (width, height), (0, 0, 0, 255))
        draw = ImageDraw.Draw(image)
        colors: list[tuple[int, int, int, int]] = []
        for row in range(rows):
            for column in range(cols):
                index = row * cols + column
                color = (40 + index * 9, 20 + index * 3, 180 - index * 5, 255)
                colors.append(color)
                left = width * column // cols
                right = width * (column + 1) // cols
                top = height * row // rows
                bottom = height * (row + 1) // rows
                draw.rectangle((left, top, right - 1, bottom - 1), fill=color)
        return image, colors

    def motion_sheet(self, path: Path, *, rows: int = 2, cols: int = 4) -> None:
        cell_width = 96
        cell_height = 112
        image = Image.new("RGBA", (cols * cell_width, rows * cell_height), (0, 255, 0, 255))
        draw = ImageDraw.Draw(image)
        for index in range(rows * cols):
            row, column = divmod(index, cols)
            left = column * cell_width
            top = row * cell_height
            subject_width = 22 + index * 3
            subject_height = 50 + (index % 4) * 8
            subject_left = left + (cell_width - subject_width) // 2
            subject_bottom = top + cell_height - 8 - (18 if index == 1 else 0)
            draw.rectangle(
                (
                    subject_left,
                    subject_bottom - subject_height + 1,
                    subject_left + subject_width - 1,
                    subject_bottom,
                ),
                fill=(176, 45 + index, 70, 255),
            )
        image.save(path)

    def config(
        self,
        *,
        output_root: Path | None = None,
        report_path: Path | None = None,
        preview: bool = False,
    ) -> Path:
        sheet = self.root / "motion.png"
        self.motion_sheet(sheet)
        output = output_root or self.root / "output" / "animations"
        report = report_path or self.root / "output" / "sprite-report.json"
        payload: dict[str, object] = {
            "canvas": 512,
            "groundY": 481,
            "outputRoot": str(output),
            "report": str(report),
            "sheets": {
                "motion": {
                    "path": str(sheet),
                    "rows": 2,
                    "cols": 4,
                    "paddingPx": 16,
                }
            },
            "actions": [
                {
                    "id": "walk",
                    "sheet": "motion",
                    "row": 0,
                    "supportFrames": [0, 1, 2, 3],
                    "lockAllToGround": True,
                },
                {
                    "id": "wave",
                    "sheet": "motion",
                    "row": 1,
                    "supportFrames": [0, 1, 2, 3],
                    "lockAllToGround": True,
                },
            ],
        }
        if preview:
            payload.update(
                {
                    "previewAction": "wave",
                    "previewFrame": 2,
                    "previewPath": str(self.root / "output" / "preview.png"),
                }
            )
        path = self.root / "sprites.json"
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return path

    def test_exact_split_supports_four_by_two_three_and_four(self) -> None:
        chroma_disabled = sprites.ChromaKeySpec(enabled=False)
        for rows in (2, 3, 4):
            with self.subTest(grid=f"4x{rows}"):
                width = 47
                height = rows * 13 + 2
                sheet, colors = self.colored_grid(rows=rows, cols=4, width=width, height=height)
                cells = sprites.split_grid(
                    sheet,
                    rows=rows,
                    cols=4,
                    margin_px=0,
                    overlap_px=0,
                    edge_clear_px=0,
                    component_cleanup=False,
                    component_gap_px=48,
                    component_close_gap_px=8,
                    chroma_key=chroma_disabled,
                )
                self.assertEqual(rows * 4, len(cells))
                for index, cell in enumerate(cells):
                    row, column = divmod(index, 4)
                    expected_width = width * (column + 1) // 4 - width * column // 4
                    expected_height = height * (row + 1) // rows - height * row // rows
                    self.assertEqual((expected_width, expected_height), cell.size)
                    self.assertEqual(colors[index], cell.getpixel((cell.width // 2, cell.height // 2)))

    def test_chroma_key_clears_near_green_and_despills_edges(self) -> None:
        image = Image.new("RGBA", (7, 3), (0, 255, 0, 255))
        image.putpixel((1, 1), (20, 230, 10, 255))
        image.putpixel((3, 1), (80, 180, 70, 255))
        image.putpixel((4, 1), (200, 30, 40, 255))
        cleaned = sprites.clean_chroma_key(image, sprites.ChromaKeySpec())
        self.assertEqual(0, cleaned.getpixel((0, 0))[3])
        self.assertEqual(0, cleaned.getpixel((1, 1))[3])
        spill = cleaned.getpixel((3, 1))
        self.assertEqual(255, spill[3])
        self.assertLessEqual(spill[1], max(spill[0], spill[2]) + 8)
        self.assertEqual((200, 30, 40, 255), cleaned.getpixel((4, 1)))

    def test_resampled_semitransparent_edges_cannot_reintroduce_green_halo(self) -> None:
        image = Image.new("RGBA", (3, 1), (0, 0, 0, 0))
        image.putpixel((1, 0), (80, 190, 70, 128))
        image.putpixel((2, 0), (20, 180, 40, 255))
        cleaned = sprites.neutralize_resampled_edge_spill(image, tolerance=2)
        self.assertEqual((80, 82, 70, 128), cleaned.getpixel((1, 0)))
        self.assertEqual((20, 180, 40, 255), cleaned.getpixel((2, 0)))

    def test_overlap_recovers_subject_and_discards_adjacent_row_fragment(self) -> None:
        sheet = Image.new("RGBA", (120, 200), (0, 255, 0, 255))
        draw = ImageDraw.Draw(sheet)
        # Intended row-two subject crosses 10 px above the nominal boundary.
        draw.rectangle((45, 90, 75, 185), fill=(170, 45, 70, 255))
        # Unrelated row-one fragment spills 12 px below the boundary, but is
        # separated from the main subject and must not survive cleanup.
        draw.rectangle((5, 80, 25, 112), fill=(60, 70, 180, 255))

        cells = sprites.split_grid(
            sheet,
            rows=2,
            cols=1,
            margin_px=0,
            overlap_px=16,
            edge_clear_px=0,
            component_cleanup=True,
            component_gap_px=8,
            component_close_gap_px=8,
            chroma_key=sprites.ChromaKeySpec(),
        )
        second = cells[1]
        bbox = second.getchannel("A").getbbox()
        self.assertIsNotNone(bbox)
        assert bbox is not None
        # The recovered subject begins at source y=90 (local y=6), while the
        # foreign x=5 fragment has been removed.
        self.assertEqual((45, 6, 76, 102), bbox)
        self.assertEqual(0, second.getpixel((10, 20))[3])

    def test_ground_lock_uses_declared_baseline_and_one_common_scale(self) -> None:
        config = self.config()
        report = sprites.build_from_config(config)
        self.assertEqual([481, 481], report["actions"]["walk"]["bboxBottomRange"])
        widths = []
        for index in range(4):
            frame = Image.open(self.root / "output" / "animations" / "walk" / f"frame_{index:02d}.png")
            self.addCleanup(frame.close)
            self.assertEqual("RGBA", frame.mode)
            self.assertEqual((512, 512), frame.size)
            bbox = frame.getchannel("A").getbbox()
            self.assertIsNotNone(bbox)
            assert bbox is not None
            self.assertEqual(481, bbox[3] - 1)
            self.assertGreater(bbox[0], 0)
            self.assertGreater(bbox[1], 0)
            self.assertLess(bbox[2], 512)
            self.assertLess(bbox[3], 512)
            widths.append(bbox[2] - bbox[0])
        # Source widths deliberately grow; a forbidden per-frame "fit" would
        # normalize these silhouettes to nearly the same width.
        self.assertGreater(widths[-1], widths[0] * 1.25)

    def test_subject_scale_is_independent_of_contact_sheet_cell_aspect_ratio(self) -> None:
        def subject_cell(size: tuple[int, int], subject_box: tuple[int, int, int, int]) -> Image.Image:
            frame = Image.new("RGBA", size, (0, 0, 0, 0))
            ImageDraw.Draw(frame).rectangle(subject_box, fill=(150, 45, 70, 255))
            return frame

        square_cells = [subject_cell((320, 320), (110, 20, 209, 299)) for _ in range(4)]
        strip_cells = [subject_cell((320, 1280), (110, 180, 209, 1179)) for _ in range(4)]

        square, square_aperture, _, _ = sprites.common_sheet_transform(
            square_cells,
            canvas=512,
            ground_y=481,
            padding_px=16,
        )
        strip, strip_aperture, _, _ = sprites.common_sheet_transform(
            strip_cells,
            canvas=512,
            ground_y=481,
            padding_px=16,
        )

        square_bbox = square[0].getchannel("A").getbbox()
        strip_bbox = strip[0].getchannel("A").getbbox()
        self.assertIsNotNone(square_bbox)
        self.assertIsNotNone(strip_bbox)
        assert square_bbox is not None and strip_bbox is not None
        self.assertEqual((100, 280), square_aperture)
        self.assertEqual((100, 1000), strip_aperture)
        self.assertLessEqual(abs((square_bbox[3] - square_bbox[1]) - (strip_bbox[3] - strip_bbox[1])), 1)

    def test_airborne_offsets_are_retained_and_optionally_clamped(self) -> None:
        def frame(top: int, bottom: int) -> Image.Image:
            result = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
            ImageDraw.Draw(result).rectangle((220, top, 290, bottom), fill=(180, 60, 80, 255))
            return result

        source = [frame(300, 400), frame(200, 300), frame(220, 320), frame(310, 410)]
        flight = sprites.align_action(
            source,
            ground_y=481,
            support_frames=(0, 3),
            lock_all_to_ground=False,
            max_air_gap_px=None,
        )
        bottoms = [item.getchannel("A").getbbox()[3] - 1 for item in flight]  # type: ignore[index]
        self.assertEqual([471, 371, 391, 481], bottoms)

        clamped = sprites.align_action(
            source,
            ground_y=481,
            support_frames=(0, 3),
            lock_all_to_ground=False,
            max_air_gap_px=20,
        )
        clamped_bottoms = [item.getchannel("A").getbbox()[3] - 1 for item in clamped]  # type: ignore[index]
        self.assertEqual([471, 461, 461, 481], clamped_bottoms)

    def test_optional_bounds_center_alignment_removes_horizontal_pose_jitter(self) -> None:
        frames = []
        for left, right in ((20, 120), (180, 260), (330, 470)):
            frame = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
            ImageDraw.Draw(frame).rectangle((left, 120, right, 430), fill=(180, 60, 80, 255))
            frames.append(frame)
        aligned = sprites.align_action(
            frames,
            ground_y=481,
            support_frames=(0, 1, 2),
            lock_all_to_ground=True,
            max_air_gap_px=None,
            align_bounds_center_x=True,
        )
        centers = []
        for frame in aligned:
            bbox = frame.getchannel("A").getbbox()
            self.assertIsNotNone(bbox)
            assert bbox is not None
            centers.append((bbox[0] + bbox[2]) / 2)
        self.assertLessEqual(max(centers) - min(centers), 1)

    def test_repeated_build_is_byte_deterministic(self) -> None:
        config = self.config(preview=True)
        first = sprites.build_from_config(config)
        outputs = sorted(path for path in (self.root / "output").rglob("*") if path.is_file())
        first_hashes = {path.relative_to(self.root / "output").as_posix(): file_sha256(path) for path in outputs}
        second = sprites.build_from_config(config)
        outputs = sorted(path for path in (self.root / "output").rglob("*") if path.is_file())
        second_hashes = {path.relative_to(self.root / "output").as_posix(): file_sha256(path) for path in outputs}
        self.assertEqual(first, second)
        self.assertEqual(first_hashes, second_hashes)
        self.assertTrue((self.root / "output" / "preview.png").is_file())

    def test_invalid_configuration_is_rejected(self) -> None:
        base = json.loads(self.config().read_text(encoding="utf-8"))
        cases = []

        zero_rows = json.loads(json.dumps(base))
        zero_rows["sheets"]["motion"]["rows"] = 0
        cases.append(zero_rows)

        unknown_sheet = json.loads(json.dumps(base))
        unknown_sheet["actions"][0]["sheet"] = "missing"
        cases.append(unknown_sheet)

        unsafe_action = json.loads(json.dumps(base))
        unsafe_action["actions"][0]["id"] = "../escape"
        cases.append(unsafe_action)

        wrong_canvas = json.loads(json.dumps(base))
        wrong_canvas["canvas"] = 256
        cases.append(wrong_canvas)

        overlapping_output = json.loads(json.dumps(base))
        overlapping_output["report"] = overlapping_output["outputRoot"]
        cases.append(overlapping_output)

        for index, payload in enumerate(cases):
            with self.subTest(index=index):
                path = self.root / f"invalid-{index}.json"
                path.write_text(json.dumps(payload), encoding="utf-8")
                with self.assertRaises(sprites.ConfigurationError):
                    sprites.load_build_spec(path)

    def test_processing_failure_leaves_existing_action_atomically_untouched(self) -> None:
        config = self.config()
        old_action = self.root / "output" / "animations" / "walk"
        old_action.mkdir(parents=True)
        old_marker = old_action / "keep.txt"
        old_marker.write_text("stable old action", encoding="utf-8")
        original_save = sprites._save_png
        calls = 0

        def fail_during_second_frame(frame: Image.Image, path: Path) -> None:
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("synthetic encoder failure")
            original_save(frame, path)

        with mock.patch.object(sprites, "_save_png", side_effect=fail_during_second_frame):
            with self.assertRaisesRegex(OSError, "synthetic encoder failure"):
                sprites.build_from_config(config)

        self.assertEqual("stable old action", old_marker.read_text(encoding="utf-8"))
        self.assertEqual(["keep.txt"], sorted(path.name for path in old_action.iterdir()))
        self.assertFalse((self.root / "output" / "animations" / "wave").exists())
        self.assertFalse((self.root / "output" / "sprite-report.json").exists())
        self.assertEqual([], list((self.root / "output").glob(".sprite-build-*")))

    def test_dry_run_and_thread_cancellation_publish_nothing(self) -> None:
        config = self.config(preview=True)
        report = sprites.build_from_config(config, dry_run=True)
        self.assertTrue(report["dryRun"])
        self.assertFalse((self.root / "output").exists())
        self.assertFalse((self.root / "output" / "animations" / "walk").exists())
        self.assertFalse((self.root / "output" / "sprite-report.json").exists())
        self.assertFalse((self.root / "output" / "preview.png").exists())

        cancelled = threading.Event()
        cancelled.set()
        with self.assertRaises(sprites.BuildCancelled):
            sprites.build_from_config(config, cancel_event=cancelled)
        self.assertFalse((self.root / "output" / "animations" / "walk").exists())


if __name__ == "__main__":
    unittest.main()
