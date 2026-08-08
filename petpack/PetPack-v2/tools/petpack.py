#!/usr/bin/env python3
"""Validate and build SweetPet declarative .petpack archives."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

try:
    from PIL import Image, UnidentifiedImageError
    PIL_DECOMPRESSION_ERROR = Image.DecompressionBombError
except ImportError:  # pragma: no cover - exercised by the CLI on machines without Pillow
    Image = None

    class UnidentifiedImageError(Exception):
        """Fallback type used only to keep exception handling import-safe."""

    class PIL_DECOMPRESSION_ERROR(Exception):
        """Fallback type used only to keep exception handling import-safe."""


SAFE_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
SAFE_EXTENSION_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{2,95}$")
STABLE_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
HEX_COLOR = re.compile(r"^#[0-9A-Fa-f]{6}$")

MAX_VERSION_COMPONENT = 2_147_483_647

MAX_FILES = 2_000
MAX_EXPANDED_BYTES = 256 * 1024 * 1024
MAX_SINGLE_BITMAP_PIXELS = 4_194_304
MAX_CLIP_BITMAP_PIXELS = 16_777_216
MAX_PACK_BITMAP_PIXELS = 67_108_864
MAX_DIALOGUE_LINES_PER_EVENT = 256
MAX_DIALOGUE_LINE_LENGTH = 500
MAX_DIALOGUE_RULES = 256
MAX_TASKS = 256

VALID_FACING = {"front", "left", "right"}
VALID_ROTATION = {"upright", "align-surface", "align-velocity"}
VALID_PLAY_MODES = {"GRAVITY", "BORDER_WALK", "BORDER_RUN", "HIDE_SEEK", "BOMBER", "SNAKE"}
ARCADE_PLAY_MODES = {"HIDE_SEEK", "BOMBER", "SNAKE"}
VALID_AVATAR_SHAPES = {"circle", "rect"}
REQUIRED_BEHAVIOR_PROFILES = {"daily", "sweet", "quiet"}
BEHAVIOR_WEIGHT_KEYS = ("idleWeight", "walkWeight", "runWeight", "socialWeight")
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"}
DIALOGUE_CONDITION_KEYS = {
    "hourStart",
    "hourEnd",
    "dayOfWeek",
    "chance",
    "weatherEquals",
    "weatherIn",
    "temperatureMin",
    "temperatureMax",
    "settingKey",
    "settingEquals",
}
TASK_CONDITION_KEYS = {"hourStart", "hourEnd", "weatherIn"}


def is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def is_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def is_stable_version(value: object) -> bool:
    """Match the Android runtime's strict, non-overflowing x.y.z pack-version protocol."""
    if not isinstance(value, str):
        return False
    match = STABLE_VERSION.fullmatch(value)
    return match is not None and all(int(component) <= MAX_VERSION_COMPONENT for component in match.groups())


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"Unable to read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def safe_relative(value: str) -> bool:
    if not isinstance(value, str) or "\\" in value:
        return False
    path = PurePosixPath(value)
    return bool(value) and not path.is_absolute() and all(part not in ("", ".", "..") for part in path.parts)


def require_file(root: Path, relative: str, errors: list[str]) -> Path | None:
    if not safe_relative(relative):
        errors.append(f"Unsafe relative path: {relative}")
        return None
    candidate = root / PurePosixPath(relative)
    if not candidate.is_file():
        errors.append(f"Missing file: {relative}")
        return None
    return candidate


def require_nonblank(value: object, label: str, errors: list[str], max_length: int = 500) -> bool:
    valid = isinstance(value, str) and bool(value.strip()) and len(value) <= max_length
    if not valid:
        errors.append(f"{label} must be a non-blank string of at most {max_length} characters")
    return valid


