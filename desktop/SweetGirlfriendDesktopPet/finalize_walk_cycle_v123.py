from __future__ import annotations

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parent
SHEETS = ROOT / "tmp" / "imagegen" / "generated_alpha"
OUTPUT = ROOT / "assets" / "animations" / "walk"
CANVAS = 512
TARGET_HEIGHT = 456
TARGET_BOTTOM = 483
TARGET_CENTER_X = 256


def extract_cell(sheet: Image.Image, index: int) -> Image.Image:
    width = sheet.width // 2
    height = sheet.height // 2
    left = (index % 2) * width
    top = (index // 2) * height
    return sheet.crop((left, top, left + width, top + height))


def normalize(cell: Image.Image) -> Image.Image:
    bounds = cell.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError("walk sheet contains an empty cell")
    subject = cell.crop(bounds)
    scale = TARGET_HEIGHT / subject.height
    target_width = max(1, round(subject.width * scale))
    target_height = TARGET_HEIGHT
    if target_width > 470:
        scale *= 470 / target_width
        target_width = 470
        target_height = max(1, round(subject.height * scale))
    subject = subject.resize(
        (target_width, target_height), Image.Resampling.LANCZOS
    )
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    x = TARGET_CENTER_X - target_width // 2
    y = TARGET_BOTTOM - target_height + 1
    canvas.alpha_composite(subject, (x, y))
    return canvas


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    output_index = 0
    for sheet_index in range(3):
        sheet = Image.open(SHEETS / f"walk_{sheet_index}.png").convert("RGBA")
        for cell_index in range(4):
            frame = normalize(extract_cell(sheet, cell_index))
            frame.save(OUTPUT / f"frame_{output_index:02d}.png", optimize=True)
            output_index += 1
    print(f"{output_index} walk frames -> {OUTPUT}")


if __name__ == "__main__":
    main()
