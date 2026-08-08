from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parent
ANIMATION_ROOT = ROOT / "assets" / "animations"
RUN_SHEET = ROOT / "tmp" / "imagegen" / "run_cycle_rgba.png"
RUN_PASSING = ROOT / "tmp" / "imagegen" / "run_passing_rgba.png"
CANVAS = 512


def load_frames(name: str) -> list[Image.Image]:
    return [
        Image.open(path).convert("RGBA")
        for path in sorted((ANIMATION_ROOT / name).glob("frame_*.png"))
    ]


def alpha_bbox(frame: Image.Image) -> tuple[int, int, int, int]:
    bbox = frame.getchannel("A").getbbox()
    if bbox is None:
        raise ValueError("animation frame is empty")
    return bbox


def normalize_subject(
    frame: Image.Image,
    *,
    target_height: int,
    center_x: int = CANVAS // 2,
    bottom: int = 483,
) -> Image.Image:
    bbox = alpha_bbox(frame)
    subject = frame.crop(bbox)
    scale = target_height / max(1, subject.height)
    width = max(1, round(subject.width * scale))
    subject = subject.resize((width, target_height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    x = center_x - width // 2
    y = bottom - target_height + 1
    canvas.alpha_composite(subject, (x, y))
    return canvas


def translate(frame: Image.Image, dx: int = 0, dy: int = 0) -> Image.Image:
    canvas = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    canvas.alpha_composite(frame, (dx, dy))
    return canvas


def scale_about_bottom(
    frame: Image.Image,
    *,
    scale_x: float = 1.0,
    scale_y: float = 1.0,
    dy: int = 0,
) -> Image.Image:
    bbox = alpha_bbox(frame)
    subject = frame.crop(bbox)
    size = (
        max(1, round(subject.width * scale_x)),
        max(1, round(subject.height * scale_y)),
    )
    subject = subject.resize(size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    x = (CANVAS - subject.width) // 2
    y = bbox[3] - subject.height + dy
    canvas.alpha_composite(subject, (x, y))
    return canvas


def move_outer_limbs(
    frame: Image.Image,
    *,
    left_dx: int,
    right_dx: int,
    depth: int = 126,
) -> Image.Image:
    """Move opaque leg cutouts without reversing or crossfading the torso."""

    bbox = alpha_bbox(frame)
    bottom = bbox[3]
    top = max(0, bottom - depth)
    center = CANVAS // 2
    source_region = frame.crop((0, top, CANVAS, CANVAS))
    alpha = source_region.getchannel("A")
    light_core = source_region.convert("L").point(
        lambda value: 255 if value >= 118 else 0
    )
    # Shoes and exposed legs are light cream. Expanding their bright cores by
    # a few pixels retains the dark outline while leaving the red skirt intact.
    limb_mask = ImageChops.multiply(
        alpha, light_core.filter(ImageFilter.MaxFilter(9))
    )
    red_core = Image.new("L", source_region.size, 0)
    red_core.putdata(
        [
            255
            if a > 12 and r > 92 and r > g * 1.28 and r > b * 1.18
            else 0
            for r, g, b, a in source_region.getdata()
        ]
    )
    skirt_protection = red_core.filter(ImageFilter.MaxFilter(21))
    limb_mask = ImageChops.subtract(limb_mask, skirt_protection)
    spatial = Image.new("L", source_region.size, 0)
    spatial_draw = ImageDraw.Draw(spatial)
    spatial_draw.rectangle((0, 0, center - 30, source_region.height), fill=255)
    spatial_draw.rectangle(
        (center + 30, 0, source_region.width, source_region.height), fill=255
    )
    limb_mask = ImageChops.multiply(limb_mask, spatial)
    left_mask = limb_mask.copy()
    ImageDraw.Draw(left_mask).rectangle(
        (center, 0, source_region.width, source_region.height), fill=0
    )
    right_mask = limb_mask.copy()
    ImageDraw.Draw(right_mask).rectangle(
        (0, 0, center, source_region.height), fill=0
    )

    left_limb = source_region.copy()
    left_limb.putalpha(ImageChops.multiply(alpha, left_mask))
    right_limb = source_region.copy()
    right_limb.putalpha(ImageChops.multiply(alpha, right_mask))
    result = frame.copy()
    erase = Image.new("L", frame.size, 0)
    erase.paste(limb_mask, (0, top))
    result.putalpha(ImageChops.subtract(result.getchannel("A"), erase))
    result.alpha_composite(left_limb, (left_dx, top))
    result.alpha_composite(right_limb, (right_dx, top))
    return result


def mirror_lower_body(frame: Image.Image, depth: int = 126) -> Image.Image:
    return move_outer_limbs(
        frame, left_dx=116, right_dx=-116, depth=depth
    )


def keep_center_component(frame: Image.Image) -> Image.Image:
    """Drop neighboring sprites that slightly cross generated sheet cells."""

    binary = frame.getchannel("A").point(lambda value: 255 if value > 12 else 0)
    bbox = binary.getbbox()
    if bbox is None:
        return frame
    center = ((bbox[0] + bbox[2]) // 2, (bbox[1] + bbox[3]) // 2)
    if binary.getpixel(center) == 0:
        found: tuple[int, int] | None = None
        for radius in range(1, max(frame.size)):
            left = max(0, center[0] - radius)
            right = min(frame.width - 1, center[0] + radius)
            top = max(0, center[1] - radius)
            bottom = min(frame.height - 1, center[1] + radius)
            candidates = (
                ((x, top) for x in range(left, right + 1)),
                ((x, bottom) for x in range(left, right + 1)),
                ((left, y) for y in range(top, bottom + 1)),
                ((right, y) for y in range(top, bottom + 1)),
            )
            for edge in candidates:
                found = next((point for point in edge if binary.getpixel(point)), None)
                if found is not None:
                    break
            if found is not None:
                center = found
                break
    ImageDraw.floodfill(binary, center, 128, thresh=0)
    component = binary.point(lambda value: 255 if value == 128 else 0)
    result = frame.copy()
    result.putalpha(ImageChops.multiply(frame.getchannel("A"), component))
    return result


def crop_run_sheet(sheet: Image.Image) -> list[Image.Image]:
    frames: list[Image.Image] = []
    for row in range(3):
        top = round(row * sheet.height / 3)
        bottom = round((row + 1) * sheet.height / 3)
        for column in range(4):
            left = round(column * sheet.width / 4)
            right = round((column + 1) * sheet.width / 4)
            frames.append(keep_center_component(sheet.crop((left, top, right, bottom))))
    return frames


def write_frames(name: str, frames: list[Image.Image]) -> None:
    directory = ANIMATION_ROOT / name
    directory.mkdir(parents=True, exist_ok=True)
    for index, frame in enumerate(frames):
        frame.save(directory / f"frame_{index:02d}.png")


def select(source: list[Image.Image], indexes: list[int]) -> list[Image.Image]:
    return [source[index].copy() for index in indexes]


def main() -> None:
    sources = {
        name: load_frames(name)
        for name in (
            "idle",
            "walk",
            "drag",
            "climb",
            "perch",
            "happy",
            "jump",
            "beg",
            "pout",
            "stomp",
            "wait",
            "sleep",
            "wave",
            "stretch",
            "read",
            "umbrella",
        )
    }

    run_cells = crop_run_sheet(Image.open(RUN_SHEET).convert("RGBA"))
    run_base = [
        normalize_subject(frame, target_height=430, bottom=481)
        for frame in run_cells
    ]
    passing = normalize_subject(
        Image.open(RUN_PASSING).convert("RGBA"), target_height=430, bottom=481
    )
    run_bob = (0, 4, 2, -2, -7, -4, 0, 4, 2, -2, -7, -4)
    run_leg_travel = (0, 20, 45, 70, 92, 108, 116, 100, 80, 56, 30, 10)
    run_frames: list[Image.Image] = []
    for index, frame in enumerate(run_base):
        if index in (3, 9):
            frame = passing.copy()
        travel = run_leg_travel[index]
        if travel and index not in (3, 9):
            frame = move_outer_limbs(
                frame, left_dx=travel, right_dx=-travel, depth=88
            )
        run_frames.append(translate(frame, dy=run_bob[index]))
    write_frames("run", run_frames)

    walk_base = [
        normalize_subject(frame, target_height=456, bottom=483)
        for frame in sources["walk"][:12]
    ]
    walk_bob = (0, 2, 3, 1, -1, 0, 0, 2, 3, 1, -1, 0)
    walk_leg_travel = (0, 14, 32, 52, 76, 98, 112, 94, 72, 50, 26, 10)
    walk_frames: list[Image.Image] = []
    for index, frame in enumerate(walk_base):
        travel = walk_leg_travel[index]
        if travel:
            frame = move_outer_limbs(
                frame, left_dx=travel, right_dx=-travel, depth=82
            )
        walk_frames.append(translate(frame, dy=walk_bob[index]))
    write_frames("walk", walk_frames)

    jump = sources["jump"]
    jump_frames = [
        jump[0],
        scale_about_bottom(jump[1], scale_x=1.02, scale_y=0.96, dy=5),
        translate(jump[2], dy=8),
        translate(jump[2], dy=-14),
        translate(jump[3], dy=-28),
        translate(jump[3], dy=-18),
        translate(jump[4], dy=-6),
        translate(jump[4], dy=8),
        scale_about_bottom(jump[5], scale_x=1.04, scale_y=0.94, dy=6),
        jump[5],
    ]
    write_frames("jump", jump_frames)

    climb_base = [
        normalize_subject(frame, target_height=430, bottom=480)
        for frame in sources["climb"][:4]
    ]
    climb_frames = [
        translate(climb_base[0], dy=5),
        translate(climb_base[1], dy=0),
        translate(climb_base[2], dy=-5),
        translate(climb_base[3], dy=-1),
        translate(mirror_lower_body(climb_base[0], depth=82), dy=5),
        translate(mirror_lower_body(climb_base[1], depth=82), dy=0),
        translate(mirror_lower_body(climb_base[2], depth=82), dy=-5),
        translate(mirror_lower_body(climb_base[3], depth=82), dy=-1),
    ]
    write_frames("climb", climb_frames)

    drag_source = [
        normalize_subject(frame, target_height=448, bottom=480)
        for frame in sources["drag"]
    ]
    drag_frames = [
        translate(drag_source[0], dy=0),
        translate(drag_source[2], dy=3),
        translate(drag_source[5], dy=6),
        translate(drag_source[2], dy=3),
        translate(drag_source[0], dy=0),
        translate(mirror_lower_body(drag_source[2], depth=84), dy=3),
        translate(mirror_lower_body(drag_source[5], depth=84), dy=6),
        translate(mirror_lower_body(drag_source[2], depth=84), dy=3),
    ]
    write_frames("drag", drag_frames)

    stomp = sources["stomp"]
    write_frames("stomp", select(stomp, [5, 0, 2, 1, 3, 4, 5]))
    write_frames("idle", select(sources["idle"], [0, 1, 0, 2, 0, 3, 0, 6, 0]))
    write_frames("perch", select(sources["perch"], [0, 1, 0, 2, 0, 5]))
    write_frames("happy", select(sources["happy"], [0, 1, 0, 5]))
    write_frames("beg", select(sources["beg"], [5, 0, 1, 3, 2, 3, 1, 0]))
    write_frames("pout", select(sources["pout"], [0, 1, 2, 3, 2, 1, 4, 5]))
    write_frames("wait", select(sources["wait"], [2, 0, 1, 0, 3, 2]))
    write_frames("sleep", select(sources["sleep"], [0, 1, 2, 3, 4, 5]))
    write_frames("wave", select(sources["wave"], [5, 0, 1, 2, 1, 0, 5]))
    write_frames("stretch", select(sources["stretch"], [0, 1, 2, 1, 3, 1, 4, 5]))
    write_frames("read", select(sources["read"], [0, 1, 2, 3, 4, 3, 2, 1]))
    write_frames("umbrella", select(sources["umbrella"], [0, 1, 2, 3, 4, 3, 2, 1, 5]))


if __name__ == "__main__":
    main()
