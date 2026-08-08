#!/usr/bin/env python3
"""Build normalized 512x512 JK beach-summer frames from generated sprite sheets.

The creative source sheets are produced through the image generation workflow and
already have a soft chroma-key matte.  This script performs only deterministic
production work: grid slicing, residual key cleanup, one common transform per
sheet, baseline alignment per action, and PNG validation.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image


CANVAS = 512
GROUND_Y = 481  # 0.94 * 512, rounded.
GRID_MARGIN = 3
CELL_EDGE_CLEAR = 5


@dataclass(frozen=True)
class ActionSpec:
    name: str
    sheet: str
    row: int | None
    support_frames: tuple[int, ...]
    lock_each_frame_to_ground: bool = True
    max_air_gap_px: int | None = None


ACTION_SPECS = (
    ActionSpec("idle", "idle-wave-alpha.png", 0, (0, 1, 2, 3)),
    ActionSpec("wave", "idle-wave-alpha.png", 1, (0, 1, 2, 3)),
    ActionSpec("walk", "walk-cycle-alpha.png", None, tuple(range(8))),
    ActionSpec("run", "run-cycle-v2-alpha-strict.png", None, tuple(range(8)), False, 8),
    ActionSpec("photo_pose", "photo-sunset-alpha.png", 0, (0, 1, 2, 3)),
    ActionSpec("sunset_flower", "photo-sunset-alpha.png", 1, (0, 1, 2, 3)),
    ActionSpec("shell_pick", "shell-splash-alpha.png", 0, (0, 1, 2, 3)),
    ActionSpec("splash_jump", "shell-splash-alpha.png", 1, (0, 3), False),
    ActionSpec("sea_breeze", "breeze-rest-alpha.png", 0, (0, 1, 2, 3)),
    ActionSpec("sleepy_pose", "breeze-rest-alpha.png", 1, (0, 1, 2, 3)),
)


def clean_key(image: Image.Image) -> Image.Image:
    """Remove only residual neon green while preserving natural/leaf greens."""
    rgba = np.asarray(image.convert("RGBA")).copy()
    rgb = rgba[:, :, :3].astype(np.int16)
    red, green, blue = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    neon = (
        (green >= 155)
        & ((green - red) >= 55)
        & ((green - blue) >= 55)
        & (red <= 150)
        & (blue <= 150)
    )
    rgba[neon, 3] = 0
    rgba[rgba[:, :, 3] == 0, :3] = 0
    return Image.fromarray(rgba, mode="RGBA")


def split_grid(sheet: Image.Image) -> list[Image.Image]:
    width, height = sheet.size
    cells: list[Image.Image] = []
    for row in range(2):
        for column in range(4):
            left = round(width * column / 4) + GRID_MARGIN
            right = round(width * (column + 1) / 4) - GRID_MARGIN
            top = round(height * row / 2) + GRID_MARGIN
            bottom = round(height * (row + 1) / 2) - GRID_MARGIN
            if right <= left or bottom <= top:
                raise ValueError(f"invalid grid cell in {sheet.size}: {(left, top, right, bottom)}")
            cleaned = np.asarray(clean_key(sheet.crop((left, top, right, bottom)))).copy()
            # Generated sprite grids can retain a dark separator at the cell
            # boundary.  Clear only the outer matte band; subjects are framed
            # well inside it, so this prevents phantom full-canvas alpha bounds
            # without touching hair, hands, or feet.
            cleaned[:CELL_EDGE_CLEAR, :, :] = 0
            cleaned[-CELL_EDGE_CLEAR:, :, :] = 0
            cleaned[:, :CELL_EDGE_CLEAR, :] = 0
            cleaned[:, -CELL_EDGE_CLEAR:, :] = 0
            cells.append(Image.fromarray(cleaned, mode="RGBA"))
    return cells


def common_transform(cells: list[Image.Image]) -> list[Image.Image]:
    """Map equal source cells through one camera transform without per-frame scaling."""
    if not cells:
        raise ValueError("empty action")
    # Generated sheets can be one or two pixels off an exact 4x2 division. Pad
    # those cells to one common camera aperture instead of scaling each frame.
    source_size = (
        max(cell.width for cell in cells),
        max(cell.height for cell in cells),
    )
    normalized_cells: list[Image.Image] = []
    for cell in cells:
        if cell.size == source_size:
            normalized_cells.append(cell)
            continue
        padded = Image.new("RGBA", source_size, (0, 0, 0, 0))
        padded.alpha_composite(
            cell,
            ((source_size[0] - cell.width) // 2, (source_size[1] - cell.height) // 2),
        )
        normalized_cells.append(padded)
    # Keep at least a small transparent safety band around hair and extended
    # limbs.  Ground alignment happens afterwards and leaves 30 px below feet.
    scale = min((CANVAS - 16) / source_size[0], (CANVAS - 16) / source_size[1])
    new_size = (
        max(1, round(source_size[0] * scale)),
        max(1, round(source_size[1] * scale)),
    )
    offset = ((CANVAS - new_size[0]) // 2, (CANVAS - new_size[1]) // 2)
    output: list[Image.Image] = []
    for cell in normalized_cells:
        resized = cell.resize(new_size, Image.Resampling.LANCZOS)
        frame = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        frame.alpha_composite(resized, offset)
        output.append(frame)
    return output


def translate_y(frame: Image.Image, dy: int) -> Image.Image:
    shifted = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    shifted.alpha_composite(frame, (0, dy))
    return shifted


def align_action(
    frames: list[Image.Image],
    support_frames: tuple[int, ...],
    lock_each_frame_to_ground: bool,
    max_air_gap_px: int | None,
) -> list[Image.Image]:
    bottoms: list[int] = []
    for index in support_frames:
        bbox = frames[index].getchannel("A").getbbox()
        if bbox is None:
            raise ValueError(f"support frame {index} is empty")
        bottoms.append(bbox[3] - 1)
    if lock_each_frame_to_ground:
        aligned = []
        for frame in frames:
            bbox = frame.getchannel("A").getbbox()
            if bbox is None:
                raise ValueError("cannot align an empty frame")
            aligned.append(translate_y(frame, GROUND_Y - (bbox[3] - 1)))
    else:
        # Dynamic actions retain their airborne offsets, while the lowest
        # contact pose is kept on (never below) the declared ground anchor.
        dy = GROUND_Y - max(bottoms)
        aligned = [translate_y(frame, dy) for frame in frames]
        if max_air_gap_px is not None:
            # Border-running reads as continuous surface contact, so retain a
            # small lift without letting generated airborne poses visibly
            # detach from the physical screen edge.
            minimum_bottom = GROUND_Y - max_air_gap_px
            clamped: list[Image.Image] = []
            for frame in aligned:
                bbox = frame.getchannel("A").getbbox()
                if bbox is None:
                    raise ValueError("cannot clamp an empty frame")
                bottom = bbox[3] - 1
                clamped.append(translate_y(frame, max(0, minimum_bottom - bottom)))
            aligned = clamped
    for index, frame in enumerate(aligned):
        alpha = frame.getchannel("A")
        bbox = alpha.getbbox()
        if bbox is None:
            raise ValueError(f"aligned frame {index} is empty")
        if bbox[0] < 0 or bbox[1] < 0 or bbox[2] > CANVAS or bbox[3] > CANVAS:
            raise ValueError(f"frame {index} exceeds canvas: {bbox}")
        corners = (alpha.getpixel((0, 0)), alpha.getpixel((511, 0)), alpha.getpixel((0, 511)), alpha.getpixel((511, 511)))
        if any(corners):
            raise ValueError(f"frame {index} has non-transparent corner: {corners}")
    return aligned


def action_cells(sheet_cells: list[Image.Image], row: int | None) -> list[Image.Image]:
    if row is None:
        return sheet_cells
    start = row * 4
    return sheet_cells[start : start + 4]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--pack", type=Path, required=True)
    args = parser.parse_args()

    animations_root = args.pack / "character" / "animations"
    animations_root.mkdir(parents=True, exist_ok=True)
    sheet_cache: dict[str, list[Image.Image]] = {}
    report: dict[str, dict[str, object]] = {}

    for spec in ACTION_SPECS:
        if spec.sheet not in sheet_cache:
            sheet_path = args.work / spec.sheet
            if not sheet_path.is_file():
                raise FileNotFoundError(sheet_path)
            sheet_cache[spec.sheet] = split_grid(Image.open(sheet_path).convert("RGBA"))

        cells = action_cells(sheet_cache[spec.sheet], spec.row)
        frames = align_action(
            common_transform(cells),
            spec.support_frames,
            spec.lock_each_frame_to_ground,
            spec.max_air_gap_px,
        )
        output_dir = animations_root / spec.name
        output_dir.mkdir(parents=True, exist_ok=True)

        bboxes: list[tuple[int, int, int, int]] = []
        for index, frame in enumerate(frames):
            output_path = output_dir / f"frame_{index:02d}.png"
            frame.save(output_path, format="PNG", optimize=True)
            bbox = frame.getchannel("A").getbbox()
            assert bbox is not None
            bboxes.append(bbox)

        report[spec.name] = {
            "frames": len(frames),
            "bboxWidthRange": [min(b[2] - b[0] for b in bboxes), max(b[2] - b[0] for b in bboxes)],
            "bboxHeightRange": [min(b[3] - b[1] for b in bboxes), max(b[3] - b[1] for b in bboxes)],
            "bboxBottomRange": [min(b[3] - 1 for b in bboxes), max(b[3] - 1 for b in bboxes)],
        }

    preview_source = animations_root / "photo_pose" / "frame_00.png"
    Image.open(preview_source).save(args.pack / "preview.png", format="PNG", optimize=True)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
