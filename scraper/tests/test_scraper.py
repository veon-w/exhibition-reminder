"""fixture 全部來自真實網站回應(三源為 2026-08-05,ff/cwt 為 2026-08-15)。"""
import json
from datetime import timedelta
from pathlib import Path

import pytest

from expo_scraper.links import build_links
from expo_scraper.model import Exhibition
from expo_scraper.reconcile import reconcile
from expo_scraper.sources import cwt as cwt_src
from expo_scraper.sources import ff as ff_src
from expo_scraper.sources.moc import _parse_one
from expo_scraper.sources.twtc import _parse_rows
from expo_scraper.sources.udn import QINFO_RE, _parse_cards, _parse_sale_dt
from expo_scraper.util import (extract_city, guess_city, parse_md_period,
                               title_similarity, today_taipei)

FIX = Path(__file__).parent / "fixtures"


# ---------- util ----------

def test_extract_city_moc_weird_address():
    # 真實格式:郵遞區號夾中間、縣市重複
    assert extract_city("臺中市40453 臺中市北區館前路一號") == "臺中市"
    assert extract_city("台北市信義區信義路五段5號") == "臺北市"  # 台→臺
    assert extract_city("松山文創園區 5號倉庫") == ""


def test_guess_city_venue_hints():
    # 場地不含縣市名 → 靠場館關鍵字推斷(地區篩選要用)
    assert guess_city("松山文創園區 5號倉庫") == "臺北市"
    assert guess_city("駁二藝術特區 P3倉庫") == "高雄市"
    assert guess_city("臺北流行音樂中心 文化館 4~6樓") == "臺北市"
    assert guess_city("完全不認識的場地") == ""
    assert guess_city("臺中市某某展館") == "臺中市"  # 有縣市名優先


def test_parse_md_period_cross_year():
    assert parse_md_period("12/31 ~ 01/04", 2026) == ("2026-12-31", "2027-01-04")
    assert parse_md_period("07/03 ~ 07/06", 2026) == ("2026-07-03", "2026-07-06")


def test_title_similarity():
    assert title_similarity("埃及木乃伊—永生傳說", "埃及木乃伊-永生傳說") == 1.0
    assert title_similarity("完全不同的展", "埃及木乃伊") < 0.3


# ---------- 三源解析 ----------

def test_moc_parse():
    data = json.loads((FIX / "moc.json").read_text(encoding="utf-8"))
    exs = [x for e in data for x in _parse_one(e, "6", "2026-07-29")]
    assert len(exs) >= 400
    e = exs[0]
    assert e.id.startswith("moc:") and e.startDate <= e.endDate
    # 開閉館時間:大多數有值(09:00 等),無資訊(00:00-00:00)則為空字串
    with_hours = [x for x in exs if x.openTime]
    assert len(with_hours) > len(exs) * 0.9
    assert all(len(x.openTime) == 5 and ":" in x.openTime for x in with_hours)
    assert all(x.onSales in ("Y", "N", "UNKNOWN") for x in exs)
    # 殭屍資料被濾掉:全部 endDate >= cutoff
    assert all(x.endDate >= "2026-07-29" for x in exs)


def test_twtc_parse():
    from bs4 import BeautifulSoup
    soup = BeautifulSoup((FIX / "twtc.html").read_text(encoding="utf-8"), "html.parser")
    # 頁面有 4 個表:總表(5欄,含場地)+ 3 個依場館分頁的重複子表(4欄)。
    # 只解析 5 欄總表;fixture(2026 Q3)總表 44 筆 = 三個子表合計,無漏抓。
    exs = _parse_rows(soup, 2026)
    assert len(exs) == 44
    assert all(e.city == "臺北市" for e in exs)
    assert all("more" not in e.title.lower()[-6:] for e in exs)  # more 連結字樣已清除


def test_udn_parse():
    data = json.loads((FIX / "udn_api.json").read_text(encoding="utf-8"))
    exs = _parse_cards(data["d"]["ReturnData"]["script"], "展覽")
    assert len(exs) == 6
    e = next(x for x in exs if x.sourceId == "N23SE92T")
    assert e.startDate == "2022-09-01" and e.endDate == "2030-12-31"
    assert "NT" in e.priceText          # 票價在 yd_card-price
    assert "臺北流行音樂中心" in e.venueName
    assert e.onSales == "Y" and "PRODUCT_ID=N23SE92T" in e.ticketUrl


