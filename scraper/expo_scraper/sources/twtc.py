"""世貿/南港展覽館檔期表(twtc.com.tw)。
ASP.NET WebForms:先 GET 拿 __VIEWSTATE,再對 年 × 季 postback。
表格欄位:[檔期, 展名(+more連結), 主辦, 電話, 場地]。日期無年份,年份取自 ddlYear。
"""
from __future__ import annotations

import hashlib
import re

import requests
from bs4 import BeautifulSoup

from ..model import Exhibition
from ..util import normalize_title, parse_md_period, today_taipei

URL = "https://www.twtc.com.tw/exhibition.aspx"
VENUE_CITY = "臺北市"  # 世貿一館 / 南港展覽館皆在台北
VENUE_ADDRESS = {
    "世貿一館": "臺北市信義區信義路五段5號",
    "南港展覽館1館": "臺北市南港區經貿二路1號",
    "南港展覽館2館": "臺北市南港區經貿二路2號",
}


def fetch(session: requests.Session) -> list[Exhibition]:
    r = session.get(URL, timeout=60)
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")

    years_available = [o["value"] for o in soup.select("#body_ddlYear option")]
    this_year = today_taipei().year
    want_years = [y for y in years_available if y.isdigit() and int(y) in (this_year, this_year + 1)]
    if not want_years:
        raise RuntimeError(f"ddlYear 找不到 {this_year}/{this_year + 1},頁面選項={years_available}")

    seen: dict[str, Exhibition] = {}
    state = _hidden_fields(soup)
    for year in want_years:
        for quarter in ("1", "2", "3", "4"):
            page = _postback(session, state, year, quarter)
            psoup = BeautifulSoup(page, "html.parser")
            state = _hidden_fields(psoup)  # postback 後 VIEWSTATE 會更新
            for ex in _parse_rows(psoup, int(year)):
                seen.setdefault(ex.id, ex)  # 季度邊界同檔展可能重複,取先出現者
    return list(seen.values())


def _hidden_fields(soup: BeautifulSoup) -> dict:
    out = {}
    for name in ("__VIEWSTATE", "__VIEWSTATEGENERATOR", "__EVENTVALIDATION"):
        el = soup.find("input", {"name": name})
        out[name] = el["value"] if el else ""
    return out


def _postback(session: requests.Session, state: dict, year: str, quarter: str) -> str:
    data = {
        **state,
        "__EVENTTARGET": "ctl00$body$ddlMoth",
        "ctl00$body$ddlYear": year,
        "ctl00$body$ddlMoth": quarter,
    }
    r = session.post(URL, data=data, timeout=60)
    r.raise_for_status()
    return r.text


def _parse_rows(soup: BeautifulSoup, year: int) -> list[Exhibition]:
    out = []
    for tr in soup.select("tr"):
        cells = tr.find_all("td")
        if len(cells) < 5:
            continue
        period = cells[0].get_text(strip=True)
        if not re.match(r"\d{1,2}/\d{1,2}", period):
            continue
        title = re.sub(r"\s*more\s*$", "", " ".join(cells[1].get_text().split()), flags=re.I).strip()
        link = cells[1].find("a", href=True)
        official = ""
        if link and link["href"].startswith("http"):
            official = link["href"]
        try:
            start, end = parse_md_period(period, year)
        except ValueError:
            continue
        venue = cells[4].get_text(strip=True)
        source_id = hashlib.sha1(f"{normalize_title(title)}|{start}".encode()).hexdigest()[:10]
        out.append(Exhibition(
            id=f"twtc:{source_id}",  # 初始代理鍵;改名/改期由 reconcile 沿用舊 id
            source="twtc",
            sourceId=source_id,
            title=title,
            startDate=start,
            endDate=end,
            venueName=venue,
            address=VENUE_ADDRESS.get(venue, ""),
            city=VENUE_CITY,
            organizer=cells[2].get_text(strip=True),
            category="商展",
            onSales="UNKNOWN",  # 檔期表無售票資訊(多為換證入場),R3 不適用
            officialUrl=official,
        ))
    return out
