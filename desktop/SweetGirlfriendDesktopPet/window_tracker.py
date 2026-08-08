from __future__ import annotations

import ctypes
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from PySide6.QtCore import QPoint, QRect

from activity import ACTIVITY_LABELS, classify_activity


@dataclass(frozen=True)
class WindowSurface:
    hwnd: int
    title: str
    rect: QRect
    is_foreground: bool = False
    visible_samples: int = 0

    @property
    def top(self) -> int:
        return self.rect.top()

    @property
    def left(self) -> int:
        return self.rect.left()

    @property
    def right(self) -> int:
        return self.rect.right()

    @property
    def width(self) -> int:
        return self.rect.width()


EXCLUDED_CLASSES = {
    "Progman",
    "WorkerW",
    "Shell_TrayWnd",
    "Shell_SecondaryTrayWnd",
    "DV2ControlHost",
}


@dataclass(frozen=True)
class ForegroundActivity:
    hwnd: int = 0
    title: str = ""
    process_name: str = ""
    category: str = "general"

    @property
    def label(self) -> str:
        return ACTIVITY_LABELS.get(self.category, ACTIVITY_LABELS["general"])


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


def surface_score(surface: WindowSurface, origin: QPoint) -> float:
    center_x = surface.rect.center().x()
    horizontal_distance = abs(center_x - origin.x())
    vertical_distance = abs(surface.top - origin.y()) * 0.35
    width_bonus = min(surface.width, 1200) * 0.12
    foreground_bonus = 360.0 if surface.is_foreground else 0.0
    return foreground_bonus + width_bonus - horizontal_distance - vertical_distance


def choose_surface(
    surfaces: Iterable[WindowSurface],
    origin: QPoint,
    *,
    random_jitter: float = 0.0,
) -> WindowSurface | None:
    candidates = list(surfaces)
    if not candidates:
        return None
    return max(
        candidates,
        key=lambda surface: surface_score(surface, origin)
        + random_jitter * ((surface.hwnd % 97) / 97.0),
    )


