"""與前次輸出對帳:
1. twtc 的 id 是「展名+起日」推導的代理鍵,主辦方改名/改期會讓 id 變 →
   用「標題相似 + 檔期相近」把新資料對回舊 id,避免使用者的訂閱無聲丟失。
2. 從來源消失的展覽標 removedAt 保留(app 提醒續用最後已知日期),不物理刪除。
3. 保留期滿(閉展 30 天後,或消失超過 60 天)才真正移出檔案,控制檔案大小。
4. 讀前次輸出檔時逐筆容錯:舊檔是上一個版本的 schema(moc 只有 12 欄、當時沒有
   saleStartAt/openTime),欄位改名或新增時若直接走 dataclass 建構會整支爬蟲中斷。
   壞掉的單筆丟掉並印警告,不讓它拖垮整次抓取。
"""
from __future__ import annotations

import dataclasses
import logging
from datetime import date, timedelta

from .model import Exhibition
from .util import dates_overlap, title_similarity, today_taipei

log = logging.getLogger(__name__)

SIM_THRESHOLD = 0.6
START_NEAR_DAYS = 45      # 檔期沒重疊時,起日相差在此天數內仍視為同檔改期
KEEP_ENDED_DAYS = 30
KEEP_REMOVED_DAYS = 60
_DATE_ONLY_LEN = 10       # "YYYY-MM-DD"

_FIELDS = {f.name for f in dataclasses.fields(Exhibition)}


def _exhibition_from_prev(p: dict) -> Exhibition | None:
    """用前次輸出的 dict 重建 Exhibition。壞掉的一筆回 None(呼叫端丟掉它)。

    容忍三種舊檔:欄位改名的殘留、新增欄位缺漏、多出來的欄位。id 是必須的,
    沒 id 就沒身分可比對。
    """
    if not isinstance(p, dict) or not p.get("id"):
        return None
    unknown = set(p) - _FIELDS
    if unknown:
        log.warning("前次資料的 id=%s 含未知欄位 %s,已忽略", p.get("id"), sorted(unknown))
    known = {k: v for k, v in p.items() if k in _FIELDS}
    try:
        return Exhibition(**known)
    except TypeError as e:
        log.warning("前次資料的 id=%s 無法還原(%s),已丟棄該筆", p.get("id"), e)
        return None
    except Exception as e:                      # 缺必要欄位等
        log.warning("前次資料的 id=%s 解析失敗(%s),已丟棄該筆", p.get("id"), e)
        return None


def reconcile(
    fresh: list[Exhibition],
    prev: list[dict],
    fresh_sources: set[str],
    now_iso: str,
) -> list[Exhibition]:
    """fresh: 本次抓到的;prev: 上次輸出的 exhibitions;fresh_sources: 本次成功抓取的來源
    (失敗的來源整包沿用 prev,不做移除判定)。回傳合併後清單。

    prev 中無法還原的單筆會被丟棄並記警告 —— 舊檔壞掉時寧可少幾筆舊資料,
    也不要讓整個來源因此抓不到新資料。
    """
    today = today_taipei()

    # 先把壞掉的舊資料濾掉,後面的比對與沿用都只看能還原的
    prev = [p for p in (_exhibition_from_prev(p) for p in prev) if p is not None]
    fresh = _reuse_twtc_ids(fresh, prev)

    merged: dict[str, Exhibition] = {}
    for ex in fresh:
        ex.lastSeenAt = now_iso
        ex.removedAt = None
        merged[ex.id] = ex

    for old in prev:
        if old.id in merged:
            continue
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
    except (ValueError, TypeError):
        ended_long_ago = True
    if ended_long_ago:
        return False
    if ex.removedAt:
        removed_date = _parse_date(ex.removedAt)
        # removedAt 壞掉時無法判斷保留期,選擇保留(少刪勝過誤刪使用者的追蹤對象)
        if removed_date is not None and removed_date < today - timedelta(days=KEEP_REMOVED_DAYS):
            return False
    return True


def _parse_date(value) -> date | None:
    """只取日期部分解析。removedAt 可能是 "2026-08-01T08:00:00+08:00",也可能壞掉。"""
    if not isinstance(value, str) or len(value) < _DATE_ONLY_LEN:
        return None
    try:
        return date.fromisoformat(value[:_DATE_ONLY_LEN])
    except ValueError:
        return None


def _reuse_twtc_ids(fresh: list[Exhibition], prev: list[Exhibition]) -> list[Exhibition]:
    fresh_ids = {ex.id for ex in fresh}
    # 只拿「新資料裡沒有的舊 twtc id」當候選,exact match 的不用對
    candidates = [p for p in prev if p.source == "twtc" and p.id not in fresh_ids]
    unmatched_new = [ex for ex in fresh if ex.source == "twtc" and not any(
        p.id == ex.id for p in prev)]

    # 貪婪配對:全部組合算分,分數高的先配,一個舊 id 只給一個新展
    scored = []
    for ex in unmatched_new:
        for p in candidates:
            sim = title_similarity(ex.title, p.title)
            if sim < SIM_THRESHOLD:
                continue
            if not _dates_near(ex, p):
                continue
            scored.append((sim, ex, p))
    scored.sort(key=lambda t: -t[0])
    used_old, used_new = set(), set()
    for sim, ex, p in scored:
        if p.id in used_old or id(ex) in used_new:
            continue
        used_old.add(p.id)
        used_new.add(id(ex))
        ex.id = p.id
        ex.sourceId = p.id.split(":", 1)[1]
    return fresh


def _dates_near(ex: Exhibition, p: Exhibition) -> bool:
    if dates_overlap(ex.startDate, ex.endDate, p.startDate, p.endDate):
        return True
    try:
        delta = abs((date.fromisoformat(ex.startDate) - date.fromisoformat(p.startDate)).days)
        return delta <= START_NEAR_DAYS
    except (TypeError, ValueError):
        return False
