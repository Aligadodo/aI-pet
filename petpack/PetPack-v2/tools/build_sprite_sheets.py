#!/usr/bin/env python3
"""Deterministically turn generated sprite sheets into PetPack action frames.

The tool is deliberately limited to production post-processing.  It does not
invent motion: it slices an explicit grid, removes a configurable green-screen
matte, applies one camera transform to every cell from the same sheet, aligns
actions to a declared ground line, and transactionally publishes validated
512x512 RGBA PNGs.

Example configuration::

    {
      "canvas": 512,
      "groundY": 481,
      "outputRoot": "../packs/example/character/animations",
      "report": "reports/sprite-build.json",
      "sheets": {
        "motion": {
          "path": "sheets/motion.png",
          "rows": 2,
          "cols": 4,
          "marginPx": 3,
          "edgeClearPx": 2
        }
      },
      "actions": [
        {
          "id": "idle",
          "sheet": "motion",
          "row": 0,
          "supportFrames": [0, 1, 2, 3],
          "lockAllToGround": true
        },
        {
          "id": "wave",
          "sheet": "motion",
          "row": 1,
          "supportFrames": [0, 1, 2, 3],
          "lockAllToGround": true
        }
      ],
      "previewAction": "idle",
      "previewFrame": 0,
      "previewPath": "../packs/example/preview.png"
    }

All paths are resolved relative to the configuration file.  ``dry-run`` does
the complete decode/transform/validation pass but writes nothing.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tempfile
import threading
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

import numpy as np
from PIL import Image


OUTPUT_SIZE = 512
DEFAULT_PADDING_PX = 16
DEFAULT_GREEN_MIN = 150
DEFAULT_GREEN_DOMINANCE = 45
DEFAULT_KEY_DISTANCE = 105.0
DEFAULT_DESPILL_RADIUS = 4
DEFAULT_DESPILL_TOLERANCE = 0
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


class ConfigurationError(ValueError):
    """Raised when the JSON contract is invalid."""


class BuildCancelled(RuntimeError):
    """Raised when a caller-provided cancellation event is set."""


@dataclass(frozen=True)
class ChromaKeySpec:
    enabled: bool = True
    green_min: int = DEFAULT_GREEN_MIN
    dominance: int = DEFAULT_GREEN_DOMINANCE
    key_distance: float = DEFAULT_KEY_DISTANCE
    despill: bool = True
    despill_radius: int = DEFAULT_DESPILL_RADIUS
    despill_tolerance: int = DEFAULT_DESPILL_TOLERANCE


@dataclass(frozen=True)
class SheetSpec:
    sheet_id: str
    path: Path
    display_path: str
    rows: int
    cols: int
    margin_px: int = 0
    overlap_px: int = 0
    edge_clear_px: int = 0
    padding_px: int = DEFAULT_PADDING_PX
    component_cleanup: bool = True
    component_gap_px: int = 48
    component_close_gap_px: int = 8
    chroma_key: ChromaKeySpec = field(default_factory=ChromaKeySpec)


@dataclass(frozen=True)
class ActionSpec:
    action_id: str
    sheet_id: str
    cells: tuple[int, ...]
    support_frames: tuple[int, ...]
    lock_all_to_ground: bool
    align_bounds_center_x: bool
    max_air_gap_px: int | None


@dataclass(frozen=True)
class BuildSpec:
    config_path: Path
    canvas: int
    ground_y: int
    output_root: Path
    report_path: Path
    sheets: tuple[SheetSpec, ...]
    actions: tuple[ActionSpec, ...]
    preview_action: str | None
    preview_frame: int
    preview_path: Path | None


@dataclass(frozen=True)
class SheetResult:
    spec: SheetSpec
    cells: tuple[Image.Image, ...]
    source_cell_size: tuple[int, int]
    output_cell_size: tuple[int, int]
    output_offset: tuple[int, int]
    source_sha256: str


@dataclass
class PendingArtifact:
    staged: Path
    target: Path
    is_directory: bool
    backup: Path | None = None
    installed: bool = False


def _as_mapping(value: object, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ConfigurationError(f"{label} must be an object")
    return value


def _as_int(value: object, label: str, *, minimum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ConfigurationError(f"{label} must be an integer")
    if minimum is not None and value < minimum:
        raise ConfigurationError(f"{label} must be >= {minimum}")
    return value


def _as_bool(value: object, label: str) -> bool:
    if not isinstance(value, bool):
        raise ConfigurationError(f"{label} must be a boolean")
    return value


def _as_number(value: object, label: str, *, minimum: float | None = None) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ConfigurationError(f"{label} must be a number")
    number = float(value)
    if minimum is not None and number < minimum:
        raise ConfigurationError(f"{label} must be >= {minimum}")
    return number


def _safe_id(value: object, label: str) -> str:
    if not isinstance(value, str) or not SAFE_ID.fullmatch(value):
        raise ConfigurationError(
            f"{label} must match {SAFE_ID.pattern!r}; path separators are not allowed"
        )
    return value


def _resolve_path(base: Path, value: object, label: str) -> tuple[Path, str]:
    if not isinstance(value, str) or not value.strip():
        raise ConfigurationError(f"{label} must be a non-empty path string")
    raw = Path(value)
    resolved = (base / raw).resolve() if not raw.is_absolute() else raw.resolve()
    return resolved, raw.as_posix()


def _parse_canvas(value: object) -> int:
    if isinstance(value, int) and not isinstance(value, bool):
        size = value
    elif isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        if len(value) != 2:
            raise ConfigurationError("canvas array must be [width, height]")
        width = _as_int(value[0], "canvas[0]", minimum=1)
        height = _as_int(value[1], "canvas[1]", minimum=1)
        if width != height:
            raise ConfigurationError("canvas must be square")
        size = width
    elif isinstance(value, Mapping):
        width = _as_int(value.get("width"), "canvas.width", minimum=1)
        height = _as_int(value.get("height"), "canvas.height", minimum=1)
        if width != height:
            raise ConfigurationError("canvas must be square")
        size = width
    else:
        raise ConfigurationError("canvas must be 512, [512, 512], or an object")
    if size != OUTPUT_SIZE:
        raise ConfigurationError(f"canvas must be {OUTPUT_SIZE}; got {size}")
    return size


def _parse_chroma(value: object, label: str) -> ChromaKeySpec:
    if value is None:
        return ChromaKeySpec()
    data = _as_mapping(value, label)
    enabled = _as_bool(data.get("enabled", True), f"{label}.enabled")
    green_min = _as_int(data.get("greenMin", DEFAULT_GREEN_MIN), f"{label}.greenMin", minimum=0)
    dominance = _as_int(data.get("dominance", DEFAULT_GREEN_DOMINANCE), f"{label}.dominance", minimum=0)
    key_distance = _as_number(
        data.get("keyDistance", DEFAULT_KEY_DISTANCE),
        f"{label}.keyDistance",
        minimum=0,
    )
    despill = _as_bool(data.get("despill", True), f"{label}.despill")
    despill_radius = _as_int(
        data.get("despillRadius", DEFAULT_DESPILL_RADIUS),
        f"{label}.despillRadius",
        minimum=0,
    )
    despill_tolerance = _as_int(
        data.get("despillTolerance", DEFAULT_DESPILL_TOLERANCE),
        f"{label}.despillTolerance",
        minimum=0,
    )
    for name, number in (
        ("greenMin", green_min),
        ("dominance", dominance),
        ("despillTolerance", despill_tolerance),
    ):
        if number > 255:
            raise ConfigurationError(f"{label}.{name} must be <= 255")
    if despill_radius > 32:
        raise ConfigurationError(f"{label}.despillRadius must be <= 32")
    return ChromaKeySpec(
        enabled=enabled,
        green_min=green_min,
        dominance=dominance,
        key_distance=key_distance,
        despill=despill,
        despill_radius=despill_radius,
        despill_tolerance=despill_tolerance,
    )


def _sheet_entries(value: object) -> list[tuple[str, Mapping[str, Any]]]:
    if isinstance(value, Mapping):
        return [(_safe_id(key, "sheets key"), _as_mapping(item, f"sheets.{key}")) for key, item in value.items()]
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        result: list[tuple[str, Mapping[str, Any]]] = []
        for index, item in enumerate(value):
            data = _as_mapping(item, f"sheets[{index}]")
            sheet_id = _safe_id(data.get("id"), f"sheets[{index}].id")
            result.append((sheet_id, data))
        return result
    raise ConfigurationError("sheets must be an object or array")


def _parse_cells(
    data: Mapping[str, Any],
    *,
    action_label: str,
    rows: int,
    cols: int,
) -> tuple[int, ...]:
    total = rows * cols
    selectors = sum(key in data for key in ("cells", "row"))
    if selectors > 1:
        raise ConfigurationError(f"{action_label} may specify only one of cells or row")
    if "cells" in data:
        raw_cells = data["cells"]
        if not isinstance(raw_cells, Sequence) or isinstance(raw_cells, (str, bytes)) or not raw_cells:
            raise ConfigurationError(f"{action_label}.cells must be a non-empty integer array")
        cells = tuple(
            _as_int(value, f"{action_label}.cells[{index}]", minimum=0)
            for index, value in enumerate(raw_cells)
        )
    elif "row" in data:
        row = _as_int(data["row"], f"{action_label}.row", minimum=0)
        if row >= rows:
            raise ConfigurationError(f"{action_label}.row {row} is outside 0..{rows - 1}")
        cells = tuple(range(row * cols, (row + 1) * cols))
    else:
        cells = tuple(range(total))
    if any(cell >= total for cell in cells):
        raise ConfigurationError(f"{action_label}.cells contains an index outside 0..{total - 1}")
    if len(set(cells)) != len(cells):
        raise ConfigurationError(f"{action_label}.cells must not contain duplicates")
    return cells


def load_build_spec(
    config_path: Path,
    *,
    report_override: Path | None = None,
    preview_action_override: str | None = None,
    preview_frame_override: int | None = None,
    preview_path_override: Path | None = None,
) -> BuildSpec:
    """Load and validate a build configuration without touching outputs."""
    path = config_path.resolve()
    try:
        raw = json.loads(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError:
        raise
    except json.JSONDecodeError as error:
        raise ConfigurationError(f"invalid JSON in {path}: {error}") from error
    data = _as_mapping(raw, "configuration")
    base = path.parent

    canvas = _parse_canvas(data.get("canvas"))
    ground_y = _as_int(data.get("groundY"), "groundY", minimum=1)
    if ground_y >= canvas - 1:
        raise ConfigurationError(f"groundY must leave a transparent bottom edge (1..{canvas - 2})")

    output_root, _ = _resolve_path(base, data.get("outputRoot"), "outputRoot")
    if report_override is not None:
        report_path = report_override.resolve()
    elif "report" in data:
        report_path, _ = _resolve_path(base, data["report"], "report")
    else:
        report_path = (output_root.parent / "sprite-build-report.json").resolve()

    sheets: list[SheetSpec] = []
    sheet_ids: set[str] = set()
    for sheet_id, item in _sheet_entries(data.get("sheets")):
        if sheet_id in sheet_ids:
            raise ConfigurationError(f"duplicate sheet id: {sheet_id}")
        sheet_ids.add(sheet_id)
        sheet_path, display_path = _resolve_path(base, item.get("path"), f"sheets.{sheet_id}.path")
        rows = _as_int(item.get("rows"), f"sheets.{sheet_id}.rows", minimum=1)
        cols = _as_int(item.get("cols"), f"sheets.{sheet_id}.cols", minimum=1)
        margin = _as_int(item.get("marginPx", 0), f"sheets.{sheet_id}.marginPx", minimum=0)
        overlap = _as_int(item.get("overlapPx", 0), f"sheets.{sheet_id}.overlapPx", minimum=0)
        edge_clear = _as_int(
            item.get("edgeClearPx", 0),
            f"sheets.{sheet_id}.edgeClearPx",
            minimum=0,
        )
        padding = _as_int(
            item.get("paddingPx", DEFAULT_PADDING_PX),
            f"sheets.{sheet_id}.paddingPx",
            minimum=1,
        )
        if padding >= canvas // 2:
            raise ConfigurationError(f"sheets.{sheet_id}.paddingPx is too large for canvas")
        component_cleanup = _as_bool(
            item.get("componentCleanup", True),
            f"sheets.{sheet_id}.componentCleanup",
        )
        component_gap = _as_int(
            item.get("componentGapPx", 48),
            f"sheets.{sheet_id}.componentGapPx",
            minimum=0,
        )
        component_close_gap = _as_int(
            item.get("componentCloseGapPx", 8),
            f"sheets.{sheet_id}.componentCloseGapPx",
            minimum=0,
        )
        sheets.append(
            SheetSpec(
                sheet_id=sheet_id,
                path=sheet_path,
                display_path=display_path,
                rows=rows,
                cols=cols,
                margin_px=margin,
                overlap_px=overlap,
                edge_clear_px=edge_clear,
                padding_px=padding,
                component_cleanup=component_cleanup,
                component_gap_px=component_gap,
                component_close_gap_px=component_close_gap,
                chroma_key=_parse_chroma(item.get("chromaKey"), f"sheets.{sheet_id}.chromaKey"),
            )
        )
    if not sheets:
        raise ConfigurationError("sheets must not be empty")
    sheet_by_id = {sheet.sheet_id: sheet for sheet in sheets}

    raw_actions = data.get("actions")
    if not isinstance(raw_actions, Sequence) or isinstance(raw_actions, (str, bytes)) or not raw_actions:
        raise ConfigurationError("actions must be a non-empty array")
    actions: list[ActionSpec] = []
    action_ids: set[str] = set()
    for index, item in enumerate(raw_actions):
        action_label = f"actions[{index}]"
        action_data = _as_mapping(item, action_label)
        action_id = _safe_id(action_data.get("id"), f"{action_label}.id")
        if action_id in action_ids:
            raise ConfigurationError(f"duplicate action id: {action_id}")
        action_ids.add(action_id)
        sheet_id = _safe_id(action_data.get("sheet"), f"{action_label}.sheet")
        if sheet_id not in sheet_by_id:
            raise ConfigurationError(f"{action_label}.sheet references unknown sheet {sheet_id!r}")
        sheet = sheet_by_id[sheet_id]
        cells = _parse_cells(
            action_data,
            action_label=action_label,
            rows=sheet.rows,
            cols=sheet.cols,
        )
        raw_support = action_data.get("supportFrames", list(range(len(cells))))
        if not isinstance(raw_support, Sequence) or isinstance(raw_support, (str, bytes)) or not raw_support:
            raise ConfigurationError(f"{action_label}.supportFrames must be a non-empty integer array")
        support_frames = tuple(
            _as_int(value, f"{action_label}.supportFrames[{support_index}]", minimum=0)
            for support_index, value in enumerate(raw_support)
        )
        if any(frame >= len(cells) for frame in support_frames):
            raise ConfigurationError(
                f"{action_label}.supportFrames contains an index outside 0..{len(cells) - 1}"
            )
        if len(set(support_frames)) != len(support_frames):
            raise ConfigurationError(f"{action_label}.supportFrames must not contain duplicates")
        lock_all = _as_bool(
            action_data.get("lockAllToGround", True),
            f"{action_label}.lockAllToGround",
        )
        align_bounds_center_x = _as_bool(
            action_data.get("alignBoundsCenterX", False),
            f"{action_label}.alignBoundsCenterX",
        )
        raw_gap = action_data.get("maxAirGapPx")
        max_air_gap = None if raw_gap is None else _as_int(raw_gap, f"{action_label}.maxAirGapPx", minimum=0)
        if max_air_gap is not None and max_air_gap >= canvas:
            raise ConfigurationError(f"{action_label}.maxAirGapPx must be < {canvas}")
        actions.append(
            ActionSpec(
                action_id=action_id,
                sheet_id=sheet_id,
                cells=cells,
                support_frames=support_frames,
                lock_all_to_ground=lock_all,
                align_bounds_center_x=align_bounds_center_x,
                max_air_gap_px=max_air_gap,
            )
        )

    raw_preview_action = preview_action_override if preview_action_override is not None else data.get("previewAction")
    preview_action: str | None
    if raw_preview_action is None:
        preview_action = None
    else:
        preview_action = _safe_id(raw_preview_action, "previewAction")
        if preview_action not in action_ids:
            raise ConfigurationError(f"previewAction references unknown action {preview_action!r}")
    raw_preview_frame = preview_frame_override if preview_frame_override is not None else data.get("previewFrame", 0)
    preview_frame = _as_int(raw_preview_frame, "previewFrame", minimum=0)
    if preview_action is not None:
        selected = next(action for action in actions if action.action_id == preview_action)
        if preview_frame >= len(selected.cells):
            raise ConfigurationError(
                f"previewFrame {preview_frame} is outside action {preview_action!r} (0..{len(selected.cells) - 1})"
            )
    if preview_path_override is not None:
        preview_path = preview_path_override.resolve()
    elif "previewPath" in data:
        preview_path, _ = _resolve_path(base, data["previewPath"], "previewPath")
    elif preview_action is not None:
        preview_path = (base / "preview.png").resolve()
    else:
        preview_path = None
    if preview_action is None and preview_path is not None:
        raise ConfigurationError("previewPath requires previewAction")

    action_targets = [output_root / action.action_id for action in actions]
    file_targets = [report_path]
    if preview_path is not None:
        file_targets.append(preview_path)
    targets = action_targets + file_targets
    normalized_targets = [os.path.normcase(str(target.resolve())) for target in targets]
    if len(set(normalized_targets)) != len(normalized_targets):
        raise ConfigurationError("action, report, and preview output paths must be distinct")
    for file_target in file_targets:
        for action_target in action_targets:
            if file_target.is_relative_to(action_target) or action_target.is_relative_to(file_target):
                raise ConfigurationError(
                    f"file output {file_target} must not contain or be contained by action directory {action_target}"
                )
    for sheet in sheets:
        for action_target in action_targets:
            if sheet.path == action_target or sheet.path.is_relative_to(action_target):
                raise ConfigurationError(
                    f"source sheet {sheet.path} would be replaced by action directory {action_target}"
                )
        if sheet.path in file_targets:
            raise ConfigurationError(f"source sheet {sheet.path} is also configured as an output file")

    return BuildSpec(
        config_path=path,
        canvas=canvas,
        ground_y=ground_y,
        output_root=output_root,
        report_path=report_path,
        sheets=tuple(sheets),
        actions=tuple(actions),
        preview_action=preview_action,
        preview_frame=preview_frame,
        preview_path=preview_path,
    )


def _check_cancelled(cancel_event: threading.Event | None) -> None:
    if cancel_event is not None and cancel_event.is_set():
        raise BuildCancelled("sprite build cancelled")


def _dilate(mask: np.ndarray, radius: int) -> np.ndarray:
    """Small dependency-free square dilation used for edge despill."""
    if radius <= 0:
        return mask.copy()
    height, width = mask.shape
    padded = np.pad(mask, radius, mode="constant", constant_values=False)
    result = np.zeros_like(mask)
    for dy in range(2 * radius + 1):
        for dx in range(2 * radius + 1):
            result |= padded[dy : dy + height, dx : dx + width]
    return result


def clean_chroma_key(image: Image.Image, spec: ChromaKeySpec) -> Image.Image:
    """Clear green-screen pixels and neutralize green spill next to the matte."""
    rgba = np.asarray(image.convert("RGBA"), dtype=np.uint8).copy()
    if not spec.enabled:
        rgba[rgba[:, :, 3] == 0, :3] = 0
        return Image.fromarray(rgba, mode="RGBA")

    rgb = rgba[:, :, :3].astype(np.int16)
    red, green, blue = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    distance = np.sqrt(
        red.astype(np.float32) ** 2
        + (255 - green).astype(np.float32) ** 2
        + blue.astype(np.float32) ** 2
    )
    keyed = (
        (green >= spec.green_min)
        & ((green - red) >= spec.dominance)
        & ((green - blue) >= spec.dominance)
        & (distance <= spec.key_distance)
        & (rgba[:, :, 3] > 0)
    )
    rgba[keyed, 3] = 0

    if spec.despill and keyed.any():
        neighbors = _dilate(keyed, spec.despill_radius) & ~keyed & (rgba[:, :, 3] > 0)
        neutral = np.maximum(red, blue) + spec.despill_tolerance
        spill = neighbors & (green > neutral)
        rgba[:, :, 1][spill] = np.clip(neutral[spill], 0, 255).astype(np.uint8)

    rgba[rgba[:, :, 3] == 0, :3] = 0
    return Image.fromarray(rgba, mode="RGBA")


def retain_subject_components(
    image: Image.Image,
    *,
    peripheral_gap_px: int,
    close_gap_px: int,
) -> Image.Image:
    """Remove fragments spilled in from adjacent contact-sheet cells.

    Image generators occasionally let shoes, a hat, or part of the preceding
    row cross an otherwise regular grid boundary.  A small grid overlap is
    useful to recover the intended subject, but it also captures those foreign
    fragments.  Keep the largest 8-connected alpha component, nearby pieces
    (for detached shoes/hair), and lateral props that overlap the subject's
    vertical span (for a held leaf, chalk, or a flower).  Components solely
    above/below the subject are discarded.
    """
    rgba = np.asarray(image.convert("RGBA"), dtype=np.uint8).copy()
    mask = rgba[:, :, 3] > 0
    if not mask.any():
        return Image.fromarray(rgba, mode="RGBA")

    parent: list[int] = []
    ranks: list[int] = []
    runs: list[tuple[int, int, int, int]] = []  # y, start, end-inclusive, node

    def make_node() -> int:
        node = len(parent)
        parent.append(node)
        ranks.append(0)
        return node

    def find(node: int) -> int:
        while parent[node] != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    def union(left: int, right: int) -> None:
        left_root, right_root = find(left), find(right)
        if left_root == right_root:
            return
        if ranks[left_root] < ranks[right_root]:
            left_root, right_root = right_root, left_root
        parent[right_root] = left_root
        if ranks[left_root] == ranks[right_root]:
            ranks[left_root] += 1

    previous: list[tuple[int, int, int]] = []  # start, end, node
    for y in range(mask.shape[0]):
        xs = np.flatnonzero(mask[y])
        current: list[tuple[int, int, int]] = []
        if xs.size:
            boundaries = np.flatnonzero(np.diff(xs) > 1)
            starts = np.concatenate(([0], boundaries + 1))
            ends = np.concatenate((boundaries, [xs.size - 1]))
            previous_index = 0
            for start_index, end_index in zip(starts.tolist(), ends.tolist()):
                start, end = int(xs[start_index]), int(xs[end_index])
                node = make_node()
                while previous_index < len(previous) and previous[previous_index][1] < start - 1:
                    previous_index += 1
                candidate_index = previous_index
                while candidate_index < len(previous) and previous[candidate_index][0] <= end + 1:
                    union(node, previous[candidate_index][2])
                    candidate_index += 1
                current.append((start, end, node))
                runs.append((y, start, end, node))
        previous = current

    stats: dict[int, list[int]] = {}
    for y, start, end, node in runs:
        root = find(node)
        area = end - start + 1
        if root not in stats:
            stats[root] = [area, start, y, end + 1, y + 1]
        else:
            item = stats[root]
            item[0] += area
            item[1] = min(item[1], start)
            item[2] = min(item[2], y)
            item[3] = max(item[3], end + 1)
            item[4] = max(item[4], y + 1)

    main_root = max(stats, key=lambda root: stats[root][0])
    _, main_left, main_top, main_right, main_bottom = stats[main_root]
    keep = {main_root}
    for root, (area, left, top, right, bottom) in stats.items():
        if root == main_root or area < 3:
            continue
        x_gap = max(main_left - right, left - main_right, 0)
        y_gap = max(main_top - bottom, top - main_bottom, 0)
        x_overlap = min(main_right, right) > max(main_left, left)
        y_overlap = min(main_bottom, bottom) > max(main_top, top)
        if (
            (y_overlap and x_gap <= peripheral_gap_px)
            or (x_overlap and y_gap <= close_gap_px)
            or (x_gap <= close_gap_px and y_gap <= close_gap_px)
        ):
            keep.add(root)

    kept_mask = np.zeros_like(mask)
    for y, start, end, node in runs:
        if find(node) in keep:
            kept_mask[y, start : end + 1] = True
    rgba[~kept_mask] = 0
    return Image.fromarray(rgba, mode="RGBA")


def neutralize_resampled_edge_spill(image: Image.Image, *, tolerance: int = 2) -> Image.Image:
    """Remove chroma tint reintroduced into semi-transparent resize pixels."""
    rgba = np.asarray(image.convert("RGBA"), dtype=np.uint8).copy()
    alpha = rgba[:, :, 3]
    red = rgba[:, :, 0].astype(np.int16)
    green = rgba[:, :, 1].astype(np.int16)
    blue = rgba[:, :, 2].astype(np.int16)
    neutral = np.maximum(red, blue) + tolerance
    spill = (alpha > 0) & (alpha < 255) & (green > neutral)
    rgba[:, :, 1][spill] = np.clip(neutral[spill], 0, 255).astype(np.uint8)
    rgba[alpha == 0, :3] = 0
    return Image.fromarray(rgba, mode="RGBA")


def split_grid(
    sheet: Image.Image,
    *,
    rows: int,
    cols: int,
    margin_px: int,
    overlap_px: int,
    edge_clear_px: int,
    component_cleanup: bool,
    component_gap_px: int,
    component_close_gap_px: int,
    chroma_key: ChromaKeySpec,
    cancel_event: threading.Event | None = None,
) -> list[Image.Image]:
    """Split every source pixel into an exact row-major grid cell.

    Integer proportional boundaries cover the complete sheet even when its
    dimensions are not divisible by the requested row/column count.  The
    optional inner margin is then removed symmetrically from each cell.
    """
    width, height = sheet.size
    if width < cols or height < rows:
        raise ValueError(f"sheet {sheet.size} is smaller than its {rows}x{cols} grid")
    cells: list[Image.Image] = []
    for row in range(rows):
        for column in range(cols):
            _check_cancelled(cancel_event)
            raw_left = width * column // cols
            raw_right = width * (column + 1) // cols
            raw_top = height * row // rows
            raw_bottom = height * (row + 1) // rows
            left = max(0, raw_left - overlap_px) + margin_px
            right = min(width, raw_right + overlap_px) - margin_px
            top = max(0, raw_top - overlap_px) + margin_px
            bottom = min(height, raw_bottom + overlap_px) - margin_px
            if right <= left or bottom <= top:
                raise ValueError(
                    f"marginPx={margin_px} empties grid cell ({row}, {column}) "
                    f"with raw bounds {(raw_left, raw_top, raw_right, raw_bottom)}"
                )
            cell = clean_chroma_key(sheet.crop((left, top, right, bottom)), chroma_key)
            if edge_clear_px:
                if edge_clear_px * 2 >= min(cell.size):
                    raise ValueError(
                        f"edgeClearPx={edge_clear_px} empties grid cell ({row}, {column}) {cell.size}"
                    )
                pixels = np.asarray(cell, dtype=np.uint8).copy()
                pixels[:edge_clear_px, :, :] = 0
                pixels[-edge_clear_px:, :, :] = 0
                pixels[:, :edge_clear_px, :] = 0
                pixels[:, -edge_clear_px:, :] = 0
                cell = Image.fromarray(pixels, mode="RGBA")
            if component_cleanup:
                cell = retain_subject_components(
                    cell,
                    peripheral_gap_px=component_gap_px,
                    close_gap_px=component_close_gap_px,
                )
            cells.append(cell)
    return cells


def common_sheet_transform(
    cells: Sequence[Image.Image],
    *,
    canvas: int,
    ground_y: int,
    padding_px: int,
    cancel_event: threading.Event | None = None,
) -> tuple[list[Image.Image], tuple[int, int], tuple[int, int], tuple[int, int]]:
    """Apply one subject aperture, scale, and offset to every cell in a sheet.

    Generated sheets are not guaranteed to use square cells: a four-frame
    strip is commonly much taller than a 4x4 contact sheet.  Fitting the raw
    cell dimensions would therefore make the same character appear at wildly
    different sizes.  Build one aperture from the union of every alpha bbox,
    then crop/scale that exact aperture for every cell.  This keeps one camera
    transform per sheet, preserves authored airborne offsets, and makes the
    visible character scale independent of transparent/green cell padding.
    """
    if not cells:
        raise ValueError("sheet has no cells")
    cell_size = (
        max(cell.width for cell in cells),
        max(cell.height for cell in cells),
    )
    normalized_cells: list[Image.Image] = []
    bboxes: list[tuple[int, int, int, int]] = []
    for index, cell in enumerate(cells):
        _check_cancelled(cancel_event)
        if cell.size == cell_size:
            normalized = cell
        else:
            normalized = Image.new("RGBA", cell_size, (0, 0, 0, 0))
            normalized.alpha_composite(
                cell,
                ((cell_size[0] - cell.width) // 2, (cell_size[1] - cell.height) // 2),
            )
        bbox = normalized.getchannel("A").getbbox()
        if bbox is None:
            raise ValueError(f"sheet cell {index} is empty after chroma-key cleanup")
        normalized_cells.append(normalized)
        bboxes.append(bbox)

    aperture = (
        min(bbox[0] for bbox in bboxes),
        min(bbox[1] for bbox in bboxes),
        max(bbox[2] for bbox in bboxes),
        max(bbox[3] for bbox in bboxes),
    )
    source_size = (aperture[2] - aperture[0], aperture[3] - aperture[1])
    available_width = canvas - 2 * padding_px
    # A fully occupied cell can still be grounded without clipping its head.
    available_height = ground_y - padding_px + 1
    if available_width <= 0 or available_height <= 0:
        raise ValueError("paddingPx and groundY leave no drawable area")
    scale = min(available_width / source_size[0], available_height / source_size[1])
    output_size = (
        max(1, round(source_size[0] * scale)),
        max(1, round(source_size[1] * scale)),
    )
    offset = ((canvas - output_size[0]) // 2, padding_px)

    output: list[Image.Image] = []
    for cell in normalized_cells:
        _check_cancelled(cancel_event)
        subject_aperture = cell.crop(aperture)
        resized = subject_aperture.resize(output_size, Image.Resampling.LANCZOS)
        frame = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        frame.alpha_composite(resized, offset)
        output.append(neutralize_resampled_edge_spill(frame))
    return output, source_size, output_size, offset


def _alpha_bbox(frame: Image.Image, label: str) -> tuple[int, int, int, int]:
    bbox = frame.getchannel("A").getbbox()
    if bbox is None:
        raise ValueError(f"{label} is empty after chroma-key cleanup")
    return bbox


def _translate_checked(
    frame: Image.Image,
    dy: int,
    *,
    canvas: int,
    label: str,
    dx: int = 0,
) -> Image.Image:
    bbox = _alpha_bbox(frame, label)
    shifted_bbox = (bbox[0] + dx, bbox[1] + dy, bbox[2] + dx, bbox[3] + dy)
    if shifted_bbox[0] <= 0 or shifted_bbox[1] <= 0 or shifted_bbox[2] >= canvas or shifted_bbox[3] >= canvas:
        raise ValueError(
            f"{label} would lose its transparent edge after y translation {dy}: {shifted_bbox}"
        )
    shifted = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    shifted.alpha_composite(frame, (dx, dy))
    return shifted


def _align_bounds_center_x(frame: Image.Image, *, canvas: int, label: str) -> Image.Image:
    bbox = _alpha_bbox(frame, label)
    dx = round(canvas / 2 - (bbox[0] + bbox[2]) / 2)
    return _translate_checked(frame, 0, canvas=canvas, label=label, dx=dx)


def align_action(
    frames: Sequence[Image.Image],
    *,
    ground_y: int,
    support_frames: Sequence[int],
    lock_all_to_ground: bool,
    max_air_gap_px: int | None,
    align_bounds_center_x: bool = False,
    cancel_event: threading.Event | None = None,
) -> list[Image.Image]:
    """Align a grounded or airborne action without changing frame scale."""
    if not frames:
        raise ValueError("action has no frames")
    canvas = frames[0].width
    if any(frame.size != (canvas, canvas) for frame in frames):
        raise ValueError("all action frames must use one square canvas")
    support_bottoms = [
        _alpha_bbox(frames[index], f"support frame {index}")[3] - 1
        for index in support_frames
    ]

    aligned: list[Image.Image] = []
    if lock_all_to_ground:
        for index, frame in enumerate(frames):
            _check_cancelled(cancel_event)
            bottom = _alpha_bbox(frame, f"frame {index}")[3] - 1
            aligned.append(
                _translate_checked(
                    frame,
                    ground_y - bottom,
                    canvas=canvas,
                    label=f"frame {index}",
                )
            )
    else:
        # One translation preserves authored airborne offsets.  The lowest
        # declared support pose is the shared ground contact reference.
        common_dy = ground_y - max(support_bottoms)
        for index, frame in enumerate(frames):
            _check_cancelled(cancel_event)
            aligned.append(
                _translate_checked(
                    frame,
                    common_dy,
                    canvas=canvas,
                    label=f"frame {index}",
                )
            )
        if max_air_gap_px is not None:
            minimum_bottom = ground_y - max_air_gap_px
            clamped: list[Image.Image] = []
            for index, frame in enumerate(aligned):
                _check_cancelled(cancel_event)
                bottom = _alpha_bbox(frame, f"frame {index}")[3] - 1
                extra_dy = max(0, minimum_bottom - bottom)
                clamped.append(
                    _translate_checked(
                        frame,
                        extra_dy,
                        canvas=canvas,
                        label=f"frame {index}",
                    )
                    if extra_dy
                    else frame
                )
            aligned = clamped

    for index, frame in enumerate(aligned):
        if frame.mode != "RGBA" or frame.size != (OUTPUT_SIZE, OUTPUT_SIZE):
            raise ValueError(f"frame {index} is not {OUTPUT_SIZE}x{OUTPUT_SIZE} RGBA")
        bbox = _alpha_bbox(frame, f"frame {index}")
        if bbox[0] <= 0 or bbox[1] <= 0 or bbox[2] >= canvas or bbox[3] >= canvas:
            raise ValueError(f"frame {index} lacks a transparent canvas edge: {bbox}")
    if align_bounds_center_x:
        aligned = [
            _align_bounds_center_x(frame, canvas=canvas, label=f"frame {index}")
            for index, frame in enumerate(aligned)
        ]
    return aligned


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _save_png(frame: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    frame.save(path, format="PNG", optimize=False, compress_level=9)


def _png_bytes(frame: Image.Image) -> bytes:
    stream = io.BytesIO()
    frame.save(stream, format="PNG", optimize=False, compress_level=9)
    return stream.getvalue()


def _remove_path(path: Path, *, is_directory: bool) -> None:
    if not path.exists() and not path.is_symlink():
        return
    if is_directory and path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
    else:
        path.unlink()


def _commit_artifacts(artifacts: Sequence[PendingArtifact], *, token: str) -> None:
    """Install all staged artifacts with rollback on any BaseException."""
    try:
        for artifact in artifacts:
            artifact.target.parent.mkdir(parents=True, exist_ok=True)
            if artifact.target.is_symlink():
                raise RuntimeError(f"refusing to replace symlink output: {artifact.target}")
            if artifact.target.exists():
                artifact.backup = artifact.target.parent / f".{artifact.target.name}.sprite-backup-{token}"
                if artifact.backup.exists():
                    _remove_path(artifact.backup, is_directory=artifact.is_directory)
                os.replace(artifact.target, artifact.backup)
            os.replace(artifact.staged, artifact.target)
            artifact.installed = True
    except BaseException:
        for artifact in reversed(artifacts):
            try:
                if artifact.installed:
                    _remove_path(artifact.target, is_directory=artifact.is_directory)
                if artifact.backup is not None and artifact.backup.exists():
                    os.replace(artifact.backup, artifact.target)
            except OSError:
                # Preserve the original exception; a rollback failure is still
                # visible through the surviving .sprite-backup path.
                pass
        raise
    else:
        for artifact in artifacts:
            if artifact.backup is not None and artifact.backup.exists():
                _remove_path(artifact.backup, is_directory=artifact.is_directory)


def _bbox_report(frames: Sequence[Image.Image]) -> dict[str, list[int]]:
    bboxes = [_alpha_bbox(frame, f"report frame {index}") for index, frame in enumerate(frames)]
    widths = [bbox[2] - bbox[0] for bbox in bboxes]
    heights = [bbox[3] - bbox[1] for bbox in bboxes]
    bottoms = [bbox[3] - 1 for bbox in bboxes]
    tops = [bbox[1] for bbox in bboxes]
    return {
        "bboxWidthRange": [min(widths), max(widths)],
        "bboxHeightRange": [min(heights), max(heights)],
        "bboxTopRange": [min(tops), max(tops)],
        "bboxBottomRange": [min(bottoms), max(bottoms)],
    }


def build_sprites(
    spec: BuildSpec,
    *,
    dry_run: bool = False,
    cancel_event: threading.Event | None = None,
    before_commit: Callable[[], None] | None = None,
) -> dict[str, Any]:
    """Build one configuration and return its deterministic report.

    ``cancel_event`` is checked between sheet cells, frame transforms, action
    frames, and file writes, so a worker thread can abort without publishing a
    partial action.  ``before_commit`` is a narrow test/integration hook; an
    exception from it leaves existing outputs untouched.
    """
    _check_cancelled(cancel_event)
    sheet_results: dict[str, SheetResult] = {}
    for sheet_spec in spec.sheets:
        _check_cancelled(cancel_event)
        if not sheet_spec.path.is_file():
            raise FileNotFoundError(sheet_spec.path)
        with Image.open(sheet_spec.path) as source:
            source.load()
            cells = split_grid(
                source.convert("RGBA"),
                rows=sheet_spec.rows,
                cols=sheet_spec.cols,
                margin_px=sheet_spec.margin_px,
                overlap_px=sheet_spec.overlap_px,
                edge_clear_px=sheet_spec.edge_clear_px,
                component_cleanup=sheet_spec.component_cleanup,
                component_gap_px=sheet_spec.component_gap_px,
                component_close_gap_px=sheet_spec.component_close_gap_px,
                chroma_key=sheet_spec.chroma_key,
                cancel_event=cancel_event,
            )
        transformed, source_size, output_size, offset = common_sheet_transform(
            cells,
            canvas=spec.canvas,
            ground_y=spec.ground_y,
            padding_px=sheet_spec.padding_px,
            cancel_event=cancel_event,
        )
        sheet_results[sheet_spec.sheet_id] = SheetResult(
            spec=sheet_spec,
            cells=tuple(transformed),
            source_cell_size=source_size,
            output_cell_size=output_size,
            output_offset=offset,
            source_sha256=_sha256_file(sheet_spec.path),
        )

    action_frames: dict[str, list[Image.Image]] = {}
    for action in spec.actions:
        _check_cancelled(cancel_event)
        sheet = sheet_results[action.sheet_id]
        selected = [sheet.cells[index].copy() for index in action.cells]
        action_frames[action.action_id] = align_action(
            selected,
            ground_y=spec.ground_y,
            support_frames=action.support_frames,
            lock_all_to_ground=action.lock_all_to_ground,
            align_bounds_center_x=action.align_bounds_center_x,
            max_air_gap_px=action.max_air_gap_px,
            cancel_event=cancel_event,
        )

    report: dict[str, Any] = {
        "schemaVersion": 1,
        "canvas": {"width": spec.canvas, "height": spec.canvas},
        "groundY": spec.ground_y,
        "dryRun": dry_run,
        "sheets": {},
        "actions": {},
        "preview": None,
    }
    for sheet_id in sorted(sheet_results):
        sheet = sheet_results[sheet_id]
        report["sheets"][sheet_id] = {
            "path": sheet.spec.display_path,
            "sha256": sheet.source_sha256,
            "rows": sheet.spec.rows,
            "cols": sheet.spec.cols,
            "cells": len(sheet.cells),
            "sourceCellSize": list(sheet.source_cell_size),
            "outputCellSize": list(sheet.output_cell_size),
            "outputOffset": list(sheet.output_offset),
            "paddingPx": sheet.spec.padding_px,
        }

    # Stage first; no target path changes until every action and report passes.
    # A dry run serializes PNGs in memory, so it does not even create output
    # parents or transient directories.
    stage_root: Path | None = None
    if not dry_run:
        stage_parent = spec.output_root.parent
        stage_parent.mkdir(parents=True, exist_ok=True)
        stage_root = Path(tempfile.mkdtemp(prefix=".sprite-build-", dir=stage_parent))
    token = uuid.uuid4().hex
    artifacts: list[PendingArtifact] = []
    try:
        for action in spec.actions:
            _check_cancelled(cancel_event)
            frames = action_frames[action.action_id]
            staged_action = stage_root / "actions" / action.action_id if stage_root is not None else None
            if staged_action is not None:
                staged_action.mkdir(parents=True, exist_ok=False)
            width = max(2, len(str(max(0, len(frames) - 1))))
            files: list[dict[str, object]] = []
            for index, frame in enumerate(frames):
                _check_cancelled(cancel_event)
                name = f"frame_{index:0{width}d}.png"
                if staged_action is None:
                    payload = _png_bytes(frame)
                    byte_count = len(payload)
                    digest = _sha256_bytes(payload)
                else:
                    output = staged_action / name
                    _save_png(frame, output)
                    byte_count = output.stat().st_size
                    digest = _sha256_file(output)
                files.append(
                    {
                        "file": name,
                        "bytes": byte_count,
                        "sha256": digest,
                    }
                )
            action_report: dict[str, Any] = {
                "sheet": action.sheet_id,
                "cells": list(action.cells),
                "frames": len(frames),
                "supportFrames": list(action.support_frames),
                "lockAllToGround": action.lock_all_to_ground,
                "alignBoundsCenterX": action.align_bounds_center_x,
                "maxAirGapPx": action.max_air_gap_px,
                "files": files,
            }
            action_report.update(_bbox_report(frames))
            report["actions"][action.action_id] = action_report
            if staged_action is not None:
                artifacts.append(
                    PendingArtifact(
                        staged=staged_action,
                        target=spec.output_root / action.action_id,
                        is_directory=True,
                    )
                )

        if spec.preview_action is not None:
            assert spec.preview_path is not None
            preview = action_frames[spec.preview_action][spec.preview_frame]
            if stage_root is None:
                preview_payload = _png_bytes(preview)
                preview_bytes = len(preview_payload)
                preview_sha256 = _sha256_bytes(preview_payload)
                staged_preview = None
            else:
                staged_preview = stage_root / "metadata" / "preview.png"
                _save_png(preview, staged_preview)
                preview_bytes = staged_preview.stat().st_size
                preview_sha256 = _sha256_file(staged_preview)
            report["preview"] = {
                "action": spec.preview_action,
                "frame": spec.preview_frame,
                "bytes": preview_bytes,
                "sha256": preview_sha256,
            }
            if staged_preview is not None:
                artifacts.append(
                    PendingArtifact(staged=staged_preview, target=spec.preview_path, is_directory=False)
                )

        report_payload = (json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
        if stage_root is not None:
            staged_report = stage_root / "metadata" / "report.json"
            staged_report.parent.mkdir(parents=True, exist_ok=True)
            staged_report.write_bytes(report_payload)
            artifacts.append(
                PendingArtifact(staged=staged_report, target=spec.report_path, is_directory=False)
            )

        _check_cancelled(cancel_event)
        if before_commit is not None and not dry_run:
            before_commit()
        _check_cancelled(cancel_event)
        if not dry_run:
            _commit_artifacts(artifacts, token=token)
    finally:
        if stage_root is not None:
            shutil.rmtree(stage_root, ignore_errors=True)
    return report


def build_from_config(
    config_path: Path,
    *,
    dry_run: bool = False,
    cancel_event: threading.Event | None = None,
    report_override: Path | None = None,
    preview_action_override: str | None = None,
    preview_frame_override: int | None = None,
    preview_path_override: Path | None = None,
) -> dict[str, Any]:
    spec = load_build_spec(
        config_path,
        report_override=report_override,
        preview_action_override=preview_action_override,
        preview_frame_override=preview_frame_override,
        preview_path_override=preview_path_override,
    )
    return build_sprites(spec, dry_run=dry_run, cancel_event=cancel_event)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build deterministic 512x512 RGBA PetPack frames from configured sprite sheets."
    )
    parser.add_argument("--config", type=Path, required=True, help="JSON build configuration")
    parser.add_argument("--dry-run", action="store_true", help="validate and report without writing outputs")
    parser.add_argument("--report", type=Path, help="override the configured report path")
    parser.add_argument("--preview-action", help="override previewAction")
    parser.add_argument("--preview-frame", type=int, help="override previewFrame")
    parser.add_argument("--preview-path", type=Path, help="override previewPath")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        report = build_from_config(
            args.config,
            dry_run=args.dry_run,
            report_override=args.report,
            preview_action_override=args.preview_action,
            preview_frame_override=args.preview_frame,
            preview_path_override=args.preview_path,
        )
    except (BuildCancelled, KeyboardInterrupt):
        print("sprite build cancelled", file=sys.stderr)
        return 130
    except (ConfigurationError, FileNotFoundError, OSError, ValueError) as error:
        print(f"sprite build failed: {error}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
