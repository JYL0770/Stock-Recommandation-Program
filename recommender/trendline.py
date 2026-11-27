"""상승 추세선 채널 → 매수권 판정 (종목 일봉·주봉 + 지수 일봉·주봉 4개 차트 모두 충족).

차트에 손으로 긋는 방식을 옮겼습니다.
  1) 하단선(지지선): 상승 추세 중 스윙 저점을 가장 많이 잇는 우상향 직선.
     - 추세선 시작부터 최근 조정 직전까지 종가가 선 아래로 '허용폭' 넘게 내려간 적이 없어야 함
     - 연 20% 이상 오르는 선, 저점 접점 3회 이상
     - 허용폭 = max(선 값의 3%, 차트 높이(기간 최고가-최저가)의 2%) → 눈으로 '닿아 보이는' 정도
  2) 상단선: 하단선과 평행, 현재 하단선 값의 10% 위 (조정 가능)
  3) 마지막 종가가 하단선 이상 ~ 상단선 이하이면 그 차트는 '매수권'

매수권 = 종목 일봉, 종목 주봉, 지수 일봉, 지수 주봉 네 차트가 모두 매수권일 때.
가격 축은 일반 증권사 차트처럼 선형이며, 두 선은 같은 기울기(평행)입니다.
"""
from __future__ import annotations

from dataclasses import dataclass, replace

import numpy as np
import pandas as pd

STATUS_IN = "매수권"
STATUS_BELOW = "지지선 이탈"
STATUS_ABOVE = "채널 위"


@dataclass(frozen=True)
class ChannelParams:
    lookback: int = 156             # 사용할 최근 봉 수
    pivot_k: int = 3                # 좌우 k봉보다 낮으면 스윙 저점
    recent: int = 8                 # 최근 n봉은 '조정 구간'으로 보고 추세선 유지 검사에서 제외
    min_gap: int = 6                # 추세선을 정하는 두 저점 사이 최소 간격
    min_span: int = 26              # 추세선 시작 ~ 현재 최소 기간
    min_rise: float = 0.3           # 추세선 시작 → 현재 지지선 값 최소 상승률
    min_trend_per_year: float = 0.2 # 지지선 기울기: 현재 값 대비 연 20% 이상
    bars_per_year: int = 52
    tol_rel: float = 0.03           # 허용폭: 선 값의 3%
    tol_chart: float = 0.02         #        또는 차트 높이의 2% 중 큰 값
    min_touches: int = 3
    width: float = 0.10             # 상단선 = 하단선 × (1 + width)
    pullback: int = 26              # '최근 고점' 기준 기간


# 주봉: 최근 3년, 반년 이상 이어진 추세
WEEKLY = ChannelParams()
# 일봉: 최근 1년, 3개월 이상 이어진 추세
DAILY = ChannelParams(lookback=250, pivot_k=5, recent=10, min_gap=10, min_span=60, min_rise=0.1,
                      bars_per_year=252, pullback=120)


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
    })


def fit_channel(bars: pd.DataFrame, p: ChannelParams = WEEKLY) -> dict | None:
    """봉(일봉 또는 주봉) → 채널 정보 dict (추세선이 없으면 None)"""
    w = bars.sort_values("date").tail(p.lookback).reset_index(drop=True)
    n = len(w)
    if n < p.min_span + p.recent:
        return None
    low = w["low"].to_numpy(float)
    close = w["close"].to_numpy(float)
    chart_tol = p.tol_chart * (w["high"].max() - w["low"].min())

    def tol(v):
        return np.maximum(p.tol_rel * v, chart_tol)

    k = p.pivot_k
    end = n - p.recent
    pivots = [i for i in range(k, n - k)
              if low[i] < low[i - k:i].min() and low[i] <= low[i + 1:i + k + 1].min()]
    cand = [i for i in pivots if i < end]

    best = None
    for ai, a in enumerate(cand):
        if n - 1 - a < p.min_span:
            continue
        for b in cand[ai + 1:]:
            if b - a < p.min_gap:
                continue
            slope = (low[b] - low[a]) / (b - a)
            if slope <= 0:
                continue
            sup_now = low[a] + slope * (n - 1 - a)
            if sup_now / low[a] - 1 < p.min_rise or slope * p.bars_per_year / sup_now < p.min_trend_per_year:
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
    c = close[-1]
    status = STATUS_BELOW if c < sup else (STATUS_IN if c <= upper else STATUS_ABOVE)
    recent_high = close[-p.pullback:].max()
    return {
        "status": status,
        "close": c,
        "support": sup,                                 # 하단선 (지지선)
        "upper": upper,                                 # 상단선
        "position": (c - sup) / (upper - sup),          # 0=하단선, 1=상단선
        "touches": touches,
        "start_date": w["date"].iloc[a],
        "pivot2_date": w["date"].iloc[b],
        "trend_per_year": slope * p.bars_per_year / sup,
        "recent_high": recent_high,
        "from_recent_high": c / recent_high - 1,
        # 차트용: support(t) = start_low + slope*(t-a)  (t = 봉 인덱스)
        "_a": a, "_slope": slope, "_start_low": low[a], "_offset": upper - sup, "_bars": w,
    }


