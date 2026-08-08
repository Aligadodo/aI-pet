#!/usr/bin/env python3
"""One-command authoring pipeline for SweetPet protocol-v2 resource packs.

The pipeline deliberately works on a private snapshot.  Safe normalization,
checksum generation and QA artifacts therefore never rewrite the author's
source tree.  It supplements (rather than duplicates) petpack.py's protocol and
security validator.
"""

from __future__ import annotations

import hashlib
import filecmp
import json
import math
import os
import re
import shutil
import statistics
import tempfile
import unicodedata
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable

import petpack

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageStat
except ImportError as exc:  # pragma: no cover - CLI dependency error
    raise RuntimeError("PetPack pipeline requires Pillow: python -m pip install Pillow") from exc


PIPELINE_SCHEMA_VERSION = 1
PROJECT_ROOT = Path(__file__).resolve().parents[1]
TEMPLATE_ROOT = PROJECT_ROOT / "templates" / "minimal-v2"
PLACEHOLDER_PATTERN = re.compile(r"(?<!\{)\{([A-Za-z_][A-Za-z0-9_]*)\}(?!\})")
KNOWN_PLACEHOLDERS = {"city", "date", "hour", "temperature", "weather", "weekday"}
COPY_FIELDS = {"label", "lines", "prompt", "response", "title"}
NORMALIZABLE_SUFFIXES = {".png", ".webp"}


@dataclass(frozen=True)
class Diagnostic:
    severity: str
    code: str
    message: str
    location: str = ""

    def as_dict(self) -> dict[str, str]:
        value = {"severity": self.severity, "code": self.code, "message": self.message}
        if self.location:
            value["location"] = self.location
        return value