def test_udn_sale_dt_patterns():
    # QINFO 真實格式未驗證過,多 pattern 防禦(見 udn.py docstring)
    assert _parse_sale_dt("2026/08/08 12:00").isoformat() == "2026-08-08T12:00:00"
    assert _parse_sale_dt("2026/08/08 12:00:00").isoformat() == "2026-08-08T12:00:00"
    assert _parse_sale_dt("2026-08-08T12:00").isoformat() == "2026-08-08T12:00:00"
    assert _parse_sale_dt("2026/08/08").isoformat() == "2026-08-08T00:00:00"
    assert _parse_sale_dt("八月八日中午") is None  # 對不上 → None,不炸


def test_udn_qinfo_regex():
    assert QINFO_RE.search(
        '<input type="hidden" id="ctl00_ContentPlaceHolder1_QINFO" value="2026/08/08 12:00" />'
    ).group(1) == "2026/08/08 12:00"
    # 無 value 屬性(已開賣的實際樣態)→ 不匹配,視同空
    assert QINFO_RE.search('<input id="ctl00_ContentPlaceHolder1_QINFO" />') is None


# ---------- 同人場(ff / cwt) ----------

def _ff_parse(fixture: str, category: str):
    from bs4 import BeautifulSoup
    soup = BeautifulSoup((FIX / fixture).read_text(encoding="utf-8"), "html.parser")
    blocks = soup.select("div.activity-info")
    return [x for x in (ff_src._parse(b, category) for b in blocks) if x]


def test_ff_doujin_parse():
    exs = _ff_parse("ff_doujin.html", "同人展")
    assert len(exs) == 5
    ff47 = next(e for e in exs if "47" in e.title)
    # 三天活動:展期取最早~最晚,開放時間取第一天
    assert (ff47.startDate, ff47.endDate) == ("2026-08-21", "2026-08-23")
    assert ff47.openTime == "10:30" and ff47.closeTime == "17:00"
    assert ff47.venueName == "花博公園爭艷館"        # 括號內的地址已切開
    assert ff47.address == "台北市中山區玉門街一號"
    assert ff47.city == "臺北市"
    assert ff47.category == "同人展" and ff47.id.startswith("ff:")
    assert "同人誌即售會" in ff47.descriptionShort
    # 場地沒寫地址的(駁二)靠場館關鍵字推縣市
    bq = next(e for e in exs if "駁二" in e.venueName)
    assert bq.city == "高雄市" and bq.address == ""
    # PF44 用的是另一種冒號(︰)排版,一樣要吃得下
    pf44 = next(e for e in exs if e.title.startswith("Petit Fancy") and "44" in e.title)
    assert pf44.openTime == "10:30" and pf44.closeTime == "17:00"


def test_ff_concert_parse():
    exs = _ff_parse("ff_concert.html", "演唱會")
    assert len(exs) == 5
    e = next(x for x in exs if x.title.startswith("PERSONA"))
    assert e.startDate == e.endDate == "2026-10-10"
    assert e.category == "演唱會"
    # 「16:00 OPEN / 17:00 START」不是開放時間,不能被當成開閉館
    assert e.openTime == "" and e.closeTime == ""
    assert e.city == "新北市"


def test_cwt_parse():
    from bs4 import BeautifulSoup
    soup = BeautifulSoup((FIX / "cwt.html").read_text(encoding="utf-8"), "html.parser")
    exs = [x for x in (cwt_src._parse(b) for b in soup.select("div.activity")) if x]
    assert len(exs) == 5
    e = next(x for x in exs if x.sourceId == "170")
    assert e.id == "cwt:170"                       # 站方數字 id,改名改期都不會變
    assert e.title == "CWT-74 台灣同人誌販售會(台北場)"   # 原字串只有【CWT-74】(台北場)
    assert (e.startDate, e.endDate) == ("2026-12-12", "2026-12-13")
    assert e.city == "臺北市" and "台灣大學綜合體育館" in e.venueName
    assert e.officialUrl.startswith("https://www.comicworld.com.tw/Act")
    assert e.imageUrl.startswith("https://www.comicworld.com.tw/")
    assert all(x.category == "同人展" for x in exs)


