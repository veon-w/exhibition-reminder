"""udn售票網(商業特展/演唱會)。
清單走 WebMethod:POST UTK0101_009.aspx/Product_Category_List,回 {"d":{"ReturnData":
{"script": "<卡片HTML>", "totalCount": N}}}。卡片含展名/展期(meta itemprop)/場地/票價/PRODUCT_ID。

開賣時間:詳情頁 hidden input `ctl00_ContentPlaceHolder1_QINFO`。
- 有值且為未來 → 尚未開賣(onSales=N, saleStartAt=該時間)
- 空或已過 → 售票中(onSales=Y)
- 詳情頁抓失敗 → onSales=UNKNOWN(app 端 R3 只認 N→Y,UNKNOWN 恢復不會誤報)
注意:QINFO 有值的格式從未實測過(2026-08-05 掃 25 檔全為已開賣、皆空),
解析採多 pattern + 容錯,對不上時保留原始字串並在 stderr 警告。
"""
from __future__ import annotations

import re
import sys
import time
from datetime import datetime

import requests
from bs4 import BeautifulSoup

from ..model import Exhibition
from ..util import TAIPEI, guess_city

API = "https://tickets.udnfunlife.com/Application/UTK01/UTK0101_009.aspx/Product_Category_List"
DETAIL = "https://tickets.udnfunlife.com/application/UTK02/UTK0201_.aspx?PRODUCT_ID={pid}"
CATEGORY_NAMES = {"231": "展覽", "205": "演唱會"}
PAGE_SIZE = 24


def fetch(session: requests.Session, categories: list[str] | None = None) -> list[Exhibition]:
    out = _fetch_list(session, categories)
    for ex in out:
        _enrich_sale_info(session, ex)
        time.sleep(0.5)
    return out


def _fetch_list(session: requests.Session, categories: list[str] | None) -> list[Exhibition]:
    out: dict[str, Exhibition] = {}
    for cat in categories or ["231"]:
        page = 1
        total = None
        got = 0
        while total is None or got < total:
            r = session.post(
                API,
                json={"category": cat, "pageNo": str(page), "pageSize": str(PAGE_SIZE)},
                headers={"Content-Type": "application/json; charset=utf-8"},
                timeout=60,
            )
            r.raise_for_status()
            rd = r.json()["d"]["ReturnData"]
            total = int(rd.get("totalCount") or 0)
            cards = _parse_cards(rd.get("script") or "", CATEGORY_NAMES.get(cat, cat))
            if not cards:
                break  # 防禦:回空頁就停,避免無限迴圈
            got += len(cards)
            for ex in cards:
                out.setdefault(ex.id, ex)
            page += 1
            if page > 50:
                break
    return list(out.values())


QINFO_RE = re.compile(
    r'id="ctl00_ContentPlaceHolder1_QINFO"[^>]*?value="([^"]*)"')
# 格式未實測(見模組 docstring),涵蓋 JS Date 常見可解析形式
SALE_DT_PATTERNS = ["%Y/%m/%d %H:%M:%S", "%Y/%m/%d %H:%M", "%Y/%m/%d",
                    "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M:%S",
                    "%Y-%m-%dT%H:%M", "%Y-%m-%d"]


def _enrich_sale_info(session: requests.Session, ex: Exhibition) -> None:
    """讀詳情頁 QINFO 推導 onSales / saleStartAt。失敗 → UNKNOWN,不讓單檔炸掉整源。"""
    try:
        r = session.get(DETAIL.format(pid=ex.sourceId), timeout=30)
        r.raise_for_status()
        m = QINFO_RE.search(r.text)
        # value 屬性不存在(如 <input id=... />)視同空值
        raw = (m.group(1) if m else "").strip()
    except Exception as e:
        print(f"[udn] {ex.sourceId} 詳情頁失敗,onSales=UNKNOWN: {e}", file=sys.stderr)
        ex.onSales = "UNKNOWN"
        ex.saleStartAt = None
        return

    if not raw:
        ex.onSales = "Y"
        return

    ex.saleStartText = raw
    dt = _parse_sale_dt(raw)
    if dt is None:
        print(f"[udn] {ex.sourceId} QINFO 格式解析失敗(首見樣本!): {raw!r}", file=sys.stderr)
        ex.onSales = "UNKNOWN"
        return
    ex.saleStartAt = dt.strftime("%Y-%m-%dT%H:%M")
    ex.onSales = "N" if dt > datetime.now(TAIPEI).replace(tzinfo=None) else "Y"


def _parse_sale_dt(raw: str) -> datetime | None:
    for p in SALE_DT_PATTERNS:
        try:
            return datetime.strptime(raw.strip(), p)
        except ValueError:
            continue
    return None


def _parse_cards(html: str, category: str) -> list[Exhibition]:
    out = []
    soup = BeautifulSoup(html, "html.parser")
    for card in soup.select("div.yd_card"):
        a = card.find("a", href=re.compile(r"PRODUCT_ID="))
        if not a:
            continue
        pid = re.search(r"PRODUCT_ID=(\w+)", a["href"]).group(1)
        title = (a.get("title") or "").strip() or card.get_text(strip=True)[:60]
        start_meta = card.find("meta", itemprop="startDate")
        end_meta = card.find("meta", itemprop="endDate")
        if not (start_meta and end_meta):
            continue
        texts = [" ".join(d.get_text().split()) for d in card.select(".yd_card-iconText")]
        venue = next((t for t in texts if not re.match(r"\d{4}/", t)), "")
        price_el = card.select_one(".yd_card-price")
        price = " ".join(price_el.get_text().split()) if price_el else ""
        img = card.find("img", src=re.compile(r"_Product", re.I))
        out.append(Exhibition(
            id=f"udn:{pid}",
            source="udn",
            sourceId=pid,
            title=title,
            startDate=start_meta["content"],
            endDate=end_meta["content"],
            venueName=venue,
            city=guess_city(venue),
            category=category,
            priceText=price,
            onSales="Y",
            ticketUrl=DETAIL.format(pid=pid),
            imageUrl=(img.get("src") or "") if img else "",
        ))
    return out