def _json(path: Path) -> dict[str, Any]:
    return petpack.load_json(path)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write_text_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _copy_atomic(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent
    )
    os.close(descriptor)
    temporary = Path(name)
    try:
        shutil.copyfile(source, temporary)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def _path_present(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def _remove_path(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink(missing_ok=True)
    elif path.exists():
        shutil.rmtree(path)


def _move_publish_path(source: Path, destination: Path) -> None:
    """Move one staged/backup publish path; kept separate for fault-injection tests."""
    os.replace(source, destination)


def _commit_publish_set(
    items: list[tuple[Path, Path]],
    *,
    backup_root: Path,
    verify: Callable[[], None],
) -> None:
    """Commit a staged publish set and restore every old target if any step fails."""
    for staged, target in items:
        if not _path_present(staged):
            raise ValueError(f"Staged publish item is missing: {staged}")
        target.parent.mkdir(parents=True, exist_ok=True)

    backup_root.mkdir(parents=True, exist_ok=False)
    originally_present = {target: _path_present(target) for _, target in items}
    backups: dict[Path, Path] = {}
    try:
        for index, (_, target) in enumerate(items):
            if originally_present[target]:
                backup = backup_root / f"{index}-{target.name}"
                _move_publish_path(target, backup)
                backups[target] = backup

        for staged, target in items:
            _move_publish_path(staged, target)
        verify()
    except Exception as commit_error:
        rollback_errors: list[str] = []
        for _, target in reversed(items):
            if target in backups or not originally_present[target]:
                try:
                    if _path_present(target):
                        _remove_path(target)
                except Exception as rollback_error:  # pragma: no cover - catastrophic filesystem failure
                    rollback_errors.append(f"remove {target}: {rollback_error}")
        for target, backup in reversed(list(backups.items())):
            try:
                if _path_present(backup):
                    _move_publish_path(backup, target)
            except Exception as rollback_error:  # pragma: no cover - catastrophic filesystem failure
                rollback_errors.append(f"restore {target}: {rollback_error}")
        if rollback_errors:
            detail = " | ".join(rollback_errors)
            raise RuntimeError(
                f"Publish commit failed and rollback was incomplete: {commit_error}; {detail}"
            ) from commit_error
        raise


def _volume_key(path: Path) -> str:
    return os.path.normcase(path.drive or path.anchor)


def _tree_fingerprint(root: Path) -> str:
    """Hash logical input content, independent of mtimes and checksums.json."""
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name == "checksums.json":
            continue
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def _placeholder_image(path: Path) -> None:
    """Create a deterministic, transparent mascot placeholder for `init`."""
    image = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((154, 62, 358, 266), fill=(255, 219, 204, 255), outline=(100, 64, 80, 255), width=5)
    draw.rounded_rectangle((130, 226, 382, 476), radius=86, fill=(255, 145, 159, 255))
    draw.ellipse((211, 145, 229, 163), fill=(67, 55, 66, 255))
    draw.ellipse((283, 145, 301, 163), fill=(67, 55, 66, 255))
    draw.arc((230, 157, 282, 207), 12, 168, fill=(130, 60, 79, 255), width=5)
    draw.line((198, 476, 198, 481), fill=(90, 55, 70, 255), width=8)
    draw.line((314, 476, 314, 481), fill=(90, 55, 70, 255), width=8)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", compress_level=9)


def initialize_pack(destination: Path, *, pack_id: str, name: str, version: str = "0.1.0") -> None:
    """Create a valid minimal v2 pack from the checked-in editable template."""
    if not petpack.SAFE_ID.fullmatch(pack_id):
        raise ValueError(f"Unsafe pack id: {pack_id}")
    if not petpack.is_stable_version(version):
        raise ValueError("pack.version must be a stable numeric x.y.z version")
    if not name.strip() or len(name) > 80:
        raise ValueError("pack.name must be 1..80 characters")
    if destination.exists() and any(destination.iterdir()):
        raise ValueError(f"Destination is not empty: {destination}")
    if not TEMPLATE_ROOT.is_dir():
        raise ValueError(f"Missing pipeline template: {TEMPLATE_ROOT}")

    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(TEMPLATE_ROOT, destination, dirs_exist_ok=True)
    manifest_path = destination / "pack.json"
    manifest = _json(manifest_path)
    manifest["id"] = pack_id
    manifest["name"] = name
    manifest["version"] = version
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )

    frame = destination / "character" / "animations" / "idle" / "frame_00.png"
    _placeholder_image(frame)
    shutil.copyfile(frame, destination / "preview.png")
    petpack.write_checksums(destination)
    _, errors = petpack.validate_pack(destination, verify_checksums=True)
    if errors:
        raise ValueError("Initialized template is invalid:\n" + "\n".join(errors))
    print(f"已初始化 {pack_id} v{version}: {destination}")


def _animation_document(root: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    entrypoints = manifest.get("entrypoints", {})
    relative = entrypoints.get("animations") if isinstance(entrypoints, dict) else None
    if not isinstance(relative, str) or not petpack.safe_relative(relative):
        raise ValueError("Cannot load animations entrypoint")
    return _json(root / PurePosixPath(relative))


def _fit_frame(
    source: Image.Image,
    canvas: tuple[int, int],
    anchor: tuple[float, float],
    ground_bound: bool,
) -> Image.Image:
    rgba = source.convert("RGBA")
    bbox = rgba.getchannel("A").getbbox()
    if bbox is None:
        raise ValueError("Cannot normalize an empty animation frame")
    cropped = rgba.crop(bbox)
    width, height = canvas
    target_bottom = round(anchor[1] * height)
    available_height = max(1, target_bottom - 8) if ground_bound else max(1, height - 16)
    scale = min((width - 16) / cropped.width, available_height / cropped.height)
    new_size = (max(1, round(cropped.width * scale)), max(1, round(cropped.height * scale)))
    resized = cropped.resize(new_size, Image.Resampling.LANCZOS)
    x = round(anchor[0] * width - new_size[0] / 2)
    x = min(max(0, x), width - new_size[0])
    if ground_bound:
        y = target_bottom - new_size[1] + 1
    else:
        y = round((height - new_size[1]) / 2)
    y = min(max(0, y), height - new_size[1])
    output = Image.new("RGBA", canvas, (0, 0, 0, 0))
    output.alpha_composite(resized, (x, y))
    return output


def _save_normalized(image: Image.Image, path: Path) -> None:
    suffix = path.suffix.lower()
    if suffix == ".png":
        image.save(path, format="PNG", compress_level=9)
    elif suffix == ".webp":
        image.save(path, format="WEBP", lossless=True, method=6)
    else:
        raise ValueError(
            f"Safe frame normalization only supports transparent PNG/WebP: {path.as_posix()}"
        )


def normalize_frames(root: Path, manifest: dict[str, Any], mode: str) -> list[str]:
    """Normalize only the private build snapshot; already-normal frames stay byte-identical."""
    if mode == "none":
        return []
    if mode != "safe":
        raise ValueError(f"Unsupported normalization mode: {mode}")
    document = _animation_document(root, manifest)
    raw_canvas = document.get("canvas")
    if not isinstance(raw_canvas, list) or len(raw_canvas) != 2:
        raise ValueError("animations.canvas is invalid")
    canvas = (int(raw_canvas[0]), int(raw_canvas[1]))
    actions = document.get("actions")
    if not isinstance(actions, dict):
        raise ValueError("animations.actions is invalid")

    normalized: list[str] = []
    seen: set[str] = set()
    for action, node in actions.items():
        if not isinstance(node, dict):
            continue
        motion = node.get("motion") if isinstance(node.get("motion"), dict) else {}
        raw_anchor = motion.get("groundAnchor", [0.5, 0.94])
        anchor = (float(raw_anchor[0]), float(raw_anchor[1]))
        tags = {str(tag) for tag in motion.get("sceneTags", []) if isinstance(tag, str)}
        ground_bound = "ground" in tags and not ({"airborne", "jump"} & tags)
        for frame in node.get("frames", []):
            if not isinstance(frame, str) or frame in seen:
                continue
            seen.add(frame)
            relative = f"character/{frame}"
            path = root / PurePosixPath(relative)
            with Image.open(path) as source:
                source.load()
                needs_rewrite = source.size != canvas or source.mode != "RGBA"
                if not needs_rewrite:
                    continue
                if path.suffix.lower() not in NORMALIZABLE_SUFFIXES:
                    raise ValueError(f"Frame requires normalization but is not PNG/WebP: {relative}")
                if "A" not in source.getbands() and "transparency" not in source.info:
                    raise ValueError(f"Frame requires a real alpha channel before normalization: {relative}")
                output = _fit_frame(source, canvas, anchor, ground_bound)
            _save_normalized(output, path)
            normalized.append(relative)
    return sorted(normalized)


def _collect_copy_strings(document: Any, location: str) -> Iterable[tuple[str, str]]:
    if isinstance(document, dict):
        for key, value in document.items():
            child = f"{location}.{key}" if location else str(key)
            if key in COPY_FIELDS:
                if isinstance(value, str):
                    yield child, value
                elif isinstance(value, list):
                    for index, item in enumerate(value):
                        if isinstance(item, str):
                            yield f"{child}[{index}]", item
            yield from _collect_copy_strings(value, child)
    elif isinstance(document, list):
        for index, item in enumerate(document):
            yield from _collect_copy_strings(item, f"{location}[{index}]")


def _normalized_copy(value: str) -> str:
    value = unicodedata.normalize("NFKC", value)
    return re.sub(r"\s+", " ", value).strip().casefold()


def _settings(root: Path, manifest: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], list[Diagnostic]]:
    diagnostics: list[Diagnostic] = []
    settings: dict[str, dict[str, Any]] = {}
    extensions = manifest.get("extensions", [])
    entrypoint: str | None = None
    if isinstance(extensions, list):
        for extension in extensions:
            if isinstance(extension, dict) and extension.get("id") == "io.sweetpet.pack-settings":
                if extension.get("apiVersion") == 1 and isinstance(extension.get("entrypoint"), str):
                    entrypoint = extension["entrypoint"]
    if entrypoint is None:
        return settings, diagnostics
    document = _json(root / PurePosixPath(entrypoint))
    nodes = document.get("settings")
    if not isinstance(nodes, list):
        return settings, [Diagnostic("error", "settings.type", "settings must be an array", entrypoint)]
    for index, node in enumerate(nodes):
        location = f"{entrypoint}:settings[{index}]"
        if not isinstance(node, dict):
            diagnostics.append(Diagnostic("error", "settings.item", "setting must be an object", location))
            continue
        key = node.get("key")
        if not isinstance(key, str) or not petpack.SAFE_ID.fullmatch(key):
            diagnostics.append(Diagnostic("error", "settings.key", f"invalid setting key: {key}", location))
            continue
        if key in settings:
            diagnostics.append(Diagnostic("error", "settings.duplicate", f"duplicate setting key: {key}", location))
            continue
        settings[key] = node
        kind = node.get("type")
        default = node.get("default")
        if kind not in {"boolean", "integer", "choice"}:
            diagnostics.append(Diagnostic("error", "settings.type", f"unsupported setting type: {kind}", location))
        if not isinstance(node.get("label"), str) or not node["label"].strip():
            diagnostics.append(Diagnostic("error", "settings.label", "setting label must be non-blank", location))
        if kind == "boolean" and not isinstance(default, bool):
            diagnostics.append(Diagnostic("error", "settings.default", "boolean default must be boolean", location))
        elif kind == "integer":
            if not petpack.is_int(default):
                diagnostics.append(Diagnostic("error", "settings.default", "integer default must be integer", location))
            minimum = node.get("min")
            maximum = node.get("max")
            if minimum is not None and not petpack.is_int(minimum):
                diagnostics.append(Diagnostic("error", "settings.range", "integer min must be an integer", location))
            if maximum is not None and not petpack.is_int(maximum):
                diagnostics.append(Diagnostic("error", "settings.range", "integer max must be an integer", location))
            if petpack.is_int(minimum) and petpack.is_int(maximum) and minimum > maximum:
                diagnostics.append(Diagnostic("error", "settings.range", "integer min cannot exceed max", location))
            if petpack.is_int(default) and petpack.is_int(minimum) and default < minimum:
                diagnostics.append(Diagnostic("error", "settings.default", "integer default is below min", location))
            if petpack.is_int(default) and petpack.is_int(maximum) and default > maximum:
                diagnostics.append(Diagnostic("error", "settings.default", "integer default is above max", location))
        elif kind == "choice":
            options = node.get("options")
            values: set[str] = set()
            if isinstance(options, list):
                for option_index, option in enumerate(options):
                    option_location = f"{location}.options[{option_index}]"
                    value = option.get("value") if isinstance(option, dict) else None
                    label = option.get("label") if isinstance(option, dict) else None
                    if not isinstance(value, str) or not value.strip() or value in values:
                        diagnostics.append(Diagnostic("error", "settings.option", "choice value must be non-blank and unique", option_location))
                    else:
                        values.add(value)
                    if not isinstance(label, str) or not label.strip():
                        diagnostics.append(Diagnostic("error", "settings.option-label", "choice label must be non-blank", option_location))
            if not values or default not in values:
                diagnostics.append(Diagnostic("error", "settings.default", "choice default must match an option", location))
    return settings, diagnostics


def semantic_lint(root: Path, manifest: dict[str, Any]) -> list[Diagnostic]:
    """Check cross-file meaning that JSON shape validation cannot establish."""
    diagnostics: list[Diagnostic] = []
    entrypoints = manifest.get("entrypoints", {})
    documents: list[tuple[str, dict[str, Any]]] = []
    for key in ("dialogue", "tasks"):
        relative = entrypoints.get(key) if isinstance(entrypoints, dict) else None
        if isinstance(relative, str) and petpack.safe_relative(relative):
            documents.append((relative, _json(root / PurePosixPath(relative))))
    rules: tuple[str, dict[str, Any]] | None = None
    for extension in manifest.get("extensions", []):
        if not isinstance(extension, dict):
            continue
        if extension.get("id") == "io.sweetpet.dialogue-rules" and isinstance(extension.get("entrypoint"), str):
            relative = extension["entrypoint"]
            rules = (relative, _json(root / PurePosixPath(relative)))
            documents.append(rules)

    copies: dict[str, list[str]] = {}
    dialogue_relative = entrypoints.get("dialogue") if isinstance(entrypoints, dict) else None
    for relative, document in documents:
        strings = list(_collect_copy_strings(document, relative))
        if relative == dialogue_relative:
            strings.extend(
                (f"{relative}:{event}[{index}]", line)
                for event, lines in document.items()
                if event != "schemaVersion" and isinstance(lines, list)
                for index, line in enumerate(lines)
                if isinstance(line, str)
            )
        for location, value in strings:
            normalized = _normalized_copy(value)
            if normalized:
                copies.setdefault(normalized, []).append(location)
            for placeholder in PLACEHOLDER_PATTERN.findall(value):
                if placeholder not in KNOWN_PLACEHOLDERS:
                    diagnostics.append(
                        Diagnostic(
                            "error",
                            "copy.unknown-placeholder",
                            f"unknown runtime placeholder: {{{placeholder}}}",
                            location,
                        )
                    )
            if value.count("{") != value.count("}"):
                diagnostics.append(Diagnostic("error", "copy.unbalanced-brace", "unbalanced placeholder brace", location))
    for locations in copies.values():
        if len(locations) > 1:
            diagnostics.append(
                Diagnostic(
                    "warning",
                    "copy.duplicate",
                    f"same copy appears {len(locations)} times",
                    ", ".join(locations[:4]),
                )
            )

    settings, setting_diagnostics = _settings(root, manifest)
    diagnostics.extend(setting_diagnostics)
    referenced_settings: set[str] = set()
    if rules is not None:
        relative, document = rules
        for index, rule in enumerate(document.get("rules", [])):
            if not isinstance(rule, dict) or not isinstance(rule.get("when"), dict):
                continue
            condition = rule["when"]
            key = condition.get("settingKey")
            if not isinstance(key, str):
                continue
            location = f"{relative}:rules[{index}].when"
            referenced_settings.add(key)
            setting = settings.get(key)
            if setting is None:
                diagnostics.append(Diagnostic("error", "reference.setting", f"unknown setting: {key}", location))
                continue
            expected = condition.get("settingEquals")
            kind = setting.get("type")
            if kind == "boolean" and expected not in ("true", "false"):
                diagnostics.append(Diagnostic("error", "reference.setting-value", f"invalid boolean value: {expected}", location))
            elif kind == "integer":
                try:
                    int(str(expected))
                except (TypeError, ValueError):
                    diagnostics.append(Diagnostic("error", "reference.setting-value", f"invalid integer value: {expected}", location))
            elif kind == "choice":
                options = setting.get("options", [])
                values = {option.get("value") for option in options if isinstance(option, dict)}
                if expected not in values:
                    diagnostics.append(Diagnostic("error", "reference.setting-value", f"unknown choice value: {expected}", location))
    for key in sorted(set(settings) - referenced_settings):
        diagnostics.append(Diagnostic("warning", "reference.unused-setting", f"setting is never referenced: {key}"))

    animations = _animation_document(root, manifest)
    actions = animations.get("actions", {})
    referenced_frames = {
        f"character/{frame}"
        for node in actions.values()
        if isinstance(node, dict)
        for frame in node.get("frames", [])
        if isinstance(frame, str)
    } if isinstance(actions, dict) else set()
    animation_root = root / "character" / "animations"
    for path in sorted(animation_root.rglob("*")) if animation_root.is_dir() else []:
        if not path.is_file() or path.suffix.lower() not in petpack.IMAGE_SUFFIXES:
            continue
        relative = path.relative_to(root).as_posix()
        if relative not in referenced_frames:
            diagnostics.append(Diagnostic("warning", "reference.unused-frame", "animation frame is not referenced", relative))
    return diagnostics


def _frame_data(path: Path) -> dict[str, Any]:
    with Image.open(path) as image:
        rgba = image.convert("RGBA")
        rgba.load()
    alpha = rgba.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:
        raise ValueError(f"Empty animation frame: {path}")
    histogram = alpha.histogram()
    visible_pixels = sum(histogram[1:])
    thumb = alpha.resize((64, 64), Image.Resampling.BILINEAR)
    width = bbox[2] - bbox[0]
    height = bbox[3] - bbox[1]
    return {
        "bbox": list(bbox),
        "width": width,
        "height": height,
        "bottom": bbox[3] - 1,
        "center": [(bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2],
        "visiblePixels": visible_pixels,
        "sha256": _sha256(path),
        "alphaThumb": thumb,
    }


def animation_qa(root: Path, manifest: dict[str, Any]) -> tuple[dict[str, Any], list[Diagnostic]]:
    document = _animation_document(root, manifest)
    canvas = tuple(int(value) for value in document["canvas"])
    actions = document.get("actions", {})
    diagnostics: list[Diagnostic] = []
    report: dict[str, Any] = {"canvas": list(canvas), "actions": {}}
    cache: dict[str, dict[str, Any]] = {}

    for action, node in actions.items():
        if not isinstance(node, dict):
            continue
        frames = [frame for frame in node.get("frames", []) if isinstance(frame, str)]
        sequence: list[dict[str, Any]] = []
        for frame in frames:
            relative = f"character/{frame}"
            if relative not in cache:
                cache[relative] = _frame_data(root / PurePosixPath(relative))
            sequence.append(cache[relative])
            bbox = cache[relative]["bbox"]
            if bbox[0] == 0 or bbox[1] == 0 or bbox[2] == canvas[0] or bbox[3] == canvas[1]:
                diagnostics.append(Diagnostic("warning", "frame.edge-touch", "visible pixels touch canvas edge", relative))

        if not sequence:
            continue
        widths = [item["width"] for item in sequence]
        heights = [item["height"] for item in sequence]
        median_width = statistics.median(widths)
        median_height = statistics.median(heights)
        width_deviation = max(abs(value - median_width) / median_width for value in widths)
        height_deviation = max(abs(value - median_height) / median_height for value in heights)
        if max(width_deviation, height_deviation) > 0.40:
            diagnostics.append(
                Diagnostic("warning", "frame.size-pop", "subject bounds vary by more than 40% within action", action)
            )

        motion = node.get("motion") if isinstance(node.get("motion"), dict) else {}
        anchor = motion.get("groundAnchor", [0.5, 0.94])
        ground_y = round(float(anchor[1]) * canvas[1])
        tags = {tag for tag in motion.get("sceneTags", []) if isinstance(tag, str)}
        ground_bound = "ground" in tags and not ({"airborne", "jump"} & tags)
        gaps = [ground_y - item["bottom"] for item in sequence]
        if ground_bound and min(gaps) < -2:
            diagnostics.append(Diagnostic("error", "frame.below-anchor", "frame extends below declared ground anchor", action))
        if ground_bound and max(gaps) > max(24, round(canvas[1] * 0.05)):
            diagnostics.append(Diagnostic("warning", "frame.air-gap", "ground action visibly floats above its anchor", action))

        transitions = list(zip(sequence, sequence[1:]))
        if node.get("loop") is True and len(sequence) > 1:
            transitions.append((sequence[-1], sequence[0]))
        center_steps: list[float] = []
        area_ratios: list[float] = []
        alpha_differences: list[float] = []
        for first, second in transitions:
            center_steps.append(math.dist(first["center"], second["center"]))
            small_area = max(1, min(first["visiblePixels"], second["visiblePixels"]))
            area_ratios.append(max(first["visiblePixels"], second["visiblePixels"]) / small_area)
            difference = ImageChops.difference(first["alphaThumb"], second["alphaThumb"])
            alpha_differences.append(ImageStat.Stat(difference).mean[0] / 255.0)
        max_center_step = max(center_steps, default=0.0)
        max_area_ratio = max(area_ratios, default=1.0)
        if max_center_step > max(canvas) * 0.20:
            diagnostics.append(Diagnostic("warning", "frame.position-jump", "subject center jumps over 20% of canvas", action))
        if max_area_ratio > 2.0:
            diagnostics.append(Diagnostic("warning", "frame.area-pop", "visible subject area changes by more than 2x", action))

        unique_paths = list(dict.fromkeys(frames))
        report["actions"][action] = {
            "fps": node.get("fps", 4),
            "loop": node.get("loop", False),
            "frameCount": len(frames),
            "uniqueFrameCount": len(unique_paths),
            "bboxWidthRange": [min(widths), max(widths)],
            "bboxHeightRange": [min(heights), max(heights)],
            "bboxBottomRange": [min(item["bottom"] for item in sequence), max(item["bottom"] for item in sequence)],
            "groundGapRange": [min(gaps), max(gaps)],
            "maxCenterStepPx": round(max_center_step, 3),
            "maxVisibleAreaRatio": round(max_area_ratio, 4),
            "maxAlphaDifference": round(max(alpha_differences, default=0.0), 4),
            "frames": [
                {
                    "path": relative,
                    "bbox": cache[f"character/{relative}"]["bbox"],
                    "sha256": cache[f"character/{relative}"]["sha256"],
                }
                for relative in unique_paths
            ],
        }
    return report, diagnostics


def write_contact_sheet(root: Path, manifest: dict[str, Any], output: Path) -> None:
    document = _animation_document(root, manifest)
    actions = document.get("actions", {})
    rows: list[tuple[str, list[str]]] = []
    for action, node in actions.items():
        if isinstance(node, dict):
            rows.append((action, list(dict.fromkeys(frame for frame in node.get("frames", []) if isinstance(frame, str)))[:12]))
    cell = 104
    label_width = 150
    header = 32
    columns = max((len(frames) for _, frames in rows), default=1)
    sheet = Image.new("RGB", (label_width + columns * cell, header + len(rows) * cell), (246, 248, 251))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    draw.text((8, 10), "SweetPet animation contact sheet", fill=(31, 42, 55), font=font)
    for row_index, (action, frames) in enumerate(rows):
        top = header + row_index * cell
        draw.text((8, top + 8), action, fill=(31, 42, 55), font=font)
        draw.text((8, top + 24), f"{len(frames)} unique", fill=(91, 104, 120), font=font)
        for column, frame in enumerate(frames):
            left = label_width + column * cell
            for y in range(top, top + cell, 12):
                for x in range(left, left + cell, 12):
                    shade = 232 if ((x - left) // 12 + (y - top) // 12) % 2 == 0 else 214
                    draw.rectangle((x, y, min(x + 11, left + cell - 1), min(y + 11, top + cell - 1)), fill=(shade, shade, shade))
            with Image.open(root / "character" / PurePosixPath(frame)) as source:
                rgba = source.convert("RGBA")
                rgba.thumbnail((cell - 8, cell - 18), Image.Resampling.LANCZOS)
            x = left + (cell - rgba.width) // 2
            y = top + (cell - rgba.height) // 2 - 4
            sheet.paste(rgba, (x, y), rgba)
            draw.text((left + 3, top + cell - 13), Path(frame).stem, fill=(30, 30, 30), font=font)
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output, format="PNG", compress_level=9)


def _markdown(report: dict[str, Any]) -> str:
    pack = report["pack"]
    summary = report["summary"]
    artifact = report.get("artifact")
    deterministic_build = report.get("deterministicBuild")
    deterministic_label = (
        "未运行"
        if deterministic_build is None
        else ("通过" if deterministic_build else "未通过")
    )
    lines = [
        f"# PetPack QA：{pack['name']}",
        "",
        f"- ID / 版本：`{pack['id']}` / `{pack['version']}`",
        f"- 输入指纹：`{report['inputSha256']}`",
        f"- 动作 / 声明帧 / 唯一帧：{summary['actions']} / {summary['frames']} / {summary['uniqueFrames']}",
        f"- 归一化帧：{summary['normalizedFrames']}",
        f"- 诊断：{summary['errors']} errors，{summary['warnings']} warnings",
        f"- 确定性复建：{deterministic_label}",
    ]
    if artifact:
        lines.extend(
            [
                f"- 产物：`{artifact['name']}`（{artifact['sizeBytes']} bytes）",
                f"- 产物 SHA-256：`{artifact['sha256']}`",
            ]
        )
    lines.extend(["", "## 动作 QA", "", "| 动作 | 帧/唯一帧 | 尺寸范围 | 落脚差值 | 最大中心步进 |", "|---|---:|---|---|---:|"])
    for action, value in report["animations"]["actions"].items():
        lines.append(
            f"| `{action}` | {value['frameCount']}/{value['uniqueFrameCount']} | "
            f"{value['bboxWidthRange']} × {value['bboxHeightRange']} | {value['groundGapRange']} | "
            f"{value['maxCenterStepPx']} px |"
        )
    lines.extend(["", "## 诊断", ""])
    if report["diagnostics"]:
        for item in report["diagnostics"]:
            location = f"（`{item.get('location')}`）" if item.get("location") else ""
            lines.append(f"- **{item['severity'].upper()}** `{item['code']}`：{item['message']}{location}")
    else:
        lines.append("- 无。")
    return "\n".join(lines) + "\n"


def _write_report(report: dict[str, Any], reports: Path) -> None:
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    _write_text_atomic(reports / "qa-report.json", payload)
    _write_text_atomic(reports / "qa-report.md", _markdown(report))


def run_qa(
    source: Path,
    *,
    reports: Path | None = None,
    normalization: str = "none",
    strict: bool = False,
) -> dict[str, Any]:
    """Run non-mutating protocol, semantic and visual QA without publishing."""
    if not source.is_dir():
        raise ValueError(f"Pack directory does not exist: {source}")
    source = source.resolve()
    manifest, structural_errors = petpack.validate_pack(source, verify_checksums=False)
    if structural_errors:
        raise ValueError("\n".join(structural_errors))
    pack_id = str(manifest["id"])
    version = str(manifest["version"])
    reports = (reports or PROJECT_ROOT / "reports" / f"{pack_id}-{version}").resolve()
    if reports == source or source in reports.parents:
        raise ValueError("QA reports cannot be inside the pack source directory")

    with tempfile.TemporaryDirectory(prefix="sweetpet-qa-") as temporary:
        staging = Path(temporary) / "pack"
        shutil.copytree(source, staging)
        normalized = normalize_frames(staging, manifest, normalization)
        staged_manifest, staged_errors = petpack.validate_pack(staging, verify_checksums=False)
        diagnostics = [Diagnostic("error", "protocol.validation", error) for error in staged_errors]
        if not staged_errors:
            diagnostics.extend(semantic_lint(staging, staged_manifest))
            animations, frame_diagnostics = animation_qa(staging, staged_manifest)
            diagnostics.extend(frame_diagnostics)
        else:
            animations = {"canvas": [], "actions": {}}
        error_count = sum(item.severity == "error" for item in diagnostics)
        warning_count = sum(item.severity == "warning" for item in diagnostics)
        report: dict[str, Any] = {
            "schemaVersion": PIPELINE_SCHEMA_VERSION,
            "pack": {"id": pack_id, "name": manifest["name"], "version": version},
            "normalization": normalization,
            "inputSha256": _tree_fingerprint(staging),
            "deterministicBuild": None,
            "summary": {
                "actions": len(animations["actions"]),
                "frames": sum(value["frameCount"] for value in animations["actions"].values()),
                "uniqueFrames": len(
                    {
                        frame["path"]
                        for value in animations["actions"].values()
                        for frame in value["frames"]
                    }
                ),
                "normalizedFrames": len(normalized),
                "errors": error_count,
                "warnings": warning_count,
            },
            "normalizedFramePaths": normalized,
            "animations": animations,
            "diagnostics": [item.as_dict() for item in diagnostics],
            "artifact": None,
        }
        reports.mkdir(parents=True, exist_ok=True)
        if not staged_errors:
            write_contact_sheet(staging, staged_manifest, reports / "contact-sheet.png")
        _write_report(report, reports)
        if error_count or (strict and warning_count):
            qualifier = "errors" if error_count else "warnings in strict mode"
            raise ValueError(f"QA stopped: {error_count} errors, {warning_count} warnings ({qualifier})")
        print(f"QA 通过: {pack_id} v{version} ({warning_count} warnings)")
        print(f"报告: {reports}")
        return report


def run_pipeline(
    source: Path,
    *,
    output: Path | None = None,
    reports: Path | None = None,
    normalization: str = "safe",
    strict: bool = False,
) -> dict[str, Any]:
    """Run the complete, deterministic release pipeline and return its report."""
    if not source.is_dir():
        raise ValueError(f"Pack directory does not exist: {source}")
    source = source.resolve()
    manifest, structural_errors = petpack.validate_pack(source, verify_checksums=False)
    if structural_errors:
        raise ValueError("\n".join(structural_errors))
    pack_id = str(manifest["id"])
    version = str(manifest["version"])
    candidate_root = PROJECT_ROOT / "work" / pack_id
    output = (output or candidate_root / f"{pack_id}-{version}.candidate.petpack").resolve()
    reports = (reports or candidate_root / "release-report").resolve()
    if output == source or source in output.parents:
        raise ValueError("Pipeline output cannot be inside the pack source directory")
    if reports == source or source in reports.parents:
        raise ValueError("Pipeline reports cannot be inside the pack source directory")

    with tempfile.TemporaryDirectory(prefix="sweetpet-pipeline-") as temporary:
        staging = Path(temporary) / "pack"
        shutil.copytree(source, staging)
        normalized = normalize_frames(staging, manifest, normalization)
        staged_manifest, staged_errors = petpack.validate_pack(staging, verify_checksums=False)
        diagnostics = [Diagnostic("error", "protocol.validation", error) for error in staged_errors]
        if not staged_errors:
            diagnostics.extend(semantic_lint(staging, staged_manifest))
            animations, frame_diagnostics = animation_qa(staging, staged_manifest)
            diagnostics.extend(frame_diagnostics)
        else:
            animations = {"canvas": [], "actions": {}}

        error_count = sum(item.severity == "error" for item in diagnostics)
        warning_count = sum(item.severity == "warning" for item in diagnostics)
        action_values = animations["actions"].values()
        report: dict[str, Any] = {
            "schemaVersion": PIPELINE_SCHEMA_VERSION,
            "pack": {"id": pack_id, "name": manifest["name"], "version": version},
            "normalization": normalization,
            "inputSha256": _tree_fingerprint(staging),
            "deterministicBuild": None,
            "summary": {
                "actions": len(animations["actions"]),
                "frames": sum(value["frameCount"] for value in action_values),
                "uniqueFrames": len(
                    {
                        frame["path"]
                        for value in animations["actions"].values()
                        for frame in value["frames"]
                    }
                ),
                "normalizedFrames": len(normalized),
                "errors": error_count,
                "warnings": warning_count,
            },
            "normalizedFramePaths": normalized,
            "animations": animations,
            "diagnostics": [item.as_dict() for item in diagnostics],
            "artifact": None,
        }
        reports.mkdir(parents=True, exist_ok=True)
        if not staged_errors:
            write_contact_sheet(staging, staged_manifest, reports / "contact-sheet.png")
        _write_report(report, reports)
        if error_count or (strict and warning_count):
            qualifier = "errors" if error_count else "warnings in strict mode"
            raise ValueError(f"Pipeline stopped: {error_count} errors, {warning_count} warnings ({qualifier})")

        first = Path(temporary) / "first.petpack"
        second = Path(temporary) / "second.petpack"
        petpack.build_pack(staging, first)
        petpack.build_pack(staging, second)
        first_hash = _sha256(first)
        second_hash = _sha256(second)
        if first_hash != second_hash or not filecmp.cmp(first, second, shallow=False):
            report["diagnostics"].append(
                Diagnostic("error", "build.nondeterministic", "two builds produced different bytes").as_dict()
            )
            report["summary"]["errors"] += 1
            _write_report(report, reports)
            raise ValueError("Deterministic build check failed")

        _copy_atomic(first, output)
        artifact_hash = _sha256(output)
        report["deterministicBuild"] = True
        report["artifact"] = {
            "name": output.name,
            "sizeBytes": output.stat().st_size,
            "sha256": artifact_hash,
        }
        _write_text_atomic(output.with_name(output.name + ".sha256"), f"{artifact_hash}  {output.name}\n")
        _write_report(report, reports)
        print(f"流水线通过: {pack_id} v{version}")
        print(f"产物: {output} ({output.stat().st_size} bytes)")
        print(f"SHA-256: {artifact_hash}")
        print(f"QA: {reports}")
        return report


def run_publish(
    source: Path,
    *,
    install_gate: Callable[[Path], dict[str, object]],
    output: Path | None = None,
    reports: Path | None = None,
    normalization: str = "safe",
    strict: bool = True,
) -> dict[str, Any]:
    """Build a private candidate and publish it only after the Android install gate passes."""
    source = source.resolve()
    manifest, structural_errors = petpack.validate_pack(source, verify_checksums=False)
    if structural_errors:
        raise ValueError("\n".join(structural_errors))
    pack_id = str(manifest["id"])
    version = str(manifest["version"])
    output = (output or PROJECT_ROOT / "dist" / f"{pack_id}-{version}.petpack").resolve()
    reports = (reports or PROJECT_ROOT / "reports" / f"{pack_id}-{version}").resolve()
    sidecar = output.with_name(output.name + ".sha256")
    if output == source or source in output.parents:
        raise ValueError("Publish output cannot be inside the pack source directory")
    if reports == source or source in reports.parents:
        raise ValueError("Publish reports cannot be inside the pack source directory")
    if (
        reports in (output, sidecar)
        or reports in output.parents
        or output in reports.parents
        or reports in sidecar.parents
        or sidecar in reports.parents
    ):
        raise ValueError("Publish output, sidecar, and reports must be separate paths")
    if _volume_key(output) != _volume_key(reports):
        raise ValueError("Publish output and reports must be on the same filesystem volume")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f".{output.name}.publish-staging-",
        dir=output.parent,
    ) as temporary:
        staging_root = Path(temporary)
        candidate = staging_root / output.name
        staged_sidecar = staging_root / sidecar.name
        staged_reports = staging_root / "reports"
        report = run_pipeline(
            source,
            output=candidate,
            reports=staged_reports,
            normalization=normalization,
            strict=strict,
        )
        candidate_hash = _sha256(candidate)
        candidate_size = candidate.stat().st_size
        try:
            gate_report = install_gate(candidate)
            if not isinstance(gate_report, dict) or gate_report.get("result") != "pass":
                raise ValueError("gate did not return result=pass")
            if gate_report.get("archiveSha256") != candidate_hash:
                raise ValueError("gate archive SHA-256 does not match the private candidate")
            if gate_report.get("archiveSizeBytes") != candidate_size:
                raise ValueError("gate archive size does not match the private candidate")
            if gate_report.get("packId") != pack_id or gate_report.get("version") != version:
                raise ValueError("gate pack identity/version does not match the source manifest")
            if _sha256(candidate) != candidate_hash or candidate.stat().st_size != candidate_size:
                raise ValueError("private candidate changed while the Android gate was running")
        except Exception as error:
            report["androidInstallGate"] = {
                "result": "fail",
                "message": str(error),
            }
            report["summary"]["errors"] += 1
            report["diagnostics"].append(
                Diagnostic("error", "android.install-gate", str(error)).as_dict()
            )
            _write_report(report, staged_reports)
            raise ValueError(f"Android install gate failed: {error}") from error

        artifact_hash = candidate_hash
        report["artifact"] = {
            "name": output.name,
            "sizeBytes": candidate_size,
            "sha256": artifact_hash,
        }
        report["androidInstallGate"] = gate_report
        expected_sidecar = f"{artifact_hash}  {output.name}\n"
        _write_text_atomic(staged_sidecar, expected_sidecar)
        _write_report(report, staged_reports)

        def verify_committed_publish() -> None:
            if _sha256(output) != artifact_hash or output.stat().st_size != candidate_size:
                raise ValueError("Committed PetPack differs from the gated private candidate")
            if sidecar.read_text(encoding="utf-8") != expected_sidecar:
                raise ValueError("Committed SHA-256 sidecar is inconsistent")
            committed_report = _json(reports / "qa-report.json")
            if committed_report.get("artifact") != report["artifact"]:
                raise ValueError("Committed publish report is inconsistent")

        try:
            _commit_publish_set(
                [
                    (candidate, output),
                    (staged_sidecar, sidecar),
                    (staged_reports, reports),
                ],
                backup_root=staging_root / "backups",
                verify=verify_committed_publish,
            )
        except Exception as error:
            raise ValueError(f"Publish commit failed; previous release restored: {error}") from error

        print(f"Android-gated publish passed: {pack_id} v{version}")
        print(f"Published artifact: {output} ({output.stat().st_size} bytes)")
        print(f"SHA-256: {artifact_hash}")
        return report
