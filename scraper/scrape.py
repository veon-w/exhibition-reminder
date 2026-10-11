#!/usr/bin/env python3
"""展覽資料彙整入口。

用法:
    python scrape.py --out ../data/exhibitions.json
--out 同時也是前次輸出的位置(對帳 + 失敗沿用都靠它)。
單源失敗:該源沿用前次資料、sourceStatus 標 stale;全部失敗:exit 1(不覆寫檔案)。
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
        prev = json.loads(out_path.read_text(encoding="utf-8"))

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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
