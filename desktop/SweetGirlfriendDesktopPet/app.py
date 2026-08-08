from __future__ import annotations

import ctypes
import json
import random
import sys
import time
from datetime import datetime
from pathlib import Path
from statistics import median
from typing import Callable

from PySide6.QtCore import QPoint, QRect, QRectF, QSize, QSettings, Qt, QTimer, QUrl
from PySide6.QtGui import (
    QAction,
    QActionGroup,
    QColor,
    QCursor,
    QDesktopServices,
    QFont,
    QIcon,
    QPainter,
    QPixmap,
    QPolygon,
    QRegion,
    QTransform,
)
from PySide6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QMenu,
    QProgressBar,
    QPushButton,
    QSystemTrayIcon,
    QVBoxLayout,
    QWidget,
)

from activity import ACTIVITY_LABELS, InputActivityMonitor, TypingSnapshot
from dialogue import DialogueEngine
from environment import WEATHER_LABELS, WeatherContext
from logic import ClickBurstDetector, clamp, ease_out_cubic, parabolic_arc
from recommendations import RecommendationService
from tasks import SweetTask, TaskChoice, choose_task
from window_tracker import ForegroundActivity, WindowSurface, WindowTracker, choose_surface


APP_NAME = "我家女友·甜蜜桌宠"
APP_VERSION = "1.2.4"
WINDOW_SIZE = 320
QA_MODE = "--qa-window" in sys.argv
QA_EXPLORE = "--qa-explore" in sys.argv
QA_TASK = "--qa-task" in sys.argv
QA_PHOTO = "--qa-photo" in sys.argv
QA_WALK = "--qa-walk" in sys.argv
QA_STAND = "--qa-stand" in sys.argv
QA_ANIMATION = next(
    (
        argument.split("=", 1)[1]
        for argument in sys.argv
        if argument.startswith("--qa-animation=")
    ),
    None,
)
QA_EXIT_AFTER_MS = next(
    (
        int(argument.split("=", 1)[1])
        for argument in sys.argv
        if argument.startswith("--qa-exit-after=")
    ),
    0,
)

ANIMATION_NAMES = (
    "idle",
    "walk",
    "run",
    "drag",
    "climb",
    "perch",
    "happy",
    "jump",
    "hug",
    "heart",
    "eat",
    "beg",
    "pout",
    "cry",
    "angry",
    "stomp",
    "gaze",
    "wait",
    "sleep",
    "wave",
    "stretch",
    "read",
    "umbrella",
    "sip",
    "photo_pose",
)
NON_MIRRORED_ANIMATIONS = set(ANIMATION_NAMES) - {"walk", "run"}
WINDOW_STATES = {
    "approach_window",
    "climb_window",
    "perch_window",
    "walk_window",
}
RESPONSE_DURATIONS = {
    "happy": 2.5,
    "hug": 2.9,
    "heart": 2.8,
    "eat": 3.1,
    "pout": 3.0,
    "cry": 3.6,
    "angry": 3.2,
    "stomp": 3.0,
    "wave": 2.6,
    "stretch": 3.2,
    "read": 4.2,
    "umbrella": 3.4,
    "sip": 3.4,
    "photo_pose": 3.3,
}


def resource_path(relative: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / relative


class LastInputInfo(ctypes.Structure):
    _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]


def user_idle_seconds() -> float:
    if sys.platform != "win32":
        return 0.0
    info = LastInputInfo()
    info.cbSize = ctypes.sizeof(info)
    if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
        return 0.0
    current = ctypes.windll.kernel32.GetTickCount()
    return ((current - info.dwTime) & 0xFFFFFFFF) / 1000.0


class SpeechBubble(QWidget):
    def __init__(self) -> None:
        super().__init__(
            None,
            Qt.Tool
            | Qt.FramelessWindowHint
            | Qt.WindowStaysOnTopHint
            | Qt.WindowDoesNotAcceptFocus,
        )
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.text = ""
        self.tail_center_x = 110
        self.tail_side = "bottom"
        self.resize(220, 74)
        self.hide_timer = QTimer(self)
        self.hide_timer.setSingleShot(True)
        self.hide_timer.timeout.connect(self.hide)

    def show_message(self, text: str, anchor: QRect, duration_ms: int = 3200) -> None:
        self.text = text
        metrics = self.fontMetrics()
        width = 350 if len(text) > 19 else int(
            clamp(metrics.horizontalAdvance(text) + 48, 140, 350)
        )
        height = 96 if len(text) > 19 else 76
        self.resize(width, height)
        self.follow_anchor(anchor, force=True)
        self.show()
        self.raise_()
        self.hide_timer.start(duration_ms)
        self.update()

    def follow_anchor(self, anchor: QRect, *, force: bool = False) -> None:
        if not force and not self.isVisible():
            return
        screen = QApplication.screenAt(anchor.center()) or QApplication.primaryScreen()
        bounds = screen.availableGeometry()
        width, height = self.width(), self.height()
        x = int(
            clamp(
                anchor.center().x() - width // 2,
                bounds.left() + 8,
                bounds.right() - width - 8,
            )
        )
        above_y = anchor.top() - height + 26
        below_y = anchor.bottom() - 18
        if above_y >= bounds.top() + 8:
            y, tail_side = above_y, "bottom"
        elif below_y + height <= bounds.bottom() - 8:
            y, tail_side = below_y, "top"
        else:
            y, tail_side = (
                int(clamp(above_y, bounds.top() + 8, bounds.bottom() - height - 8)),
                "bottom",
            )
        tail_center_x = int(clamp(anchor.center().x() - x, 22, width - 22))
        changed = tail_side != self.tail_side or tail_center_x != self.tail_center_x
        self.tail_side = tail_side
        self.tail_center_x = tail_center_x
        target = QPoint(x, int(y))
        if self.pos() != target:
            self.move(target)
        if changed:
            self.update()

    def paintEvent(self, _event) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        body_y = 14 if self.tail_side == "top" else 4
        body = QRectF(4, body_y, self.width() - 8, self.height() - 18)
        painter.setPen(QColor("#8A5D58"))
        painter.setBrush(QColor(255, 248, 238, 247))
        painter.drawRoundedRect(body, 18, 18)
        painter.setPen(QColor("#FFF8EE"))
        painter.setBrush(QColor("#FFF8EE"))
        x = self.tail_center_x
        if self.tail_side == "top":
            points = [QPoint(x - 10, 17), QPoint(x + 10, 17), QPoint(x, 4)]
        else:
            points = [
                QPoint(x - 10, self.height() - 18),
                QPoint(x + 10, self.height() - 18),
                QPoint(x, self.height() - 5),
            ]
        painter.drawPolygon(QPolygon(points))
        painter.setPen(QColor("#5F4543"))
        font = QFont("Microsoft YaHei UI", 11)
        font.setWeight(QFont.DemiBold)
        painter.setFont(font)
        painter.drawText(body, Qt.AlignCenter | Qt.TextWordWrap, self.text)


class SweetTaskPanel(QWidget):
    def __init__(self) -> None:
        super().__init__(
            None,
            Qt.Tool
            | Qt.FramelessWindowHint
            | Qt.WindowStaysOnTopHint
            | Qt.WindowDoesNotAcceptFocus,
        )
        self.setWindowTitle("甜蜜小任务")
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setFocusPolicy(Qt.NoFocus)
        self.setObjectName("taskPanel")
        self.setStyleSheet(
            """
            QWidget#taskCard {
                background: rgba(255, 248, 239, 250);
                border: 2px solid #D88B83;
                border-radius: 18px;
            }
            QLabel#taskTitle { color: #A14D52; font: 700 13px 'Microsoft YaHei UI'; }
            QLabel#taskPrompt { color: #553D3C; font: 700 15px 'Microsoft YaHei UI'; }
            QPushButton {
                background: #FFF0E6; color: #714B49; border: 1px solid #E2A09A;
                border-radius: 10px; padding: 8px 10px;
                font: 600 12px 'Microsoft YaHei UI';
            }
            QPushButton:hover { background: #FFD8CF; border-color: #C96868; }
            QPushButton:pressed { background: #F8B8B0; }
            QProgressBar { border: 0; background: #F1D8D1; border-radius: 4px; height: 7px; }
            QProgressBar::chunk { background: #D97173; border-radius: 4px; }
            """
        )
        self.resize(470, 174)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        self.card = QWidget(self)
        self.card.setObjectName("taskCard")
        outer.addWidget(self.card)
        layout = QVBoxLayout(self.card)
        layout.setContentsMargins(18, 13, 18, 14)
        layout.setSpacing(8)
        self.title_label = QLabel("甜蜜小任务")
        self.title_label.setObjectName("taskTitle")
        self.prompt_label = QLabel("")
        self.prompt_label.setObjectName("taskPrompt")
        self.prompt_label.setWordWrap(True)
        self.prompt_label.setAlignment(Qt.AlignCenter)
        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.setTextVisible(False)
        self.choice_layout = QHBoxLayout()
        self.choice_layout.setSpacing(7)
        layout.addWidget(self.title_label)
        layout.addWidget(self.prompt_label)
        layout.addWidget(self.progress)
        layout.addLayout(self.choice_layout)

        self.active_task: SweetTask | None = None
        self.choice_callback: Callable[[TaskChoice], None] | None = None
        self.timeout_callback: Callable[[], None] | None = None
        self.deadline = 0.0
        self.duration = 1.0
        self.timer = QTimer(self)
        self.timer.setTimerType(Qt.PreciseTimer)
        self.timer.timeout.connect(self._tick)
        self.timer.setInterval(50)

    def _clear_choices(self) -> None:
        while self.choice_layout.count():
            item = self.choice_layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()

    def show_task(
        self,
        task: SweetTask,
        anchor: QRect,
        choice_callback: Callable[[TaskChoice], None],
        timeout_callback: Callable[[], None],
    ) -> None:
        self.active_task = task
        self.choice_callback = choice_callback
        self.timeout_callback = timeout_callback
        self.duration = max(1.0, task.duration_seconds)
        self.deadline = time.monotonic() + self.duration
        self.prompt_label.setText(task.prompt)
        self._clear_choices()
        for choice in task.choices:
            button = QPushButton(choice.label)
            button.setCursor(Qt.PointingHandCursor)
            button.setFocusPolicy(Qt.NoFocus)
            button.setAutoDefault(False)
            button.setDefault(False)
            button.clicked.connect(
                lambda _checked=False, value=choice: self._choose(value)
            )
            self.choice_layout.addWidget(button, 1)
        self.adjustSize()
        self.resize(max(470, self.width()), max(174, self.height()))
        self.follow_anchor(anchor, force=True)
        self.show()
        self.raise_()
        self.timer.start()
        self._tick()

    def close_task(self) -> None:
        self.timer.stop()
        self.active_task = None
        self.choice_callback = None
        self.timeout_callback = None
        self.hide()

    def _choose(self, choice: TaskChoice) -> None:
        callback = self.choice_callback
        self.close_task()
        if callback is not None:
            callback(choice)

    def _tick(self) -> None:
        if self.active_task is None:
            return
        remaining = max(0.0, self.deadline - time.monotonic())
        fraction = remaining / self.duration
        self.progress.setValue(round(fraction * 1000))
        self.title_label.setText(
            f"情境小任务 · {self.active_task.scene} · 还剩 {remaining:0.1f} 秒"
        )
        if remaining <= 0:
            callback = self.timeout_callback
            self.close_task()
            if callback is not None:
                callback()

    def keyPressEvent(self, event) -> None:
        event.ignore()

    def follow_anchor(self, anchor: QRect, *, force: bool = False) -> None:
        if not force and not self.isVisible():
            return
        screen = QApplication.screenAt(anchor.center()) or QApplication.primaryScreen()
        bounds = screen.availableGeometry()
        width, height = self.width(), self.height()
        x = int(
            clamp(
                anchor.center().x() - width // 2,
                bounds.left() + 10,
                bounds.right() - width - 10,
            )
        )
        above = anchor.top() - height + 18
        below = anchor.bottom() - 8
        y = above if above >= bounds.top() + 10 else below
        y = int(clamp(y, bounds.top() + 10, bounds.bottom() - height - 10))
        self.move(x, y)