def validate_animations(
    root: Path,
    entrypoint: object,
    errors: list[str],
) -> tuple[set[str], dict[str, list[str]]]:
    actions: set[str] = set()
    frame_paths: dict[str, list[str]] = {}
    if not isinstance(entrypoint, str):
        return actions, frame_paths
    path = require_file(root, entrypoint, errors)
    if path is None:
        return actions, frame_paths
    try:
        document = load_json(path)
    except ValueError as exc:
        errors.append(str(exc))
        return actions, frame_paths

    if document.get("schemaVersion") != 1:
        errors.append("animations.schemaVersion must be 1")
    canvas = document.get("canvas")
    if (
        not isinstance(canvas, list)
        or len(canvas) != 2
        or any(not is_int(value) or not 1 <= value <= 8_192 for value in canvas)
    ):
        errors.append("animations.canvas must contain two integers in 1..8192")
    nodes = document.get("actions")
    if not isinstance(nodes, dict):
        errors.append("animations.actions must be an object")
        return actions, frame_paths
    if "idle" not in nodes:
        errors.append("Animation manifest must provide idle")

    for action, node in nodes.items():
        if not isinstance(action, str) or not SAFE_ID.fullmatch(action):
            errors.append(f"Unsafe action id: {action}")
            continue
        actions.add(action)
        if not isinstance(node, dict):
            errors.append(f"Animation action must be an object: {action}")
            continue
        fps = node.get("fps", 4)
        if not is_int(fps) or not 1 <= fps <= 30:
            errors.append(f"Animation fps must be in 1..30: {action}")
        if "loop" in node and not isinstance(node["loop"], bool):
            errors.append(f"Animation loop must be boolean: {action}")

        motion = node.get("motion", {})
        if not isinstance(motion, dict):
            errors.append(f"Animation motion must be an object: {action}")
            motion = {}
        if motion.get("defaultFacing", "front") not in VALID_FACING:
            errors.append(f"Unsupported defaultFacing: {action}")
        if motion.get("rotationPolicy", "upright") not in VALID_ROTATION:
            errors.append(f"Unsupported rotationPolicy: {action}")
        if not isinstance(motion.get("supportsHorizontalMirror", True), bool):
            errors.append(f"supportsHorizontalMirror must be boolean: {action}")
        anchor = motion.get("groundAnchor", [0.5, 0.94])
        if (
            not isinstance(anchor, list)
            or len(anchor) != 2
            or any(not is_number(value) or not 0 <= float(value) <= 1 for value in anchor)
        ):
            errors.append(f"groundAnchor must contain two normalized numbers: {action}")
        tags = motion.get("sceneTags", [])
        if not isinstance(tags, list) or any(
            not isinstance(tag, str) or not SAFE_ID.fullmatch(tag) for tag in tags
        ):
            errors.append(f"Invalid motion sceneTags: {action}")

        frames = node.get("frames")
        if not isinstance(frames, list) or not frames:
            errors.append(f"Animation has no frames: {action}")
            continue
        resolved_frames: list[str] = []
        for frame in frames:
            if not isinstance(frame, str) or not safe_relative(frame) or not frame.startswith("animations/"):
                errors.append(f"Unsafe frame path in {action}: {frame}")
                continue
            relative = f"character/{frame}"
            require_file(root, relative, errors)
            resolved_frames.append(relative)
        frame_paths[action] = resolved_frames
    return actions, frame_paths


def validate_behavior(root: Path, entrypoint: object, actions: set[str], errors: list[str]) -> None:
    if not isinstance(entrypoint, str):
        return
    path = require_file(root, entrypoint, errors)
    if path is None:
        return
    try:
        document = load_json(path)
    except ValueError as exc:
        errors.append(str(exc))
        return
    if document.get("schemaVersion") != 1:
        errors.append("behavior.schemaVersion must be 1")
    profiles = document.get("profiles")
    if not isinstance(profiles, dict):
        errors.append("behavior.profiles must be an object")
        profiles = {}
    missing_profiles = REQUIRED_BEHAVIOR_PROFILES - set(profiles)
    for profile in sorted(missing_profiles):
        errors.append(f"Missing behavior profile: {profile}")
    for profile, node in profiles.items():
        if not isinstance(profile, str) or not SAFE_ID.fullmatch(profile):
            errors.append(f"Unsafe behavior profile id: {profile}")
            continue
        if not isinstance(node, dict):
            errors.append(f"Behavior profile must be an object: {profile}")
            continue
        weights: list[int] = []
        for key in BEHAVIOR_WEIGHT_KEYS:
            value = node.get(key)
            if not is_int(value) or not 0 <= value <= 1_000:
                errors.append(f"Behavior weight must be an integer in 0..1000: {profile}.{key}")
            else:
                weights.append(value)
        if len(weights) == len(BEHAVIOR_WEIGHT_KEYS) and sum(weights) <= 0:
            errors.append(f"Behavior profile has no selectable action: {profile}")

    fallback = document.get("fallbackAction", "idle")
    if not isinstance(fallback, str) or not SAFE_ID.fullmatch(fallback):
        errors.append(f"Invalid behavior fallbackAction: {fallback}")
    elif fallback not in actions:
        errors.append(f"Behavior fallbackAction references unknown action: {fallback}")
    rest_seconds = document.get("manualPlacementRestSeconds", 300)
    if not is_int(rest_seconds) or not 10 <= rest_seconds <= 86_400:
        errors.append("manualPlacementRestSeconds must be an integer in 10..86400")


