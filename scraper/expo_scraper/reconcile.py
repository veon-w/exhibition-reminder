"""與前次輸出對帳:
1. twtc 的 id 是「展名+起日」推導的代理鍵,主辦方改名/改期會讓 id 變 →
   用「標題相似 + 檔期相近」把新資料對回舊 id,避免使用者的訂閱無聲丟失。
2. 從來源消失的展覽標 removedAt 保留(app 提醒續用最後已知日期),不物理刪除。
3. 保留期滿(閉展 30 天後,或消失超過 60 天)才真正移出檔案,控制檔案大小。
"""
from __future__ import annotations

from datetime import date, timedelta

from .model import Exhibition
from .util import dates_overlap, title_similarity, today_taipei

SIM_THRESHOLD = 0.6
START_NEAR_DAYS = 45      # 檔期沒重疊時,起日相差在此天數內仍視為同檔改期
KEEP_ENDED_DAYS = 30
KEEP_REMOVED_DAYS = 60


def reconcile(
    fresh: list[Exhibition],
    prev: list[dict],
    fresh_sources: set[str],
    now_iso: str,
) -> list[Exhibition]:
    """fresh: 本次抓到的;prev: 上次輸出的 exhibitions;fresh_sources: 本次成功抓取的來源
    (失敗的來源整包沿用 prev,不做移除判定)。回傳合併後清單。"""
    today = today_taipei()

    fresh = _reuse_twtc_ids(fresh, prev)

    merged: dict[str, Exhibition] = {}
    for ex in fresh:
        ex.lastSeenAt = now_iso
        ex.removedAt = None
        merged[ex.id] = ex

    for p in prev:
        pid = p.get("id")
        if not pid or pid in merged:
            continue
        old = Exhibition(**p)
        if old.source in fresh_sources:
            # 本次有抓該來源但沒出現 → 從來源消失,標記保留
            if old.removedAt is None:
                old.removedAt = now_iso
        # 該來源本次失敗 → 原樣沿用(lastSeenAt / removedAt 不動)
        if _should_keep(old, today):
            merged[old.id] = old
    return list(merged.values())


def _should_keep(ex: Exhibition, today: date) -> bool:
    try:
        ended_long_ago = date.fromisoformat(ex.endDate) < today - timedelta(days=KEEP_ENDED_DAYS)
    except ValueError:
        ended_long_ago = True
    if ended_long_ago:
        return False
    if ex.removedAt:
        removed_date = date.fromisoformat(ex.removedAt[:10])
        if removed_date < today - timedelta(days=KEEP_REMOVED_DAYS):
            return False
    return True


def _reuse_twtc_ids(fresh: list[Exhibition], prev: list[dict]) -> list[Exhibition]:
    fresh_ids = {ex.id for ex in fresh}
    # 只拿「新資料裡沒有的舊 twtc id」當候選,exact match 的不用對
    candidates = [p for p in prev if p.get("source") == "twtc" and p.get("id") not in fresh_ids]
    unmatched_new = [ex for ex in fresh if ex.source == "twtc" and not any(
        p.get("id") == ex.id for p in prev)]

    # 貪婪配對:全部組合算分,分數高的先配,一個舊 id 只給一個新展
    scored = []
    for ex in unmatched_new:
        for p in candidates:
            sim = title_similarity(ex.title, p.get("title", ""))
            if sim < SIM_THRESHOLD:
                continue
            if not _dates_near(ex, p):
                continue
            scored.append((sim, ex, p))
    scored.sort(key=lambda t: -t[0])
    used_old, used_new = set(), set()
    for sim, ex, p in scored:
        if p["id"] in used_old or id(ex) in used_new:
            continue
        used_old.add(p["id"])
        used_new.add(id(ex))
        ex.id = p["id"]
        ex.sourceId = p["id"].split(":", 1)[1]
    return fresh


def _dates_near(ex: Exhibition, p: dict) -> bool:
    if dates_overlap(ex.startDate, ex.endDate, p.get("startDate", ""), p.get("endDate", "")):
        return True
    try:
        delta = abs((date.fromisoformat(ex.startDate) - date.fromisoformat(p["startDate"])).days)
        return delta <= START_NEAR_DAYS
    except (KeyError, ValueError):
        return False
