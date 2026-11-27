"""주봉 상승 추세선 채널 → '지지선과 그 위 평행선 사이로 내려온 종목' 찾기.

차트에 손으로 긋는 방식을 옮겼습니다.
  1) 하단선(지지선): 상승 추세 중 주봉 저점(스윙 로우)들을 가장 많이 잇는 우상향 직선.
     - 추세선 시작부터 최근 조정 직전까지 주봉 종가가 선 아래로 '허용폭' 넘게 내려간 적이 없어야 함
     - 연 20% 이상 오르는 선만 (완만한 바닥선 제외)
     - 허용폭 = max(선 값의 3%, 차트 높이(기간 최고가-최저가)의 2%)
       → 눈으로 볼 때 '닿아 보이는' 정도. 큰 시세가 난 종목은 초반 저점에서 허용폭이 넓어짐
  2) 상단선: 하단선과 평행, 현재 하단선 값의 10% 위 (조정 가능)
  3) 현재가가 두 선 사이(하단선 아래 허용폭까지 포함)면 '매수권'

가격 축은 일반 증권사 차트처럼 선형이며, 두 선은 같은 기울기(평행)입니다.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

STATUS_IN = "매수권"
STATUS_BELOW = "지지선 이탈"
STATUS_ABOVE = "채널 위"


@dataclass
class ChannelParams:
    lookback_weeks: int = 156       # 최근 3년 주봉
    pivot_k: int = 3                # 좌우 3주보다 낮으면 스윙 저점
    recent_weeks: int = 8           # 최근 8주는 '조정 구간'으로 보고 추세선 유지 검사에서 제외
    min_gap_weeks: int = 6          # 추세선을 정하는 두 저점 사이 최소 간격
    min_span_weeks: int = 26        # 추세선 시작 ~ 현재 최소 반년
    min_rise: float = 0.3           # 추세선 시작 → 현재 지지선 값 최소 +30%
    min_trend_per_year: float = 0.2 # 지지선 기울기: 현재 값 대비 연 20% 이상
    tol_rel: float = 0.03           # 허용폭: 선 값의 3%
    tol_chart: float = 0.02         #        또는 차트 높이의 2% 중 큰 값
    min_touches: int = 3
    width: float = 0.10             # 상단선 = 하단선 × (1 + width)
    pullback_weeks: int = 26        # '최근 고점' 기준 기간


def weekly(daily: pd.DataFrame) -> pd.DataFrame:
    """한 종목 일봉 → 주봉 (월요일 시작 주, date=그 주 마지막 거래일)"""
    d = daily.sort_values("date")
    week = d["date"] - pd.to_timedelta(d["date"].dt.weekday, unit="D")
    g = d.groupby(week.values, sort=True)
    return pd.DataFrame({
        "date": g["date"].last().values,
        "open": g["open"].first().values,
        "high": g["high"].max().values,
        "low": g["low"].min().values,
        "close": g["close"].last().values,
        "volume": g["volume"].sum().values,
    })


def fit_channel(w: pd.DataFrame, p: ChannelParams | None = None) -> dict | None:
    """주봉 → 채널 정보 dict (없으면 None)"""
    p = p or ChannelParams()
    w = w.tail(p.lookback_weeks).reset_index(drop=True)
    n = len(w)
    if n < p.min_span_weeks + p.recent_weeks:
        return None
    low = w["low"].to_numpy(float)
    close = w["close"].to_numpy(float)
    chart_tol = p.tol_chart * (w["high"].max() - w["low"].min())

    def tol(v):
        return np.maximum(p.tol_rel * v, chart_tol)

    k = p.pivot_k
    end = n - p.recent_weeks
    pivots = [i for i in range(k, n - k)
              if low[i] < low[i - k:i].min() and low[i] <= low[i + 1:i + k + 1].min()]
    cand = [i for i in pivots if i < end]

    best = None
    for ai, a in enumerate(cand):
        if n - 1 - a < p.min_span_weeks:
            continue
        for b in cand[ai + 1:]:
            if b - a < p.min_gap_weeks:
                continue
            slope = (low[b] - low[a]) / (b - a)
            if slope <= 0:
                continue
            sup_now = low[a] + slope * (n - 1 - a)
            if sup_now / low[a] - 1 < p.min_rise or slope * 52 / sup_now < p.min_trend_per_year:
                continue
            line = low[a] + slope * (np.arange(a, end) - a)
            if (close[a:end] < line - tol(line)).any():
                continue
            touches = 0
            for q in pivots:
                if a <= q < end:
                    lq = low[a] + slope * (q - a)
                    if abs(low[q] - lq) <= tol(lq):
                        touches += 1
            if touches < p.min_touches:
                continue
            key = (touches, sup_now)
            if best is None or key > best[0]:
                best = (key, a, b, slope, touches)
    if best is None:
        return None

    _, a, b, slope, touches = best
    sup = low[a] + slope * (n - 1 - a)
    upper = sup * (1 + p.width)
    t_now = float(tol(sup))
    c = close[-1]
    if c < sup - t_now:
        status = STATUS_BELOW
    elif c <= upper:
        status = STATUS_IN
    else:
        status = STATUS_ABOVE
    recent_high = close[-p.pullback_weeks:].max()
    return {
        "status": status,
        "close": c,
        "support": sup,                                 # 하단선 (지지선)
        "upper": upper,                                 # 상단선
        "position": (c - sup) / (upper - sup),          # 0=하단선, 1=상단선
        "tolerance": t_now,
        "touches": touches,
        "start_date": w["date"].iloc[a],
        "pivot2_date": w["date"].iloc[b],
        "trend_per_year": slope * 52 / sup,             # 현재 지지선 값 대비 연간 상승 폭
        "recent_high": recent_high,
        "from_recent_high": c / recent_high - 1,
        # 차트용: support(t) = start_low + slope*(t-a)  (t = 주봉 인덱스)
        "_a": a, "_slope": slope, "_start_low": low[a], "_offset": upper - sup, "_weeks": w,
    }


def channel_lines(ch: dict) -> pd.DataFrame:
    """차트용: 주봉 + 하단선/상단선 값"""
    w = ch["_weeks"]
    t = np.arange(len(w))
    sup = ch["_start_low"] + ch["_slope"] * (t - ch["_a"])
    out = w.copy()
    out["support"] = np.where(t >= ch["_a"], sup, np.nan)
    out["upper"] = out["support"] + ch["_offset"]
    return out


def scan(daily: pd.DataFrame, meta: pd.DataFrame | None = None, p: ChannelParams | None = None,
         statuses: tuple[str, ...] = (STATUS_IN,)) -> pd.DataFrame:
    """전체 종목 → 추세선 채널 목록 (기본: 매수권만). 접점 많은 순"""
    rows = []
    for t, g in daily.groupby("ticker"):
        ch = fit_channel(weekly(g), p)
        if ch is None or ch["status"] not in statuses:
            continue
        rows.append({"ticker": t, **{k: v for k, v in ch.items() if not k.startswith("_")}})
    out = pd.DataFrame(rows)
    if out.empty:
        return out
    if meta is not None and not meta.empty:
        cols = [c for c in ("ticker", "name", "market") if c in meta.columns]
        out = out.merge(meta[cols], on="ticker", how="left")
    order = {STATUS_IN: 0, STATUS_BELOW: 1, STATUS_ABOVE: 2}
    out["_o"] = out["status"].map(order)
    return (out.sort_values(["_o", "touches", "position"], ascending=[True, False, True])
            .drop(columns="_o").reset_index(drop=True))
