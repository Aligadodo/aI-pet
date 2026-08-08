from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "assets" / "animations" / "manifest.json"
OUTPUTS = {
    "walk": ROOT.parent.parent / "outputs" / "我家女友_走路循环_v1.2.4.gif",
    "run": ROOT.parent.parent / "outputs" / "我家女友_跑步循环_v1.2.4.gif",
}
SIZE = 512
BACKGROUND = (255, 247, 230, 255)


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path in (
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
    ):
        if path.exists():
            return ImageFont.truetype(str(path), size=size)
    return ImageFont.load_default()


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    label_font = font(22)
    for name, output in OUTPUTS.items():
        spec = manifest["animations"][name]
        paths = [ROOT / "assets" / relative for relative in spec["frames"]]
        frames: list[Image.Image] = []
        for index, path in enumerate(paths):
            canvas = Image.new("RGBA", (SIZE, SIZE), BACKGROUND)
            character = Image.open(path).convert("RGBA")
            canvas.alpha_composite(character)
            draw = ImageDraw.Draw(canvas)
            label = f"{name.title()} {index + 1}/{len(paths)}"
            box = draw.textbbox((0, 0), label, font=label_font)
            draw.rounded_rectangle(
                (14, 14, 34 + box[2], 28 + box[3]),
                radius=12,
                fill=(255, 255, 255, 220),
            )
            draw.text((24, 19), label, font=label_font, fill=(103, 71, 59, 255))
            frames.append(canvas.convert("P", palette=Image.Palette.ADAPTIVE))
        output.parent.mkdir(parents=True, exist_ok=True)
        frames[0].save(
            output,
            save_all=True,
            append_images=frames[1:],
            duration=round(1000 / float(spec["fps"])),
            loop=0,
            disposal=2,
            optimize=False,
        )
        print(output)


if __name__ == "__main__":
    main()
