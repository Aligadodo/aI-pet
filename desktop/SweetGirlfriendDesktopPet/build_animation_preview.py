from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
ANIMATION_DIR = ROOT / "assets" / "animations"
OUTPUT = ROOT.parent.parent / "outputs" / "我家女友_甜蜜桌宠_逐帧动画预览_v1.2.4.gif"

LABELS = (
    ("idle", "待机眨眼"),
    ("walk", "行走"),
    ("run", "奔跑"),
    ("drag", "拖拽悬挂"),
    ("climb", "攀爬"),
    ("perch", "顶部陪伴"),
    ("happy", "开心庆祝"),
    ("jump", "开心跳跳"),
    ("hug", "抱抱"),
    ("heart", "双手比心"),
    ("eat", "分享甜品"),
    ("beg", "发起任务"),
    ("pout", "委屈撅嘴"),
    ("cry", "轻轻哭哭"),
    ("angry", "生气抱臂"),
    ("stomp", "生气跺脚"),
    ("gaze", "六向鼠标注视"),
    ("wait", "等待选择"),
    ("sleep", "安静打盹"),
    ("wave", "主动招手"),
    ("stretch", "伸懒腰"),
    ("read", "坐下阅读"),
    ("umbrella", "雨天撑伞"),
    ("sip", "喝热饮"),
    ("photo_pose", "拍照 Pose"),
)

CELL = 300
HEADER = 62
ROWS = 5
COLS = 5
BACKGROUND = (255, 246, 225, 255)
INK = (89, 68, 59, 255)


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = (
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
    )
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size=size)
    return ImageFont.load_default()


def main() -> None:
    title_font = load_font(30)
    label_font = load_font(21)
    manifest = json.loads(
        (ANIMATION_DIR / "manifest.json").read_text(encoding="utf-8")
    )
    animation_frames = {
        name: [
            Image.open(ROOT / "assets" / relative).convert("RGBA")
            for relative in manifest["animations"][name]["frames"]
        ]
        for name, _label in LABELS
    }

    preview_frames: list[Image.Image] = []
    max_frames = max(len(frames) for frames in animation_frames.values())
    for frame_index in range(max_frames):
        canvas = Image.new(
            "RGBA",
            (COLS * CELL, HEADER + ROWS * CELL),
            BACKGROUND,
        )
        draw = ImageDraw.Draw(canvas)
        title = "我家女友 · 甜蜜桌宠 v1.2.4 — 25 动作 / 181 真实帧 / 完整运动周期"
        title_box = draw.textbbox((0, 0), title, font=title_font)
        title_width = title_box[2] - title_box[0]
        draw.text(
            ((canvas.width - title_width) / 2, 13),
            title,
            font=title_font,
            fill=INK,
        )

        for index, (name, label) in enumerate(LABELS):
            column = index % COLS
            row = index // COLS
            x0 = column * CELL
            y0 = HEADER + row * CELL
            draw.rounded_rectangle(
                (x0 + 8, y0 + 8, x0 + CELL - 8, y0 + CELL - 8),
                radius=20,
                fill=(255, 251, 241, 255),
                outline=(222, 195, 152, 255),
                width=2,
            )

            frames = animation_frames[name]
            sprite = frames[frame_index % len(frames)].copy()
            sprite.thumbnail((250, 250), Image.Resampling.LANCZOS)
            sprite_x = x0 + (CELL - sprite.width) // 2
            sprite_y = y0 + 10
            canvas.alpha_composite(sprite, (sprite_x, sprite_y))

            label_box = draw.textbbox((0, 0), label, font=label_font)
            label_width = label_box[2] - label_box[0]
            draw.text(
                (x0 + (CELL - label_width) / 2, y0 + CELL - 42),
                label,
                font=label_font,
                fill=INK,
            )

        preview_frames.append(canvas.convert("P", palette=Image.Palette.ADAPTIVE))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    preview_frames[0].save(
        OUTPUT,
        save_all=True,
        append_images=preview_frames[1:],
        duration=220,
        loop=0,
        optimize=True,
        disposal=2,
    )
    print(OUTPUT)


if __name__ == "__main__":
    main()
