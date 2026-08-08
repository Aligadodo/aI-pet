from __future__ import annotations

import json
import random
import time
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Any


DENSITY_GAPS = {
    "off": float("inf"),
    "quiet": 32.0,
    "standard": 16.0,
    "lively": 7.0,
}


def part_of_day(moment: datetime | None = None) -> str:
    hour = (moment or datetime.now()).hour
    if 5 <= hour < 11:
        return "morning"
    if 11 <= hour < 17:
        return "day"
    if 17 <= hour < 22:
        return "evening"
    return "night"


class DialogueEngine:
    def __init__(
        self,
        source: Path,
        density: str = "standard",
        *,
        clock=time.monotonic,
        random_source: random.Random | None = None,
    ) -> None:
        with source.open("r", encoding="utf-8") as file:
            data = json.load(file)
        self.events: dict[str, dict[str, Any]] = data["events"]
        self.density = density if density in DENSITY_GAPS else "standard"
        self.clock = clock
        self.random = random_source or random.Random()
        self.last_spoken_at = float("-inf")
        self.recent: deque[str] = deque(maxlen=10)

    def set_density(self, density: str) -> None:
        if density not in DENSITY_GAPS:
            raise ValueError(f"Unknown dialogue density: {density}")
        self.density = density

    def choose(
        self,
        event: str,
        *,
        context: dict[str, Any] | None = None,
        force: bool = False,
        moment: datetime | None = None,
    ) -> str | None:
        event_key = event
        timed_key = f"{event}_{part_of_day(moment)}"
        if timed_key in self.events:
            event_key = timed_key
        spec = self.events.get(event_key)
        if not spec or self.density == "off":
            return None

        now = self.clock()
        gap = DENSITY_GAPS[self.density]
        if not force and now - self.last_spoken_at < gap:
            return None
        if not force and self.random.random() > float(spec.get("chance", 1.0)):
            return None

        lines = list(spec.get("lines", []))
        candidates = [line for line in lines if line not in self.recent] or lines
        if not candidates:
            return None

        line = self.random.choice(candidates)
        values = {
            "time": (moment or datetime.now()).strftime("%H:%M"),
            "hour": (moment or datetime.now()).strftime("%H"),
            "minutes": 50,
            "app": "当前应用",
            "window": "当前窗口",
            "activity_label": "手上的事情",
            "typing_rate": 0,
            "weather": "",
            "temperature": "",
        }
        values.update(context or {})
        try:
            rendered = line.format_map(values)
        except (KeyError, ValueError):
            rendered = line

        self.last_spoken_at = now
        self.recent.append(line)
        return rendered
