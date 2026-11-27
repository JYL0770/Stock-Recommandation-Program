"""일봉 OHLCV → 가격 기반 기술적 지표.

모든 지표는 해당 날짜까지의 과거 데이터만 사용합니다(미래 데이터 누수 없음).
그래서 같은 함수로 '오늘 스냅샷'과 '과거 시점 백테스트'를 모두 처리합니다.

입력: ticker, date, open, high, low, close, volume[, amount(백만원)] 컬럼의 long DataFrame
출력: 입력 행마다 지표 컬럼이 붙은 DataFrame
"""
from __future__ import annotations

import numpy as np
import pandas as pd

# 지표 설명 (대시보드 / 추천 사유에 사용)
FEATURE_DESCRIPTIONS = {
    "ret_20": "1개월(20거래일) 수익률",
    "ret_60": "3개월(60거래일) 수익률",
    "ret_120": "6개월(120거래일) 수익률",
    "ret_250": "12개월(250거래일) 수익률",
    "mom_12_1": "12-1개월 모멘텀 (최근 1개월 제외한 11개월 수익률)",
    "ma_align": "이동평균 정배열 점수 (0~1, 종가>MA5>MA20>MA60>MA120)",
    "dist_ma20": "20일선 이격도",
    "dist_ma60": "60일선 이격도",
    "dist_ma120": "120일선 이격도",
    "ma20_slope": "20일선 기울기 (5일 변화율)",
    "rsi14": "RSI(14)",
    "macd_hist": "MACD 히스토그램 (종가 대비 %)",
    "bb_pctb": "볼린저밴드 %B (20일, 2σ)",
    "high_52w": "52주 최고가 대비 위치 (1=신고가)",
    "low_52w": "52주 최저가 대비 상승률",
    "vol_20": "20일 연환산 변동성",
    "vol_ratio": "거래량 비율 (5일 평균 / 60일 평균)",
    "amount_20": "20일 평균 거래대금 (백만원)",
    "mdd_120": "120일 최대낙폭",
    "n_days": "보유 데이터 일수",
}


def _rsi(close: pd.Series, n: int = 14) -> pd.Series:
    """Wilder 방식 RSI"""
    diff = close.diff()
    gain = diff.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    loss = (-diff.clip(upper=0)).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    rs = gain / loss.replace(0, np.nan)
    rsi = 100 - 100 / (1 + rs)
    # 하락이 전혀 없으면 RSI=100
    return rsi.where(loss != 0, 100.0).where(gain.notna())


def _features_one(g: pd.DataFrame) -> pd.DataFrame:
    g = g.sort_values("date")
    c, h, l, v = g["close"], g["high"], g["low"], g["volume"]
    out = pd.DataFrame(index=g.index)

    for n in (20, 60, 120, 250):
        out[f"ret_{n}"] = c / c.shift(n) - 1
    out["mom_12_1"] = c.shift(20) / c.shift(250) - 1

    ma = {n: c.rolling(n, min_periods=n).mean() for n in (5, 20, 60, 120)}
    for n, s in ma.items():
        out[f"ma{n}"] = s
    align = (
        (c > ma[5]).astype(float)
        + (ma[5] > ma[20]).astype(float)
        + (ma[20] > ma[60]).astype(float)
        + (ma[60] > ma[120]).astype(float)
    ) / 4
    out["ma_align"] = align.where(ma[120].notna())
    out["dist_ma20"] = c / ma[20] - 1
    out["dist_ma60"] = c / ma[60] - 1
    out["dist_ma120"] = c / ma[120] - 1
    out["ma20_slope"] = ma[20] / ma[20].shift(5) - 1

    out["rsi14"] = _rsi(c, 14)

    ema12 = c.ewm(span=12, adjust=False, min_periods=12).mean()
    ema26 = c.ewm(span=26, adjust=False, min_periods=26).mean()
    macd = ema12 - ema26
    signal = macd.ewm(span=9, adjust=False, min_periods=9).mean()
    out["macd_hist"] = (macd - signal) / c

    std20 = c.rolling(20, min_periods=20).std()
    upper, lower = ma[20] + 2 * std20, ma[20] - 2 * std20
    out["bb_pctb"] = (c - lower) / (upper - lower).replace(0, np.nan)

    out["high_52w"] = c / h.rolling(250, min_periods=120).max()
    out["low_52w"] = c / l.rolling(250, min_periods=120).min() - 1

    daily_ret = c.pct_change()
    out["vol_20"] = daily_ret.rolling(20, min_periods=20).std() * np.sqrt(252)
    out["vol_ratio"] = v.rolling(5, min_periods=5).mean() / v.rolling(60, min_periods=60).mean().replace(0, np.nan)

    amount = g["amount"] if "amount" in g and g["amount"].notna().any() else c * v / 1e6
    out["amount_20"] = amount.rolling(20, min_periods=20).mean()

    peak = c.rolling(120, min_periods=60).max()
    out["mdd_120"] = (c / peak - 1).rolling(120, min_periods=60).min()

    out["n_days"] = np.arange(1, len(g) + 1)
    return out


def compute_features(daily: pd.DataFrame) -> pd.DataFrame:
    """전체 종목 일봉에 지표 컬럼을 붙여 반환 (ticker, date 정렬)"""
    if daily.empty:
        return daily.copy()
    df = daily.sort_values(["ticker", "date"]).reset_index(drop=True)
    feats = pd.concat([_features_one(g) for _, g in df.groupby("ticker", sort=False)])
    return df.join(feats.replace([np.inf, -np.inf], np.nan))


def latest_snapshot(features: pd.DataFrame, as_of: pd.Timestamp | None = None) -> pd.DataFrame:
    """종목별로 as_of(기본: 최신) 이전 마지막 거래일의 지표 한 줄씩"""
    df = features if as_of is None else features[features["date"] <= as_of]
    if df.empty:
        return df.copy()
    idx = df.groupby("ticker")["date"].idxmax()
    return df.loc[idx].reset_index(drop=True)
