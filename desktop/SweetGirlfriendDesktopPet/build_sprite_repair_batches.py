from __future__ import annotations

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parent
ANIMATIONS = ROOT / "assets" / "animations"
TMP = ROOT / "tmp" / "imagegen"
CELL = 512
SHEET = 1024
KEY = (255, 0, 255, 255)
# Keep the legacy crop large enough for identity/detail matching while leaving
# a narrow, explicit completion strip below the skirt.  The generated upper
# body is never shipped; only the repaired lower strip is composited later.
SOURCE_SCALE = 0.90


STANDING_BATCHES = (
    ("standing_batch_0.png", (("idle", 0), ("idle", 1), ("idle", 2), ("idle", 3))),
    ("standing_batch_1.png", (("idle", 4), ("idle", 5), ("idle", 6), ("gaze", 0))),
    ("standing_batch_2.png", (("gaze", 1), ("gaze", 2), ("gaze", 3), ("gaze", 4))),
    ("standing_batch_3.png", (("gaze", 5), ("stretch", 0), ("stretch", 1), ("stretch", 2))),
    ("standing_batch_4.png", (("stretch", 3), ("stretch", 4), ("stretch", 5))),
)

WALK_BATCHES = (
    ("walk_batch_a.png", (("walk", 0), ("walk", 1), ("walk", 2), ("walk", 3))),
    ("walk_batch_b.png", (("walk", 4), ("walk", 5), ("walk", 6), ("walk", 0))),
)


def make_sheet(output: Path, frames: tuple[tuple[str, int], ...]) -> None:
    sheet = Image.new("RGBA", (SHEET, SHEET), KEY)
    for cell_index, (animation, frame_index) in enumerate(frames):
        source = Image.open(
            ANIMATIONS / animation / f"frame_{frame_index:02d}.png"
        ).convert("RGBA")
        resized = source.resize(
            (round(source.width * SOURCE_SCALE), round(source.height * SOURCE_SCALE)),
            Image.Resampling.LANCZOS,
        )
        column = cell_index % 2
        row = cell_index // 2
        x = column * CELL + (CELL - resized.width) // 2
        y = row * CELL + 10
        sheet.alpha_composite(resized, (x, y))
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(output, quality=96)


def main() -> None:
    standing_dir = TMP / "standing_batches"
    walk_dir = TMP / "walk_batches"
    for filename, frames in STANDING_BATCHES:
        make_sheet(standing_dir / filename, frames)
    for filename, frames in WALK_BATCHES:
        make_sheet(walk_dir / filename, frames)
    print(standing_dir)
    print(walk_dir)


if __name__ == "__main__":
    main()