def validate_game_kit(
    root: Path,
    entrypoint: str,
    errors: list[str],
) -> tuple[str | None, set[str]]:
    avatar_path: str | None = None
    supported_modes: set[str] = set()
    path = require_file(root, entrypoint, errors)
    if path is None:
        return avatar_path, supported_modes
    try:
        document = load_json(path)
    except ValueError as exc:
        errors.append(str(exc))
        return avatar_path, supported_modes
    if document.get("schemaVersion") != 1:
        errors.append("game-kit.schemaVersion must be 1")

    avatar = document.get("avatar")
    if avatar is not None and not isinstance(avatar, dict):
        errors.append("game-kit.avatar must be an object")
        avatar = None
    if isinstance(avatar, dict):
        source = avatar.get("source")
        if not isinstance(source, str) or not safe_relative(source):
            errors.append("game-kit avatar source is unsafe")
        else:
            avatar_path = f"character/{source}"
            require_file(root, avatar_path, errors)
        crop = avatar.get("crop")
        valid_crop = (
            isinstance(crop, list)
            and len(crop) == 4
            and all(is_number(value) and 0 <= float(value) <= 1 for value in crop)
        )
        if valid_crop:
            valid_crop = float(crop[0]) < float(crop[2]) and float(crop[1]) < float(crop[3])
        if not valid_crop:
            errors.append("game-kit avatar crop must be [left, top, right, bottom] with positive normalized area")
        if avatar.get("shape", "circle") not in VALID_AVATAR_SHAPES:
            errors.append("game-kit avatar shape must be circle or rect")

    modes = document.get("supportedModes")
    if not isinstance(modes, list):
        errors.append("game-kit.supportedModes must be an array")
    else:
        for mode in modes:
            if not isinstance(mode, str) or mode not in VALID_PLAY_MODES:
                errors.append(f"Unsupported game mode: {mode}")
            elif mode in supported_modes:
                errors.append(f"Duplicate game mode: {mode}")
            else:
                supported_modes.add(mode)
    if supported_modes & ARCADE_PLAY_MODES and avatar_path is None:
        errors.append("Arcade game modes require a game-kit avatar")
    for key in ("accentColor", "foodColor", "bombColor"):
        value = document.get(key)
        if value is not None and (not isinstance(value, str) or not HEX_COLOR.fullmatch(value)):
            errors.append(f"game-kit.{key} must be a #RRGGBB color")
    return avatar_path, supported_modes


def validate_task_condition(condition: object, label: str, errors: list[str]) -> None:
    if condition is None:
        return
    if not isinstance(condition, dict):
        errors.append(f"{label}.when must be an object")
        return
    unknown = set(condition) - TASK_CONDITION_KEYS
    for key in sorted(unknown):
        errors.append(f"Unsupported task condition: {label}.when.{key}")
    for key in ("hourStart", "hourEnd"):
        if key in condition and (not is_int(condition[key]) or not 0 <= condition[key] <= 23):
            errors.append(f"{label}.when.{key} must be an integer in 0..23")
    if "weatherIn" in condition:
        values = condition["weatherIn"]
        if not isinstance(values, list) or not values or any(
            not isinstance(value, str) or not value.strip() or len(value) > 64 for value in values
        ):
            errors.append(f"{label}.when.weatherIn must be a non-empty string array")


def validate_tasks(
    root: Path,
    entrypoint: object,
    actions: set[str],
    supported_modes: set[str],
    errors: list[str],
) -> None:
    if not isinstance(entrypoint, str):
        return
    path = require_file(root, entrypoint, errors)
    if path is None:
        return
    try:
        document = load_json(path)
    except ValueError as exc:
        errors.append(str(exc))
        return
    if document.get("schemaVersion", 1) not in (1, 2):
        errors.append("Unsupported tasks.schemaVersion")
    tasks = document.get("tasks")
    if not isinstance(tasks, list):
        errors.append("tasks.tasks must be an array")
        return
    if len(tasks) > MAX_TASKS:
        errors.append(f"Too many tasks: {len(tasks)} > {MAX_TASKS}")
    task_ids: set[str] = set()
    for index, task in enumerate(tasks):
        label = f"tasks[{index}]"
        if not isinstance(task, dict):
            errors.append(f"{label} must be an object")
            continue
        task_id = task.get("id")
        if not isinstance(task_id, str) or not SAFE_ID.fullmatch(task_id) or task_id in task_ids:
            errors.append(f"Invalid or duplicate task id: {task_id}")
        else:
            task_ids.add(task_id)
            label = f"task {task_id}"
        require_nonblank(task.get("prompt"), f"{label}.prompt", errors)
        if "title" in task:
            require_nonblank(task["title"], f"{label}.title", errors, 120)
        action = task.get("action", "wave")
        if not isinstance(action, str) or not SAFE_ID.fullmatch(action) or action not in actions:
            errors.append(f"{label}.action references unknown action: {action}")
        cooldown = task.get("cooldownMinutes", 90)
        if not is_int(cooldown) or not 1 <= cooldown <= 10_080:
            errors.append(f"{label}.cooldownMinutes must be an integer in 1..10080")
        validate_task_condition(task.get("when"), label, errors)

        options = task.get("options", [])
        if not isinstance(options, list):
            errors.append(f"{label}.options must be an array")
            continue
        option_ids: set[str] = set()
        for option_index, option in enumerate(options):
            option_label = f"{label}.options[{option_index}]"
            if not isinstance(option, dict):
                errors.append(f"{option_label} must be an object")
                continue
            option_id = option.get("id")
            if not isinstance(option_id, str) or not SAFE_ID.fullmatch(option_id) or option_id in option_ids:
                errors.append(f"Invalid or duplicate option id in {label}: {option_id}")
            else:
                option_ids.add(option_id)
                option_label = f"{label}.option {option_id}"
            require_nonblank(option.get("label"), f"{option_label}.label", errors, 120)
            if "response" in option:
                require_nonblank(option["response"], f"{option_label}.response", errors)
            option_action = option.get("action", action)
            if (
                not isinstance(option_action, str)
                or not SAFE_ID.fullmatch(option_action)
                or option_action not in actions
            ):
                errors.append(f"{option_label}.action references unknown action: {option_action}")
            snooze = option.get("snoozeMinutes", 0)
            if not is_int(snooze) or not 0 <= snooze <= 1_440:
                errors.append(f"{option_label}.snoozeMinutes must be an integer in 0..1440")
            mode = option.get("playMode")
            if mode is not None:
                if not isinstance(mode, str) or mode not in VALID_PLAY_MODES:
                    errors.append(f"{option_label}.playMode is unsupported: {mode}")
                elif mode not in supported_modes:
                    errors.append(f"{option_label}.playMode is not declared by game-kit: {mode}")


