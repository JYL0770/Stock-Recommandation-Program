"""데이터 수집 실행 스크립트

사용 예:
    python collect_data.py                         # KOSPI+KOSDAQ 전체 수집 (2015년~)
    python collect_data.py --tickers 005930 000660 # 특정 종목만
    python collect_data.py --start 20200101 --limit 50 --skip-info
"""
from __future__ import annotations

import argparse
import logging
from datetime import timedelta

from kiwoom.collector import KiwoomCollector
from kiwoom.client import KiwoomClient, KiwoomConfig
from kiwoom.storage import Storage

logger = logging.getLogger("main")


def parse_args():
    p = argparse.ArgumentParser(description="키움 REST API 주식 데이터 수집")
    p.add_argument("--tickers", nargs="*", help="수집할 종목코드 (미지정 시 전체 유니버스)")
    p.add_argument("--markets", nargs="*", default=["KOSPI", "KOSDAQ"])
    p.add_argument("--start", default="20150101", help="수집 시작일 YYYYMMDD")
    p.add_argument("--limit", type=int, help="테스트용: 앞에서부터 N개 종목만")
    p.add_argument("--skip-info", action="store_true", help="기본정보/재무지표 수집 생략")
    p.add_argument("--data-dir", default="data")
    return p.parse_args()


def main():
    args = parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    client = KiwoomClient(KiwoomConfig.from_env())
    collector = KiwoomCollector(client)
    storage = Storage(args.data_dir)

    # 1) 종목 목록 / 유니버스
    if args.tickers:
        tickers = args.tickers
    else:
        stocks = collector.get_stock_list(tuple(args.markets))
        storage.save_table("stock_list", stocks)
        universe = collector.filter_universe(stocks)
        storage.save_table("universe", universe)
        tickers = universe["ticker"].tolist()
        logger.info("전체 %d개 중 유니버스 %d개", len(stocks), len(tickers))
    if args.limit:
        tickers = tickers[: args.limit]

    # 2) 일봉 (이미 받은 종목은 마지막 날짜 이후만 증분 수집)
    failed = []
    for i, t in enumerate(tickers, 1):
        try:
            last = storage.last_date(t)
            start = (last - timedelta(days=7)).strftime("%Y%m%d") if last is not None else args.start
            df = collector.get_daily_ohlcv(t, start=start)
            if not df.empty:
                total = storage.save_daily(t, df)
                logger.info("[%d/%d] %s 일봉 %d행 (누적 %d행)", i, len(tickers), t, len(df), len(total))
        except Exception as e:
            logger.warning("[%d/%d] %s 일봉 수집 실패: %s", i, len(tickers), t, e)
            failed.append(t)

    # 3) 기본정보/재무지표 스냅샷 (날짜별로 쌓아 두면 나중에 시계열 피처로 활용 가능)
    if not args.skip_info:
        info = collector.get_basic_info_bulk(tickers)
        if not info.empty:
            path = storage.save_table(f"basic_info_{info['as_of'].iloc[0].replace('-', '')}", info)
            logger.info("기본정보 %d건 저장: %s", len(info), path)

    if failed:
        logger.warning("실패 종목 %d개: %s", len(failed), failed)
    logger.info("완료")


if __name__ == "__main__":
    main()