def channel_lines(ch: dict) -> pd.DataFrame:
    """차트용: 봉 + 하단선/상단선 값"""
    w = ch["_bars"]
    t = np.arange(len(w))
    sup = ch["_start_low"] + ch["_slope"] * (t - ch["_a"])
    out = w.copy()
    out["support"] = np.where(t >= ch["_a"], sup, np.nan)
    out["upper"] = out["support"] + ch["_offset"]
    return out


# ------------------------------------------------------------------ 4개 차트 매수권 판정
CHECKS = ("stock_daily", "stock_weekly", "index_daily", "index_weekly")
CHECK_LABELS = {"stock_daily": "종목 일봉", "stock_weekly": "종목 주봉",
                "index_daily": "지수 일봉", "index_weekly": "지수 주봉"}


def channels_of(daily: pd.DataFrame, width: float = 0.10) -> tuple[dict | None, dict | None]:
    """일봉 → (일봉 채널, 주봉 채널)"""
    return (fit_channel(daily, replace(DAILY, width=width)),
            fit_channel(weekly(daily), replace(WEEKLY, width=width)))


def passes(ch: dict | None) -> bool:
    return ch is not None and ch["status"] == STATUS_IN


def evaluate(stock_daily: pd.DataFrame, index_channels: tuple[dict | None, dict | None],
             width: float = 0.10) -> dict:
    """종목 일봉 + (지수 일봉 채널, 지수 주봉 채널) → 4개 차트 판정"""
    sd, sw = channels_of(stock_daily, width)
    chs = {"stock_daily": sd, "stock_weekly": sw,
           "index_daily": index_channels[0], "index_weekly": index_channels[1]}
    ok = {k: passes(v) for k, v in chs.items()}
    return {"channels": chs, "ok": ok, "n_ok": sum(ok.values()), "buy": all(ok.values()),
            "stock_ok": ok["stock_daily"] and ok["stock_weekly"]}


def make_index(daily: pd.DataFrame, tickers) -> pd.DataFrame:
    """데모용: 종목들의 동일가중 지수 (시작=1000)"""
    d = daily[daily["ticker"].isin(list(tickers))]
    base = d.groupby("ticker")["close"].transform("first")
    norm = d[["open", "high", "low", "close"]].div(base, axis=0) * 1000
    norm["date"] = d["date"]
    return norm.groupby("date", as_index=False)[["open", "high", "low", "close"]].mean()


def load_index_daily(daily: pd.DataFrame, meta: pd.DataFrame | None, data_dir: str | None) -> dict[str, pd.DataFrame]:
    """지수 일봉. data_dir/index_KOSPI.parquet 등이 있으면 사용, 데모(data_dir=None)는 종목 동일가중 지수"""
    from pathlib import Path
    out = {}
    for m in ("KOSPI", "KOSDAQ"):
        if data_dir is not None:
            f = Path(data_dir) / f"index_{m}.parquet"
            if f.exists():
                out[m] = pd.read_parquet(f)
        elif meta is not None and "market" in meta:
            tickers = meta.loc[meta["market"] == m, "ticker"]
            if len(tickers):
                out[m] = make_index(daily, tickers)
    return out


def scan(daily: pd.DataFrame, meta: pd.DataFrame | None, index_daily: dict[str, pd.DataFrame],
         width: float = 0.10, only_buy: bool = True) -> tuple[pd.DataFrame, dict]:
    """전체 종목 4개 차트 판정.

    index_daily: {"KOSPI": 지수 일봉, "KOSDAQ": ...}. 반환: (종목별 결과 표, 지수별 (일봉채널, 주봉채널))
    """
    idx = {m: channels_of(df, width) for m, df in index_daily.items()}
    market = meta.set_index("ticker")["market"].to_dict() if meta is not None and "market" in meta else {}
    names = meta.set_index("ticker")["name"].to_dict() if meta is not None and "name" in meta else {}
    rows = []
    for t, g in daily.groupby("ticker"):
        m = market.get(t, "KOSPI")
        ev = evaluate(g, idx.get(m, (None, None)), width)
        if only_buy and not ev["buy"]:
            continue
        sd, sw = ev["channels"]["stock_daily"], ev["channels"]["stock_weekly"]
        rows.append({
            "ticker": t, "name": names.get(t, t), "market": m, "buy": ev["buy"], "n_ok": ev["n_ok"],
            **{f"ok_{k}": v for k, v in ev["ok"].items()},
            "close": g.sort_values("date")["close"].iloc[-1],
            "d_support": sd and sd["support"], "d_upper": sd and sd["upper"], "d_touches": sd and sd["touches"],
            "w_support": sw and sw["support"], "w_upper": sw and sw["upper"], "w_touches": sw and sw["touches"],
        })
    out = pd.DataFrame(rows)
    if not out.empty:
        out = out.sort_values(["buy", "n_ok", "w_touches"], ascending=False, na_position="last").reset_index(drop=True)
    return out, idx