class WindowTracker:
    DWMWA_EXTENDED_FRAME_BOUNDS = 9
    DWMWA_CLOAKED = 14
    GA_ROOT = 2
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    def __init__(self) -> None:
        self.available = sys.platform == "win32"
        self.user32 = ctypes.windll.user32 if self.available else None
        if self.user32 is not None:
            self.user32.WindowFromPoint.argtypes = [POINT]
            self.user32.WindowFromPoint.restype = ctypes.c_void_p
            self.user32.GetAncestor.argtypes = [ctypes.c_void_p, ctypes.c_uint]
            self.user32.GetAncestor.restype = ctypes.c_void_p
        try:
            self.dwmapi = ctypes.windll.dwmapi if self.available else None
        except OSError:
            self.dwmapi = None

    def _window_rect(self, hwnd: int) -> QRect | None:
        if not self.available:
            return None

        class RECT(ctypes.Structure):
            _fields_ = [
                ("left", ctypes.c_long),
                ("top", ctypes.c_long),
                ("right", ctypes.c_long),
                ("bottom", ctypes.c_long),
            ]

        rect = RECT()
        result = 0
        if self.dwmapi is not None:
            result = (
                self.dwmapi.DwmGetWindowAttribute(
                    hwnd,
                    self.DWMWA_EXTENDED_FRAME_BOUNDS,
                    ctypes.byref(rect),
                    ctypes.sizeof(rect),
                )
                == 0
            )
        if not result and not self.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
            return None
        width = rect.right - rect.left
        height = rect.bottom - rect.top
        if width <= 0 or height <= 0:
            return None
        return QRect(rect.left, rect.top, width, height)

    def _window_title(self, hwnd: int) -> str:
        length = self.user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return ""
        buffer = ctypes.create_unicode_buffer(length + 1)
        self.user32.GetWindowTextW(hwnd, buffer, len(buffer))
        return buffer.value.strip()

    def _process_name(self, hwnd: int) -> str:
        pid = ctypes.c_ulong()
        self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if not pid.value:
            return ""
        kernel32 = ctypes.windll.kernel32
        kernel32.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_bool, ctypes.c_ulong]
        kernel32.OpenProcess.restype = ctypes.c_void_p
        kernel32.QueryFullProcessImageNameW.argtypes = [
            ctypes.c_void_p,
            ctypes.c_ulong,
            ctypes.c_wchar_p,
            ctypes.POINTER(ctypes.c_ulong),
        ]
        kernel32.QueryFullProcessImageNameW.restype = ctypes.c_bool
        kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = kernel32.OpenProcess(
            self.PROCESS_QUERY_LIMITED_INFORMATION, False, pid.value
        )
        if not handle:
            return ""
        try:
            size = ctypes.c_ulong(1024)
            buffer = ctypes.create_unicode_buffer(size.value)
            if kernel32.QueryFullProcessImageNameW(
                handle, 0, buffer, ctypes.byref(size)
            ):
                return Path(buffer.value).name
        finally:
            kernel32.CloseHandle(handle)
        return ""

    def foreground_activity(self) -> ForegroundActivity:
        if not self.available:
            return ForegroundActivity()
        hwnd = int(self.user32.GetForegroundWindow())
        if not hwnd:
            return ForegroundActivity()
        title = self._window_title(hwnd)
        process_name = self._process_name(hwnd)
        return ForegroundActivity(
            hwnd=hwnd,
            title=title[:80],
            process_name=process_name,
            category=classify_activity(process_name, title),
        )

    def _visible_top_samples(
        self,
        hwnd: int,
        rect: QRect,
        screen_rect: QRect | None,
    ) -> int:
        """Count reachable points on a window ledge after z-order occlusion."""

        visible = rect.intersected(screen_rect) if screen_rect is not None else rect
        if visible.width() <= 0 or visible.height() <= 0:
            return 0
        margin = min(20, max(4, visible.width() // 12))
        left = visible.left() + margin
        right = visible.right() - margin
        if right < left:
            return 0
        y = visible.top() + min(10, max(3, visible.height() // 10))
        sample_count = 9
        hits = 0
        for index in range(sample_count):
            fraction = index / max(1, sample_count - 1)
            x = round(left + (right - left) * fraction)
            top_hwnd = int(self.user32.WindowFromPoint(POINT(x, y)))
            root_hwnd = int(self.user32.GetAncestor(top_hwnd, self.GA_ROOT)) if top_hwnd else 0
            if root_hwnd == hwnd:
                hits += 1
        return hits

    def get(self, hwnd: int) -> WindowSurface | None:
        if not self.available or not hwnd:
            return None
        if not self.user32.IsWindow(hwnd):
            return None
        return next(
            (surface for surface in self.list_surfaces() if surface.hwnd == hwnd),
            None,
        )

    def list_surfaces(
        self,
        *,
        exclude_hwnd: int = 0,
        screen_rect: QRect | None = None,
    ) -> list[WindowSurface]:
        if not self.available:
            return []

        foreground = int(self.user32.GetForegroundWindow())
        own_pid = os.getpid()
        surfaces: list[WindowSurface] = []
        callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

        def enum_callback(raw_hwnd, _lparam) -> bool:
            hwnd = int(raw_hwnd)
            if hwnd == exclude_hwnd:
                return True
            if not self.user32.IsWindowVisible(hwnd) or self.user32.IsIconic(hwnd):
                return True

            pid = ctypes.c_ulong()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            if pid.value == own_pid:
                return True

            title = self._window_title(hwnd)
            if not title:
                return True

            class_buffer = ctypes.create_unicode_buffer(256)
            self.user32.GetClassNameW(hwnd, class_buffer, len(class_buffer))
            if class_buffer.value in EXCLUDED_CLASSES:
                return True

            if self.dwmapi is not None:
                cloaked = ctypes.c_int(0)
                if (
                    self.dwmapi.DwmGetWindowAttribute(
                        hwnd,
                        self.DWMWA_CLOAKED,
                        ctypes.byref(cloaked),
                        ctypes.sizeof(cloaked),
                    )
                    == 0
                    and cloaked.value
                ):
                    return True

            rect = self._window_rect(hwnd)
            if rect is None or rect.width() < 320 or rect.height() < 180:
                return True
            if screen_rect is not None:
                visible = rect.intersected(screen_rect)
                if visible.width() < 240 or visible.height() < 100:
                    return True
                # A maximized window has no reachable visible top ledge.
                if rect.top() < screen_rect.top() + 96:
                    return True
            visible_samples = self._visible_top_samples(hwnd, rect, screen_rect)
            # IsWindowVisible also returns true for fully covered background windows.
            # Requiring a reachable title-bar sample prevents climbing onto surfaces
            # that the user cannot currently see.
            if visible_samples <= 0:
                return True

            surfaces.append(
                WindowSurface(
                    hwnd=hwnd,
                    title=title,
                    rect=rect,
                    is_foreground=hwnd == foreground,
                    visible_samples=visible_samples,
                )
            )
            return True

        self.user32.EnumWindows(callback_type(enum_callback), 0)
        return surfaces
