"""가격 지표 기반 추천 결과를 터미널에 출력 / CSV 저장

사용 예:
    python recommend.py --demo                       # 가상 데이터로 체험
    python recommend.py --strategy 추세추종 --top 30  # data/ 의 수집 데이터 사용
    python recommend.py --backtest --csv out.csv
"""
from __future__ import annotations

import argparse

import pandas as pd

from recommender import (
    STRATEGIES, Filters, attach_meta, compute_features, latest_snapshot,
    load_collected, make_demo_data, recommend, run_backtest,
)


def main():
    p = argparse.ArgumentParser(description="가격 지표 기반 국내 주식 추천")
    p.add_argument("--demo", action="store_true", help="가상 데모 데이터 사용")
    p.add_argument("--data-dir", default="data")
    p.add_argument("--strategy", default="균형형", choices=list(STRATEGIES))
    p.add_argument("--top", type=int, default=20)
    p.add_argument("--min-amount", type=float, default=10, help="20일 평균 거래대금 하한 (억원)")
    p.add_argument("--max-rsi", type=float, help="RSI 상한 (과열 종목 제외)")
    p.add_argument("--above-ma120", action="store_true", help="120일선 위 종목만")
    p.add_argument("--backtest", action="store_true")
    p.add_argument("--csv", help="추천 결과 저장 경로")
    p.add_argument("--trend", action="store_true", help="주봉 추세선 채널 매수권 종목 목록")
    p.add_argument("--trend-width", type=float, default=0.10, help="채널 폭 (상단선 = 지지선 × (1+폭))")
    a = p.parse_args()

    daily, meta = make_demo_data() if a.demo else load_collected(a.data_dir)
    if daily.empty:
        raise SystemExit(f"{a.data_dir}/daily 에 데이터가 없습니다. collect_data.py 실행 또는 --demo 사용")

    if a.trend:
        from recommender.trendline import CHECK_LABELS, CHECKS, STATUS_IN, load_index_daily, scan
        idx_daily = load_index_daily(daily, meta, None if a.demo else a.data_dir)
        tv, idx = scan(daily, meta, idx_daily, width=a.trend_width, only_buy=False)
        print("\n[지수]")
        for m, (d, w) in idx.items():
            print(f"  {m}: 일봉 {d['status'] if d else '추세선 없음'} / 주봉 {w['status'] if w else '추세선 없음'}")
        buy = tv[tv["buy"]] if not tv.empty else tv
        print(f"\n[매수권] 종목 일봉·주봉 + 지수 일봉·주봉 4개 모두 충족: {len(buy)}종목")
        for i, r in enumerate(buy.itertuples(), 1):
            print(f"{i:>3}. {r.ticker} {r.name:<12} 현재 {r.close:>10,.0f} | 일봉 지지 {r.d_support:>10,.0f} | 주봉 지지 {r.w_support:>10,.0f}")
        part = tv[(~tv["buy"]) & tv["ok_stock_daily"] & tv["ok_stock_weekly"]] if not tv.empty else tv
        if len(part):
            print(f"\n(참고) 종목 조건은 충족, 지수 조건 미충족: {', '.join(part['name'])}")
        if a.csv:
            tv.to_csv(a.csv, index=False, encoding="utf-8-sig")
        return

    feats = compute_features(daily)
    snap = attach_meta(latest_snapshot(feats), meta)
    strat = STRATEGIES[a.strategy]
    filters = Filters(min_amount=a.min_amount * 100, max_rsi=a.max_rsi, above_ma120=a.above_ma120)
    top = recommend(snap, strat.weights, filters, a.top)

    print(f"\n[{strat.name}] {strat.description}")
    print(f"기준일 {snap['date'].max():%Y-%m-%d} | 후보 {top['n_candidates'].iloc[0] if len(top) else 0}종목 중 상위 {len(top)}\n")
    for _, r in top.iterrows():
        name = r.get("name") if pd.notna(r.get("name")) else ""
        print(f"{r['rank']:>3}. {r['ticker']} {name:<12} 점수 {r['score']:5.1f} | 종가 {r['close']:>10,.0f} "
              f"| 3M {r['ret_60'] * 100:+6.1f}% | RSI {r['rsi14']:4.0f}")
        print("      + " + " / ".join(r["reasons"]))
        if r["warnings"]:
            print("      ! " + " / ".join(r["warnings"]))

    if a.csv:
        out = top.copy()
        out["reasons"] = out["reasons"].str.join(" / ")
        out["warnings"] = out["warnings"].str.join(" / ")
        out.to_csv(a.csv, index=False, encoding="utf-8-sig")
        print(f"\n저장: {a.csv}")

    if a.backtest:
        n = min(a.top, 10)
        res = run_backtest(feats, strat.weights, filters, top_n=n)
        print(f"\n[백테스트] 20거래일 리밸런싱, 상위 {n}종목 동일비중, 거래비용 0.3%")
        for k, v in res.stats.items():
            print(f"  {k:<14} {v:8.2f}" if k in ("기간(년)", "전략 샤프(근사)") else f"  {k:<14} {v * 100:7.1f}%")


if __name__ == "__main__":
    main()
