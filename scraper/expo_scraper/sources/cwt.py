"""CWT 台灣同人誌販售會官網(comicworld.com.tw)的活動一覽。

/Acts/tw 是台灣場一覽,每筆是一個 div.activity:
    <a class="activity_img" href="/Act1/172"><img class="a_i_2" src="../ULimages/…"></a>
    <span class="a_c_tit">【CWT-T36】(台中場)</span>
    <span class="a_row"><span class="a_c_i">活動日期</span>2026年8月29 / 30日</span>
    <span class="a_row"><span class="a_c_i">活動地點</span>臺中世界貿易中心二館</span>
    <span class="a_row"><span class="a_c_i">地　　址</span>台中市西屯區天保街60號</span>
只列未來場次(過去的會被移掉),reconcile 會把消失的標 removedAt 而不是直接刪。
"""
from __future__ import annotations

import re
from datetime import date
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

from ..model import Exhibition
from ..util import guess_city

URL = "https://www.comicworld.com.tw/Acts/tw"
ORGANIZER = "CWT 台灣同人誌販售會"
ACT_ID_RE = re.compile(r"/Act\d*/(\d+)")


def fetch(session: requests.Session) -> list[Exhibition]:
    r = session.get(URL, timeout=60)
    r.raise_for_status()
    r.encoding = "utf-8"
    soup = BeautifulSoup(r.text, "html.parser")
    blocks = soup.select("div.activity")
    if not blocks:
        raise RuntimeError("找不到 div.activity,CWT 版型可能改了")

    out: dict[str, Exhibition] = {}
    for block in blocks:
        ex = _parse(block)
        if ex:
            out.setdefault(ex.id, ex)
    return list(out.values())


def _parse(block) -> Exhibition | None:
    tit = block.select_one(".a_c_tit")
    href = next((a["href"] for a in block.select("a[href]")
                 if ACT_ID_RE.search(a["href"])), "")
    m = ACT_ID_RE.search(href or "")
    if not tit or not m:
        return None
    source_id = m.group(1)

    rows = _rows(block)
    days = _parse_days(rows.get("活動日期", ""))
    if not days:
        return None

    address = rows.get("地址", "")
    venue = rows.get("活動地點", "")
    img = block.select_one("img.a_i_2[src]") or block.select_one("img[src]")

    return Exhibition(
        id=f"cwt:{source_id}",
        source="cwt",
        sourceId=source_id,
        title=_title(tit.get_text(strip=True)),
        startDate=min(days).isoformat(),
        endDate=max(days).isoformat(),
        venueName=venue,
        address=address,
        city=guess_city(f"{address} {venue}"),
        organizer=ORGANIZER,
        category="同人展",
        onSales="UNKNOWN",   # 門票另在 fonticket 售票,一覽頁沒有狀態
        officialUrl=urljoin(URL, href),
        imageUrl=urljoin(URL, img["src"]) if img else "",
    )


def _rows(block) -> dict[str, str]:
    """把 <span class=a_row><span class=a_c_i>標籤</span>值</span> 收成 dict。
    標籤用全形空白排版(「地　　址」),鍵一律去掉全形空白再存。"""
    out = {}
    for row in block.select("span.a_row"):
        key = row.select_one("span.a_c_i")
        if not key:
            continue
        label = key.get_text(strip=True).replace("　", "")
        key.extract()                      # 拆掉標籤,剩下的就是值
        out[label] = " ".join(row.get_text(" ", strip=True).split())
    return out


def _title(raw: str) -> str:
    """「【CWT-T36】(台中場)」→「CWT-T36 台灣同人誌販售會(台中場)」
    (原字串只有場次代號,單獨顯示在清單上看不出是什麼活動)。"""
    m = re.match(r"\s*【\s*(.+?)\s*】\s*(.*)$", raw)
    if not m:
        return raw.strip()
    code, region = m.group(1), m.group(2).strip()
    return f"{code} 台灣同人誌販售會{region}"


def _parse_days(raw: str) -> list[date]:
    """「2026年8月29 / 30日」→ [8/29, 8/30];跨月寫成「8月31 / 9月1日」也吃得下。"""
    m = re.search(r"(\d{4})\s*年\s*(.+)", raw)
    if not m:
        return []
    year, rest = int(m.group(1)), m.group(2)
    days: list[date] = []
    month = None
    for part in re.split(r"[/／、,，]", rest):
        # 「9月1日」要先吃掉月份再取日,否則 9 會被當成日、月份沿用前一段
        mo = re.search(r"(\d{1,2})\s*月", part)
        if mo:
            month = int(mo.group(1))
            part = part[mo.end():]
        token = re.search(r"\d{1,2}", part)
        if not token or month is None:
            continue
        day = date(year, month, int(token.group()))
        if days and day < days[-1]:
            day = day.replace(year=year + 1)   # 跨年場次(12/31 / 1/1)
        days.append(day)
    return days