class PetWindow(QWidget):
    def __init__(self) -> None:
        window_flags = (
            Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
            if QA_MODE
            else Qt.Tool
            | Qt.FramelessWindowHint
            | Qt.WindowStaysOnTopHint
            | Qt.WindowDoesNotAcceptFocus
            | Qt.NoDropShadowWindowHint
        )
        super().__init__(None, window_flags)
        self.setWindowTitle(APP_NAME)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setMouseTracking(True)
        self.setFixedSize(QSize(WINDOW_SIZE, WINDOW_SIZE))

        self.settings = QSettings("SweetGirlfriendDesktopPet", "GirlfriendPet")
        density = str(self.settings.value("dialogue_density", "standard"))
        self.dialogue = DialogueEngine(
            resource_path("assets/dialogues_zh-CN.json"),
            density=density,
        )
        self.window_roaming_enabled = self.settings.value(
            "window_roaming", True, type=bool
        )
        self.proactive_enabled = self.settings.value(
            "proactive_interactions", True, type=bool
        )
        self.tasks_enabled = self.settings.value("tasks_enabled", True, type=bool)
        self.task_frequency = str(self.settings.value("task_frequency", "standard"))
        if self.task_frequency not in {"low", "standard", "frequent"}:
            self.task_frequency = "standard"
        weather_mode = str(self.settings.value("weather_mode", "auto"))
        self.weather_context = WeatherContext(weather_mode)
        self.weather_context.refresh_async(force=True)
        self.display_mode = str(self.settings.value("display_mode", "always_on_top"))
        if self.display_mode not in {"always_on_top", "desktop_only"}:
            self.display_mode = "always_on_top"
        self.manual_rest_minutes = int(self.settings.value("manual_rest_minutes", 5))
        if self.manual_rest_minutes not in {2, 5, 10, 20}:
            self.manual_rest_minutes = 5
        self.recommendations_enabled = self.settings.value(
            "recommendations_enabled", True, type=bool
        )
        self.recommendations = RecommendationService(self.recommendations_enabled)
        self.recommendations.refresh_async(force=True)

        self.animation_specs: dict[str, dict] = {}
        self.animations: dict[str, list[QPixmap]] = {}
        self.animation_fps_multiplier = 2
        self._load_animations()
        self.sprites = {name: frames[0] for name, frames in self.animations.items()}
        self._stable_window_mask = self._build_stable_window_mask()
        self.setMask(self._stable_window_mask)

        now = time.monotonic()
        self.state = "idle"
        self.mode = "daily"
        self.direction = 1
        self.speed = 0.0
        self.paused = False
        self.allow_exit = False
        self.state_started = now
        self.state_until: float | None = None
        self.idle_deadline = now + random.uniform(5.0, 9.0)
        self.last_tick = now
        self.last_hour_key = ""
        self.active_work_seconds = 0.0
        self.work_reminder_cooldown_until = 0.0
        self.last_user_idle_seconds = user_idle_seconds()
        self.next_proactive_at = now + random.uniform(16.0, 26.0)
        self.next_recommendation_at = now + random.uniform(280.0, 520.0)
        self.recent_recommendation_urls: list[str] = []
        self.recommendation_opens = int(
            self.settings.value("recommendation_opens", 0)
        )

        self.animation_name = "idle"
        self.animation_started = now
        self.animation_frame_index = -1
        self.animation_render_index = -1
        self._display_pixmap = QPixmap()

        self.press_global: QPoint | None = None
        self.drag_offset = QPoint()
        self.dragging = False
        self.clicks = ClickBurstDetector(count=5, window_seconds=2.0)
        self.input_monitor = InputActivityMonitor()
        self.typing_snapshot = TypingSnapshot(False, 0, float("inf"))
        self.typing_session_active = False
        self.typing_hold_until = 0.0
        self.typing_target_x = 0.0
        self.manual_rest_until = 0.0

        self.jump_start = QPoint()
        self.jump_end = QPoint()
        self.jump_height = 120.0
        self.jump_duration = 0.9
        self.jump_kind = "normal"

        self.window_tracker = WindowTracker()
        self.foreground_activity = ForegroundActivity()
        self.activity_refresh_at = 0.0
        self.target_surface: WindowSurface | None = None
        self.window_target_x = 0.0
        self.window_walk_min_x = 0.0
        self.window_walk_max_x = 0.0
        self.window_refresh_at = 0.0
        self.climb_start_pos = QPoint()
        self.climb_end_pos = QPoint()
        self.climb_duration = 1.4
        self.gaze_frame_index = -1
        self.gaze_active = False
        self.gaze_candidate_index = -1
        self.gaze_candidate_since = 0.0
        self.gaze_last_change_at = 0.0
        self.cursor_over_pet = False
        self.gaze_resume_after = 0.0

        self.active_task: SweetTask | None = None
        self.last_task_key = ""
        self.task_successes = int(self.settings.value("task_successes", 0))
        self.task_failures = int(self.settings.value("task_failures", 0))
        self.task_misses = int(self.settings.value("task_misses", 0))
        self.next_task_at = now + self._task_interval(initial=True)

        self.bubble = SpeechBubble()
        self.task_panel = SweetTaskPanel()
        self.menu = QMenu()
        self._menu_restore_bubble = False
        self._menu_restore_task = False
        self.mode_actions: dict[str, QAction] = {}
        self.display_actions: dict[str, QAction] = {}
        self.tray = self._create_tray()
        self._apply_display_mode(initial=True)
        initial_animation = (
            QA_ANIMATION
            if QA_ANIMATION in self.animations
            else ("walk" if QA_WALK else "idle")
        )
        self._set_animation(initial_animation)

        self.tick_timer = QTimer(self)
        self.tick_timer.setTimerType(Qt.PreciseTimer)
        self.tick_timer.timeout.connect(self._tick)
        self.tick_timer.start(16)
        self.reminder_timer = QTimer(self)
        self.reminder_timer.timeout.connect(self._check_reminders)
        self.reminder_timer.start(1000)

        QTimer.singleShot(0, self.place_on_floor)
        if QA_ANIMATION is None:
            QTimer.singleShot(
                700, lambda: self._say_event("startup", force=True, duration_ms=4300)
            )
        if QA_EXPLORE:
            QTimer.singleShot(1400, lambda: self.start_window_expedition(True))
        if QA_TASK:
            QTimer.singleShot(1200, lambda: self.start_sweet_task(force=True))
        if QA_PHOTO:
            QTimer.singleShot(
                900, lambda: self._play_manual_action("photo_pose", "photo_pose")
            )
        if QA_WALK:
            QTimer.singleShot(900, self._start_qa_walk)
        if QA_ANIMATION in self.animations:
            QTimer.singleShot(250, self._start_qa_animation)
        if QA_EXIT_AFTER_MS > 0:
            QTimer.singleShot(QA_EXIT_AFTER_MS, QApplication.quit)

    def _load_animations(self) -> None:
        manifest_path = resource_path("assets/animations/manifest.json")
        with manifest_path.open("r", encoding="utf-8") as file:
            manifest = json.load(file)
        self.animation_fps_multiplier = max(
            1, int(manifest.get("render_fps_multiplier", 1))
        )
        normalize = bool(manifest.get("normalize_within_animation", False))
        for name, spec in manifest["animations"].items():
            frames = [
                QPixmap(str(resource_path(f"assets/{relative}")))
                for relative in spec["frames"]
            ]
            if not frames or any(frame.isNull() for frame in frames):
                raise RuntimeError(f"无法载入逐帧动画：{name}")
            if spec.get("normalize", normalize):
                frames = self._normalize_animation_frames(frames)
            self.animation_specs[name] = spec
            self.animations[name] = frames
        missing = [name for name in ANIMATION_NAMES if name not in self.animations]
        if missing:
            raise RuntimeError(f"动画清单不完整：{', '.join(missing)}")

    def _normalize_animation_frames(self, frames: list[QPixmap]) -> list[QPixmap]:
        """Keep scale and foot anchors stable without flattening deliberate pose changes."""

        bounds = [QRegion(frame.mask()).boundingRect() for frame in frames]
        heights = [rect.height() for rect in bounds if rect.height() > 0]
        if len(heights) != len(frames):
            return frames
        shortest, tallest = min(heights), max(heights)
        # Large height changes are usually intentional (jump/climb/crouch), not jitter.
        if shortest <= 0 or tallest / shortest > 1.28:
            return frames
        target_height = max(1, round(median(heights)))
        target_bottom = round(median([rect.bottom() for rect in bounds]))
        target_center_x = round(median([rect.center().x() for rect in bounds]))
        normalized: list[QPixmap] = []
        for frame, rect in zip(frames, bounds):
            subject = frame.copy(rect)
            scale = target_height / max(1, rect.height())
            target_width = max(1, round(rect.width() * scale))
            subject = subject.scaled(
                target_width,
                target_height,
                Qt.KeepAspectRatio,
                Qt.SmoothTransformation,
            )
            canvas = QPixmap(frame.size())
            canvas.fill(Qt.transparent)
            painter = QPainter(canvas)
            painter.setRenderHint(QPainter.SmoothPixmapTransform)
            x = target_center_x - subject.width() // 2
            y = target_bottom - subject.height() + 1
            painter.drawPixmap(x, y, subject)
            painter.end()
            normalized.append(canvas)
        return normalized

    def _build_stable_window_mask(self) -> QRegion:
        region = QRegion()
        for name, frames in self.animations.items():
            for frame in frames:
                pixmap = frame.scaled(
                    self.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation
                )
                region = region.united(QRegion(pixmap.mask()))
                if name not in NON_MIRRORED_ANIMATIONS:
                    mirrored = pixmap.transformed(QTransform().scale(-1, 1))
                    region = region.united(QRegion(mirrored.mask()))
        return region if not region.isEmpty() else QRegion(self.rect())

    def _create_tray(self) -> QSystemTrayIcon:
        icon = QIcon(str(resource_path("assets/pet_icon.png")))
        self.setWindowIcon(icon)
        tray = QSystemTrayIcon(icon, self)
        tray.setToolTip(f"{APP_NAME} v{APP_VERSION}")
        self.menu.setStyleSheet(
            """
            QMenu {
                background: #FFF9F2; color: #5C4140; border: 1px solid #E7B8AF;
                border-radius: 10px; padding: 7px; font: 10pt 'Microsoft YaHei UI';
            }
            QMenu::item { padding: 7px 28px 7px 12px; border-radius: 7px; }
            QMenu::item:selected { background: #FADBD3; color: #8E4649; }
            QMenu::item:disabled { color: #A77B76; }
            QMenu::separator { height: 1px; background: #EED4CE; margin: 6px 7px; }
            QMenu::indicator { width: 14px; height: 14px; }
            """
        )
        title = self.menu.addAction(f"♡  我家女友 · 甜蜜桌宠  v{APP_VERSION}")
        title.setEnabled(False)
        self.status_action = self.menu.addAction("")
        self.status_action.setEnabled(False)
        self.menu.addSeparator()

        quick_menu = self.menu.addMenu("✨ 现在陪我")
        quick_menu.addAction("来一个情境小任务").triggered.connect(
            lambda: self.start_sweet_task(force=True)
        )
        quick_menu.addAction("看看猜你喜欢").triggered.connect(
            lambda: self.start_recommendation_task(force=True)
        )
        action_menu = quick_menu.addMenu("做个可爱动作")
        for label, animation, event in (
            ("拍一张纪念照", "photo_pose", "photo_pose"),
            ("伸个懒腰", "stretch", "contextual_stretch"),
            ("坐下阅读", "read", "contextual_read"),
            ("撑一会儿伞", "umbrella", "weather_rain"),
            ("喝口热饮", "sip", "contextual_sip"),
            ("招手打招呼", "wave", "attention_call"),
            ("来一个抱抱", "hug", "double_click"),
            ("比个心", "heart", "heart"),
        ):
            action = action_menu.addAction(label)
            action.triggered.connect(
                lambda _checked=False, state=animation, dialogue_event=event: self._play_manual_action(
                    state, dialogue_event
                )
            )
        quick_menu.addSeparator()
        quick_menu.addAction("爬到屏幕顶部").triggered.connect(self.start_climb)
        quick_menu.addAction("寻找可见窗口探险").triggered.connect(
            lambda: self.start_window_expedition(True)
        )

        mode_menu = self.menu.addMenu("💗 陪伴模式")
        mode_group = QActionGroup(self)
        mode_group.setExclusive(True)
        for label, mode in (
            ("日常陪伴", "daily"),
            ("甜蜜互动", "sweet"),
            ("安静陪伴", "quiet"),
            ("打盹休息", "sleep"),
        ):
            action = QAction(label, self)
            action.setCheckable(True)
            action.setChecked(mode == "daily")
            action.triggered.connect(
                lambda _checked=False, value=mode: self.set_mode(value)
            )
            mode_group.addAction(action)
            mode_menu.addAction(action)
            self.mode_actions[mode] = action

        smart_menu = self.menu.addMenu("🪄 智能互动")
        task_toggle = QAction("启用随机甜蜜任务", self)
        task_toggle.setCheckable(True)
        task_toggle.setChecked(self.tasks_enabled)
        task_toggle.toggled.connect(self._set_tasks_enabled)
        smart_menu.addAction(task_toggle)

        task_frequency_menu = smart_menu.addMenu("任务出现频率")
        task_group = QActionGroup(self)
        task_group.setExclusive(True)
        for label, value in (("偶尔", "low"), ("标准", "standard"), ("经常", "frequent")):
            action = QAction(label, self)
            action.setCheckable(True)
            action.setChecked(value == self.task_frequency)
            action.triggered.connect(
                lambda _checked=False, frequency=value, name=label: self._set_task_frequency(
                    frequency, name
                )
            )
            task_group.addAction(action)
            task_frequency_menu.addAction(action)

        density_menu = smart_menu.addMenu("主动台词频率")
        density_group = QActionGroup(self)
        density_group.setExclusive(True)
        for label, value in (
            ("安静", "quiet"),
            ("标准", "standard"),
            ("活泼", "lively"),
            ("关闭台词", "off"),
        ):
            action = QAction(label, self)
            action.setCheckable(True)
            action.setChecked(value == self.dialogue.density)
            action.triggered.connect(
                lambda _checked=False, density=value, name=label: self._set_dialogue_density(
                    density, name
                )
            )
            density_group.addAction(action)
            density_menu.addAction(action)

        proactive_action = QAction("允许主动情境互动", self)
        proactive_action.setCheckable(True)
        proactive_action.setChecked(self.proactive_enabled)
        proactive_action.toggled.connect(self._set_proactive_interactions)
        smart_menu.addAction(proactive_action)

        smart_menu.addSeparator()
        recommendation_toggle = QAction("猜你喜欢（联网）", self)
        recommendation_toggle.setCheckable(True)
        recommendation_toggle.setChecked(self.recommendations_enabled)
        recommendation_toggle.toggled.connect(self._set_recommendations_enabled)
        smart_menu.addAction(recommendation_toggle)
        self.recommendation_status_action = smart_menu.addAction("")
        self.recommendation_status_action.setEnabled(False)
        smart_menu.addSeparator()
        self.stats_action = smart_menu.addAction("")
        self.stats_action.setEnabled(False)
        self._update_stats_text()

        context_menu = self.menu.addMenu("🌤 情境与天气")
        weather_menu = context_menu.addMenu("天气情境")
        weather_group = QActionGroup(self)
        weather_group.setExclusive(True)
        weather_options = (("自动识别（联网）", "auto"),) + tuple(
            (label, value) for value, label in WEATHER_LABELS.items()
        )
        for label, value in weather_options:
            action = QAction(label, self)
            action.setCheckable(True)
            action.setChecked(value == self.weather_context.mode)
            action.triggered.connect(
                lambda _checked=False, mode=value, name=label: self._set_weather_mode(
                    mode, name
                )
            )
            weather_group.addAction(action)
            weather_menu.addAction(action)
        weather_menu.addSeparator()
        self.weather_status_action = weather_menu.addAction("")
        self.weather_status_action.setEnabled(False)
        self._update_weather_status()

        display_menu = self.menu.addMenu("🖥 显示与活动")
        layer_menu = display_menu.addMenu("显示层级")
        layer_group = QActionGroup(self)
        layer_group.setExclusive(True)
        for label, value in (
            ("始终置顶", "always_on_top"),
            ("只在桌面显示", "desktop_only"),
        ):
            action = QAction(label, self)
            action.setCheckable(True)
            action.setChecked(value == self.display_mode)
            action.triggered.connect(
                lambda _checked=False, mode=value: self._set_display_mode(mode)
            )
            layer_group.addAction(action)
            layer_menu.addAction(action)
            self.display_actions[value] = action

        roaming_action = QAction("允许在可见窗口上漫游", self)
        roaming_action.setCheckable(True)
        roaming_action.setChecked(self.window_roaming_enabled)
        roaming_action.toggled.connect(self._set_window_roaming)
        display_menu.addAction(roaming_action)

        rest_menu = display_menu.addMenu("手动摆放后原地休息")
        rest_group = QActionGroup(self)
        rest_group.setExclusive(True)
        for minutes in (2, 5, 10, 20):
            action = QAction(f"{minutes} 分钟", self)
            action.setCheckable(True)
            action.setChecked(minutes == self.manual_rest_minutes)
            action.triggered.connect(
                lambda _checked=False, value=minutes: self._set_manual_rest_minutes(value)
            )
            rest_group.addAction(action)
            rest_menu.addAction(action)
        rest_menu.addSeparator()
        rest_menu.addAction("现在恢复自由活动").triggered.connect(
            self._resume_free_activity
        )

        pause_action = QAction("暂停活动", self)
        pause_action.setCheckable(True)
        pause_action.toggled.connect(self._set_paused)
        display_menu.addAction(pause_action)

        privacy_menu = self.menu.addMenu("🔒 隐私与联网")
        privacy_note = privacy_menu.addAction("输入只统计频率，不记录按键内容")
        privacy_note.setEnabled(False)
        network_note = privacy_menu.addAction("活动信息仅本地判断，不上传")
        network_note.setEnabled(False)
        privacy_menu.addSeparator()
        privacy_menu.addAction("刷新天气与推荐缓存").triggered.connect(
            self._refresh_network_context
        )

        self.menu.addSeparator()
        self.show_action = self.menu.addAction("隐藏桌宠")
        self.show_action.triggered.connect(self.toggle_visibility)
        self.menu.addSeparator()
        self.menu.addAction("退出").triggered.connect(self.quit_app)

        tray.setContextMenu(self.menu)
        tray.activated.connect(self._tray_activated)
        self.menu.aboutToShow.connect(self._on_menu_about_to_show)
        self.menu.aboutToHide.connect(self._on_menu_about_to_hide)
        self._update_recommendation_status()
        self._refresh_menu_status()
        tray.show()
        return tray

    def _tray_activated(self, reason) -> None:
        if reason == QSystemTrayIcon.ActivationReason.DoubleClick:
            self.toggle_visibility()

    def _on_menu_about_to_show(self) -> None:
        self._menu_restore_bubble = self.bubble.isVisible()
        self._menu_restore_task = self.task_panel.isVisible()
        self.bubble.hide()
        self.task_panel.hide()
        self._refresh_menu_status()

    def _on_menu_about_to_hide(self) -> None:
        if self._menu_restore_task and self.active_task is not None and self.isVisible():
            self.task_panel.show()
            self.task_panel.follow_anchor(self.geometry(), force=True)
            self.task_panel.raise_()
        if (
            self._menu_restore_bubble
            and self.bubble.hide_timer.isActive()
            and self.active_task is None
            and self.isVisible()
        ):
            self.bubble.show()
            self.bubble.follow_anchor(self.geometry(), force=True)
            self.bubble.raise_()
        self._menu_restore_bubble = False
        self._menu_restore_task = False

    def _refresh_menu_status(self) -> None:
        if not hasattr(self, "status_action"):
            return
        mode_labels = {
            "daily": "日常陪伴",
            "sweet": "甜蜜互动",
            "quiet": "安静陪伴",
            "sleep": "打盹休息",
        }
        if self.typing_session_active:
            detail = f"专注避让 · {self.typing_snapshot.keys_per_minute} 次/分"
        elif self.manual_rest_until > time.monotonic():
            remaining = max(1, round((self.manual_rest_until - time.monotonic()) / 60))
            detail = f"原地休息 · 约 {remaining} 分钟"
        else:
            detail = mode_labels.get(self.mode, "日常陪伴")
        layer = "置顶" if self.display_mode == "always_on_top" else "仅桌面"
        self.status_action.setText(f"状态：{detail} · {layer}")
        self._update_weather_status()
        self._update_recommendation_status()

    def _apply_native_layer(self, widget: QWidget, on_top: bool) -> None:
        if sys.platform != "win32" or not widget.isVisible():
            return
        insert_after = -1 if on_top else 1  # HWND_TOPMOST / HWND_BOTTOM
        flags = 0x0001 | 0x0002 | 0x0010 | 0x0040  # no size/move/activate + show
        ctypes.windll.user32.SetWindowPos(
            int(widget.winId()), insert_after, 0, 0, 0, 0, flags
        )

    def _apply_display_mode(self, *, initial: bool = False) -> None:
        on_top = self.display_mode == "always_on_top"
        for widget in (self, self.bubble, self.task_panel):
            was_visible = widget.isVisible()
            widget.setWindowFlag(Qt.WindowStaysOnTopHint, on_top)
            widget.setWindowFlag(Qt.WindowStaysOnBottomHint, not on_top)
            if was_visible:
                widget.show()
            QTimer.singleShot(
                80, lambda target=widget, top=on_top: self._apply_native_layer(target, top)
            )
        if not initial and self.isVisible():
            self.show()

    def _set_display_mode(self, mode: str) -> None:
        if mode not in {"always_on_top", "desktop_only"}:
            return
        self.display_mode = mode
        self.settings.setValue("display_mode", mode)
        self._apply_display_mode()
        if mode in self.display_actions:
            self.display_actions[mode].setChecked(True)
        self._say_event(
            "display_always" if mode == "always_on_top" else "display_desktop",
            force=True,
        )
        self._refresh_menu_status()

    def _set_manual_rest_minutes(self, minutes: int) -> None:
        if minutes not in {2, 5, 10, 20}:
            return
        self.manual_rest_minutes = minutes
        self.settings.setValue("manual_rest_minutes", minutes)
        self._say(f"手动摆放后会原地休息 {minutes} 分钟。", 2600)

    def _resume_free_activity(self) -> None:
        self.manual_rest_until = 0.0
        if self.state == "manual_rest":
            self.place_on_floor()
            self._enter_state("idle")
        self._say_event("resume", force=True, duration_ms=2400)

    def _set_recommendations_enabled(self, enabled: bool) -> None:
        self.recommendations_enabled = enabled
        self.settings.setValue("recommendations_enabled", enabled)
        self.recommendations.set_enabled(enabled)
        if enabled:
            self.recommendations.refresh_async(force=True)
            self.next_recommendation_at = time.monotonic() + random.uniform(180, 360)
        self._say_event(
            "recommendations_enabled" if enabled else "recommendations_disabled",
            force=True,
            duration_ms=4100,
        )
        self._update_recommendation_status()

    def _update_recommendation_status(self) -> None:
        if hasattr(self, "recommendation_status_action"):
            self.recommendation_status_action.setText(
                f"推荐缓存：{self.recommendations.status_text()}"
            )

    def _refresh_network_context(self) -> None:
        self.weather_context.refresh_async(force=True)
        self.recommendations.refresh_async(force=True)
        self._say("正在后台刷新天气和猜你喜欢，不会上传你的活动信息。", 3400)
        self._update_recommendation_status()

    def _set_dialogue_density(self, density: str, label: str) -> None:
        self.dialogue.set_density(density)
        self.settings.setValue("dialogue_density", density)
        message = "台词已经关闭，我会安静陪你。" if density == "off" else f"台词频率：{label}。"
        self._say(message, 2400)

    def _set_tasks_enabled(self, enabled: bool) -> None:
        self.tasks_enabled = enabled
        self.settings.setValue("tasks_enabled", enabled)
        if not enabled:
            self._cancel_active_task()
        else:
            self.next_task_at = time.monotonic() + self._task_interval(initial=True)
        self._say_event("tasks_enabled" if enabled else "tasks_disabled", force=True)

    def _set_task_frequency(self, frequency: str, label: str) -> None:
        self.task_frequency = frequency
        self.settings.setValue("task_frequency", frequency)
        self.next_task_at = time.monotonic() + self._task_interval(initial=True)
        self._say(f"甜蜜任务频率：{label}。", 2500)

    def _set_weather_mode(self, mode: str, label: str) -> None:
        self.weather_context.set_mode(mode)
        self.settings.setValue("weather_mode", mode)
        if mode == "auto":
            self.weather_context.refresh_async(force=True)
            message = "天气情境已改为自动识别。"
        else:
            message = f"天气情境已设为：{label}。"
        self._update_weather_status()
        self._say(message, 2600)

    def _update_weather_status(self) -> None:
        if not hasattr(self, "weather_status_action"):
            return
        snapshot = self.weather_context.snapshot()
        suffix = (
            f" · {snapshot.temperature_c}℃"
            if snapshot.temperature_c is not None
            else ""
        )
        mode = "自动" if self.weather_context.mode == "auto" else "手动"
        self.weather_status_action.setText(
            f"当前：{snapshot.weather_label}{suffix} · {mode}"
        )

    def _set_window_roaming(self, enabled: bool) -> None:
        self.window_roaming_enabled = enabled
        self.settings.setValue("window_roaming", enabled)
        if not enabled and self.state in WINDOW_STATES:
            self._leave_window("window_roaming_disabled")
        self._say_event(
            "window_roaming_enabled" if enabled else "window_roaming_disabled",
            force=True,
        )

    def _set_proactive_interactions(self, enabled: bool) -> None:
        self.proactive_enabled = enabled
        self.settings.setValue("proactive_interactions", enabled)
        self.next_proactive_at = time.monotonic() + (
            random.uniform(14.0, 24.0) if enabled else 3600.0
        )
        self._say_event("proactive_enabled" if enabled else "proactive_disabled", force=True)

    def _set_paused(self, paused: bool) -> None:
        self.paused = paused
        if paused:
            self._cancel_active_task()
        self._say_event("pause" if paused else "resume", force=True)
        if not paused:
            self._enter_state("sleep" if self.mode == "sleep" else "idle")

    def _play_manual_action(self, animation: str, event: str) -> None:
        if self.paused or self.active_task is not None:
            return
        self.target_surface = None
        self.place_on_floor()
        self._enter_state(animation, RESPONSE_DURATIONS.get(animation, 2.8))
        self._say_event(event, force=True, duration_ms=3300)

    def toggle_visibility(self) -> None:
        if self.isVisible():
            self.hide()
            self.bubble.hide()
            self.task_panel.hide()
            self.show_action.setText("显示桌宠")
        else:
            self.show()
            self.raise_()
            if self.active_task is not None:
                self.task_panel.show()
                self.task_panel.follow_anchor(self.geometry(), force=True)
            self.show_action.setText("隐藏桌宠")

    def quit_app(self) -> None:
        self.allow_exit = True
        self.tray.hide()
        self.bubble.close()
        self.task_panel.close()
        self.close()
        QApplication.quit()

    def closeEvent(self, event) -> None:
        if self.allow_exit:
            event.accept()
        else:
            event.ignore()
            self.toggle_visibility()

    def contextMenuEvent(self, event) -> None:
        self.menu.popup(event.globalPos())

    def current_screen_geometry(self) -> QRect:
        screen = QApplication.screenAt(self.geometry().center()) or QApplication.primaryScreen()
        return screen.availableGeometry()

    def floor_y(self) -> int:
        return self.current_screen_geometry().bottom() - self.height() + 1

    def place_on_floor(self) -> None:
        bounds = self.current_screen_geometry()
        x = int(clamp(self.x(), bounds.left(), bounds.right() - self.width() + 1))
        self.move(x, self.floor_y())

    def _set_animation(self, name: str) -> None:
        if name not in self.animations:
            name = "idle"
        self.animation_name = name
        self.animation_started = time.monotonic()
        self.animation_frame_index = -1
        self.animation_render_index = -1
        self._advance_animation(self.animation_started)

    def _advance_animation(self, now: float) -> None:
        # Gaze frames are selected from the cursor direction.  Advancing that
        # sprite sheet as a time-based loop paints an unrelated direction for
        # one render pass before cursor tracking corrects it, which appears as
        # a flash on translucent Windows surfaces.
        if self.animation_name == "gaze" and self.gaze_active:
            return
        frames = self.animations[self.animation_name]
        spec = self.animation_specs[self.animation_name]
        multiplier = self.animation_fps_multiplier
        render_fps = float(spec["fps"]) * multiplier
        render_count = len(frames) * multiplier
        raw_index = int((now - self.animation_started) * render_fps)
        render_index = (
            raw_index % render_count
            if spec.get("loop", True)
            else min(raw_index, render_count - 1)
        )
        if render_index != self.animation_render_index:
            self.animation_render_index = render_index
            # Never alpha-crossfade two complete character sprites on a
            # translucent desktop window: their different silhouettes become
            # a visible double image.  At the extra render step, select the
            # nearest real pose so exactly one opaque character is shown.
            source_index = (render_index + multiplier // 2) // multiplier
            if spec.get("loop", True):
                source_index %= len(frames)
            else:
                source_index = min(source_index, len(frames) - 1)
            if source_index != self.animation_frame_index:
                self.animation_frame_index = source_index
                self._apply_animation_frame()

    def _apply_animation_frame(self) -> None:
        frames = self.animations[self.animation_name]
        index = int(clamp(self.animation_frame_index, 0, len(frames) - 1))
        pixmap = frames[index].scaled(
            self.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation
        )
        if self.direction < 0 and self.animation_name not in NON_MIRRORED_ANIMATIONS:
            pixmap = pixmap.transformed(QTransform().scale(-1, 1))
        self._display_pixmap = pixmap
        self.update()

    def _show_gaze_frame(self, index: int) -> None:
        frames = self.animations["gaze"]
        index = int(clamp(index, 0, len(frames) - 1))
        if self.gaze_frame_index == index and self.animation_name == "gaze":
            return
        self.gaze_frame_index = index
        self.animation_name = "gaze"
        self.animation_frame_index = index
        self.animation_render_index = index * self.animation_fps_multiplier
        self.gaze_last_change_at = time.monotonic()
        self._display_pixmap = frames[index].scaled(
            self.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation
        )
        self.update()

    def _enter_state(
        self,
        state: str,
        duration: float | None = None,
        *,
        animation: str | None = None,
    ) -> None:
        now = time.monotonic()
        self.state = state
        self.state_started = now
        self.state_until = now + duration if duration is not None else None
        self.gaze_active = False
        self.gaze_candidate_index = -1
        self._set_animation(animation or state)
        if state == "idle":
            self.idle_deadline = now + random.uniform(4.5, 9.5)

    def _mark_mode(self, mode: str) -> None:
        if mode in self.mode_actions:
            self.mode_actions[mode].setChecked(True)

    def set_mode(self, mode: str) -> None:
        self._cancel_active_task()
        self.target_surface = None
        self.mode = mode
        self._mark_mode(mode)
        if mode == "sleep":
            self.place_on_floor()
            self._enter_state("sleep")
        elif mode == "sweet":
            self.place_on_floor()
            self._enter_state("happy", 2.5)
        else:
            self.place_on_floor()
            self._enter_state("idle")
        self.next_task_at = time.monotonic() + self._task_interval(initial=True)
        self._schedule_next_proactive(time.monotonic())
        self._say_event(f"mode_{mode}", force=True, duration_ms=3000)

    def _say(self, text: str, duration_ms: int = 3200) -> None:
        if not text or self.active_task is not None:
            return
        self.bubble.show_message(text, self.geometry(), duration_ms)

    def _say_event(
        self,
        event: str,
        *,
        context: dict | None = None,
        force: bool = False,
        duration_ms: int = 3200,
        moment: datetime | None = None,
    ) -> None:
        text = self.dialogue.choose(
            event, context=context, force=force, moment=moment
        )
        if text:
            self._say(text, duration_ms)

    def _refresh_activity_context(
        self, now: float | None = None, *, force: bool = False
    ) -> ForegroundActivity:
        now = time.monotonic() if now is None else now
        if force or now >= self.activity_refresh_at:
            self.activity_refresh_at = now + 1.0
            self.foreground_activity = self.window_tracker.foreground_activity()
        return self.foreground_activity

    def _activity_values(self) -> dict[str, object]:
        activity = self.foreground_activity
        app_label = Path(activity.process_name).stem or "当前应用"
        return {
            "app": app_label[:24],
            "window": activity.title[:28] or "当前窗口",
            "activity_label": activity.label,
            "typing_rate": self.typing_snapshot.keys_per_minute,
        }

    def _work_focus_active(self, now: float | None = None) -> bool:
        self._refresh_activity_context(now)
        return self.typing_session_active or self.foreground_activity.category == "meeting"

    def _begin_typing_retreat(self) -> None:
        if self.dragging or self.manual_rest_until > time.monotonic():
            return
        self._cancel_active_task()
        self.target_surface = None
        bounds = self.current_screen_geometry()
        left_x = bounds.left()
        right_x = bounds.right() - self.width() + 1
        self.typing_target_x = (
            left_x
            if abs(self.x() - left_x) <= abs(self.x() - right_x)
            else right_x
        )
        self.direction = 1 if self.typing_target_x >= self.x() else -1
        self.speed = 165.0
        self.move(self.x(), self.floor_y())
        self._enter_state("typing_retreat", animation="walk")

    def _update_typing_retreat(self, dt: float) -> None:
        distance = self.typing_target_x - self.x()
        if abs(distance) <= 3:
            self.move(round(self.typing_target_x), self.floor_y())
            self._enter_state(
                "typing_companion", animation=random.choice(("read", "idle"))
            )
            return
        self.direction = 1 if distance > 0 else -1
        step = min(abs(distance), self.speed * dt)
        self.move(round(self.x() + self.direction * step), self.floor_y())

    def _handle_input_activity(self, now: float) -> None:
        self.typing_snapshot = self.input_monitor.sample(now)
        if self.typing_snapshot.active:
            self.typing_hold_until = now + 22.0
            if not self.typing_session_active:
                self.typing_session_active = True
                self._begin_typing_retreat()
            return
        if self.typing_session_active and now >= self.typing_hold_until:
            self.typing_session_active = False
            if self.state in {"typing_retreat", "typing_companion"}:
                self._enter_state("idle")
                self.idle_deadline = now + random.uniform(7.0, 11.0)
                self._say_event("typing_finished", duration_ms=3000)

    def _clamp_visible_position(self) -> None:
        screen = QApplication.screenAt(self.geometry().center()) or QApplication.primaryScreen()
        bounds = screen.availableGeometry()
        x = int(clamp(self.x(), bounds.left(), bounds.right() - self.width() + 1))
        y = int(clamp(self.y(), bounds.top(), bounds.bottom() - self.height() + 1))
        self.move(x, y)

    def _begin_manual_rest(self) -> None:
        self.target_surface = None
        self._clamp_visible_position()
        self.manual_rest_until = time.monotonic() + self.manual_rest_minutes * 60.0
        self._enter_state("manual_rest", animation=random.choice(("idle", "read")))
        self._say_event("drag_rest", force=True, duration_ms=3200)
        self._refresh_menu_status()

    def start_move(self, force_run: bool | None = None) -> None:
        if (
            self.paused
            or self.active_task is not None
            or self.mode in {"sleep", "quiet"}
            or self.manual_rest_until > time.monotonic()
            or self._work_focus_active()
        ):
            return
        run_chance = 0.62 if self.mode == "sweet" else 0.28
        running = random.random() < run_chance if force_run is None else force_run
        state = "run" if running else "walk"
        self.direction = random.choice((-1, 1))
        self.speed = random.uniform(120.0, 170.0) if running else random.uniform(55.0, 88.0)
        self._enter_state(state, random.uniform(3.2, 6.8))

    def _start_qa_walk(self) -> None:
        self.direction = 1
        self.speed = 72.0
        self._enter_state("walk", 12.0)

    def _start_qa_animation(self) -> None:
        if QA_ANIMATION not in self.animations:
            return
        self.direction = 1
        self.speed = 0.0
        self._enter_state(
            f"qa_{QA_ANIMATION}", 30.0, animation=QA_ANIMATION
        )
        bounds = self.current_screen_geometry()
        self.move(bounds.left() + 48, bounds.top() + 48)

    def start_jump(
        self,
        target: QPoint | None = None,
        *,
        high: bool = False,
        line: str | None = None,
    ) -> None:
        if self.paused:
            return
        bounds = self.current_screen_geometry()
        target = target or self.geometry().center()
        end_x = int(
            clamp(
                target.x() - self.width() // 2,
                bounds.left(),
                bounds.right() - self.width() + 1,
            )
        )
        self.jump_start = self.pos()
        self.jump_end = QPoint(end_x, self.floor_y())
        self.jump_height = 205.0 if high else 125.0
        self.jump_duration = 1.05 if high else 0.86
        self.jump_kind = "high" if high else "normal"
        self.direction = 1 if end_x >= self.x() else -1
        self._enter_state("jump", self.jump_duration)
        if line:
            QTimer.singleShot(50, lambda value=line: self._say(value, 3300))

    def start_climb(self, speak: bool = True) -> None:
        if self.paused or self.active_task is not None:
            return
        self.target_surface = None
        bounds = self.current_screen_geometry()
        x = int(clamp(self.x(), bounds.left(), bounds.right() - self.width() + 1))
        self.climb_start_pos = self.pos()
        self.climb_end_pos = QPoint(x, bounds.top() - 10)
        distance = abs(self.climb_end_pos.y() - self.climb_start_pos.y())
        self.climb_duration = clamp(distance / 420.0, 1.25, 2.45)
        self._enter_state("climb", self.climb_duration)
        if speak:
            self._say_event("climb_start", force=True, duration_ms=2400)

    def _surface_y(self, surface: WindowSurface) -> int:
        return surface.top - self.height() + 17

    def _surface_x_bounds(self, surface: WindowSurface) -> tuple[int, int]:
        minimum = surface.left
        maximum = max(minimum, surface.right - self.width() + 1)
        return minimum, maximum

    def _refresh_target_surface(self) -> WindowSurface | None:
        if self.target_surface is None:
            return None
        surfaces = self.window_tracker.list_surfaces(
            exclude_hwnd=int(self.winId()), screen_rect=self.current_screen_geometry()
        )
        refreshed = next(
            (surface for surface in surfaces if surface.hwnd == self.target_surface.hwnd),
            None,
        )
        self.target_surface = refreshed
        return refreshed

    def start_window_expedition(self, force_speak: bool = False) -> bool:
        if (
            self.paused
            or self.active_task is not None
            or not self.window_roaming_enabled
            or self.mode in {"sleep", "quiet"}
            or self.state in {"drag", "climb", "climb_window", "angry", "stomp"}
            or self.manual_rest_until > time.monotonic()
            or self._work_focus_active()
        ):
            if force_speak and not self.window_roaming_enabled:
                self._say_event("window_roaming_disabled", force=True)
            return False
        bounds = self.current_screen_geometry()
        surfaces = self.window_tracker.list_surfaces(
            exclude_hwnd=int(self.winId()), screen_rect=bounds
        )
        target = choose_surface(surfaces, self.geometry().center(), random_jitter=120.0)
        if target is None:
            if force_speak:
                self._say_event("window_none", force=True)
            return False
        self.target_surface = target
        minimum_x, maximum_x = self._surface_x_bounds(target)
        self.window_target_x = clamp(self.x(), minimum_x, maximum_x)
        self.direction = 1 if self.window_target_x >= self.x() else -1
        self.speed = random.uniform(112.0, 158.0)
        self.window_refresh_at = 0.0
        self._enter_state("approach_window", 18.0, animation="walk")
        self._say_event(
            "window_choose",
            context={"window": target.title[:18]},
            force=force_speak,
            duration_ms=3200,
        )
        return True

    def _window_surface_near(self, point: QPoint) -> WindowSurface | None:
        surfaces = self.window_tracker.list_surfaces(
            exclude_hwnd=int(self.winId()), screen_rect=self.current_screen_geometry()
        )
        nearby: list[tuple[float, WindowSurface]] = []
        for surface in surfaces:
            rect = surface.rect
            within_x = rect.left() - 70 <= point.x() <= rect.right() + 70
            within_y = rect.top() - 80 <= point.y() <= rect.bottom() + 40
            top_distance = abs(point.y() - rect.top()) if within_x else 9999
            side_distance = (
                min(abs(point.x() - rect.left()), abs(point.x() - rect.right()))
                if within_y
                else 9999
            )
            distance = min(top_distance, side_distance)
            if distance <= 80:
                nearby.append((distance, surface))
        return min(nearby, key=lambda item: item[0])[1] if nearby else None

    def _begin_window_climb(self) -> None:
        surface = self._refresh_target_surface()
        if surface is None:
            self._leave_window("window_lost")
            return
        minimum_x, maximum_x = self._surface_x_bounds(surface)
        target_x = int(clamp(self.x(), minimum_x, maximum_x))
        self.climb_start_pos = self.pos()
        self.climb_end_pos = QPoint(target_x, self._surface_y(surface))
        distance = abs(self.climb_end_pos.y() - self.climb_start_pos.y())
        self.climb_duration = clamp(distance / 290.0, 1.15, 2.65)
        self._enter_state("climb_window", self.climb_duration, animation="climb")
        self._say_event(
            "window_climb",
            context={"window": surface.title[:18]},
            duration_ms=2500,
        )

    def _start_window_walk(self) -> None:
        surface = self._refresh_target_surface()
        if surface is None:
            self._leave_window("window_lost")
            return
        minimum_x, maximum_x = self._surface_x_bounds(surface)
        self.window_walk_min_x = float(minimum_x)
        self.window_walk_max_x = float(maximum_x)
        self.direction = random.choice((-1, 1))
        self.speed = random.uniform(62.0, 96.0)
        self._enter_state("walk_window", random.uniform(5.5, 8.5), animation="walk")
        self._say_event(
            "window_walk",
            context={"window": surface.title[:18]},
            duration_ms=2600,
        )

    def _leave_window(self, event: str = "window_exit") -> None:
        was_above_floor = self.y() < self.floor_y() - 36
        self.target_surface = None
        if not was_above_floor:
            self.place_on_floor()
            self._enter_state("idle")
            self._say_event(event, duration_ms=2200)
            return
        bounds = self.current_screen_geometry()
        self.jump_start = self.pos()
        self.jump_end = QPoint(
            int(clamp(self.x(), bounds.left(), bounds.right() - self.width() + 1)),
            self.floor_y(),
        )
        self.jump_height = 90.0
        self.jump_duration = 0.8
        self.jump_kind = "window_drop"
        self._enter_state("jump", self.jump_duration)
        self._say_event(event, duration_ms=2200)

    def _update_climb(self, now: float) -> None:
        progress = (now - self.state_started) / max(0.01, self.climb_duration)
        eased = ease_out_cubic(progress)
        x = self.climb_start_pos.x() + (self.climb_end_pos.x() - self.climb_start_pos.x()) * eased
        y = self.climb_start_pos.y() + (self.climb_end_pos.y() - self.climb_start_pos.y()) * eased
        self.move(round(x), round(y))

    def _update_window_behavior(self, dt: float, now: float) -> None:
        if now >= self.window_refresh_at:
            self.window_refresh_at = now + 0.35
            surface = self._refresh_target_surface()
            if surface is None:
                self._leave_window("window_lost")
                return
        else:
            surface = self.target_surface
        if surface is None:
            return
        minimum_x, maximum_x = self._surface_x_bounds(surface)
        surface_y = self._surface_y(surface)
        if self.state == "approach_window":
            self.window_target_x = clamp(self.window_target_x, minimum_x, maximum_x)
            distance = self.window_target_x - self.x()
            if abs(distance) <= 5:
                self.move(round(self.window_target_x), self.floor_y())
                self._begin_window_climb()
                return
            self.direction = 1 if distance > 0 else -1
            step = min(abs(distance), self.speed * dt)
            self.move(round(self.x() + self.direction * step), self.floor_y())
        elif self.state == "climb_window":
            end_x = int(clamp(self.climb_end_pos.x(), minimum_x, maximum_x))
            self.climb_end_pos = QPoint(end_x, surface_y)
            self._update_climb(now)
        elif self.state == "perch_window":
            self.move(int(clamp(self.x(), minimum_x, maximum_x)), surface_y)
        elif self.state == "walk_window":
            new_x = self.x() + self.direction * self.speed * dt
            if new_x <= minimum_x or new_x >= maximum_x:
                new_x = clamp(new_x, minimum_x, maximum_x)
                self.direction *= -1
                self._apply_animation_frame()
                self._say_event("window_edge", duration_ms=1800)
            self.move(round(new_x), surface_y)

    def _schedule_next_proactive(self, now: float) -> None:
        ranges = {
            "off": (38.0, 58.0),
            "quiet": (32.0, 48.0),
            "standard": (20.0, 34.0),
            "lively": (12.0, 22.0),
        }
        low, high = ranges.get(self.dialogue.density, ranges["standard"])
        if self.mode == "sweet":
            low, high = low * 0.65, high * 0.72
        self.next_proactive_at = now + random.uniform(low, high)

    def _run_proactive_interaction(self, now: float) -> None:
        self._schedule_next_proactive(now)
        if (
            not self.proactive_enabled
            or self.active_task is not None
            or self.state != "idle"
            or self.mode in {"sleep", "quiet"}
            or self.dragging
            or self.manual_rest_until > now
            or self._work_focus_active(now)
        ):
            return
        activity = self._refresh_activity_context(now)
        if random.random() < 0.34:
            activity_animation = {
                "coding": "read",
                "writing": "read",
                "spreadsheet": "gaze",
                "browsing": "gaze",
                "music": "happy",
                "design": "photo_pose",
                "gaming": "stretch",
                "reading": "read",
                "general": "wave",
            }.get(activity.category, "wave")
            self._enter_state(
                activity_animation,
                RESPONSE_DURATIONS.get(activity_animation, 2.8),
            )
            self._say_event(
                f"activity_{activity.category}",
                context=self._activity_values(),
                duration_ms=3600,
            )
            return
        snapshot = self.weather_context.snapshot()
        contextual_actions: list[tuple[str, str]] = []
        if snapshot.weather == "rain":
            contextual_actions.extend((("umbrella", "weather_rain"),) * 3)
        elif snapshot.weather in {"cold", "snow"}:
            contextual_actions.extend((("sip", f"weather_{snapshot.weather}"),) * 3)
        elif snapshot.weather == "hot":
            contextual_actions.extend((("sip", "weather_hot"),) * 2)
        elif snapshot.weather == "cloudy":
            contextual_actions.extend((("read", "weather_cloudy"),) * 2)
        else:
            contextual_actions.append(("wave", "weather_sunny"))
        if snapshot.part in {"evening", "night"}:
            contextual_actions.extend((("read", "contextual_read"),) * 2)
        else:
            contextual_actions.append(("stretch", "contextual_stretch"))
        if random.random() < 0.42:
            animation, event = random.choice(contextual_actions)
            self._enter_state(animation, RESPONSE_DURATIONS[animation])
            self._say_event(
                event,
                context={
                    "weather": snapshot.weather_label,
                    "temperature": snapshot.temperature_c
                    if snapshot.temperature_c is not None
                    else "",
                },
                duration_ms=3300,
            )
            return
        roll = random.random()
        if self.window_roaming_enabled and roll < 0.18 and self.start_window_expedition():
            return
        if roll < 0.43:
            self._enter_state("wave", 2.7)
            event = random.choice(("attention_call", "daily_question", "small_talk"))
            self._say_event(event, duration_ms=3200)
        elif roll < 0.66:
            self._enter_state("heart", 2.8)
            event = random.choice(("heart", "affection", "memory"))
            self._say_event(event, duration_ms=3100)
        elif roll < 0.84:
            self._enter_state("happy", 2.5)
            event = random.choice(("compliment", "encouragement", "work_companion", "food_chat"))
            self._say_event(event, duration_ms=3100)
        else:
            self.start_jump(high=self.mode == "sweet")
            event = random.choice(("active_jump", "date_idea", "random_tease"))
            self._say_event(event, duration_ms=2800)

    def _update_cursor_gaze(self, now: float | None = None) -> None:
        if self.state not in {"idle", "perch", "perch_window"}:
            return
        now = time.monotonic() if now is None else now
        if self.cursor_over_pet or now < self.gaze_resume_after:
            self.gaze_candidate_index = -1
            return
        center = self.geometry().center()
        cursor = QCursor.pos()
        dx, dy = cursor.x() - center.x(), cursor.y() - center.y()
        distance_squared = dx * dx + dy * dy
        if self.gaze_active:
            if distance_squared > 780 * 780:
                self.gaze_active = False
                self.gaze_candidate_index = -1
                self._set_animation("perch" if self.state != "idle" else "idle")
                return
        elif distance_squared <= 620 * 620:
            self.gaze_active = True
            self.gaze_candidate_index = -1
        else:
            return
        if dy < -105:
            index = 5
        elif dx < -210:
            index = 0
        elif dx < -55:
            index = 1
        elif dx <= 55:
            index = 2
        elif dx <= 210:
            index = 3
        else:
            index = 4
        if index != self.gaze_candidate_index:
            self.gaze_candidate_index = index
            self.gaze_candidate_since = now
            return
        if (
            now - self.gaze_candidate_since >= 0.18
            and now - self.gaze_last_change_at >= 0.28
        ):
            self._show_gaze_frame(index)

    def _task_interval(self, *, initial: bool = False) -> float:
        if QA_TASK:
            return 1.0 if initial else 12.0
        ranges = {
            "low": (180.0, 300.0),
            "standard": (60.0, 105.0),
            "frequent": (25.0, 48.0),
        }
        low, high = ranges[self.task_frequency]
        if initial:
            initial_ranges = {
                "low": (30.0, 44.0),
                "standard": (15.0, 26.0),
                "frequent": (8.0, 14.0),
            }
            low, high = initial_ranges[self.task_frequency]
        if self.mode == "sweet":
            low, high = low * 0.62, high * 0.72
        return random.uniform(low, high)

    def _update_stats_text(self) -> None:
        if hasattr(self, "stats_action"):
            self.stats_action.setText(
                f"互动记录：开心 {self.task_successes} · 选错 {self.task_failures} · 错过 {self.task_misses} · 推荐打开 {self.recommendation_opens}"
            )

    def _save_task_stats(self) -> None:
        self.settings.setValue("task_successes", self.task_successes)
        self.settings.setValue("task_failures", self.task_failures)
        self.settings.setValue("task_misses", self.task_misses)
        self._update_stats_text()

    def _cancel_active_task(self) -> None:
        if self.active_task is None:
            return
        self.active_task = None
        self.task_panel.close_task()
        if self.state == "wait":
            self._enter_state("idle")

    def start_sweet_task(
        self,
        force: bool = False,
        task: SweetTask | None = None,
    ) -> bool:
        if self.paused or self.active_task is not None:
            return False
        if not force and (
            (not self.tasks_enabled and (task is None or task.task_type != "recommendation"))
            or self.mode in {"sleep", "quiet"}
            or self.state not in {"idle", "perch"}
            or self.dragging
            or self.manual_rest_until > time.monotonic()
            or self._work_focus_active()
        ):
            return False
        if force and self.state in WINDOW_STATES:
            self.target_surface = None
            self.place_on_floor()
        if force and self.mode == "sleep":
            self.mode = "daily"
            self._mark_mode("daily")
            self.place_on_floor()
        snapshot = self.weather_context.snapshot()
        activity = self._refresh_activity_context(force=True)
        task = task or choose_task(
            exclude_key=self.last_task_key,
            part=snapshot.part,
            weather=snapshot.weather,
            weekend=snapshot.weekend,
            activity=activity.category,
            context=self._activity_values(),
        )
        self.active_task = task
        self.last_task_key = task.key
        self.bubble.hide()
        self.target_surface = None
        self._enter_state("wait")
        self.task_panel.show_task(
            task,
            self.geometry(),
            self._resolve_task_choice,
            self._resolve_task_timeout,
        )
        return True

    def start_recommendation_task(self, force: bool = False) -> bool:
        if not self.recommendations_enabled:
            if force:
                self._say_event("recommendations_disabled", force=True)
            return False
        if not force and (
            self.active_task is not None
            or self.state != "idle"
            or self.mode in {"sleep", "quiet"}
            or self.manual_rest_until > time.monotonic()
            or self._work_focus_active()
        ):
            return False
        news = self.recommendations.choose(
            "news", exclude_urls=self.recent_recommendation_urls
        )
        music = self.recommendations.choose(
            "music", exclude_urls=self.recent_recommendation_urls
        )
        if news is None or music is None:
            self.recommendations.refresh_async(force=True)
            if force:
                self._say_event("recommendation_empty", force=True)
            self.next_recommendation_at = time.monotonic() + 150.0
            self._update_recommendation_status()
            return False

        def short(text: str, limit: int = 24) -> str:
            return text if len(text) <= limit else f"{text[: limit - 1]}…"

        music_detail = f"{music.title} · {music.subtitle}" if music.subtitle else music.title
        task = SweetTask(
            key=f"recommend_{int(time.time())}",
            prompt="刚从公开热门榜单带回两条新发现，想先看看哪一个？",
            choices=(
                TaskChoice(
                    f"📰 {short(news.title)}",
                    action_url=news.url,
                    action_kind="news",
                ),
                TaskChoice(
                    f"🎵 {short(music_detail)}",
                    action_url=music.url,
                    action_kind="music",
                ),
                TaskChoice("先不看，继续专注", action_kind="dismiss"),
            ),
            success_animation="photo_pose",
            success_line="找到一条你可能会喜欢的新鲜事。",
            wrong_animation="pout",
            wrong_line="这次先跳过也没关系。",
            timeout_animation="read",
            timeout_line="你在忙，我把推荐收起来，晚点再说。",
            duration_seconds=14.0,
            scene="猜你喜欢",
            task_type="recommendation",
        )
        started = self.start_sweet_task(force=force, task=task)
        if started:
            self.next_recommendation_at = time.monotonic() + random.uniform(1500, 2700)
        return started

    def _resolve_task_choice(self, choice: TaskChoice) -> None:
        task = self.active_task
        if task is None:
            return
        self.active_task = None
        if task.task_type == "recommendation":
            if choice.action_kind in {"news", "music"} and choice.action_url:
                opened = QDesktopServices.openUrl(QUrl(choice.action_url))
                if opened:
                    self.recent_recommendation_urls.append(choice.action_url)
                    self.recent_recommendation_urls = self.recent_recommendation_urls[-12:]
                    self.recommendation_opens += 1
                    self.settings.setValue(
                        "recommendation_opens", self.recommendation_opens
                    )
                    animation = "photo_pose" if choice.action_kind == "news" else "happy"
                    self._enter_state(animation, RESPONSE_DURATIONS.get(animation, 3.0))
                    QTimer.singleShot(
                        40,
                        lambda kind=choice.action_kind: self._say_event(
                            f"recommendation_open_{kind}", force=True, duration_ms=3800
                        ),
                    )
                else:
                    self._play_response("pout", "页面没有成功打开，稍后再试一次吧。")
            else:
                self._enter_state("read", 2.8)
                QTimer.singleShot(
                    40,
                    lambda: self._say_event(
                        "recommendation_skip", force=True, duration_ms=3000
                    ),
                )
            self.next_recommendation_at = time.monotonic() + random.uniform(1500, 2700)
            self._update_stats_text()
            return
        if choice.correct:
            self.task_successes += 1
            self._play_response(task.success_animation, task.success_line)
        else:
            self.task_failures += 1
            self._play_response(task.wrong_animation, task.wrong_line)
        self._save_task_stats()
        self.next_task_at = time.monotonic() + self._task_interval()

    def _resolve_task_timeout(self) -> None:
        task = self.active_task
        if task is None:
            return
        self.active_task = None
        if task.task_type == "recommendation":
            self._play_response("read", task.timeout_line)
            self.next_recommendation_at = time.monotonic() + random.uniform(1200, 2100)
            return
        self.task_misses += 1
        self._save_task_stats()
        self._play_response(task.timeout_animation, task.timeout_line)
        self.next_task_at = time.monotonic() + self._task_interval()

    def _play_response(self, animation: str, line: str) -> None:
        if animation == "jump":
            self.start_jump(high=True)
        else:
            self._enter_state(animation, RESPONSE_DURATIONS.get(animation, 2.8))
        QTimer.singleShot(40, lambda value=line: self._say(value, 3800))

    def _start_annoyed(self) -> None:
        animation = random.choice(("angry", "stomp"))
        self._enter_state(animation, RESPONSE_DURATIONS[animation])
        self._say_event("rapid_click", force=True, duration_ms=3000)

    def _tick(self) -> None:
        now = time.monotonic()
        if QA_WALK or QA_STAND:
            self._advance_animation(now)
            return
        dt = min(0.05, now - self.last_tick)
        self.last_tick = now
        self._handle_input_activity(now)
        self._refresh_activity_context(now)
        if not self.isVisible() or self.paused:
            return
        self._advance_animation(now)
        if self.dragging:
            return
        if self.manual_rest_until and now >= self.manual_rest_until:
            self.manual_rest_until = 0.0
            if self.state == "manual_rest":
                self.place_on_floor()
                self._enter_state("idle")
                self._say_event("manual_rest_done", duration_ms=2800)
        if self.state == "manual_rest":
            return
        if self.state == "jump":
            progress = (now - self.state_started) / max(0.01, self.jump_duration)
            eased = ease_out_cubic(progress)
            x = self.jump_start.x() + (self.jump_end.x() - self.jump_start.x()) * eased
            base_y = self.jump_start.y() + (self.jump_end.y() - self.jump_start.y()) * eased
            y = base_y - parabolic_arc(progress, self.jump_height)
            self.move(round(x), round(y))
        elif self.state == "climb":
            self._update_climb(now)
        elif self.state in WINDOW_STATES:
            self._update_window_behavior(dt, now)
        elif self.state in {"walk", "run"}:
            self._update_movement(dt)
        elif self.state == "typing_retreat":
            self._update_typing_retreat(dt)
        if self.state_until is not None and now >= self.state_until:
            self._finish_state()
        if self.state in {"idle", "perch", "perch_window"}:
            self._update_cursor_gaze(now)
        if self.state == "idle":
            if now >= self.next_proactive_at:
                self._run_proactive_interaction(now)
                return
            if now >= self.idle_deadline:
                if self.mode == "sweet" and random.random() < 0.55:
                    idle_event = random.choice(("affection", "daily_question", "random_tease", "date_idea"))
                elif random.random() < 0.38:
                    idle_event = random.choice(("idle", "small_talk", "work_companion", "encouragement"))
                else:
                    idle_event = "idle"
                self._say_event(idle_event, duration_ms=3000)
                if self.mode == "quiet":
                    self.idle_deadline = now + random.uniform(8.0, 14.0)
                elif random.random() < (0.34 if self.mode == "sweet" else 0.18):
                    animation = random.choice(("happy", "stretch", "read", "sip"))
                    event = {
                        "happy": "affection",
                        "stretch": "contextual_stretch",
                        "read": "contextual_read",
                        "sip": "contextual_sip",
                    }[animation]
                    self._enter_state(
                        animation, RESPONSE_DURATIONS.get(animation, 2.4)
                    )
                    self._say_event(event, duration_ms=2900)
                else:
                    self.start_move()

    def _update_movement(self, dt: float) -> None:
        bounds = self.current_screen_geometry()
        minimum_x = bounds.left()
        maximum_x = bounds.right() - self.width() + 1
        new_x = self.x() + self.direction * self.speed * dt
        if new_x <= minimum_x or new_x >= maximum_x:
            new_x = clamp(new_x, minimum_x, maximum_x)
            self.direction *= -1
            self._apply_animation_frame()
            self._say_event("edge", duration_ms=2000)
        self.move(round(new_x), self.floor_y())

    def _finish_state(self) -> None:
        state = self.state
        if self.manual_rest_until > time.monotonic() and state != "manual_rest":
            self._clamp_visible_position()
            self._enter_state("manual_rest", animation="read")
            return
        if self.typing_session_active and state not in {
            "typing_retreat",
            "typing_companion",
        }:
            self._begin_typing_retreat()
            return
        if state == "jump":
            self.place_on_floor()
            if self.jump_kind == "window_drop":
                self._say_event("window_land", duration_ms=1800)
            self.jump_kind = "normal"
            self._enter_state("idle")
        elif state == "climb":
            self._enter_state("perch", random.uniform(6.0, 9.0))
            self._say_event("perch", duration_ms=2600)
        elif state == "climb_window":
            self._enter_state("perch_window", 2.7, animation="perch")
            self._say_event("window_perch", duration_ms=2500)
        elif state == "perch_window":
            self._start_window_walk()
        elif state == "walk_window":
            self._leave_window("window_exit")
        elif state == "perch":
            self.place_on_floor()
            self._enter_state("idle")
        elif state == "sleep" and self.mode == "sleep":
            self._enter_state("sleep")
        elif state == "wait" and self.active_task is not None:
            return
        else:
            self._enter_state("sleep" if self.mode == "sleep" else "idle")

    def _check_reminders(self) -> None:
        now_wall = datetime.now()
        now_mono = time.monotonic()
        self.weather_context.refresh_async()
        self.recommendations.refresh_async()
        self._update_weather_status()
        self._update_recommendation_status()
        quiet_hours = now_wall.hour >= 22 or now_wall.hour < 8
        if (
            self.tasks_enabled
            and self.active_task is None
            and now_mono >= self.next_task_at
        ):
            if (
                quiet_hours
                or self.mode in {"sleep", "quiet"}
                or self.manual_rest_until > now_mono
                or self._work_focus_active(now_mono)
            ):
                self.next_task_at = now_mono + 90.0
            elif not self.start_sweet_task():
                self.next_task_at = now_mono + 4.0

        if self.recommendations_enabled and now_mono >= self.next_recommendation_at:
            if (
                quiet_hours
                or self.mode in {"sleep", "quiet"}
                or self.manual_rest_until > now_mono
                or self._work_focus_active(now_mono)
            ):
                self.next_recommendation_at = now_mono + 120.0
            elif not self.start_recommendation_task():
                self.next_recommendation_at = now_mono + 60.0

        hour_key = now_wall.strftime("%Y-%m-%d-%H")
        if (
            now_wall.minute == 0
            and hour_key != self.last_hour_key
            and not quiet_hours
            and self.mode != "sleep"
            and self.active_task is None
            and not self._work_focus_active(now_mono)
        ):
            self.last_hour_key = hour_key
            self._say_event(
                "hourly",
                context={"time": now_wall.strftime("%H:00")},
                force=True,
                duration_ms=4500,
                moment=now_wall,
            )

        idle_seconds = user_idle_seconds()
        returned = self.last_user_idle_seconds > 300 and idle_seconds < 15
        self.last_user_idle_seconds = idle_seconds
        if (
            returned
            and self.proactive_enabled
            and self.active_task is None
            and self.mode != "sleep"
            and self.state not in {"drag", "climb", "climb_window"}
        ):
            self.target_surface = None
            self.place_on_floor()
            self._enter_state("wave", 2.8)
            self._say_event("welcome_back", force=True, duration_ms=4200)
            self._schedule_next_proactive(now_mono)

        if idle_seconds < 75:
            self.active_work_seconds += 1
        elif idle_seconds > 300:
            self.active_work_seconds = 0
        if (
            self.active_work_seconds >= 50 * 60
            and now_mono >= self.work_reminder_cooldown_until
            and not quiet_hours
            and self.active_task is None
        ):
            self.active_work_seconds = 0
            self.work_reminder_cooldown_until = now_mono + 30 * 60
            if not self.typing_session_active:
                self.start_climb(speak=False)
            elif self.state != "typing_companion":
                self._begin_typing_retreat()
            self._say_event(
                "break_reminder",
                context={"minutes": 50},
                force=True,
                duration_ms=6200,
            )

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.LeftButton:
            if self.active_task is not None:
                self.task_panel.raise_()
                event.accept()
                return
            self.press_global = event.globalPosition().toPoint()
            self.drag_offset = self.press_global - self.pos()
            self.dragging = False
            event.accept()
            return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:
        if self.press_global is not None and event.buttons() & Qt.LeftButton:
            current = event.globalPosition().toPoint()
            if not self.dragging and (current - self.press_global).manhattanLength() > 7:
                self.dragging = True
                self.manual_rest_until = 0.0
                self.target_surface = None
                self._enter_state("drag")
                self._say_event("drag_start", duration_ms=2200)
            if self.dragging:
                self.move(current - self.drag_offset)
                event.accept()
                return
        super().mouseMoveEvent(event)

    def enterEvent(self, event) -> None:
        self.cursor_over_pet = True
        self.gaze_candidate_index = -1
        # Hovering the character should hold one clean pose.  If cursor gaze
        # was already active while approaching, return to the state's neutral
        # pose once instead of switching direction frames under the pointer.
        if self.gaze_active:
            self.gaze_active = False
            self._set_animation("perch" if self.state != "idle" else "idle")
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:
        self.cursor_over_pet = False
        self.gaze_candidate_index = -1
        self.gaze_resume_after = time.monotonic() + 0.22
        super().leaveEvent(event)

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.LeftButton and self.press_global is not None:
            target = event.globalPosition().toPoint()
            was_dragging = self.dragging
            self.press_global = None
            self.dragging = False
            if was_dragging:
                self._begin_manual_rest()
            elif self.clicks.add(time.monotonic()):
                self._start_annoyed()
            elif self.mode == "sleep":
                self.mode = "daily"
                self._mark_mode("daily")
                self._enter_state("happy", 2.3)
                self._say_event("wake", force=True, duration_ms=2800)
            else:
                reaction = random.choice(
                    ("heart", "happy", "hug", "jump", "wave", "stretch")
                )
                if reaction == "jump":
                    self.start_jump(target, high=self.mode == "sweet")
                else:
                    self._enter_state(reaction, RESPONSE_DURATIONS[reaction])
                self._say_event("click", duration_ms=2600)
            event.accept()
            return
        super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event) -> None:
        if event.button() == Qt.LeftButton and self.active_task is None:
            self._enter_state("hug", RESPONSE_DURATIONS["hug"])
            self._say_event("double_click", force=True, duration_ms=3000)
            event.accept()
            return
        super().mouseDoubleClickEvent(event)

    def moveEvent(self, event) -> None:
        super().moveEvent(event)
        if hasattr(self, "bubble"):
            self.bubble.follow_anchor(self.geometry())
        if hasattr(self, "task_panel"):
            self.task_panel.follow_anchor(self.geometry())

    def paintEvent(self, _event) -> None:
        painter = QPainter(self)
        # Replace the complete translucent backing surface on every repaint.
        # This prevents stale pixels from a wider previous pose surviving at
        # the character edges on some Windows compositors.
        painter.setCompositionMode(QPainter.CompositionMode_Source)
        painter.fillRect(self.rect(), Qt.transparent)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setRenderHint(QPainter.SmoothPixmapTransform)
        painter.drawPixmap(0, 0, self._display_pixmap)


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setOrganizationName("SweetGirlfriendDesktopPet")
    app.setQuitOnLastWindowClosed(False)
    app.setStyle("Fusion")
    pet = PetWindow()
    pet.show()
    pet.raise_()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