def validate_dialogue(root: Path, entrypoint: object, errors: list[str]) -> set[str]:
    events: set[str] = set()
    if not isinstance(entrypoint, str):
        return events
    path = require_file(root, entrypoint, errors)
    if path is None:
        return events
    try:
        document = load_json(path)
    except ValueError as exc:
        errors.append(str(exc))
        return events
    if document.get("schemaVersion", 1) not in (1, 2):
        errors.append("Unsupported dialogue.schemaVersion")
    for event, lines in document.items():
        if event == "schemaVersion":
            continue
        if not isinstance(event, str) or not SAFE_ID.fullmatch(event):
            errors.append(f"Unsafe dialogue event id: {event}")
            continue
        events.add(event)
        if not isinstance(lines, list) or not 1 <= len(lines) <= MAX_DIALOGUE_LINES_PER_EVENT:
            errors.append(f"Dialogue event {event} must contain 1..{MAX_DIALOGUE_LINES_PER_EVENT} lines")
            continue
        for line_index, line in enumerate(lines):
            require_nonblank(line, f"dialogue.{event}[{line_index}]", errors, MAX_DIALOGUE_LINE_LENGTH)
    if "idle" not in events:
        errors.append("Dialogue manifest must provide idle lines")
    return events


def validate_dialogue_condition(condition: object, label: str, errors: list[str]) -> None:
    if condition is None:
        return
    if not isinstance(condition, dict):
        errors.append(f"{label}.when must be an object")
        return
    unknown = set(condition) - DIALOGUE_CONDITION_KEYS
    for key in sorted(unknown):
        errors.append(f"Unsupported dialogue condition: {label}.when.{key}")
    for key in ("hourStart", "hourEnd"):
        if key in condition and (not is_int(condition[key]) or not 0 <= condition[key] <= 23):
            errors.append(f"{label}.when.{key} must be an integer in 0..23")
    if "dayOfWeek" in condition and (
        not is_int(condition["dayOfWeek"]) or not 1 <= condition["dayOfWeek"] <= 7
    ):
        errors.append(f"{label}.when.dayOfWeek must be an integer in 1..7")
    if "chance" in condition and (
        not is_number(condition["chance"]) or not 0 <= float(condition["chance"]) <= 1
    ):
        errors.append(f"{label}.when.chance must be a number in 0..1")
    for key in ("temperatureMin", "temperatureMax"):
        if key in condition and not is_number(condition[key]):
            errors.append(f"{label}.when.{key} must be a finite number")
    if (
        is_number(condition.get("temperatureMin"))
        and is_number(condition.get("temperatureMax"))
        and float(condition["temperatureMin"]) > float(condition["temperatureMax"])
    ):
        errors.append(f"{label} temperatureMin cannot exceed temperatureMax")
    if "weatherEquals" in condition:
        require_nonblank(condition["weatherEquals"], f"{label}.when.weatherEquals", errors, 64)
    if "weatherIn" in condition:
        values = condition["weatherIn"]
        if not isinstance(values, list) or not values or any(
            not isinstance(value, str) or not value.strip() or len(value) > 64 for value in values
        ):
            errors.append(f"{label}.when.weatherIn must be a non-empty string array")
    setting_key = condition.get("settingKey")
    if setting_key is not None:
        if not isinstance(setting_key, str) or not SAFE_ID.fullmatch(setting_key):
            errors.append(f"{label}.when.settingKey is invalid")
        if not isinstance(condition.get("settingEquals"), str):
            errors.append(f"{label}.when.settingEquals is required when settingKey is used")
    elif "settingEquals" in condition:
        errors.append(f"{label}.when.settingEquals requires settingKey")


