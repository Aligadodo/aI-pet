from __future__ import annotations

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parent
ALPHA_SHEETS = ROOT / "tmp" / "imagegen" / "generated_alpha"
OUTPUT = ROOT / "tmp" / "imagegen" / "final_frames"
CANVAS = 512


STANDING_MAPPINGS = {
    "standing_0.png": (("idle", 0), ("idle", 1), ("idle", 2), ("idle", 3)),
    "standing_1.png": (("idle", 4), ("idle", 5), ("idle", 6), ("gaze", 0)),
    "standing_2.png": (("gaze", 1), ("gaze", 2), ("gaze", 3), ("gaze", 4)),
    "standing_3.png": (("gaze", 5), ("stretch", 0), ("stretch", 1), ("stretch", 2)),
    "standing_4.png": (("stretch", 3), ("stretch", 4), ("stretch", 5)),
}

WALK_MAPPINGS = {
    "walk_a.png": (("walk", 0), ("walk", 1), ("walk", 2), ("walk", 3)),
    "walk_b.png": (("walk", 4), ("walk", 5), ("walk", 6), ("walk", 7)),
}


def extract_cells(
    sheet_name: str,
    mappings: tuple[tuple[str, int], ...],
    *,
    target_height: int,
    target_bottom: int,
) -> None:
    sheet = Image.open(ALPHA_SHEETS / sheet_name).convert("RGBA")
    cell_width = sheet.width // 2
    cell_height = sheet.height // 2
    for cell_index, (animation, frame_index) in enumerate(mappings):
        left = (cell_index % 2) * cell_width
        top = (cell_index // 2) * cell_height
        cell = sheet.crop((left, top, left + cell_width, top + cell_height))
        bounds = cell.getchannel("A").getbbox()
        if bounds is None:
            raise ValueError(f"{sheet_name} cell {cell_index} is empty")
        subject = cell.crop(bounds)
        scale = target_height / subject.height
        width = max(1, round(subject.width * scale))
        if width > 470:
            scale *= 470 / width
            width = 470
        height = max(1, round(subject.height * scale))
        subject = subject.resize((width, height), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        x = (CANVAS - width) // 2
        y = target_bottom - height + 1
        canvas.alpha_composite(subject, (x, y))
        destination = OUTPUT / animation / f"frame_{frame_index:02d}.png"
        destination.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(destination, optimize=True)


def main() -> None:
    for sheet_name, mappings in STANDING_MAPPINGS.items():
        extract_cells(
            sheet_name,
            mappings,
            target_height=463,
            target_bottom=490,
        )
    for sheet_name, mappings in WALK_MAPPINGS.items():
        extract_cells(
            sheet_name,
            mappings,
            target_height=456,
            target_bottom=483,
        )
    print(OUTPUT)


if __name__ == "__main__":
    main()