def test_cwt_date_formats():
    d = cwt_src._parse_days
    assert [x.isoformat() for x in d("2026年8月29 / 30日")] == ["2026-08-29", "2026-08-30"]
    assert [x.isoformat() for x in d("2026年8月31 / 9月1日")] == ["2026-08-31", "2026-09-01"]
    assert [x.isoformat() for x in d("2026年12月31 / 1月1日")] == ["2026-12-31", "2027-01-01"]
    assert [x.isoformat() for x in d("2027年2月13日")] == ["2027-02-13"]
    assert d("日期另行公告") == []                  # 對不上 → 空,由呼叫端跳過


# ---------- reconciliation ----------

def _twtc(id_, title, start, end):
    return Exhibition(id=id_, source="twtc", sourceId=id_.split(":")[1], title=title,
                      startDate=start, endDate=end)


# reconcile 內部用 today_taipei() 讀系統日期來決定保留期,所以測試的檔期必須
# 相對「今天」而不是寫死常數 —— 寫死的話日子一過就會被 KEEP_ENDED_DAYS 濾掉,
# 測試會在某天無聲地開始失敗(2026-10 就發生過一次)。
_TODAY = today_taipei()


def _day(offset):
    return (_TODAY + timedelta(days=offset)).isoformat()


# now_iso 也必須是「現在」。removedAt 會被當成移除時間再加 KEEP_REMOVED_DAYS 天保留,
# 傳一個過去的時間戳會讓項目一進來就被判定過期而直接消失。
_NOW_ISO = f"{_TODAY.isoformat()}T08:00:00+08:00"


def test_reconcile_rename_keeps_old_id():
    """展名微調 → hash 變 → 必須沿用舊 id,訂閱才不會丟。"""
    prev = [_twtc("twtc:aaa", "2026台北國際旅展", _day(30), _day(33)).to_dict()]
    fresh = [_twtc("twtc:bbb", "2026 台北國際旅展 ITF", _day(31), _day(34))]
    out = reconcile(fresh, prev, {"twtc"}, _NOW_ISO)
    assert len(out) == 1 and out[0].id == "twtc:aaa"
    assert out[0].title == "2026 台北國際旅展 ITF"  # 資料用新的,id 用舊的


def test_reconcile_removed_marked_not_deleted():
    prev = [_twtc("twtc:aaa", "某展", _day(20), _day(25)).to_dict()]
    out = reconcile([], prev, {"twtc"}, _NOW_ISO)
    assert len(out) == 1 and out[0].removedAt is not None


def test_reconcile_failed_source_untouched():
    """該源本次抓失敗 → 不做移除判定,原樣沿用。"""
    prev = [_twtc("twtc:aaa", "某展", _day(20), _day(25)).to_dict()]
    out = reconcile([], prev, set(), _NOW_ISO)
    assert len(out) == 1 and out[0].removedAt is None


def test_reconcile_old_ended_dropped():
    prev = [_twtc("twtc:old", "早就結束的展", _day(-400), _day(-395)).to_dict()]
    out = reconcile([], prev, {"twtc"}, _NOW_ISO)
    assert out == []


def test_reconcile_ended_within_grace_kept():
    """閉展未滿 KEEP_ENDED_DAYS 天仍保留(保留期邊界)。"""
    prev = [_twtc("twtc:recent", "剛結束的展", _day(-40), _day(-10)).to_dict()]
    out = reconcile([], prev, {"twtc"}, _NOW_ISO)
    assert len(out) == 1 and out[0].removedAt is not None


# ---------- 前次資料檔的容錯 ----------
# 舊檔是上一個版本的 schema:欄位被改名或新增時,不能讓整支爬蟲中斷(會連新資料都拿不到)。

def _keep_one(prev_entry):
    """只放一筆前次資料,該來源本次成功但沒回傳它 → 會走「沿用舊資料」那條路。"""
    return reconcile([], [prev_entry], set(), _NOW_ISO)