def validate_dialogue_rules(root: Path, entrypoint: str, events: set[str], errors: list[str]) -> None:
    path = require_file(root, entrypoint, errors)
    if path is None:
        return
    try:
        document = load_json(path)
    except ValueError as exc:
        errors.append(str(exc))
        return
    if document.get("schemaVersion", 1) not in (1, 2):
        errors.append("Unsupported dialogue-rules.schemaVersion")
    rules = document.get("rules")
    if not isinstance(rules, list):
        errors.append("dialogue-rules.rules must be an array")
        return
    if len(rules) > MAX_DIALOGUE_RULES:
        errors.append(f"Too many dialogue rules: {len(rules)} > {MAX_DIALOGUE_RULES}")
    rule_ids: set[str] = set()
    for index, rule in enumerate(rules):
        label = f"dialogue rule[{index}]"
        if not isinstance(rule, dict):
            errors.append(f"{label} must be an object")
            continue
        rule_id = rule.get("id")
        if not isinstance(rule_id, str) or not SAFE_ID.fullmatch(rule_id) or rule_id in rule_ids:
            errors.append(f"Invalid or duplicate dialogue rule id: {rule_id}")
        else:
            rule_ids.add(rule_id)
            label = f"dialogue rule {rule_id}"
        event = rule.get("event")
        if not isinstance(event, str) or not SAFE_ID.fullmatch(event):
            errors.append(f"{label}.event is invalid: {event}")
        elif event not in events:
            errors.append(f"{label}.event references unknown dialogue event: {event}")
        validate_dialogue_condition(rule.get("when"), label, errors)
        lines = rule.get("lines")
        if not isinstance(lines, list) or not 1 <= len(lines) <= MAX_DIALOGUE_LINES_PER_EVENT:
            errors.append(f"{label}.lines must contain 1..{MAX_DIALOGUE_LINES_PER_EVENT} lines")
            continue
        for line_index, line in enumerate(lines):
            require_nonblank(line, f"{label}.lines[{line_index}]", errors, MAX_DIALOGUE_LINE_LENGTH)


def decode_bitmap(path: Path, relative: str, errors: list[str]) -> int:
    if Image is None:
        errors.append("Bitmap validation requires Pillow (python -m pip install Pillow)")
        return 0
    try:
        with Image.open(path) as image:
            width, height = image.size
            if width <= 0 or height <= 0:
                raise ValueError("image dimensions are not positive")
            pixels = width * height
            if pixels > MAX_SINGLE_BITMAP_PIXELS:
                errors.append(
                    f"Bitmap exceeds single-image pixel budget: {relative} "
                    f"({pixels} > {MAX_SINGLE_BITMAP_PIXELS})"
                )
            image.verify()
        if pixels > MAX_SINGLE_BITMAP_PIXELS:
            return pixels
        # verify() checks the container. Reopening and loading forces pixel-stream decoding.
        with Image.open(path) as image:
            image.load()
        return pixels
    except (OSError, ValueError, SyntaxError, UnidentifiedImageError, PIL_DECOMPRESSION_ERROR) as exc:
        errors.append(f"Unable to decode bitmap {relative}: {exc}")
        return 0


def validate_bitmaps(
    root: Path,
    preview: object,
    frame_paths: dict[str, list[str]],
    avatar_path: str | None,
    errors: list[str],
) -> None:
    cache: dict[str, int] = {}

    def pixels_for(relative: str) -> int:
        if relative not in cache:
            candidate = root / PurePosixPath(relative)
            cache[relative] = decode_bitmap(candidate, relative, errors) if candidate.is_file() else 0
        return cache[relative]

    # Preview is not rendered as an animation frame, but still must be a decodable bitmap.
    if isinstance(preview, str) and safe_relative(preview) and (root / PurePosixPath(preview)).is_file():
        pixels_for(preview)

    total_pixels = 0
    if avatar_path is not None:
        total_pixels += pixels_for(avatar_path)
    for action, frames in frame_paths.items():
        clip_pixels = sum(pixels_for(relative) for relative in frames)
        total_pixels += clip_pixels
        if clip_pixels > MAX_CLIP_BITMAP_PIXELS:
            errors.append(
                f"Animation {action} exceeds decoded pixel budget: "
                f"{clip_pixels} > {MAX_CLIP_BITMAP_PIXELS}"
            )

    # Decode image files even when they are currently unreferenced, so a corrupt payload cannot hide in a valid pack.
    referenced = {relative for frames in frame_paths.values() for relative in frames}
    if avatar_path is not None:
        referenced.add(avatar_path)
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in IMAGE_SUFFIXES:
            continue
        relative = path.relative_to(root).as_posix()
        pixels = pixels_for(relative)
        if relative not in referenced:
            total_pixels += pixels
    if total_pixels > MAX_PACK_BITMAP_PIXELS:
        errors.append(f"Pack exceeds decoded pixel budget: {total_pixels} > {MAX_PACK_BITMAP_PIXELS}")


