"""輸出 schema(schemaVersion 1)。欄位定義見 repo 根目錄 README「資料格式」節。"""
from __future__ import annotations

from dataclasses import dataclass, asdict, field


@dataclass
class Exhibition:
    id: str                 # "{source}:{sourceId}",twtc 經 reconciliation 沿用舊 id
    source: str             # "moc" | "twtc" | "udn" | "ff" | "cwt"
    sourceId: str
    title: str
    startDate: str          # ISO yyyy-mm-dd
    endDate: str
    venueName: str = ""
    address: str = ""
    city: str = ""
    lat: float | None = None
    lng: float | None = None
    organizer: str = ""
    category: str = ""      # 展覽 / 商展 / …
    priceText: str = ""
    onSales: str = "UNKNOWN"  # Y / N / UNKNOWN
    saleStartAt: str | None = None   # 開賣時間 ISO "2026-08-08T12:00"(台北),目前僅 udn 有
    saleStartText: str = ""          # 開賣時間原始字串(解析失敗時人還看得到)
    openTime: str = ""               # 每日開館 "09:00",目前僅 moc 有;空=未知
    closeTime: str = ""              # 每日閉館 "17:00"
    ticketUrl: str = ""
    officialUrl: str = ""
    imageUrl: str = ""
    descriptionShort: str = ""
    lastSeenAt: str = ""    # 最後一次出現在來源的同步時間(ISO)
    removedAt: str | None = None  # 從來源消失的時間;不物理刪除

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class SuggestedLink:
    idA: str
    idB: str
    confidence: float

    def to_dict(self) -> dict:
        return asdict(self)
