from __future__ import annotations

import json
import threading
import time
import urllib.request
from dataclasses import dataclass
from datetime import datetime

from dialogue import part_of_day


WEATHER_LABELS = {
    "sunny": "晴朗",
    "cloudy": "多云",
    "rain": "下雨",
    "snow": "下雪",
    "hot": "炎热",
    "cold": "寒冷",
}


@dataclass(frozen=True)
class EnvironmentSnapshot:
    part: str
    weather: str
    weather_label: str
    temperature_c: int | None
    weekend: bool


def classify_weather(description: str, temperature_c: int | None) -> str:
    text = description.casefold()
    if any(word in text for word in ("snow", "sleet", "blizzard", "冰雹", "雪")):
        return "snow"
    if any(word in text for word in ("rain", "drizzle", "shower", "thunder", "雨", "雷")):
        return "rain"
    if temperature_c is not None and temperature_c >= 30:
        return "hot"
    if temperature_c is not None and temperature_c <= 7:
        return "cold"
    if any(word in text for word in ("cloud", "overcast", "fog", "mist", "阴", "云", "雾")):
        return "cloudy"
    return "sunny"


class WeatherContext:
    """Small, non-blocking weather context with manual fallbacks.

    Auto mode asks wttr.in for an approximate local condition based on the
    network address.  The request runs on a daemon thread and never blocks the
    animation loop.  Manual modes do not access the network.
    """

    def __init__(self, mode: str = "auto", *, refresh_seconds: float = 1800.0) -> None:
        self.mode = mode if mode in {"auto", *WEATHER_LABELS} else "auto"
        self.refresh_seconds = refresh_seconds
        self.weather = "sunny"
        self.temperature_c: int | None = None
        self.updated_at = float("-inf")
        self.refreshing = False
        self.last_error = ""
        self._lock = threading.Lock()

    def set_mode(self, mode: str) -> None:
        if mode not in {"auto", *WEATHER_LABELS}:
            raise ValueError(f"Unknown weather mode: {mode}")
        with self._lock:
            self.mode = mode
            if mode != "auto":
                self.weather = mode
                self.temperature_c = None
                self.updated_at = time.monotonic()

    def refresh_async(self, *, force: bool = False) -> None:
        with self._lock:
            if self.mode != "auto" or self.refreshing:
                return
            if not force and time.monotonic() - self.updated_at < self.refresh_seconds:
                return
            self.refreshing = True
        threading.Thread(target=self._refresh_worker, daemon=True).start()

    def _refresh_worker(self) -> None:
        try:
            request = urllib.request.Request(
                "https://wttr.in/?format=j1",
                headers={"User-Agent": "SweetGirlfriendDesktopPet/1.2"},
            )
            with urllib.request.urlopen(request, timeout=3.5) as response:
                payload = json.loads(response.read().decode("utf-8"))
            condition = payload["current_condition"][0]
            temperature = int(round(float(condition.get("temp_C", 0))))
            descriptions = condition.get("weatherDesc") or []
            description = str(descriptions[0].get("value", "")) if descriptions else ""
            weather = classify_weather(description, temperature)
            with self._lock:
                self.weather = weather
                self.temperature_c = temperature
                self.last_error = ""
                self.updated_at = time.monotonic()
        except Exception as error:  # network failure must never disturb the pet
            with self._lock:
                self.last_error = str(error)
                self.updated_at = time.monotonic()
        finally:
            with self._lock:
                self.refreshing = False

    def snapshot(self, moment: datetime | None = None) -> EnvironmentSnapshot:
        current = moment or datetime.now()
        with self._lock:
            weather = self.mode if self.mode != "auto" else self.weather
            temperature = None if self.mode != "auto" else self.temperature_c
        return EnvironmentSnapshot(
            part=part_of_day(current),
            weather=weather,
            weather_label=WEATHER_LABELS[weather],
            temperature_c=temperature,
            weekend=current.weekday() >= 5,
        )