def validate_checksums(root: Path, manifest: dict[str, Any], errors: list[str]) -> None:
    integrity = manifest.get("integrity", "checksums.json")
    if integrity != "checksums.json":
        errors.append("pack.integrity must be checksums.json")
        return
    checksum_path = root / "checksums.json"
    if not checksum_path.is_file():
        errors.append("Missing checksums.json")
        return
    try:
        document = load_json(checksum_path)
    except ValueError as exc:
        errors.append(str(exc))
        return
    if document.get("schemaVersion") != 1 or document.get("algorithm") != "SHA-256":
        errors.append("checksums.json must use schemaVersion 1 and SHA-256")
    entries = document.get("files")
    if not isinstance(entries, dict):
        errors.append("checksums.files must be an object")
        return
    expected_paths = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() and path.name != "checksums.json"
    }
    declared_paths = set(entries)
    for relative in sorted(expected_paths - declared_paths):
        errors.append(f"Checksum manifest is missing file: {relative}")
    for relative in sorted(declared_paths - expected_paths):
        errors.append(f"Checksum manifest references missing file: {relative}")
    for relative, expected in entries.items():
        if not isinstance(relative, str) or not safe_relative(relative) or relative == "checksums.json":
            errors.append(f"Invalid checksum path: {relative}")
            continue
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9A-Fa-f]{64}", expected):
            errors.append(f"Invalid SHA-256 value: {relative}")
            continue
        candidate = root / PurePosixPath(relative)
        if not candidate.is_file():
            continue
        actual = hashlib.sha256(candidate.read_bytes()).hexdigest()
        if actual.lower() != expected.lower():
            errors.append(f"SHA-256 mismatch: {relative}")


def validate_pack(root: Path, verify_checksums: bool = True) -> tuple[dict[str, Any], list[str]]:
    errors: list[str] = []
    manifest_path = root / "pack.json"
    if not manifest_path.is_file():
        return {}, ["Missing pack.json"]
    try:
        manifest = load_json(manifest_path)
    except ValueError as exc:
        return {}, [str(exc)]

    if manifest.get("schemaVersion") != 2:
        errors.append("pack.schemaVersion must be 2")
    protocol = manifest.get("protocol")
    if not isinstance(protocol, dict):
        errors.append("pack.protocol must be an object")
        protocol = {}
    if protocol.get("id") != "io.sweetpet.pack":
        errors.append("protocol.id must be io.sweetpet.pack")
    version = protocol.get("version")
    if not isinstance(version, str) or not re.fullmatch(r"2\.[0-9]+", version):
        errors.append("Only io.sweetpet.pack protocol 2.x is supported")
    min_runtime = protocol.get("minRuntime")
    if not is_stable_version(min_runtime):
        errors.append("protocol.minRuntime must be a stable numeric x.y.z version")
    pack_id = manifest.get("id")
    if not isinstance(pack_id, str) or not SAFE_ID.fullmatch(pack_id):
        errors.append(f"Unsafe pack id: {pack_id}")
    if not is_stable_version(manifest.get("version")):
        errors.append("pack.version must be a stable numeric x.y.z version")
    require_nonblank(manifest.get("name"), "pack.name", errors, 80)

    preview = manifest.get("preview")
    if not isinstance(preview, str):
        errors.append("pack.preview must be a relative path")
    else:
        require_file(root, preview, errors)

    entrypoints = manifest.get("entrypoints")
    if not isinstance(entrypoints, dict):
        errors.append("pack.entrypoints must be an object")
        entrypoints = {}
    for key in ("animations", "dialogue", "behavior", "tasks"):
        value = entrypoints.get(key)
        if not isinstance(value, str):
            errors.append(f"Missing entrypoint: {key}")
        else:
            require_file(root, value, errors)

    actions, frame_paths = validate_animations(root, entrypoints.get("animations"), errors)
    validate_behavior(root, entrypoints.get("behavior"), actions, errors)
    dialogue_events = validate_dialogue(root, entrypoints.get("dialogue"), errors)

    avatar_path: str | None = None
    supported_modes: set[str] = set()
    dialogue_rules_entrypoint: str | None = None
    extensions = manifest.get("extensions", [])
    if not isinstance(extensions, list):
        errors.append("pack.extensions must be an array")
        extensions = []
    seen_extensions: set[str] = set()
    for index, extension in enumerate(extensions):
        if not isinstance(extension, dict):
            errors.append(f"Extension at index {index} must be an object")
            continue
        extension_id = extension.get("id")
        if not isinstance(extension_id, str) or not SAFE_EXTENSION_ID.fullmatch(extension_id):
            errors.append(f"Unsafe extension id: {extension_id}")
        elif extension_id in seen_extensions:
            errors.append(f"Duplicate extension: {extension_id}")
        else:
            seen_extensions.add(extension_id)
        api_version = extension.get("apiVersion")
        if not is_int(api_version) or api_version < 1:
            errors.append(f"Invalid extension API version: {extension_id}")
        if not isinstance(extension.get("required"), bool):
            errors.append(f"Extension required flag must be boolean: {extension_id}")
        extension_entrypoint = extension.get("entrypoint")
        if not isinstance(extension_entrypoint, str):
            errors.append(f"Extension entrypoint must be a path: {extension_id}")
            continue
        require_file(root, extension_entrypoint, errors)
        if extension_id == "io.sweetpet.game-kit" and api_version == 1:
            avatar_path, supported_modes = validate_game_kit(root, extension_entrypoint, errors)
        elif extension_id == "io.sweetpet.dialogue-rules" and api_version == 1:
            dialogue_rules_entrypoint = extension_entrypoint

    if dialogue_rules_entrypoint is not None:
        validate_dialogue_rules(root, dialogue_rules_entrypoint, dialogue_events, errors)
    validate_tasks(root, entrypoints.get("tasks"), actions, supported_modes, errors)
    validate_bitmaps(root, preview, frame_paths, avatar_path, errors)

    files = [path for path in root.rglob("*") if path.is_file()]
    if len(files) > MAX_FILES:
        errors.append(f"File count exceeds limit: {len(files)} > {MAX_FILES}")
    expanded = sum(path.stat().st_size for path in files)
    if expanded > MAX_EXPANDED_BYTES:
        errors.append(f"Expanded size exceeds limit: {expanded} > {MAX_EXPANDED_BYTES}")
    if verify_checksums:
        validate_checksums(root, manifest, errors)
    return manifest, errors


