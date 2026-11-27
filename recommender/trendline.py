"""주봉 상승 추세선 채널 → '두 선 사이로 내려온 종목' 찾기.

차트에 손으로 긋는 방식을 그대로 옮겼습니다.
  1) 상단선(지지선): 상승 추세 중 주봉 저점(스윙 로우)들을 가장 많이 잇는 우상향 직선.
     추세선 시작부터 최근 조정 직전까지 주봉 종가가 이 선 아래로 크게(3% 초과) 내려간 적이 없어야 함.
  2) 하단선: 상단선과 평행. 추세 기간 중 저점이 상단선 아래로 가장 깊게 찔렀던 폭만큼 아래 (4~15%).
  3) 현재 주가가 두 선 사이면 '매수권', 상단선 위 2% 이내면 '상단 근접'.

가격 축은 일반 증권사 차트처럼 선형(로그 아님)이며, 두 선은 같은 기울기(평행)입니다.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

STATUS_IN = "매수권"
STATUS_NEAR = "상단 근접"
STATUS_BELOW = "하단 이탈"
STATUS_ABOVE = "추세선 위"


@dataclass
class ChannelParams:
    lookback_weeks: int = 156   # 최근 3년 주봉
    pivot_k: int = 3            # 좌우 3주보다 낮으면 스윙 저점
    recent_weeks: int = 8       # 최근 8주는 '조정 구간'으로 보고 추세선 유지 검사에서 제외
    min_gap_weeks: int = 6      # 추세선을 정하는 두 저점 사이 최소 간격
    min_span_weeks: int = 26    # 추세선 시작 ~ 현재 최소 반년
    min_rise: float = 0.3       # 추세선 시작 → 현재 추세선 값 최소 +30%
    close_tol: float = 0.03     # 종가가 추세선 아래 3% 까지는 허용
    touch_tol: float = 0.04     # 저점이 추세선 ±4% 이내면 '접점'
    min_touches: int = 3
    width: float | None = None  # None=자동, 아니면 현재 상단선 대비 비율 (예: 0.08)
    min_width: float = 0.04
    max_width: float = 0.15
    near_tol: float = 0.02


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
            line = low[a] + slope * (np.arange(a, end) - a)
            if (close[a:end] < line * (1 - p.close_tol)).any():
                continue
            upper_now = low[a] + slope * (n - 1 - a)
            if upper_now / low[a] - 1 < p.min_rise:
                continue
            touches = 0
            for q in pivots:
                if a <= q < end:
                    lq = low[a] + slope * (q - a)
                    if abs(low[q] - lq) / lq <= p.touch_tol:
                        touches += 1
            if touches < p.min_touches:
                continue
            key = (touches, n - 1 - a, upper_now)
            if best is None or key > best[0]:
                best = (key, a, b, slope, touches)
    if best is None:
        return None

    _, a, b, slope, touches = best
    upper_now = low[a] + slope * (n - 1 - a)
    if p.width is None:
        line = low[a] + slope * (np.arange(a, end) - a)
        width = float(np.clip((line - low[a:end]).max() / upper_now, p.min_width, p.max_width))
    else:
        width = p.width
    offset = width * upper_now
    lower_now = upper_now - offset
    c = close[-1]
    if c < lower_now:
        status = STATUS_BELOW
    elif c <= upper_now:
        status = STATUS_IN
    elif c <= upper_now * (1 + p.near_tol):
        status = STATUS_NEAR
    else:
        status = STATUS_ABOVE
    recent_high = close[end - 1:].max()
    return {
        "status": status,
        "close": c,
        "upper": upper_now,
        "lower": lower_now,
        "position": (c - lower_now) / offset,           # 0=하단선, 1=상단선
        "width": width,
        "touches": touches,
        "start_date": w["date"].iloc[a],
        "pivot2_date": w["date"].iloc[b],
        "trend_per_year": slope * 52 / upper_now,       # 현재 추세선 값 대비 연간 상승 폭
        "recent_high": recent_high,
        "from_recent_high": c / recent_high - 1,
        # 차트용: 주봉 인덱스 기준 직선 (upper(t) = start_low + slope*(t-a))
        "_a": a, "_slope": slope, "_start_low": low[a], "_offset": offset, "_weeks": w,
    }


def channel_lines(ch: dict) -> pd.DataFrame:
    """차트용: 추세선 시작 주부터 현재까지 주봉 + 상단/하단선 값"""
    w = ch["_weeks"]
    t = np.arange(len(w))
    upper = ch["_start_low"] + ch["_slope"] * (t - ch["_a"])
    out = w.copy()
    out["upper"] = np.where(t >= ch["_a"], upper, np.nan)
    out["lower"] = out["upper"] - ch["_offset"]
    return out


def scan(daily: pd.DataFrame, meta: pd.DataFrame | None = None, p: ChannelParams | None = None,
         statuses: tuple[str, ...] = (STATUS_IN, STATUS_NEAR)) -> pd.DataFrame:
    """전체 종목 → 추세선 채널 목록 (기본: 매수권 + 상단 근접)"""
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
    order = {STATUS_IN: 0, STATUS_NEAR: 1, STATUS_BELOW: 2, STATUS_ABOVE: 3}
    out["_o"] = out["status"].map(order)
    return (out.sort_values(["_o", "touches", "trend_per_year"], ascending=[True, False, False])
            .drop(columns="_o").reset_index(drop=True))
