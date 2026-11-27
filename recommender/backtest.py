"""전략 검증용 간이 백테스트.

매 리밸런싱일(기본 20거래일마다)에 그날까지의 지표로 상위 N개를 동일비중 매수,
다음 리밸런싱일까지 보유. 비교 기준(벤치마크)은 필터를 통과한 전체 후보의 동일비중.

주의: 상장폐지 종목이 데이터에 없으면 생존편향으로 결과가 좋게 나옵니다.
      밸류 팩터는 과거 시점 재무 데이터가 없으므로 백테스트에서 제외됩니다.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from .scoring import Filters, apply_filters, composite, factor_scores


@dataclass
class BacktestResult:
    periods: pd.DataFrame   # 리밸런싱 구간별 수익률
    equity: pd.DataFrame    # 누적 수익 곡선 (strategy, benchmark)
    stats: dict[str, float]


def run_backtest(
    features: pd.DataFrame,
    weights: dict[str, float],
    filters: Filters | None = None,
    top_n: int = 10,
    hold_days: int = 20,
    cost: float = 0.003,  # 회전 시 왕복 거래비용 (수수료+세금 대략치)
    start: pd.Timestamp | None = None,
) -> BacktestResult:
    filters = filters or Filters()
    weights = {k: v for k, v in weights.items() if k != "value"}

    close = features.pivot(index="date", columns="ticker", values="close").sort_index()
    dates = close.index
    first = dates[min(len(dates) - 1, max(filters.min_days, 250))]
    if start is not None:
        first = max(first, pd.Timestamp(start))
    rebal = [d for d in dates[dates >= first]][::hold_days]

    by_date = {d: g for d, g in features[features["date"].isin(rebal)].groupby("date")}
    rows, prev = [], set()
    for d0, d1 in zip(rebal[:-1], rebal[1:]):
        snap = by_date.get(d0)
        if snap is None:
            continue
        cand = apply_filters(snap, filters)
        if len(cand) < top_n:
            continue
        scored = factor_scores(cand)
        scored["score"] = composite(scored, weights)
        picks = scored.nlargest(top_n, "score")["ticker"].tolist()

        fwd = close.loc[d1] / close.loc[d0] - 1
        strat = fwd[picks].fillna(0.0).mean()  # 데이터 끊긴 종목은 0% 가정
        bench = fwd[cand["ticker"]].mean()
        turnover = 1 - len(prev & set(picks)) / top_n
        strat -= cost * turnover
        rows.append({"start": d0, "end": d1, "strategy": strat, "benchmark": bench,
                     "turnover": turnover, "picks": ", ".join(picks)})
        prev = set(picks)

    periods = pd.DataFrame(rows)
    if periods.empty:
        return BacktestResult(periods, pd.DataFrame(), {})

    equity = (1 + periods.set_index("end")[["strategy", "benchmark"]]).cumprod()
    equity.loc[periods["start"].iloc[0]] = 1.0
    equity = equity.sort_index()

    years = (periods["end"].iloc[-1] - periods["start"].iloc[0]).days / 365.25
    per_year = len(periods) / years if years > 0 else np.nan

    def _cagr(s):
        return s.iloc[-1] ** (1 / years) - 1 if years > 0 else np.nan

    def _mdd(s):
        return (s / s.cummax() - 1).min()

    stats = {
        "기간(년)": years,
        "전략 CAGR": _cagr(equity["strategy"]),
        "벤치마크 CAGR": _cagr(equity["benchmark"]),
        "전략 MDD": _mdd(equity["strategy"]),
        "벤치마크 MDD": _mdd(equity["benchmark"]),
        "초과수익 승률": (periods["strategy"] > periods["benchmark"]).mean(),
        "전략 샤프(근사)": periods["strategy"].mean() / periods["strategy"].std() * np.sqrt(per_year),
        "평균 회전율": periods["turnover"].mean(),
    }
    return BacktestResult(periods, equity, stats)
