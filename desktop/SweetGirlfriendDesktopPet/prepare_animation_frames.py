from __future__ import annotations

import json
from array import array
from collections import deque
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parent
SHEET_DIR = ROOT / "assets" / "animation_sheets" / "key_removed"
OUTPUT_DIR = ROOT / "assets" / "animations"
FRAME_CANVAS = 512
CONTENT_LIMIT = 470

SHEETS = {
    "girl_idle_walk.png": (("idle", 7), ("walk", 7)),
    "girl_run_drag_v110.png": (("run", 6), ("drag", 6)),
    "girl_climb_perch_v110.png": (("climb", 6), ("perch", 6)),
    "girl_happy_jump.png": (("happy", 6), ("jump", 6)),
    "girl_hug_heart.png": (("hug", 6), ("heart", 6)),
    "girl_eat_beg.png": (("eat", 6), ("beg", 6)),
    "girl_pout_cry.png": (("pout", 6), ("cry", 6)),
    "girl_angry_stomp.png": (("angry", 6), ("stomp", 6)),
    "girl_gaze_wait.png": (("gaze", 6), ("wait", 6)),
    "girl_sleep_wave.png": (("sleep", 6), ("wave", 6)),
    "girl_stretch_read_v110.png": (("stretch", 6), ("read", 6)),
    "girl_umbrella_sip_v110.png": (("umbrella", 6), ("sip", 6)),
}

ANIMATIONS = {
    "idle": {"fps": 2, "loop": True},
    "walk": {"fps": 8, "loop": True},
    "run": {"fps": 10, "loop": True},
    "drag": {"fps": 4, "loop": True},
    "climb": {"fps": 5, "loop": False},
    "perch": {"fps": 2.5, "loop": True},
    "happy": {"fps": 5.5, "loop": False},
    "jump": {"fps": 7, "loop": False},
    "hug": {"fps": 5, "loop": False},
    "heart": {"fps": 4.5, "loop": False},
    "eat": {"fps": 5, "loop": False},
    "beg": {"fps": 3, "loop": True},
    "pout": {"fps": 3.5, "loop": False},
    "cry": {"fps": 3, "loop": False},
    "angry": {"fps": 4, "loop": False},
    "stomp": {"fps": 6, "loop": False},
    "gaze": {"fps": 1, "loop": True},
    "wait": {"fps": 3, "loop": True},
    "sleep": {"fps": 1.6, "loop": True},
    "wave": {"fps": 5.5, "loop": False},
    "stretch": {"fps": 4.2, "loop": False},
    "read": {"fps": 2.4, "loop": True},
    "umbrella": {"fps": 3.8, "loop": False},
    "sip": {"fps": 3.2, "loop": False},
    "photo_pose": {"fps": 4.5, "loop": False},
}

FRAME_COUNTS = {
    animation: frame_count
    for rows in SHEETS.values()
    for animation, frame_count in rows
}
FRAME_COUNTS["walk"] = 8
FRAME_COUNTS["photo_pose"] = 3


def alpha_bbox(image: Image.Image) -> tuple[int, int, int, int] | None:
    return image.getchannel("A").getbbox()


def add_padding(
    bbox: tuple[int, int, int, int], width: int, height: int, padding: int = 6
) -> tuple[int, int, int, int]:
    left, top, right, bottom = bbox
    return (
        max(0, left - padding),
        max(0, top - padding),
        min(width, right + padding),
        min(height, bottom + padding),
    )


def connected_components(row_image: Image.Image) -> tuple[array, list[dict]]:
    alpha = row_image.getchannel("A")
    alpha_bytes = alpha.tobytes()
    width, height = row_image.size
    labels = array("H", [0]) * (width * height)
    components: list[dict] = []
    component_id = 0

    for start in range(width * height):
        if labels[start] or alpha_bytes[start] <= 20:
            continue
        component_id += 1
        if component_id >= 65535:
            raise ValueError("Too many connected components")
        queue = deque([start])
        labels[start] = component_id
        size = 0
        left = right = start % width
        top = bottom = start // width

        while queue:
            index = queue.popleft()
            x = index % width
            y = index // width
            size += 1
            left = min(left, x)
            right = max(right, x)
            top = min(top, y)
            bottom = max(bottom, y)
            for neighbor in (
                index - 1 if x > 0 else -1,
                index + 1 if x + 1 < width else -1,
                index - width if y > 0 else -1,
                index + width if y + 1 < height else -1,
            ):
                if (
                    neighbor >= 0
                    and labels[neighbor] == 0
                    and alpha_bytes[neighbor] > 20
                ):
                    labels[neighbor] = component_id
                    queue.append(neighbor)

        components.append(
            {
                "id": component_id,
                "size": size,
                "bbox": (left, top, right + 1, bottom + 1),
                "center_x": (left + right) / 2,
            }
        )
    return labels, components


