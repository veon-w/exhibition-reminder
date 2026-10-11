"""開拓動漫官網(f-2.com.tw)的活動列表 —— FF / PF / RF / 駁二動漫祭等同人場。

兩個列表頁共用同一套 WordPress 版型,只是內容類型不同:
    page_id=1137  開拓動漫祭系列活動(FF、PF、RF、駁二動漫祭)  → category 同人展
    page_id=1665  其他類型動漫活動(動漫歌手來台公演)          → category 演唱會

每筆活動是一個 div.activity-info:
    <h2><a href=永久連結>展名</a></h2>
    <p><strong>活動日期：</strong><br>2026 年 8月 21日 （五） 10：30–17：00<br>…一天一行…</p>
    <p><strong>活動地點：</strong>花博公園爭艷館（台北市中山區玉門街一號）</p>
    <p><strong>活動內容：</strong>●同人誌即售會 …</p>
展期取所有日期行的最早與最晚;開放時間取第一段 HH:MM–HH:MM(演唱會寫的是
「16:00 OPEN / 17:00 START」,沒有連字號,不會被誤判成開放時間)。
"""
from __future__ import annotations

import hashlib
import re
from datetime import date

import requests
from bs4 import BeautifulSoup

from ..model import Exhibition
from ..util import extract_city, guess_city, truncate

BASE = "https://www.f-2.com.tw/"
PAGES = (
    ("https://www.f-2.com.tw/?page_id=1137", "同人展"),
    ("https://www.f-2.com.tw/?page_id=1665", "演唱會"),
)
ORGANIZER = "開拓動漫事業有限公司"

# 全形/半形/表意冒號都出現過(同一個站不同年份的頁面混用)
_COLON = "[:：︰]"
_DASH = "[–—~～-]"
DATE_RE = re.compile(r"(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日")
HOURS_RE = re.compile(rf"(\d{{1,2}}){_COLON}(\d{{2}})\s*{_DASH}\s*(\d{{1,2}}){_COLON}(\d{{2}})")


def fetch(session: requests.Session) -> list[Exhibition]:
    out: dict[str, Exhibition] = {}
    for url, category in PAGES:
        r = session.get(url, timeout=60)
        r.raise_for_status()
        r.encoding = "utf-8"
        soup = BeautifulSoup(r.text, "html.parser")
        blocks = soup.select("div.activity-info")
        if not blocks:
            raise RuntimeError(f"{url} 找不到 div.activity-info,版型可能改了")
        for info in blocks:
            ex = _parse(info, category)
            if ex:
                out.setdefault(ex.id, ex)
    return list(out.values())


def _parse(info, category: str) -> Exhibition | None:
    link = info.find("h2")
    link = link.find("a", href=True) if link else None
    if not link:
        return None
    title = " ".join(link.get_text(" ", strip=True).split())
    detail_url = link["href"]

    period = _field(info, "活動日期：")
    days = [date(int(y), int(m), int(d)) for y, m, d in DATE_RE.findall(period)]
    if not days:
        return None

    venue_raw = _field(info, "活動地點：")
    venue, address = _split_venue(venue_raw)

    open_time = close_time = ""
    hm = HOURS_RE.search(period)
    if hm:
        open_time = f"{int(hm.group(1)):02d}:{hm.group(2)}"
        close_time = f"{int(hm.group(3)):02d}:{hm.group(4)}"

    source_id = hashlib.sha1(detail_url.encode()).hexdigest()[:10]
    return Exhibition(
        id=f"ff:{source_id}",
        source="ff",
        sourceId=source_id,
        title=title,
        startDate=min(days).isoformat(),
        endDate=max(days).isoformat(),
        venueName=venue,
        address=address,
        city=guess_city(f"{address} {venue}"),
        organizer=ORGANIZER,
        category=category,
        openTime=open_time,
        closeTime=close_time,
        onSales="UNKNOWN",   # 門票/場刊在各活動頁,列表頁沒有售票狀態
        officialUrl=detail_url,
        imageUrl=_image(info),
        descriptionShort=truncate(_field(info, "活動內容：")),
    )


def _field(info, label: str) -> str:
    """取「活動日期：」這類 <p><strong>標籤</strong>內容</p> 的內容部分。"""
    for p in info.find_all("p"):
        text = p.get_text("\n", strip=True)
        if text.startswith(label):
            return text[len(label):].strip()
    return ""


def _split_venue(text: str) -> tuple[str, str]:
    """「花博公園爭艷館（台北市中山區玉門街一號）」→ (場地, 地址)。
    括號內沒有縣市名(例如場地代號)就整串當場地名。"""
    m = re.match(r"\s*(.+?)\s*[（(]\s*(.+?)\s*[）)]\s*$", text, flags=re.S)
    if m and extract_city(m.group(2)):
        return " ".join(m.group(1).split()), " ".join(m.group(2).split())
    return " ".join(text.split()), ""


def _image(info) -> str:
    align = info.find_parent("div", class_="activity-align")
    img = align.select_one(".activity-image img[src]") if align else None
    return img["src"] if img else ""
