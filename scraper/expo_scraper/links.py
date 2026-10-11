"""跨源同檔建議連結(suggestedLinks)。
只建議、不合併資料:錯誤合併(把 A 展日期蓋到 B 展)比重複顯示傷害大。
app 端用途:追蹤中的展(moc/twtc)出現指向 udn 的新連結 → 觸發「開賣了」提醒。
"""
from __future__ import annotations

from .model import Exhibition, SuggestedLink
from .util import dates_overlap, title_similarity

SIM_THRESHOLD = 0.55


def build_links(exhibitions: list[Exhibition]) -> list[SuggestedLink]:
    active = [e for e in exhibitions if not e.removedAt]
    out = []
    for i, a in enumerate(active):
        for b in active[i + 1:]:
            if a.source == b.source:
                continue
            if not dates_overlap(a.startDate, a.endDate, b.startDate, b.endDate):
                continue
            sim = title_similarity(a.title, b.title)
            if sim >= SIM_THRESHOLD:
                out.append(SuggestedLink(idA=a.id, idB=b.id, confidence=round(sim, 2)))
    return out
