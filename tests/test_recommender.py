"""추천 엔진 테스트 (데모 데이터 / 합성 시계열 사용)"""
import numpy as np
import pandas as pd
import pytest

from recommender import (
    STRATEGIES, Filters, attach_meta, compute_features, latest_snapshot,
    make_demo_data, recommend, run_backtest,
)


def _series(ticker, closes, volume=1e6):
    n = len(closes)
    c = np.asarray(closes, dtype=float)
    return pd.DataFrame({
        "ticker": ticker, "date": pd.bdate_range("2023-01-02", periods=n),
        "open": c, "high": c * 1.01, "low": c * 0.99, "close": c, "volume": volume,
        "amount": c * volume / 1e6,
    })


@pytest.fixture(scope="module")
def demo():
    daily, meta = make_demo_data(n_stocks=30, n_days=400)
    feats = compute_features(daily)
    return feats, attach_meta(latest_snapshot(feats), meta)


def test_indicators_on_known_series():
    up = _series("UP", np.linspace(100, 300, 300))
    f = latest_snapshot(compute_features(up)).iloc[0]
    assert f["ma_align"] == 1.0
    assert f["rsi14"] == pytest.approx(100)
    assert f["ret_20"] == pytest.approx(300 / up["close"].iloc[-21] - 1)
    assert f["high_52w"] == pytest.approx(300 / 303)
    assert f["mdd_120"] == pytest.approx(0)


def test_no_lookahead():
    """과거 시점 지표는 이후 데이터가 바뀌어도 동일해야 함"""
    base = _series("A", 100 + np.cumsum(np.random.default_rng(0).normal(0, 1, 300)))
    changed = base.copy()
    changed.loc[250:, "close"] *= 2
    a = compute_features(base).iloc[249]
    b = compute_features(changed).iloc[249]
    pd.testing.assert_series_equal(a, b, check_names=False)


def test_trend_strategy_prefers_uptrend():
    rng = np.random.default_rng(1)
    noise = lambda: rng.normal(0, 0.3, 300)  # noqa: E731
    daily = pd.concat([
        _series("UP", np.linspace(100, 200, 300) + noise()),
        _series("FLAT", 150 + noise()),
        _series("DOWN", np.linspace(200, 100, 300) + noise()),
    ])
    snap = latest_snapshot(compute_features(daily))
    top = recommend(snap, STRATEGIES["추세추종"].weights, Filters(min_amount=0, min_price=0), top_n=3)
    assert top["ticker"].tolist() == ["UP", "FLAT", "DOWN"]
    assert top["reasons"].iloc[0]


def test_filters(demo):
    _, snap = demo
    f = Filters(min_amount=0, max_rsi=60, above_ma120=True, markets=["KOSPI"])
    top = recommend(snap, STRATEGIES["균형형"].weights, f, top_n=100)
    assert (top["rsi14"] <= 60).all() and (top["dist_ma120"] > 0).all()
    assert set(top["market"]) <= {"KOSPI"}


@pytest.mark.parametrize("name", list(STRATEGIES))
def test_all_strategies_run(demo, name):
    _, snap = demo
    top = recommend(snap, STRATEGIES[name].weights, Filters(min_amount=0, min_days=250), top_n=5)
    assert len(top) == 5
    assert top["score"].is_monotonic_decreasing
    assert top["score"].between(0, 100).all()


def test_backtest(demo):
    feats, _ = demo
    res = run_backtest(feats, STRATEGIES["균형형"].weights, Filters(min_amount=0), top_n=5)
    assert not res.periods.empty
    assert res.equity.iloc[0].tolist() == [1.0, 1.0]
    assert set(res.stats) >= {"전략 CAGR", "벤치마크 CAGR", "전략 MDD"}


# ---------------------------------------------------------------- 주봉 추세선 채널
from recommender.trendline import STATUS_BELOW, STATUS_IN, fit_channel, weekly  # noqa: E402


def _weekly_channel(after):
    """130주: 저점이 직선(100+2t) 위로 지그재그 → 급등 → after(t) 로 조정"""
    rows = []
    for t in range(130):
        line = 100 + 2 * t
        wave = 12 * (1 - abs((t % 10) - 5) / 5)
        c = line + 2 + wave if t < 110 else (line + 40 + (t - 110) * 6 if t < 122 else after(line))
        lo = c - 2 - (0 if t % 10 == 0 else 1) if t < 122 else c - 2
        rows.append({"date": pd.Timestamp("2023-01-06") + pd.Timedelta(weeks=t),
                     "open": c, "high": c + 3, "low": lo, "close": c})
    return pd.DataFrame(rows)


def test_trend_channel_pullback_into_zone():
    ch = fit_channel(_weekly_channel(lambda line: line * 1.04))
    assert ch["status"] == STATUS_IN and ch["touches"] >= 5
    assert 0 <= ch["position"] <= 1 and ch["from_recent_high"] < -0.1


def test_trend_channel_breakdown():
    assert fit_channel(_weekly_channel(lambda line: line * 0.9))["status"] == STATUS_BELOW


def test_weekly_resample():
    d = _series("A", [10, 11, 12, 13, 14, 15, 16])  # 2023-01-02(월) ~ 01-10(화)
    w = weekly(d)
    assert len(w) == 2 and w["close"].tolist() == [14, 16] and w["open"].iloc[0] == 10


def test_buy_zone_requires_all_four():
    from recommender.trendline import evaluate
    good = _weekly_channel(lambda line: line * 1.04)
    ev = evaluate(_series("A", [1.0] * 10), (None, None))  # 데이터 부족 → 모두 미충족
    assert not ev["buy"] and ev["n_ok"] == 0
    assert fit_channel(good)["status"] == STATUS_IN
