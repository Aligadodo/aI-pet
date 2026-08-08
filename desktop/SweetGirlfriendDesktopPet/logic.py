from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def ease_out_cubic(progress: float) -> float:
    value = clamp(progress, 0.0, 1.0)
    return 1.0 - (1.0 - value) ** 3


def parabolic_arc(progress: float, height: float) -> float:
    value = clamp(progress, 0.0, 1.0)
    return 4.0 * height * value * (1.0 - value)


@dataclass
class ClickBurstDetector:
    count: int = 5
    window_seconds: float = 2.0
    events: deque[float] = field(default_factory=deque)

    def add(self, timestamp: float) -> bool:
        self.events.append(timestamp)
        cutoff = timestamp - self.window_seconds
        while self.events and self.events[0] < cutoff:
            self.events.popleft()
        triggered = len(self.events) >= self.count
        if triggered:
            self.events.clear()
        return triggered