def write_checksums(root: Path) -> None:
    files: dict[str, str] = {}
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.name != "checksums.json":
            relative = path.relative_to(root).as_posix()
            files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    document = {"schemaVersion": 1, "algorithm": "SHA-256", "files": files}
    # Keep the byte representation stable across operating systems.  The first
    # published protocol-v2 artifacts were produced on Windows, so CRLF is the
    # canonical newline for this generated file.  Path.write_text() with its
    # default newline handling silently changed these bytes to LF on Linux.
    checksum_text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    (root / "checksums.json").write_bytes(
        checksum_text.replace("\n", "\r\n").encode("utf-8")
    )


def build_pack(root: Path, output: Path) -> None:
    """Build deterministically without mutating the authoring directory."""
    root = root.resolve()
    output = output.resolve()
    manifest, errors = validate_pack(root, verify_checksums=False)
    if errors:
        raise ValueError("\n".join(errors))
    output.parent.mkdir(parents=True, exist_ok=True)

    temporary_output: Path | None = None
    try:
        with tempfile.TemporaryDirectory(prefix="sweetpet-pack-build-") as temporary:
            staged_root = Path(temporary) / "pack"
            shutil.copytree(root, staged_root)
            write_checksums(staged_root)
            _, errors = validate_pack(staged_root, verify_checksums=True)
            if errors:
                raise ValueError("\n".join(errors))

            descriptor, temporary_name = tempfile.mkstemp(
                prefix=f".{output.name}.",
                suffix=".tmp",
                dir=output.parent,
            )
            os.close(descriptor)
            temporary_output = Path(temporary_name)
            with zipfile.ZipFile(
                temporary_output,
                "w",
                compression=zipfile.ZIP_DEFLATED,
                compresslevel=6,
            ) as archive:
                for path in sorted(staged_root.rglob("*")):
                    if not path.is_file():
                        continue
                    relative = path.relative_to(staged_root).as_posix()
                    info = zipfile.ZipInfo(relative, date_time=(2026, 1, 1, 0, 0, 0))
                    # ZipInfo otherwise records the host OS (DOS on Windows,
                    # Unix on Linux) in every central-directory entry.  DOS is
                    # the canonical value used by the published artifacts.
                    info.create_system = 0
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o644 << 16
                    archive.writestr(info, path.read_bytes())
            os.replace(temporary_output, output)
            temporary_output = None
    finally:
        if temporary_output is not None:
            temporary_output.unlink(missing_ok=True)
    print(f"已生成 {output} ({output.stat().st_size} bytes)")
    print(f"资源包 {manifest['id']} v{manifest['version']} 校验通过")


