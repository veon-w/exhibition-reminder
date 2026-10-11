"""文化部展覽 API(iCulture 開放資料)。免金鑰 JSON。"""
from __future__ import annotations

import re
from datetime import timedelta

import requests

from ..model import Exhibition
from ..util import extract_city, slash_date_to_iso, strip_html, today_taipei, truncate

API = "https://cloud.culture.tw/frontsite/trans/SearchShowAction.do"
CATEGORY_NAMES = {"6": "展覽", "17": "演唱會", "1": "音樂"}
STALE_DAYS = 7  # 來源偶爾殘留已閉展資料,閉展超過 7 天的直接濾掉


def fetch(session: requests.Session, categories: list[str] | None = None) -> list[Exhibition]:
    out: list[Exhibition] = []
    cutoff = (today_taipei() - timedelta(days=STALE_DAYS)).isoformat()
    for cat in categories or ["6"]:
        r = session.get(API, params={"method": "doFindTypeJ", "category": cat}, timeout=60)
        r.raise_for_status()
        for e in r.json():
            out.extend(_parse_one(e, cat, cutoff))
    return out


def _parse_one(e: dict, cat: str, cutoff: str) -> list[Exhibition]:
    try:
        start = slash_date_to_iso(e["startDate"])
        end = slash_date_to_iso(e["endDate"])
    except (KeyError, ValueError):
        return []
    if end < cutoff:
        return []
    show = (e.get("showInfo") or [{}])[0]
    address = (show.get("location") or "").strip()
    organizer = (e.get("showUnit") or "").strip() or "、".join(e.get("masterUnit") or [])

    def _f(v):
        try:
            return float(v)
        except (TypeError, ValueError):
            return None

    def _hhmm(v):
        m = re.search(r"(\d{2}:\d{2}):\d{2}$", v or "")
        return m.group(1) if m else ""

    open_t, close_t = _hhmm(show.get("time")), _hhmm(show.get("endTime"))
    if open_t == "00:00" and close_t == "00:00":  # 兩個都 00:00 = 來源沒填
        open_t = close_t = ""

    return [Exhibition(
        id=f"moc:{e['UID']}",
        source="moc",
        sourceId=e["UID"],
        title=(e.get("title") or "").strip(),
        startDate=start,
        endDate=end,
        venueName=(show.get("locationName") or "").strip(),
        address=address,
        city=extract_city(address),
        lat=_f(show.get("latitude")),
        lng=_f(show.get("longitude")),
        organizer=organizer,
        category=CATEGORY_NAMES.get(cat, cat),
        priceText=(show.get("price") or "").strip(),
        onSales=(show.get("onSales") or "UNKNOWN").strip() or "UNKNOWN",
        openTime=open_t,
        closeTime=close_t,
        ticketUrl=(e.get("webSales") or "").strip(),
        officialUrl=(e.get("sourceWebPromote") or "").strip(),
        imageUrl=(e.get("imageUrl") or "").strip(),
        descriptionShort=truncate(strip_html(e.get("descriptionFilterHtml") or "")),
    )]
