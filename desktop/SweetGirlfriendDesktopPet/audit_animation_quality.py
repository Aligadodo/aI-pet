from __future__ import annotations

import json
from pathlib import Path
from statistics import median

from PIL import Image, ImageChops, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "assets" / "animations" / "manifest.json"
OUTPUT = ROOT.parent.parent / "outputs" / "动作帧审核_v1.2.4"


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path in (
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
    ):
        if path.exists():
            return ImageFont.truetype(str(path), size=size)
    return ImageFont.load_default()


def changed_pixels(left: Image.Image, right: Image.Image) -> int:
    difference = ImageChops.difference(left, right).convert("L")
    return sum(value > 12 for value in difference.get_flattened_data())


def shoe_span(frame: Image.Image) -> int:
    pixels = frame.load()
    xs = [
        x
        for y in range(380, frame.height)
        for x in range(frame.width)
        if pixels[x, y][3] > 180
        and pixels[x, y][0] > 175
        and pixels[x, y][1] > 130
        and pixels[x, y][2] > 80
    ]
    return max(xs) - min(xs) if xs else 0


def contact_sheet(name: str, frames: list[Image.Image]) -> None:
    columns = 4
    cell_width = 300
    cell_height = 320
    rows = (len(frames) + columns - 1) // columns
    canvas = Image.new(
        "RGBA",
        (columns * cell_width, rows * cell_height),
        (255, 248, 234, 255),
    )
    draw = ImageDraw.Draw(canvas)
    label_font = font(20)
    for index, frame in enumerate(frames):
        sprite = frame.copy()
        sprite.thumbnail((280, 280), Image.Resampling.LANCZOS)
        left = index % columns * cell_width
        top = index // columns * cell_height
        x = left + (cell_width - sprite.width) // 2
        y = top + 30 + (280 - sprite.height) // 2
        canvas.alpha_composite(sprite, (x, y))
        draw.text(
            (left + 10, top + 6),
            f"{name} {index:02d}",
            font=label_font,
            fill=(74, 54, 48, 255),
        )
    canvas.convert("RGB").save(OUTPUT / f"{name}.jpg", quality=93)


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report: dict[str, object] = {
        "version": "1.2.4",
        "render_fps_multiplier": manifest["render_fps_multiplier"],
        "animations": {},
        "failures": [],
        "warnings": [],
    }
    failures: list[str] = report["failures"]  # type: ignore[assignment]
    warnings: list[str] = report["warnings"]  # type: ignore[assignment]

    for name, spec in manifest["animations"].items():
        paths = [ROOT / "assets" / relative for relative in spec["frames"]]
        frames = [Image.open(path).convert("RGBA") for path in paths]
        contact_sheet(name, frames)
        bounds = [frame.getchannel("A").getbbox() for frame in frames]
        if any(bound is None for bound in bounds):
            failures.append(f"{name}: contains an empty frame")
            continue
        concrete = [bound for bound in bounds if bound is not None]
        heights = [bound[3] - bound[1] for bound in concrete]
        widths = [bound[2] - bound[0] for bound in concrete]
        bottoms = [bound[3] for bound in concrete]
        centers = [(bound[0] + bound[2]) / 2 for bound in concrete]
        sequential = [
            changed_pixels(left, right)
            for left, right in zip(frames, frames[1:])
        ]
        lower = [
            changed_pixels(
                left.crop((0, 300, 512, 512)),
                right.crop((0, 300, 512, 512)),
            )
            for left, right in zip(frames, frames[1:])
        ]
        loop_change = (
            changed_pixels(frames[-1], frames[0]) if spec.get("loop", True) else 0
        )
        loop_ratio = (
            round(loop_change / median(sequential), 3)
            if sequential and median(sequential) > 0 and loop_change
            else 0.0
        )
        spans = [shoe_span(frame) for frame in frames] if name in {"walk", "run"} else []
        corners = [
            max(
                frame.getchannel("A").getpixel(point)
                for point in ((0, 0), (511, 0), (0, 511), (511, 511))
            )
            for frame in frames
        ]
        if max(corners) != 0:
            failures.append(f"{name}: opaque canvas corner")
        if name in {"walk", "run"}:
            if max(heights) - min(heights) > 16:
                failures.append(f"{name}: character height varies by more than 16 px")
            if min(spans) > 155 or max(spans) < 185:
                failures.append(f"{name}: missing passing/contact shoe-span contrast")
        if loop_ratio > 1.4:
            warnings.append(f"{name}: loop seam ratio {loop_ratio}")

        report["animations"][name] = {  # type: ignore[index]
            "frames": len(frames),
            "fps": spec["fps"],
            "loop": spec.get("loop", True),
            "height_range": max(heights) - min(heights),
            "width_range": max(widths) - min(widths),
            "bottom_range": max(bottoms) - min(bottoms),
            "center_x_range": round(max(centers) - min(centers), 2),
            "sequential_changed_pixels": sequential,
            "lower_body_changed_pixels": lower,
            "loop_changed_pixels": loop_change,
            "loop_seam_ratio": loop_ratio,
            "shoe_spans": spans,
        }

    report["total_frames"] = sum(
        len(spec["frames"]) for spec in manifest["animations"].values()
    )
    report_path = OUTPUT / "animation_quality_report_v1.2.4.json"
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(report_path)
    print(f"failures={len(failures)} warnings={len(warnings)}")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
