"""Python 추천 엔진 결과를 Android(Kotlin) 엔진 검증용 픽스처로 내보냄.

Kotlin 포팅이 Python과 같은 숫자를 내는지 android/core 의 ParityTest 가 비교합니다.
    python scripts/export_parity_fixtures.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import pandas as pd  # noqa: E402

from recommender import (  # noqa: E402
    STRATEGIES, Filters, attach_meta, compute_features, latest_snapshot, make_demo_data, recommend, run_backtest,
)
from recommender.scoring import apply_filters, composite, factor_scores  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "parity"
FEATURES = ["ret_20", "ret_60", "ret_120", "ret_250", "mom_12_1", "ma_align", "dist_ma20", "dist_ma60",
            "dist_ma120", "ma20_slope", "rsi14", "macd_hist", "bb_pctb", "high_52w", "low_52w", "vol_20",
            "vol_ratio", "amount_20", "mdd_120", "n_days"]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    daily, meta = make_demo_data(n_stocks=30, n_days=340, seed=11)
    daily[["open", "high", "low", "close"]] = daily[["open", "high", "low", "close"]].round(0)
    daily["volume"] = daily["volume"].round(0)
    daily["amount"] = daily["amount"].round(3)
    daily["date"] = daily["date"].dt.strftime("%Y-%m-%d")
    daily.to_csv(OUT / "daily.csv", index=False)
    meta.to_csv(OUT / "meta.csv", index=False)

    # CSV에서 다시 읽어 Kotlin과 완전히 같은 입력으로 계산
    daily = pd.read_csv(OUT / "daily.csv", dtype={"ticker": str}, parse_dates=["date"])
    meta = pd.read_csv(OUT / "meta.csv", dtype={"ticker": str})
    feats = compute_features(daily)
    snap = attach_meta(latest_snapshot(feats), meta)
    snap[["ticker"] + FEATURES].to_csv(OUT / "expected_snapshot.csv", index=False)

    # 백테스트 시점의 과거 지표도 비교 (미래 데이터 누수 없는지 + 포팅 정확성)
    mid = feats[feats["date"] == feats["date"].unique()[280]]
    mid[["ticker"] + FEATURES].to_csv(OUT / "expected_mid.csv", index=False)

    filters = Filters(min_amount=0, min_days=250, min_price=1000)
    expected = {}
    for name, strat in STRATEGIES.items():
        scored = factor_scores(apply_filters(snap, filters))
        scored["score"] = composite(scored, strat.weights)
        top = recommend(snap, strat.weights, filters, top_n=10)
        expected[name] = {
            "scores": {t: (None if pd.isna(s) else float(s)) for t, s in zip(scored["ticker"], scored["score"])},
            "factors": {t: {k: (None if pd.isna(v) else float(v)) for k, v in row.items()}
                        for t, row in scored.set_index("ticker")[[c for c in scored if c.startswith("factor_")]]
                        .iterrows()},
            "top": top["ticker"].tolist(),
            "reasons": dict(zip(top["ticker"], top["reasons"])),
            "warnings": dict(zip(top["ticker"], top["warnings"])),
        }
        bt = run_backtest(feats, strat.weights, filters, top_n=5, hold_days=20)
        expected[name]["backtest"] = {
            "periods": [{"start": str(r.start.date()), "end": str(r.end.date()), "strategy": r.strategy,
                         "benchmark": r.benchmark, "picks": r.picks.split(", ")}
                        for r in bt.periods.itertuples()],
            "stats": {k: float(v) for k, v in bt.stats.items()},
        }
    (OUT / "expected_scores.json").write_text(json.dumps(expected, ensure_ascii=False, indent=1))
    print("픽스처 저장:", OUT)


if __name__ == "__main__":
    main()