def main() -> int:
    parser = argparse.ArgumentParser(description="SweetPet Pack Protocol v2 工具")
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate_parser = subparsers.add_parser("validate", help="校验资源包目录")
    validate_parser.add_argument("pack", type=Path)
    build_parser = subparsers.add_parser("build", help="构建 .petpack")
    build_parser.add_argument("pack", type=Path)
    build_parser.add_argument("output", type=Path)
    for command in ("new", "init"):
        init_parser = subparsers.add_parser(command, help="从最小模板初始化资源包")
        init_parser.add_argument("pack", type=Path)
        init_parser.add_argument("--id", required=True, dest="pack_id")
        init_parser.add_argument("--name", required=True)
        init_parser.add_argument("--version", default="0.1.0")
    qa_parser = subparsers.add_parser("qa", help="执行非破坏性协议、语义与帧 QA")
    qa_parser.add_argument("pack", type=Path)
    qa_parser.add_argument("--reports", type=Path)
    qa_parser.add_argument("--normalization", choices=("safe", "none"), default="none")
    qa_parser.add_argument("--strict", action="store_true", help="将 QA warning 视为失败")
    for command in ("release", "pipeline"):
        pipeline_parser = subparsers.add_parser(
            command,
            help="一键归一化、QA、语义检查、确定性构建并生成报告",
        )
        pipeline_parser.add_argument("pack", type=Path)
        pipeline_parser.add_argument("--output", type=Path)
        pipeline_parser.add_argument("--reports", type=Path)
        pipeline_parser.add_argument(
            "--normalization",
            choices=("safe", "none"),
            default="safe",
            help="safe 仅在构建快照中修复画布/像素模式，不改源目录",
        )
        pipeline_parser.add_argument("--strict", action="store_true", help="将 QA warning 视为失败")
    publish_parser = subparsers.add_parser(
        "publish",
        help="Build privately and publish only after the real Android install gate passes",
    )
    publish_parser.add_argument("pack", type=Path)
    publish_parser.add_argument("--output", type=Path)
    publish_parser.add_argument("--reports", type=Path)
    publish_parser.add_argument("--normalization", choices=("safe", "none"), default="safe")
    publish_parser.add_argument(
        "--allow-warnings",
        action="store_true",
        help="显式接受 QA warning；默认 publish 会把所有 warning 当作发布阻断",
    )
    publish_parser.add_argument("--android-project", type=Path)
    publish_parser.add_argument("--adb", type=Path)
    publish_parser.add_argument("--serial")
    publish_parser.add_argument(
        "--allow-physical-device",
        action="store_true",
        help="Explicitly authorize the Android install gate on a physical device",
    )
    publish_parser.add_argument("--skip-gate-build", action="store_true")
    gate_parser = subparsers.add_parser(
        "install-gate",
        help="Run an existing .petpack through Android preflight/install/duplicate/cold-load checks",
    )
    gate_parser.add_argument("archive", type=Path)
    gate_parser.add_argument("--android-project", type=Path)
    gate_parser.add_argument("--adb", type=Path)
    gate_parser.add_argument("--serial")
    gate_parser.add_argument(
        "--allow-physical-device",
        action="store_true",
        help="Explicitly authorize the Android install gate on a physical device",
    )
    gate_parser.add_argument("--skip-build", action="store_true")
    gate_parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    try:
        if args.command == "validate":
            manifest, errors = validate_pack(args.pack.resolve())
            if errors:
                raise ValueError("\n".join(errors))
            print(f"资源包 {manifest['id']} v{manifest['version']} 校验通过")
        elif args.command == "build":
            build_pack(args.pack.resolve(), args.output.resolve())
        elif args.command in ("new", "init"):
            from petpack_pipeline import initialize_pack

            initialize_pack(
                args.pack.resolve(),
                pack_id=args.pack_id,
                name=args.name,
                version=args.version,
            )
        elif args.command == "qa":
            from petpack_pipeline import run_qa

            run_qa(
                args.pack.resolve(),
                reports=args.reports.resolve() if args.reports else None,
                normalization=args.normalization,
                strict=args.strict,
            )
        elif args.command in ("release", "pipeline"):
            from petpack_pipeline import run_pipeline

            run_pipeline(
                args.pack.resolve(),
                output=args.output.resolve() if args.output else None,
                reports=args.reports.resolve() if args.reports else None,
                normalization=args.normalization,
                strict=args.strict,
            )
        elif args.command == "install-gate":
            from android_install_gate import (
                GateFailure,
                _device_summary,
                _write_report,
                run_android_install_gate,
            )

            try:
                gate_report = run_android_install_gate(
                    args.archive,
                    android_project=args.android_project,
                    adb=args.adb,
                    serial=args.serial,
                    skip_build=args.skip_build,
                    allow_physical_device=args.allow_physical_device,
                )
            except GateFailure as error:
                raise ValueError(str(error)) from error
            if args.report:
                _write_report(args.report, gate_report)
            print(
                "Android install gate PASS: "
                f"{gate_report['packId']} v{gate_report['version']} "
                f"({gate_report['actions']} actions, {gate_report['tasks']} tasks)"
            )
            print(f"Gate device: {_device_summary(gate_report)}")
        else:
            from android_install_gate import GateFailure, _device_summary, run_android_install_gate
            from petpack_pipeline import run_publish

            def gate(candidate: Path) -> dict[str, object]:
                try:
                    return run_android_install_gate(
                        candidate,
                        android_project=args.android_project,
                        adb=args.adb,
                        serial=args.serial,
                        skip_build=args.skip_gate_build,
                        allow_physical_device=args.allow_physical_device,
                    )
                except GateFailure as error:
                    raise ValueError(str(error)) from error

            publish_report = run_publish(
                args.pack.resolve(),
                install_gate=gate,
                output=args.output.resolve() if args.output else None,
                reports=args.reports.resolve() if args.reports else None,
                normalization=args.normalization,
                strict=not args.allow_warnings,
            )
            print(f"Gate device: {_device_summary(publish_report['androidInstallGate'])}")
        return 0
    except ValueError as exc:
        print(f"校验失败:\n{exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