def test_prev_with_unknown_field_is_ignored():
    """舊檔多了後來改名的欄位(如 saleStartTime)→ 忽略該欄位,不要 TypeError 整支掛掉。"""
    p = _twtc("twtc:aaa", "某展", _day(20), _day(25)).to_dict()
    p["saleStartTime"] = "2026-08-08T12:00"          # 已改名的舊欄位
    out = _keep_one(p)
    assert len(out) == 1 and out[0].id == "twtc:aaa"


def test_prev_missing_field_is_dropped_not_fatal():
    """舊檔少了必要欄位 → 丟掉那一筆,其他筆照常保留。"""
    bad = _twtc("twtc:bad", "缺欄位的展", _day(20), _day(25)).to_dict()
    del bad["sourceId"]
    good = _twtc("twtc:good", "正常的展", _day(20), _day(25)).to_dict()
    out = reconcile([], [bad, good], set(), _NOW_ISO)
    assert [e.id for e in out] == ["twtc:good"]


def test_prev_broken_removedAt_does_not_crash():
    """removedAt 不是 ISO → 以前 fromisoformat 會拋 ValueError,現在要能容忍。"""
    p = _twtc("twtc:aaa", "某展", _day(20), _day(25)).to_dict()
    p["removedAt"] = "壞掉的時間字串"
    out = _keep_one(p)
    assert len(out) == 1


def test_prev_broken_removedAt_keeps_record():
    """無法判斷保留期時選擇保留,不要因為解析失敗就默默刪掉使用者的追蹤對象。"""
    p = _twtc("twtc:aaa", "某展", _day(20), _day(25)).to_dict()
    p["removedAt"] = "not-a-date"
    assert len(_keep_one(p)) == 1


def test_prev_not_a_dict_is_skipped():
    """整筆不是物件(例如舊檔被手改成字串)→ 跳過,不影響其他筆。"""
    good = _twtc("twtc:good", "正常的展", _day(20), _day(25)).to_dict()
    out = reconcile([], ["壞掉的一筆", None, good], set(), _NOW_ISO)
    assert [e.id for e in out] == ["twtc:good"]


def test_prev_entry_without_id_is_skipped():
    p = _twtc("twtc:aaa", "某展", _day(20), _day(25)).to_dict()
    p.pop("id")
    good = _twtc("twtc:good", "正常的展", _day(20), _day(25)).to_dict()
    out = reconcile([], [p, good], set(), _NOW_ISO)
    assert [e.id for e in out] == ["twtc:good"]


def test_real_repo_prev_file_still_reconciles():
    """真的 repo 資料檔(上一個 schema)必須能被還原,這是防回歸的重點。"""
    import json as _json
    from pathlib import Path as _Path
    raw = _Path(__file__).resolve().parents[2] / "data" / "exhibitions.json"
    if not raw.exists():
        pytest.skip("沒有 data/exhibitions.json")
    doc = _json.loads(raw.read_text(encoding="utf-8"))
    entries = doc.get("exhibitions", [])
    assert entries, "資料檔應該是空的?"
    out = reconcile([], entries, set(), _NOW_ISO)
    # 全部來源都算「本次失敗」→ 除了超過保留期的,其他都該原樣沿用
    assert len(out) > len(entries) * 0.5, f"沿用比例太低:{len(out)}/{len(entries)}"


# ---------- links ----------

def test_links_cross_source_only():
    a = _twtc("twtc:aaa", "台北寵物展", "2026-09-01", "2026-09-05")
    b = Exhibition(id="udn:x", source="udn", sourceId="x", title="臺北寵物展",
                   startDate="2026-09-01", endDate="2026-09-05")
    c = _twtc("twtc:ccc", "台北寵物展(二館)", "2026-09-01", "2026-09-05")
    links = build_links([a, b, c])
    pairs = {(l.idA, l.idB) for l in links}
    assert ("twtc:aaa", "udn:x") in pairs
    assert ("twtc:aaa", "twtc:ccc") not in pairs  # 同源不建議


def test_links_no_date_overlap_no_link():
    a = _twtc("twtc:aaa", "台北寵物展", "2026-09-01", "2026-09-05")
    b = Exhibition(id="udn:x", source="udn", sourceId="x", title="台北寵物展",
                   startDate="2027-03-01", endDate="2027-03-05")
    assert build_links([a, b]) == []
