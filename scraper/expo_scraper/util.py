"""共用工具:縣市解析、標題正規化、相似度、日期。"""
from __future__ import annotations

import re
import unicodedata
from datetime import date, datetime
from zoneinfo import ZoneInfo

TAIPEI = ZoneInfo("Asia/Taipei")

CITIES = [
    "臺北市", "新北市", "桃園市", "臺中市", "臺南市", "高雄市",
    "基隆市", "新竹市", "嘉義市",
    "新竹縣", "苗栗縣", "彰化縣", "南投縣", "雲林縣", "嘉義縣",
    "屏東縣", "宜蘭縣", "花蓮縣", "臺東縣", "澎湖縣", "金門縣", "連江縣",
]


def today_taipei() -> date:
    return datetime.now(TAIPEI).date()


def now_taipei_iso() -> str:
    return datetime.now(TAIPEI).replace(microsecond=0).isoformat()


def norm_tw(s: str) -> str:
    return s.replace("台", "臺")


def extract_city(text: str) -> str:
    """從地址/場地文字抓第一個出現的縣市名。
    moc 地址有「臺中市40453 臺中市北區…」郵遞區號夾中間的格式,取第一個匹配即可。
    """
    if not text:
        return ""
    t = norm_tw(text)
    best_pos, best_city = len(t) + 1, ""
    for c in CITIES:
        p = t.find(c)
        if 0 <= p < best_pos:
            best_pos, best_city = p, c
    return best_city


# 場地名稱本身不含縣市時的推斷表(關鍵字 → 縣市)。udn 特展/演唱會常只給場館名。
VENUE_CITY_HINTS = {
    "松山文創": "臺北市", "華山1914": "臺北市", "華山文創": "臺北市",
    "臺北流行音樂中心": "臺北市", "北流": "臺北市", "小巨蛋": "臺北市",
    "國父紀念館": "臺北市", "中正紀念堂": "臺北市", "科學教育館": "臺北市",
    "士林科教館": "臺北市", "故宮": "臺北市", "花博": "臺北市",
    "南港展覽館": "臺北市", "世貿": "臺北市", "三創": "臺北市",
    "新莊": "新北市", "板橋": "新北市", "宏匯廣場": "新北市",
    "高雄流行音樂中心": "高雄市", "駁二": "高雄市", "衛武營": "高雄市",
    "高雄巨蛋": "高雄市", "夢時代": "高雄市",
    "臺中國家歌劇院": "臺中市", "國家歌劇院": "臺中市", "臺中軟體園區": "臺中市",
    "奇美博物館": "臺南市", "十鼓": "臺南市", "臺南國家美術館": "臺南市",
    "國立歷史博物館": "臺北市", "富邦美術館": "臺北市", "忠泰美術館": "臺北市",
    "建國啤酒廠": "臺北市", "中原文創園區": "桃園市",
}


def guess_city(venue: str) -> str:
    """場地文字推縣市:先找縣市名,找不到再比對常見場館關鍵字。"""
    city = extract_city(venue)
    if city:
        return city
    v = norm_tw(venue or "")
    for kw, c in VENUE_CITY_HINTS.items():
        if norm_tw(kw) in v:
            return c
    return ""


def normalize_title(s: str) -> str:
    """比對用標題:全形轉半形、去空白標點、小寫、台→臺。"""
    s = unicodedata.normalize("NFKC", s)
    s = norm_tw(s.lower())
    return re.sub(r"[\W_]+", "", s, flags=re.UNICODE)


def _bigrams(s: str) -> set[str]:
    if len(s) < 2:
        return {s} if s else set()
    return {s[i:i + 2] for i in range(len(s) - 1)}


def title_similarity(a: str, b: str) -> float:
    """正規化後的字元 bigram Jaccard 相似度,0~1。"""
    na, nb = normalize_title(a), normalize_title(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    A, B = _bigrams(na), _bigrams(nb)
    if not A or not B:
        return 0.0
    return len(A & B) / len(A | B)


def slash_date_to_iso(s: str) -> str:
    """'2026/08/04' → '2026-08-04'"""
    return datetime.strptime(s.strip(), "%Y/%m/%d").date().isoformat()


def parse_md_period(period: str, year: int) -> tuple[str, str]:
    """twtc 的 '07/03 ~ 07/06' + 年份 → ISO 起訖;結束月份小於開始月份視為跨年。"""
    m = re.match(r"\s*(\d{1,2})/(\d{1,2})\s*~\s*(\d{1,2})/(\d{1,2})", period)
    if not m:
        raise ValueError(f"無法解析檔期: {period!r}")
    sm, sd, em, ed = (int(x) for x in m.groups())
    ey = year + 1 if em < sm else year
    return date(year, sm, sd).isoformat(), date(ey, em, ed).isoformat()


def strip_html(s: str) -> str:
    s = re.sub(r"<[^>]+>", " ", s or "")
    return " ".join(s.split())


def truncate(s: str, n: int = 200) -> str:
    return s if len(s) <= n else s[: n - 1] + "…"


def dates_overlap(s1: str, e1: str, s2: str, e2: str) -> bool:
    return s1 <= e2 and s2 <= e1
