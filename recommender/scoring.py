"""지표 스냅샷 → 팩터 점수(0~100) → 전략별 가중합 → 추천 순위 + 추천 사유.

팩터 점수는 대부분 '같은 날 전체 후보 종목 중 백분위'(cross-sectional rank)입니다.
즉 절대 기준이 아니라 "지금 시장에서 상대적으로 얼마나 좋은가"를 봅니다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

# ---------------------------------------------------------------- 팩터 정의
FACTORS = {
    "trend": "추세",        # 이동평균 정배열·이격도·기울기
    "momentum": "모멘텀",   # 중장기 수익률
    "high": "신고가 근접",  # 52주 고가 대비 위치
    "stability": "안정성",  # 낮은 변동성·낮은 낙폭
    "timing": "과열 회피",  # RSI가 과열/과매도가 아닌 적정 구간
    "volume": "거래량 증가",
    "reversal": "단기 낙폭(반등)",  # 최근 단기 조정 폭
    "value": "밸류",        # PER·PBR·ROE (기본정보가 있을 때만)
}

FACTOR_HELP = {
    "trend": "종가>MA5>MA20>MA60>MA120 정배열 정도, 60일선 이격도, 20일선 기울기의 백분위 평균",
    "momentum": "12-1개월 모멘텀, 6개월·3개월 수익률의 백분위 평균",
    "high": "52주 최고가에 얼마나 가까운지의 백분위",
    "stability": "20일 변동성과 120일 최대낙폭이 작을수록 높음",
    "timing": "RSI(14)가 55 부근이면 100, 과열(80↑)·과매도(30↓)일수록 낮음 (절대 점수)",
    "volume": "최근 5일 거래량이 60일 평균 대비 얼마나 늘었는지의 백분위",
    "reversal": "최근 1개월 수익률·RSI·볼린저 %B가 낮을수록(단기 조정이 클수록) 높음",
    "value": "저PER·저PBR·고ROE 백분위 평균 (적자 PER은 최하위)",
}


@dataclass
class Strategy:
    name: str
    description: str
    weights: dict[str, float]


STRATEGIES: dict[str, Strategy] = {
    s.name: s
    for s in [
        Strategy("균형형", "추세·모멘텀을 중심으로 안정성과 과열 여부를 함께 고려",
                 {"trend": 25, "momentum": 25, "high": 10, "stability": 20, "timing": 10, "volume": 10}),
        Strategy("추세추종", "강하게 오르고 있는 종목 (정배열 + 신고가 근접 + 강한 모멘텀)",
                 {"trend": 35, "momentum": 35, "high": 20, "volume": 10}),
        Strategy("눌림목", "중장기 상승 추세 속에서 최근 단기 조정을 받은 종목",
                 {"trend": 35, "reversal": 35, "stability": 15, "timing": 15}),
        Strategy("저변동 안정형", "변동성과 낙폭이 작고 꾸준히 오르는 종목",
                 {"stability": 50, "trend": 25, "momentum": 15, "timing": 10}),
        Strategy("가치+모멘텀", "싼 종목 중 주가 흐름이 살아난 종목 (기본정보 필요)",
                 {"value": 40, "momentum": 30, "trend": 15, "stability": 15}),
    ]
}


@dataclass
class Filters:
    min_amount: float = 1000.0      # 20일 평균 거래대금 하한 (백만원) = 10억원
    min_days: int = 250             # 최소 상장/데이터 일수
    min_price: float = 1000.0
    max_rsi: float | None = None    # 예: 80 → 과열 종목 제외
    above_ma120: bool = False       # 120일선 위 종목만
    markets: list[str] | None = None
    exclude_tickers: list[str] = field(default_factory=list)


# ---------------------------------------------------------------- 점수 계산
def _pct(s: pd.Series, higher_is_better: bool = True) -> pd.Series:
    """백분위 점수 0~100 (NaN 유지)"""
    r = s.rank(pct=True, ascending=higher_is_better)
    return r * 100


def _mean(*cols: pd.Series) -> pd.Series:
    return pd.concat(cols, axis=1).mean(axis=1, skipna=True)


def apply_filters(snap: pd.DataFrame, f: Filters) -> pd.DataFrame:
    m = pd.Series(True, index=snap.index)
    m &= snap["amount_20"].fillna(0) >= f.min_amount
    m &= snap["n_days"].fillna(0) >= f.min_days
    m &= snap["close"].fillna(0) >= f.min_price
    if f.max_rsi is not None:
        m &= snap["rsi14"].fillna(0) <= f.max_rsi
    if f.above_ma120:
        m &= snap["dist_ma120"].fillna(-1) > 0
    if f.markets and "market" in snap:
        m &= snap["market"].isin(f.markets)
    if f.exclude_tickers:
        m &= ~snap["ticker"].isin(f.exclude_tickers)
    return snap[m].copy()


def factor_scores(snap: pd.DataFrame) -> pd.DataFrame:
    """스냅샷(필터 적용 후)에 factor_* 점수 컬럼 추가"""
    df = snap.copy()
    df["factor_trend"] = _mean(_pct(df["ma_align"]), _pct(df["dist_ma60"]), _pct(df["ma20_slope"]))
    df["factor_momentum"] = _mean(_pct(df["mom_12_1"]), _pct(df["ret_120"]), _pct(df["ret_60"]))
    df["factor_high"] = _pct(df["high_52w"])
    df["factor_stability"] = _mean(_pct(df["vol_20"], False), _pct(df["mdd_120"]))
    df["factor_timing"] = (100 * (1 - (df["rsi14"] - 55).abs() / 45)).clip(0, 100)
    df["factor_volume"] = _pct(df["vol_ratio"])
    df["factor_reversal"] = _mean(_pct(df["ret_20"], False), _pct(df["rsi14"], False), _pct(df["bb_pctb"], False))

    if {"per", "pbr", "roe"} & set(df.columns):
        parts = []
        if "per" in df:
            per = df["per"].where(df["per"] > 0)  # 적자(음수)는 NaN → 최하위 처리
            parts.append(_pct(per, False).fillna(0).where(df["per"].notna()))
        if "pbr" in df:
            parts.append(_pct(df["pbr"].where(df["pbr"] > 0), False))
        if "roe" in df:
            parts.append(_pct(df["roe"]))
        df["factor_value"] = _mean(*parts)
    else:
        df["factor_value"] = np.nan
    return df


def composite(df: pd.DataFrame, weights: dict[str, float]) -> pd.Series:
    """가중 평균 종합점수. 값이 없는 팩터는 남은 팩터들로 가중치를 재분배"""
    num = pd.Series(0.0, index=df.index)
    den = pd.Series(0.0, index=df.index)
    for k, w in weights.items():
        if w <= 0:
            continue
        col = df.get(f"factor_{k}")
        if col is None:
            continue
        ok = col.notna()
        num += col.fillna(0) * w
        den += ok * w
    return (num / den.replace(0, np.nan)).round(1)


def recommend(
    snap: pd.DataFrame,
    weights: dict[str, float],
    filters: Filters | None = None,
    top_n: int = 20,
) -> pd.DataFrame:
    """스냅샷 → 상위 top_n 추천 (score, rank, reasons, warnings 포함)"""
    filters = filters or Filters()
    cand = apply_filters(snap, filters)
    if cand.empty:
        return cand
    scored = factor_scores(cand)
    scored["score"] = composite(scored, weights)
    scored = scored.dropna(subset=["score"]).sort_values("score", ascending=False)
    scored["rank"] = np.arange(1, len(scored) + 1)
    scored["n_candidates"] = len(scored)
    top = scored.head(top_n).copy()
    top["reasons"] = top.apply(_reasons, axis=1)
    top["warnings"] = top.apply(_warnings, axis=1)
    return top.reset_index(drop=True)


# ---------------------------------------------------------------- 추천 사유
def _fmt_pct(x: float) -> str:
    return f"{x * 100:+.1f}%"


def _reasons(r: pd.Series) -> list[str]:
    out = []
    if r.get("ma_align") == 1:
        out.append("이동평균 완전 정배열 (종가>5>20>60>120일선)")
    elif r.get("ma_align", 0) >= 0.75:
        out.append("이동평균 정배열에 근접")
    if pd.notna(r.get("mom_12_1")) and r.get("factor_momentum", 0) >= 80:
        out.append(f"12-1개월 모멘텀 {_fmt_pct(r['mom_12_1'])} (상위 {100 - r['factor_momentum']:.0f}%)")
    if pd.notna(r.get("high_52w")) and r["high_52w"] >= 0.95:
        out.append(f"52주 최고가 대비 {_fmt_pct(r['high_52w'] - 1)} — 신고가 근접")
    if r.get("factor_stability", 0) >= 75 and pd.notna(r.get("vol_20")):
        out.append(f"변동성 낮음 (연 {r['vol_20'] * 100:.0f}%, 120일 MDD {r['mdd_120'] * 100:.0f}%)")
    if pd.notna(r.get("vol_ratio")) and r["vol_ratio"] >= 1.5:
        out.append(f"최근 거래량 60일 평균 대비 {r['vol_ratio']:.1f}배")
    if pd.notna(r.get("rsi14")) and 45 <= r["rsi14"] <= 65:
        out.append(f"RSI {r['rsi14']:.0f} — 과열 아닌 적정 구간")
    if (r.get("ma_align", 0) >= 0.5 and pd.notna(r.get("ret_20")) and r["ret_20"] < -0.03
            and pd.notna(r.get("dist_ma120")) and r["dist_ma120"] > 0):
        out.append(f"120일선 위에서 1개월 {_fmt_pct(r['ret_20'])} 조정 (눌림목)")
    if pd.notna(r.get("macd_hist")) and r["macd_hist"] > 0:
        out.append("MACD 히스토그램 양전환 상태")
    if r.get("factor_value", 0) >= 80:
        out.append(f"밸류 상위권 (PER {r.get('per', float('nan')):.1f}, PBR {r.get('pbr', float('nan')):.2f})")
    return out or ["여러 지표가 고르게 평균 이상"]


def _warnings(r: pd.Series) -> list[str]:
    out = []
    if pd.notna(r.get("rsi14")) and r["rsi14"] >= 75:
        out.append(f"RSI {r['rsi14']:.0f} 과열 — 단기 조정 가능성")
    if pd.notna(r.get("dist_ma20")) and r["dist_ma20"] >= 0.15:
        out.append(f"20일선 이격도 {_fmt_pct(r['dist_ma20'])} — 단기 급등")
    if pd.notna(r.get("vol_20")) and r["vol_20"] >= 0.6:
        out.append(f"변동성 매우 큼 (연 {r['vol_20'] * 100:.0f}%)")
    if pd.notna(r.get("dist_ma120")) and r["dist_ma120"] < 0:
        out.append("120일선 아래 — 장기 추세 약세")
    if pd.notna(r.get("bb_pctb")) and r["bb_pctb"] > 1:
        out.append("볼린저밴드 상단 돌파")
    return out
