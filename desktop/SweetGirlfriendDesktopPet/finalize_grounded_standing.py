from __future__ import annotations

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parent
SOURCE_ROOT = ROOT / "tmp" / "imagegen" / "legacy_standing"
GENERATED_ROOT = ROOT / "tmp" / "imagegen" / "generated_alpha"
OUTPUT_ROOT = ROOT / "assets" / "animations"
CANVAS = 512
SOURCE_SCALE = 0.97
SOURCE_TOP = 4
BOTTOM_FADE_ROWS = 14

MAPPINGS = {
    "standing_0.png": (("idle", 0), ("idle", 1), ("idle", 2), ("idle", 3)),
    "standing_1.png": (("idle", 4), ("idle", 5), ("idle", 6), ("gaze", 0)),
    "standing_2.png": (("gaze", 1), ("gaze", 2), ("gaze", 3), ("gaze", 4)),
    "standing_3.png": (("gaze", 5), ("stretch", 0), ("stretch", 1), ("stretch", 2)),
    "standing_4.png": (("stretch", 3), ("stretch", 4), ("stretch", 5)),
}


def scaled_legacy(animation: str, index: int) -> Image.Image:
    source = Image.open(
        SOURCE_ROOT / animation / f"frame_{index:02d}.png"
    ).convert("RGBA")
    size = round(CANVAS * SOURCE_SCALE)
    resized = source.resize((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((CANVAS - size) // 2, SOURCE_TOP))
    return canvas


def fade_cropped_edge(image: Image.Image) -> Image.Image:
    """Let the generated shoe patch continue through the legacy crop edge."""

    result = image.copy()
    alpha = result.getchannel("A")
    bounds = alpha.getbbox()
    if bounds is None:
        return result
    bottom = bounds[3]
    pixels = alpha.load()
    for y in range(max(bounds[1], bottom - BOTTOM_FADE_ROWS), bottom):
        factor = (bottom - y - 1) / max(1, BOTTOM_FADE_ROWS)
        for x in range(bounds[0], bounds[2]):
            pixels[x, y] = round(pixels[x, y] * factor)
    result.putalpha(alpha)
    return result


def generated_cell(sheet: Image.Image, cell_index: int) -> Image.Image:
    width = sheet.width // 2
    height = sheet.height // 2
    left = (cell_index % 2) * width
    top = (cell_index // 2) * height
    return sheet.crop((left, top, left + width, top + height))


def align_generated_patch(generated: Image.Image, legacy: Image.Image) -> Image.Image:
    generated_bounds = generated.getchannel("A").getbbox()
    legacy_bounds = legacy.getchannel("A").getbbox()
    if generated_bounds is None or legacy_bounds is None:
        raise ValueError("standing repair contains an empty sprite")

    subject = generated.crop(generated_bounds)
    # Width is a more reliable identity anchor than total height because the
    # legacy frame is exactly the part that was cropped at the bottom.
    scale = legacy_bounds[2] - legacy_bounds[0]
    scale /= max(1, generated_bounds[2] - generated_bounds[0])
    target_width = max(1, round(subject.width * scale))
    target_height = max(1, round(subject.height * scale))
    subject = subject.resize((target_width, target_height), Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    x = round((legacy_bounds[0] + legacy_bounds[2] - target_width) / 2)
    y = legacy_bounds[1]
    canvas.alpha_composite(subject, (x, y))
    return canvas


def shorten_lower_body(image: Image.Image) -> Image.Image:
    """Compress the skirt/leg tail continuously, without pasted seams."""

    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        return image
    bottom = bounds[3]
    pivot = max(bounds[1], bottom - 72)
    lower = image.crop((0, pivot, CANVAS, bottom))
    compressed_height = 52
    lower = lower.resize((CANVAS, compressed_height), Image.Resampling.LANCZOS)

    compressed = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    compressed.alpha_composite(image.crop((0, 0, CANVAS, pivot)), (0, 0))
    compressed.alpha_composite(lower, (0, pivot))
    new_bottom = pivot + compressed_height
    shift_down = bottom - new_bottom
    result = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    result.alpha_composite(compressed, (0, shift_down))
    return result


def normalize_final(image: Image.Image) -> Image.Image:
    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        return image
    subject = image.crop(bounds)
    target_height = 456
    scale = target_height / subject.height
    target_width = max(1, round(subject.width * scale))
    subject = subject.resize(
        (target_width, target_height), Image.Resampling.LANCZOS
    )
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(
        subject,
        ((CANVAS - target_width) // 2, 490 - target_height + 1),
    )
    return canvas


def main() -> None:
    for sheet_name, frames in MAPPINGS.items():
        sheet = Image.open(GENERATED_ROOT / sheet_name).convert("RGBA")
        for cell_index, (animation, index) in enumerate(frames):
            legacy = scaled_legacy(animation, index)
            generated = align_generated_patch(
                generated_cell(sheet, cell_index), legacy
            )
            final = generated.copy()
            final.alpha_composite(fade_cropped_edge(legacy))
            final = shorten_lower_body(final)
            final = normalize_final(final)
            destination = OUTPUT_ROOT / animation / f"frame_{index:02d}.png"
            final.save(destination, optimize=True)
    print(OUTPUT_ROOT)


if __name__ == "__main__":
    main()
