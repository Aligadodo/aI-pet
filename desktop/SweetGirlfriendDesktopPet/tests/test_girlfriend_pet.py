from __future__ import annotations

import json
import os
import random
import sys
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from PIL import Image, ImageChops  # noqa: E402
from PySide6.QtCore import QPoint, QRect, Qt  # noqa: E402
from PySide6.QtWidgets import QApplication, QPushButton  # noqa: E402

from app import APP_VERSION, ANIMATION_NAMES, PetWindow, SpeechBubble, SweetTaskPanel  # noqa: E402
from activity import InputActivityMonitor, classify_activity  # noqa: E402
from dialogue import DialogueEngine, part_of_day  # noqa: E402
from environment import WeatherContext, classify_weather  # noqa: E402
from logic import ClickBurstDetector, clamp, ease_out_cubic, parabolic_arc  # noqa: E402
from recommendations import parse_music_chart, parse_news_rss  # noqa: E402
from tasks import SWEET_TASKS, choose_task, correct_choice_count  # noqa: E402
from window_tracker import WindowSurface, choose_surface  # noqa: E402


class TaskDataTests(unittest.TestCase):
    def test_forty_distinct_tasks_exist(self) -> None:
        self.assertEqual(len(SWEET_TASKS), 40)
        self.assertEqual(len({task.key for task in SWEET_TASKS}), 40)

    def test_every_task_has_one_correct_choice(self) -> None:
        self.assertTrue(all(correct_choice_count(task) == 1 for task in SWEET_TASKS))

    def test_every_task_has_three_choices_and_feedback(self) -> None:
        for task in SWEET_TASKS:
            self.assertEqual(len(task.choices), 3)
            self.assertTrue(task.success_line)
            self.assertTrue(task.wrong_line)
            self.assertTrue(task.timeout_line)

    def test_choices_are_shuffled_without_losing_answer(self) -> None:
        task = choose_task(rng=random.Random(42))
        self.assertEqual(correct_choice_count(task), 1)
        self.assertIn(task.key, {item.key for item in SWEET_TASKS})

    def test_task_animations_exist(self) -> None:
        names = set(ANIMATION_NAMES)
        for task in SWEET_TASKS:
            self.assertIn(task.success_animation, names)
            self.assertIn(task.wrong_animation, names)
            self.assertIn(task.timeout_animation, names)

    def test_contextual_selection_can_surface_time_and_weather_tasks(self) -> None:
        rng = random.Random(12)
        selected = {
            choose_task(
                part="morning", weather="rain", weekend=False, rng=rng
            ).key
            for _ in range(80)
        }
        self.assertTrue(selected & {"morning_stretch", "breakfast_choice"})
        self.assertTrue(selected & {"rain_umbrella", "rain_cozy_time"})

    def test_activity_context_can_surface_dynamic_work_tasks(self) -> None:
        rng = random.Random(19)
        selected = {
            choose_task(
                activity="coding",
                context={"activity_label": "写代码"},
                rng=rng,
            ).key
            for _ in range(100)
        }
        self.assertTrue(selected & {"focus_checkpoint", "save_progress"})
        task = choose_task(
            activity="coding",
            context={"activity_label": "写代码"},
            rng=random.Random(2),
        )
        self.assertNotIn("{activity_label}", task.prompt)


class AssetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = json.loads(
            (ROOT / "assets/animations/manifest.json").read_text(encoding="utf-8")
        )

    def test_manifest_has_twenty_five_animations(self) -> None:
        animations = self.manifest["animations"]
        self.assertEqual(set(animations), set(ANIMATION_NAMES))
        self.assertEqual(len(animations), 25)

    def test_manifest_has_181_real_source_frames_without_fake_multiplier(self) -> None:
        total = sum(len(spec["frames"]) for spec in self.manifest["animations"].values())
        self.assertEqual(total, 181)
        self.assertEqual(self.manifest["render_fps_multiplier"], 1)
        self.assertEqual(len(self.manifest["animations"]["run"]["frames"]), 12)
        self.assertGreaterEqual(self.manifest["animations"]["run"]["fps"], 16)

    def test_all_frame_files_exist(self) -> None:
        for spec in self.manifest["animations"].values():
            for relative in spec["frames"]:
                self.assertTrue((ROOT / "assets" / relative).is_file(), relative)

    def test_icon_files_exist(self) -> None:
        self.assertTrue((ROOT / "assets/pet_icon.png").is_file())
        self.assertTrue((ROOT / "assets/pet_icon.ico").is_file())

    def test_running_feet_change_across_frames(self) -> None:
        frame_paths = [
            ROOT / "assets" / relative
            for relative in self.manifest["animations"]["run"]["frames"]
        ]
        lower_halves = []
        for path in frame_paths:
            image = Image.open(path).convert("RGBA")
            lower_halves.append(image.crop((0, image.height // 2, image.width, image.height)))
        changed_pairs = sum(
            ImageChops.difference(left, right).getbbox() is not None
            for left, right in zip(lower_halves, lower_halves[1:] + lower_halves[:1])
        )
        self.assertEqual(len(frame_paths), 12)
        self.assertGreaterEqual(changed_pairs, 11)

        shoe_spans = []
        for path in frame_paths:
            image = Image.open(path).convert("RGBA")
            pixels = image.load()
            shoe_x = [
                x
                for y in range(380, 512)
                for x in range(512)
                if pixels[x, y][3] > 180
                and pixels[x, y][0] > 175
                and pixels[x, y][1] > 130
                and pixels[x, y][2] > 80
            ]
            shoe_spans.append(max(shoe_x) - min(shoe_x))
        self.assertLessEqual(min(shoe_spans), 150)
        self.assertGreaterEqual(max(shoe_spans), 200)

    def test_walk_cycle_has_twelve_distinct_lower_body_phases(self) -> None:
        frame_paths = [
            ROOT / "assets" / relative
            for relative in self.manifest["animations"]["walk"]["frames"]
        ]
        self.assertEqual(len(frame_paths), 12)
        lower_bodies = [
            Image.open(path).convert("RGBA").crop((0, 350, 512, 512))
            for path in frame_paths
        ]
        for left, right in zip(lower_bodies, lower_bodies[1:] + lower_bodies[:1]):
            difference = ImageChops.difference(left, right).convert("L")
            changed_pixels = sum(
                value > 12 for value in difference.get_flattened_data()
            )
            self.assertGreater(changed_pixels, 8000)

        shoe_spans = []
        for path in frame_paths:
            image = Image.open(path).convert("RGBA")
            pixels = image.load()
            shoe_x = [
                x
                for y in range(390, 512)
                for x in range(512)
                if pixels[x, y][3] > 180
                and pixels[x, y][0] > 175
                and pixels[x, y][1] > 130
                and pixels[x, y][2] > 80
            ]
            shoe_spans.append(max(shoe_x) - min(shoe_x))
        # A real walk cycle needs narrow passing poses as well as contact
        # poses; a key-pose-only cycle never brings the feet close together.
        self.assertLessEqual(min(shoe_spans), 130)
        self.assertGreaterEqual(max(shoe_spans), 185)

    def test_standing_frames_have_complete_shoes_and_clean_transparency(self) -> None:
        for animation in ("idle", "gaze", "stretch"):
            bounds = []
            for relative in self.manifest["animations"][animation]["frames"]:
                image = Image.open(ROOT / "assets" / relative).convert("RGBA")
                self.assertEqual(image.size, (512, 512))
                alpha = image.getchannel("A")
                self.assertEqual(alpha.getpixel((0, 0)), 0)
                bounds.append(alpha.getbbox())
                pixels = image.load()
                shoe_pixels = sum(
                    pixels[x, y][3] > 180
                    and pixels[x, y][0] > 175
                    and pixels[x, y][1] > 135
                    and pixels[x, y][2] > 95
                    for y in range(400, 512)
                    for x in range(512)
                )
                self.assertGreater(shoe_pixels, 2500, relative)
            heights = [bound[3] - bound[1] for bound in bounds if bound]
            self.assertLessEqual(max(heights) - min(heights), 14)
            bottoms = [bound[3] for bound in bounds if bound]
            self.assertLessEqual(max(bottoms) - min(bottoms), 12)

    def test_climb_finishes_with_full_body_frames(self) -> None:
        for relative in self.manifest["animations"]["climb"]["frames"][-2:]:
            image = Image.open(ROOT / "assets" / relative).convert("RGBA")
            bounds = image.getchannel("A").getbbox()
            self.assertIsNotNone(bounds)
            self.assertGreater(bounds[3] - bounds[1], image.height * 0.55)

    def test_photo_pose_frames_are_transparent_and_scale_stable(self) -> None:
        bounds = []
        for relative in self.manifest["animations"]["photo_pose"]["frames"]:
            image = Image.open(ROOT / "assets" / relative).convert("RGBA")
            self.assertEqual(image.size, (512, 512))
            alpha = image.getchannel("A")
            self.assertEqual(alpha.getpixel((0, 0)), 0)
            bounds.append(alpha.getbbox())
        heights = [bound[3] - bound[1] for bound in bounds if bound]
        self.assertLessEqual(max(heights) - min(heights), 2)


class DialogueAndLogicTests(unittest.TestCase):
    def test_release_version(self) -> None:
        self.assertEqual(APP_VERSION, "1.2.4")

    def test_dialogue_library_loads_and_formats(self) -> None:
        engine = DialogueEngine(ROOT / "assets/dialogues_zh-CN.json")
        line = engine.choose("hourly", context={"time": "15:00"}, force=True)
        self.assertIsNotNone(line)
        self.assertIn("15:00", line)

    def test_dialogue_contains_core_events(self) -> None:
        engine = DialogueEngine(ROOT / "assets/dialogues_zh-CN.json")
        required = {
            "startup",
            "idle",
            "click",
            "rapid_click",
            "window_choose",
            "break_reminder",
            "small_talk",
            "affection",
            "daily_question",
            "encouragement",
            "food_chat",
            "date_idea",
            "work_companion",
            "memory",
            "random_tease",
            "contextual_stretch",
            "contextual_read",
            "weather_rain",
            "weather_cold",
            "typing_finished",
            "drag_rest",
            "photo_pose",
            "activity_coding",
            "activity_meeting",
            "recommendations_enabled",
        }
        self.assertTrue(required.issubset(engine.events))

    def test_dialogue_library_is_large_and_not_pet_styled(self) -> None:
        data = json.loads((ROOT / "assets/dialogues_zh-CN.json").read_text(encoding="utf-8"))
        lines = [line for event in data["events"].values() for line in event.get("lines", [])]
        self.assertGreaterEqual(len(lines), 370)
        banned = {"喵", "主人", "猫咪", "猫猫", "小爪", "投喂", "摸摸头", "被拎起来", "嗷呜", "一只"}
        joined = "\n".join(lines)
        self.assertFalse({word for word in banned if word in joined})

    def test_task_dialogue_is_not_pet_styled(self) -> None:
        banned = {"喵", "主人", "猫咪", "猫猫", "小爪", "投喂", "摸摸头", "被拎起来", "嗷呜", "一只"}
        lines: list[str] = []
        for task in SWEET_TASKS:
            lines.extend((task.prompt, task.success_line, task.wrong_line, task.timeout_line))
            for choice in task.choices:
                lines.append(choice.label)
        joined = "\n".join(lines)
        self.assertFalse({word for word in banned if word in joined})

    def test_part_of_day(self) -> None:
        from datetime import datetime

        self.assertEqual(part_of_day(datetime(2026, 1, 1, 8)), "morning")
        self.assertEqual(part_of_day(datetime(2026, 1, 1, 23)), "night")

    def test_weather_classification_and_manual_context(self) -> None:
        self.assertEqual(classify_weather("Light rain", 18), "rain")
        self.assertEqual(classify_weather("Clear", 33), "hot")
        self.assertEqual(classify_weather("Overcast", 18), "cloudy")
        context = WeatherContext("snow")
        snapshot = context.snapshot()
        self.assertEqual(snapshot.weather, "snow")
        self.assertEqual(snapshot.weather_label, "下雪")

    def test_motion_helpers(self) -> None:
        self.assertEqual(clamp(12, 0, 10), 10)
        self.assertAlmostEqual(ease_out_cubic(1), 1)
        self.assertAlmostEqual(parabolic_arc(0.5, 100), 100)

    def test_click_burst_detector(self) -> None:
        detector = ClickBurstDetector(count=3, window_seconds=1)
        self.assertFalse(detector.add(1.0))
        self.assertFalse(detector.add(1.2))
        self.assertTrue(detector.add(1.4))

    def test_typing_rate_counts_frequency_without_key_content(self) -> None:
        clock = [0.0]
        monitor = InputActivityMonitor(clock=lambda: clock[0])
        monitor.available = False
        for index in range(6):
            clock[0] = index * 0.3
            monitor.record_keypress()
        snapshot = monitor.sample(clock[0])
        self.assertTrue(snapshot.active)
        self.assertGreaterEqual(snapshot.keys_per_minute, 20)
        self.assertFalse(hasattr(monitor, "typed_text"))

    def test_foreground_activity_classification(self) -> None:
        self.assertEqual(classify_activity("Code.exe", "project"), "coding")
        self.assertEqual(classify_activity("EXCEL.EXE", "budget"), "spreadsheet")
        self.assertEqual(classify_activity("WeMeetApp.exe", "腾讯会议"), "meeting")

    def test_public_feed_parsers(self) -> None:
        news = parse_news_rss(
            b"<rss><channel><item><title>Hot News</title><link>https://example.com/n</link><source>Example</source></item></channel></rss>"
        )
        music_payload = json.dumps(
            {
                "feed": {
                    "results": [
                        {
                            "name": "Song",
                            "artistName": "Singer",
                            "url": "https://example.com/song",
                        }
                    ]
                }
            }
        ).encode()
        music = parse_music_chart(music_payload)
        self.assertEqual(news[0].kind, "news")
        self.assertEqual(music[0].subtitle, "Singer")

    def test_window_surface_selection(self) -> None:
        origin = QPoint(100, 500)
        near = WindowSurface(1, "near", QRect(60, 300, 700, 400), True)
        far = WindowSurface(2, "far", QRect(1800, 100, 500, 500), False)
        self.assertEqual(choose_surface([far, near], origin), near)


class WidgetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_bubble_and_task_panel_follow_anchor(self) -> None:
        anchor = QRect(200, 400, 320, 320)
        bubble = SpeechBubble()
        panel = SweetTaskPanel()
        bubble.show_message("测试跟随", anchor, 100)
        panel.follow_anchor(anchor, force=True)
        self.assertFalse(bubble.geometry().isEmpty())
        self.assertFalse(panel.geometry().isEmpty())
        bubble.close()
        panel.close()

    def test_task_panel_is_mouse_only_and_does_not_take_focus(self) -> None:
        panel = SweetTaskPanel()
        task = SWEET_TASKS[0]
        panel.show_task(task, QRect(200, 400, 320, 320), lambda _choice: None, lambda: None)
        panel.timer.stop()
        self.assertTrue(panel.windowFlags() & Qt.WindowDoesNotAcceptFocus)
        self.assertEqual(panel.focusPolicy(), Qt.NoFocus)
        buttons = panel.findChildren(QPushButton)
        self.assertEqual(len(buttons), 3)
        self.assertTrue(all(button.focusPolicy() == Qt.NoFocus for button in buttons))
        panel.close()

    def test_pet_loads_all_animations_and_uses_stable_mask(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        self.assertEqual(set(pet.animations), set(ANIMATION_NAMES))
        self.assertFalse(pet._stable_window_mask.isEmpty())
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()

    def test_display_modes_and_manual_rest_are_switchable(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        previous_mode = pet.display_mode
        pet._set_display_mode("desktop_only")
        self.assertEqual(pet.display_mode, "desktop_only")
        self.assertTrue(pet.windowFlags() & Qt.WindowStaysOnBottomHint)
        pet.move(180, 120)
        pet._begin_manual_rest()
        self.assertEqual(pet.state, "manual_rest")
        self.assertGreater(pet.manual_rest_until, 0)
        pet._set_display_mode(previous_mode)
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()

    def test_menu_temporarily_hides_task_overlay(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        pet.show()
        self.assertTrue(pet.start_sweet_task(force=True, task=SWEET_TASKS[0]))
        pet.task_panel.timer.stop()
        self.assertTrue(pet.task_panel.isVisible())
        pet._on_menu_about_to_show()
        self.assertFalse(pet.task_panel.isVisible())
        pet._on_menu_about_to_hide()
        self.assertTrue(pet.task_panel.isVisible())
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()

    def test_animation_step_uses_one_real_crisp_source_frame(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        pet._set_animation("photo_pose")
        source_fps = float(pet.animation_specs["photo_pose"]["fps"])
        pet._advance_animation(pet.animation_started + 1.1 / source_fps)
        self.assertEqual(pet.animation_fps_multiplier, 1)
        self.assertEqual(pet.animation_frame_index, 1)
        expected = pet.animations["photo_pose"][1].scaled(
            pet.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation
        )
        self.assertEqual(pet._display_pixmap.toImage(), expected.toImage())
        self.assertFalse(hasattr(pet, "animation_blend"))
        self.assertFalse(pet._display_pixmap.isNull())
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()

    def test_cursor_controlled_gaze_does_not_time_advance_or_flash(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        pet.gaze_active = True
        pet._show_gaze_frame(2)
        held_frame = pet._display_pixmap.toImage()
        pet._advance_animation(pet.animation_started + 30.0)
        self.assertEqual(pet.animation_name, "gaze")
        self.assertEqual(pet.animation_frame_index, 2)
        self.assertEqual(pet._display_pixmap.toImage(), held_frame)
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()

    def test_typing_session_moves_pet_toward_an_edge(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        pet.manual_rest_until = 0
        pet.move(500, pet.floor_y())
        pet._begin_typing_retreat()
        self.assertEqual(pet.state, "typing_retreat")
        bounds = pet.current_screen_geometry()
        edge_targets = {bounds.left(), bounds.right() - pet.width() + 1}
        self.assertIn(round(pet.typing_target_x), edge_targets)
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()

    def test_correct_task_choice_triggers_success_animation(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        pet._save_task_stats = lambda: None
        task = next(item for item in SWEET_TASKS if item.key == "red_packet")
        self.assertTrue(pet.start_sweet_task(force=True, task=task))
        pet.task_panel.timer.stop()
        correct = next(choice for choice in task.choices if choice.correct)
        previous = pet.task_successes
        pet._resolve_task_choice(correct)
        self.assertEqual(pet.task_successes, previous + 1)
        self.assertEqual(pet.state, "jump")
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()

    def test_task_timeout_triggers_sad_animation(self) -> None:
        pet = PetWindow()
        pet.tick_timer.stop()
        pet.reminder_timer.stop()
        pet._save_task_stats = lambda: None
        task = next(item for item in SWEET_TASKS if item.key == "cuddle")
        self.assertTrue(pet.start_sweet_task(force=True, task=task))
        pet.task_panel.timer.stop()
        previous = pet.task_misses
        pet._resolve_task_timeout()
        self.assertEqual(pet.task_misses, previous + 1)
        self.assertEqual(pet.state, "cry")
        pet.allow_exit = True
        pet.tray.hide()
        pet.task_panel.close()
        pet.bubble.close()
        pet.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
