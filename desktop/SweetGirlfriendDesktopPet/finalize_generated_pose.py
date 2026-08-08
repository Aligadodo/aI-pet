from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


CANVAS = (512, 512)


def alpha_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError("generated pose has no visible pixels")
    return bounds


def normalize_pose(source: Path, reference: Path, destination: Path) -> None:
    image = Image.open(source).convert("RGBA")
    reference_image = Image.open(reference).convert("RGBA")
    source_bounds = alpha_bbox(image)
    reference_bounds = alpha_bbox(reference_image)
    target_height = reference_bounds[3] - reference_bounds[1]
    target_bottom = min(CANVAS[1] - 4, reference_bounds[3])

    subject = image.crop(source_bounds)
    scale = target_height / max(1, subject.height)
    width = max(1, round(subject.width * scale))
    subject = subject.resize((width, target_height), Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", CANVAS, (0, 0, 0, 0))
    x = (CANVAS[0] - subject.width) // 2
    y = target_bottom - subject.height
    canvas.alpha_composite(subject, (x, y))
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(destination, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Normalize ImageGen pose cutouts to the desktop-pet canvas."
    )
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("inputs", nargs="+", type=Path)
    args = parser.parse_args()
    for index, source in enumerate(args.inputs):
        normalize_pose(
            source,
            args.reference,
            args.output_dir / f"frame_{index:02d}.png",
        )


if __name__ == "__main__":
    main()
