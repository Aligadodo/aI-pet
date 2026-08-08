from __future__ import annotations

import ctypes
import sys
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class TypingSnapshot:
    active: bool
    keys_per_minute: int
    seconds_since_key: float


ACTIVITY_LABELS = {
    "coding": "写代码",
    "writing": "写作",
    "spreadsheet": "处理表格",
    "meeting": "开会",
    "browsing": "浏览网页",
    "music": "听音乐",
    "design": "做设计",
    "gaming": "玩游戏",
    "reading": "阅读",
    "general": "处理事情",
}


def classify_activity(process_name: str, title: str = "") -> str:
    """Classify local foreground activity without retaining typed content."""

    process = Path(process_name).stem.casefold()
    text = f"{process} {title}".casefold()
    rules = (
        (
            "coding",
            (
                "code",
                "devenv",
                "pycharm",
                "idea64",
                "webstorm",
                "rider64",
                "terminal",
                "powershell",
                "cmd.exe",
                "windows terminal",
                "github desktop",
            ),
        ),
        (
            "spreadsheet",
            ("excel", "spreadsheet", "表格", "sheet"),
        ),
        (
            "meeting",
            (
                "zoom",
                "teams",
                "meeting",
                "会议",
                "腾讯会议",
                "feishu",
                "lark",
                "dingtalk",
            ),
        ),
        (
            "writing",
            (
                "winword",
                "word.exe",
                "notepad",
                "obsidian",
                "notion",
                "typora",
                "写作",
                "文档",
            ),
        ),
        (
            "design",
            ("figma", "photoshop", "illustrator", "blender", "设计"),
        ),
        (
            "music",
            ("spotify", "cloudmusic", "qqmusic", "music", "音乐"),
        ),
        (
            "gaming",
            ("steam", "epicgames", "game", "游戏"),
        ),
        (
            "reading",
            ("acrobat", "pdf", "reader", "kindle", "阅读"),
        ),
        (
            "browsing",
            ("chrome", "msedge", "firefox", "browser", "浏览器"),
        ),
    )
    for activity, keywords in rules:
        if any(keyword in text for keyword in keywords):
            return activity
    return "general"


class InputActivityMonitor:
    """Count global key-down transitions only; never capture key values or text."""

    WINDOW_SECONDS = 15.0
    ACTIVE_GRACE_SECONDS = 2.8
    ACTIVE_RATE = 20

    _TRACKED_KEYS = tuple(
        sorted(
            {
                0x08,
                0x09,
                0x0D,
                0x1B,
                0x20,
                *range(0x30, 0x5B),
                *range(0x60, 0x70),
                *range(0xBA, 0xE3),
            }
        )
    )

    def __init__(self, *, clock=time.monotonic) -> None:
        self.clock = clock
        self.available = sys.platform == "win32"
        self.events: deque[float] = deque()
        self.down_keys: set[int] = set()
        self.last_key_at = float("-inf")
        self.next_sample_at = float("-inf")
        self.user32 = ctypes.windll.user32 if self.available else None
        if self.user32 is not None:
            self.user32.GetAsyncKeyState.argtypes = [ctypes.c_int]
            self.user32.GetAsyncKeyState.restype = ctypes.c_short

    def _trim(self, now: float) -> None:
        cutoff = now - self.WINDOW_SECONDS
        while self.events and self.events[0] < cutoff:
            self.events.popleft()

    def record_keypress(self, timestamp: float | None = None) -> None:
        """Testable counter entry point; production polling never exposes which key."""

        now = self.clock() if timestamp is None else timestamp
        self.events.append(now)
        self.last_key_at = now
        self._trim(now)

    def sample(self, now: float | None = None) -> TypingSnapshot:
        now = self.clock() if now is None else now
        if self.available and now >= self.next_sample_at:
            self.next_sample_at = now + 0.045
            currently_down: set[int] = set()
            for key in self._TRACKED_KEYS:
                if self.user32.GetAsyncKeyState(key) & 0x8000:
                    currently_down.add(key)
                    if key not in self.down_keys:
                        self.record_keypress(now)
            self.down_keys = currently_down
        self._trim(now)
        rate = round(len(self.events) * 60.0 / self.WINDOW_SECONDS)
        since = now - self.last_key_at
        return TypingSnapshot(
            active=since <= self.ACTIVE_GRACE_SECONDS and rate >= self.ACTIVE_RATE,
            keys_per_minute=rate,
            seconds_since_key=since,
        )
