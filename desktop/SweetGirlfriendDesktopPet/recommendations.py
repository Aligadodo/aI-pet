from __future__ import annotations

import html
import json
import random
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Iterable
from urllib.parse import urlparse


NEWS_FEED = "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
MUSIC_FEED = "https://rss.marketingtools.apple.com/api/v2/cn/music/most-played/20/songs.json"


@dataclass(frozen=True)
class Recommendation:
    kind: str
    title: str
    url: str
    source: str = ""
    subtitle: str = ""


def _safe_http_url(value: str) -> str:
    parsed = urlparse(value.strip())
    return value.strip() if parsed.scheme in {"http", "https"} and parsed.netloc else ""


def parse_news_rss(payload: bytes) -> list[Recommendation]:
    root = ET.fromstring(payload)
    items: list[Recommendation] = []
    for node in root.findall("./channel/item"):
        title = html.unescape((node.findtext("title") or "").strip())
        url = _safe_http_url(node.findtext("link") or "")
        source = html.unescape((node.findtext("source") or "").strip())
        if title and url:
            items.append(Recommendation("news", title[:80], url, source=source[:30]))
    return items


def parse_music_chart(payload: bytes) -> list[Recommendation]:
    data = json.loads(payload.decode("utf-8"))
    results = data.get("feed", {}).get("results", [])
    items: list[Recommendation] = []
    for entry in results:
        title = str(entry.get("name", "")).strip()
        artist = str(entry.get("artistName", "")).strip()
        url = _safe_http_url(str(entry.get("url", "")))
        if title and url:
            items.append(
                Recommendation(
                    "music",
                    title[:60],
                    url,
                    source="Apple Music 热门榜",
                    subtitle=artist[:40],
                )
            )
    return items


class RecommendationService:
    """Fetch public charts in the background; local activity is never uploaded."""

    def __init__(
        self,
        enabled: bool = True,
        *,
        refresh_seconds: float = 2700.0,
        opener=urllib.request.urlopen,
    ) -> None:
        self.enabled = enabled
        self.refresh_seconds = refresh_seconds
        self.opener = opener
        self.updated_at = float("-inf")
        self.refreshing = False
        self.last_error = ""
        self.news: list[Recommendation] = []
        self.music: list[Recommendation] = []
        self._lock = threading.Lock()

    def set_enabled(self, enabled: bool) -> None:
        with self._lock:
            self.enabled = enabled

    def refresh_async(self, *, force: bool = False) -> None:
        with self._lock:
            if not self.enabled or self.refreshing:
                return
            if not force and time.monotonic() - self.updated_at < self.refresh_seconds:
                return
            self.refreshing = True
        threading.Thread(target=self._refresh_worker, daemon=True).start()

    def _request(self, url: str) -> bytes:
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "SweetGirlfriendDesktopPet/1.2"},
        )
        with self.opener(request, timeout=5.0) as response:
            return response.read()

    def _refresh_worker(self) -> None:
        errors: list[str] = []
        news: list[Recommendation] = []
        music: list[Recommendation] = []
        try:
            news = parse_news_rss(self._request(NEWS_FEED))
        except Exception as error:
            errors.append(f"news: {error}")
        try:
            music = parse_music_chart(self._request(MUSIC_FEED))
        except Exception as error:
            errors.append(f"music: {error}")
        with self._lock:
            if news:
                self.news = news
            if music:
                self.music = music
            self.last_error = "; ".join(errors)
            self.updated_at = time.monotonic()
            self.refreshing = False

    def choose(self, kind: str, *, exclude_urls: Iterable[str] = ()) -> Recommendation | None:
        excluded = set(exclude_urls)
        with self._lock:
            source = self.news if kind == "news" else self.music if kind == "music" else []
            candidates = [item for item in source if item.url not in excluded] or list(source)
        return random.choice(candidates) if candidates else None

    def status_text(self) -> str:
        with self._lock:
            if not self.enabled:
                return "已关闭"
            if self.refreshing:
                return "正在后台更新…"
            if self.news or self.music:
                return f"已缓存 {len(self.news)} 条新闻 · {len(self.music)} 首音乐"
            if self.last_error:
                return "暂时无法联网，稍后重试"
            return "等待首次更新"
