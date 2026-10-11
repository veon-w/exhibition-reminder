#!/usr/bin/env python3
"""展覽資料彙整入口。

用法:
    python scrape.py --out ../data/exhibitions.json
--out 同時也是前次輸出的位置(對帳 + 失敗沿用都靠它)。
退出碼:0 = 全部來源成功;2 = 部分來源失敗(檔案仍會寫出,該源標 stale);
1 = 全部失敗(不覆寫檔案)。CI 用 2 來亮紅燈,避免單源壞掉被埋在綠燈裡。
壞掉的前次資料檔會備份成 *.bad 後從零開始,不會讓今天也抓不到資料。
"""
from __future__ import annotations

import argparse
import json
import sys
import traceback
from pathlib import Path

import requests

from expo_scraper.links import build_links
from expo_scraper.model import Exhibition
from expo_scraper.reconcile import reconcile
from expo_scraper.sources import cwt, ff, moc, twtc, udn
from expo_scraper.util import now_taipei_iso

SCHEMA_VERSION = 1
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36 ExpoReminderBot/1.0")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="輸出(兼前次輸入)JSON 路徑")
    ap.add_argument("--moc-categories", default="6,17",
                    help="文化部類別,逗號分隔(6=展覽 17=演唱會 1=音樂)")
    ap.add_argument("--udn-categories", default="231,205",
                    help="udn 分類,逗號分隔(231=展覽 205=演唱會)")
    args = ap.parse_args()

    out_path = Path(args.out)
    prev = {"exhibitions": []}
    if out_path.exists():
        try:
            loaded = json.loads(out_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                prev = loaded
            else:
                raise ValueError(f"頂層應該是物件,實際是 {type(loaded).__name__}")
        except (json.JSONDecodeError, ValueError, UnicodeDecodeError) as e:
            # 前次檔案壞掉不該讓今天也抓不到資料。先備份再從零開始,
            # 這樣壞檔還留著可事後檢查,而使用者明天照樣有資料。
            bad = out_path.with_suffix(out_path.suffix + ".bad")
            try:
                out_path.replace(bad)
                where = f",已備份到 {bad}"
            except OSError:
                where = "(備份失敗)"
            print(f"前次資料檔無法解析({e}){where},本次視為無前次資料繼續", file=sys.stderr)

    session = requests.Session()
    session.headers["User-Agent"] = UA

    now_iso = now_taipei_iso()
    fresh: list[Exhibition] = []
    fresh_sources: set[str] = set()
    status: dict[str, str] = {}

    jobs = {
        "moc": lambda: moc.fetch(session, args.moc_categories.split(",")),
        "twtc": lambda: twtc.fetch(session),
        "udn": lambda: udn.fetch(session, args.udn_categories.split(",")),
        "ff": lambda: ff.fetch(session),
        "cwt": lambda: cwt.fetch(session),
    }
    for name, job in jobs.items():
        try:
            items = job()
            if not items:
                raise RuntimeError("抓到 0 筆,視為失敗(避免整源被誤標移除)")
            fresh.extend(items)
            fresh_sources.add(name)
            status[name] = "ok"
            print(f"[{name}] ok, {len(items)} 筆", file=sys.stderr)
        except Exception:
            status[name] = "stale"
            print(f"[{name}] 失敗,沿用前次資料:\n{traceback.format_exc()}", file=sys.stderr)

    if not fresh_sources:
        print("所有來源全部失敗,不更新檔案", file=sys.stderr)
        return 1

    merged = reconcile(fresh, prev.get("exhibitions", []), fresh_sources, now_iso)
    merged.sort(key=lambda e: (e.startDate, e.id))
    links = build_links(merged)

    doc = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": now_iso,
        "sourceStatus": status,
        "exhibitions": [e.to_dict() for e in merged],
        "suggestedLinks": [l.to_dict() for l in links],
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"輸出 {len(merged)} 筆展覽、{len(links)} 條跨源連結 → {out_path}", file=sys.stderr)

    stale = sorted(n for n, s in status.items() if s != "ok")
    if stale:
        # 檔案照寫(部分資料總比沒有好),但用退出碼 2 讓呼叫端知道這是「部分失敗」,
        # 讓 CI 能亮紅燈。只回 0 的話單源壞掉會被埋在綠燈裡,沒人會發現。
        print(f"部分來源失敗:{', '.join(stale)} (其餘照常輸出,退出碼 2)", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