def extract_frame_subjects(
    row_image: Image.Image, animation: str, frame_count: int
) -> list[Image.Image]:
    labels, components = connected_components(row_image)
    substantial = [component for component in components if component["size"] >= 120]
    if len(substantial) < frame_count:
        raise ValueError(f"{animation}: only {len(substantial)} substantial components")

    anchors = sorted(
        sorted(substantial, key=lambda component: component["size"], reverse=True)[:frame_count],
        key=lambda component: component["center_x"],
    )
    anchor_ids = {component["id"] for component in anchors}
    component_groups: dict[int, int] = {}
    for component in components:
        nearest = min(
            range(frame_count),
            key=lambda index: abs(component["center_x"] - anchors[index]["center_x"]),
        )
        anchor_box = anchors[nearest]["bbox"]
        component_box = component["bbox"]
        horizontal_gap = max(
            anchor_box[0] - component_box[2],
            component_box[0] - anchor_box[2],
            0,
        )
        vertical_gap = max(
            anchor_box[1] - component_box[3],
            component_box[1] - anchor_box[3],
            0,
        )
        # Keep bowls, detached tail tips and nearby motion marks, but reject
        # fragments that leaked across the source sheet's row boundary.
        if (
            component["id"] in anchor_ids
            or max(horizontal_gap, vertical_gap) <= 80
        ):
            component_groups[component["id"]] = nearest

    alpha_bytes = row_image.getchannel("A").tobytes()
    subjects: list[Image.Image] = []
    for group in range(frame_count):
        group_alpha = bytes(
            alpha_bytes[index]
            if label and component_groups.get(label) == group
            else 0
            for index, label in enumerate(labels)
        )
        subject = row_image.copy()
        subject.putalpha(Image.frombytes("L", row_image.size, group_alpha))
        bbox = alpha_bbox(subject)
        if bbox is None:
            raise ValueError(f"{animation}: empty extracted group {group}")
        subjects.append(subject.crop(add_padding(bbox, *row_image.size, padding=8)))
    return subjects


def process_row(
    sheet: Image.Image, row: int, animation: str, frame_count: int
) -> None:
    # Generated sheets leave a visual gutter around the horizontal midpoint.
    # Skip that gutter so a tail or paw that slightly overhangs its source row
    # can never be assigned to the first frame of the neighbouring row.
    row_bounds = (
        (0, 0, sheet.width, round(sheet.height * 0.49))
        if row == 0
        else (0, round(sheet.height * 0.51), sheet.width, sheet.height)
    )
    row_image = sheet.crop(row_bounds).convert("RGBA")
    subjects = extract_frame_subjects(row_image, animation, frame_count)
    max_width = max(subject.width for subject in subjects)
    max_height = max(subject.height for subject in subjects)
    scale = min(CONTENT_LIMIT / max_width, CONTENT_LIMIT / max_height)
    animation_dir = OUTPUT_DIR / animation
    animation_dir.mkdir(parents=True, exist_ok=True)
    for stale_frame in animation_dir.glob("frame_*.png"):
        stale_frame.unlink()
    align_top = animation in {"drag", "climb"}
    for index, subject in enumerate(subjects):
        target_size = (
            max(1, round(subject.width * scale)),
            max(1, round(subject.height * scale)),
        )
        content = subject.resize(target_size, Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (FRAME_CANVAS, FRAME_CANVAS), (0, 0, 0, 0))
        x = (FRAME_CANVAS - target_size[0]) // 2
        y = 21 if align_top else FRAME_CANVAS - target_size[1] - 21
        canvas.alpha_composite(content, (x, y))
        canvas.save(animation_dir / f"frame_{index:02d}.png", optimize=True)


def build_manifest() -> None:
    manifest = {
        "schema_version": 3,
        "canvas": [FRAME_CANVAS, FRAME_CANVAS],
        "render_fps_multiplier": 2,
        "normalize_within_animation": True,
        "animations": {},
    }
    for name, settings in ANIMATIONS.items():
        frames = sorted((OUTPUT_DIR / name).glob("frame_*.png"))
        manifest["animations"][name] = {
            **settings,
            "frames": [str(path.relative_to(ROOT / "assets")).replace("\\", "/") for path in frames],
        }
    (OUTPUT_DIR / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def validate_frames() -> None:
    failures: list[str] = []
    for animation in ANIMATIONS:
        frames = sorted((OUTPUT_DIR / animation).glob("frame_*.png"))
        expected = FRAME_COUNTS[animation]
        if len(frames) != expected:
            failures.append(
                f"{animation}: expected {expected} frames, got {len(frames)}"
            )
            continue
        for frame in frames:
            image = Image.open(frame).convert("RGBA")
            alpha = image.getchannel("A")
            bbox = alpha.getbbox()
            if image.size != (FRAME_CANVAS, FRAME_CANVAS):
                failures.append(f"{frame.name}: wrong size {image.size}")
            if bbox is None:
                failures.append(f"{frame.name}: empty alpha")
            corners = [
                alpha.getpixel((0, 0)),
                alpha.getpixel((FRAME_CANVAS - 1, 0)),
                alpha.getpixel((0, FRAME_CANVAS - 1)),
                alpha.getpixel((FRAME_CANVAS - 1, FRAME_CANVAS - 1)),
            ]
            if any(corners):
                failures.append(f"{frame.name}: non-transparent corner")
    if failures:
        raise ValueError("\n".join(failures))


def update_app_icon() -> None:
    icon = Image.open(OUTPUT_DIR / "idle" / "frame_00.png").convert("RGBA")
    icon.thumbnail((128, 128), Image.Resampling.LANCZOS)
    icon.save(ROOT / "assets" / "pet_icon.png", optimize=True)
    icon.save(
        ROOT / "assets" / "pet_icon.ico",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128)],
    )


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for sheet_name, rows in SHEETS.items():
        path = SHEET_DIR / sheet_name
        if not path.exists():
            raise FileNotFoundError(path)
        sheet = Image.open(path).convert("RGBA")
        process_row(sheet, 0, *rows[0])
        process_row(sheet, 1, *rows[1])
    build_manifest()
    validate_frames()
    update_app_icon()
    print(f"Prepared {len(ANIMATIONS)} animations / {sum(FRAME_COUNTS.values())} frames")


if __name__ == "__main__":
    main()
