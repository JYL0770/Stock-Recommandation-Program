"""데이터 로드: 키움 수집 데이터(data/) 또는 데모용 가상 데이터."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from kiwoom.storage import Storage


def load_collected(data_dir: str | Path = "data") -> tuple[pd.DataFrame, pd.DataFrame]:
    """collect_data.py 로 모은 데이터 → (일봉, 종목정보)

    종목정보: ticker, name, market, sector + (있으면) 최신 basic_info 의 per/pbr/roe/market_cap
    """
    st = Storage(data_dir)
    daily = st.load_all_daily()
    meta = st.load_table("universe")
    if meta is None:
        meta = st.load_table("stock_list")
    if meta is None:
        meta = pd.DataFrame({"ticker": daily["ticker"].unique() if not daily.empty else []})
    meta = meta[[c for c in ("ticker", "name", "market", "sector") if c in meta.columns]]

    infos = sorted(Path(data_dir).glob("basic_info_*.parquet"))
    if infos:
        info = pd.read_parquet(infos[-1])
        cols = [c for c in ("ticker", "per", "pbr", "roe", "eps", "bps", "market_cap") if c in info.columns]
        meta = meta.merge(info[cols], on="ticker", how="left")
        if "name" not in meta and "name" in info:
            meta = meta.merge(info[["ticker", "name"]], on="ticker", how="left")
    return daily, meta


def make_demo_data(n_stocks: int = 80, n_days: int = 750, seed: int = 7) -> tuple[pd.DataFrame, pd.DataFrame]:
    """API 키 없이 앱을 체험하기 위한 가상 주가 데이터.

    실제 종목과 무관한 '데모종목'이며, 추세형·횡보형·하락형·급등락형 등이 섞여 있습니다.
    """
    rng = np.random.default_rng(seed)
    dates = pd.bdate_range(end=pd.Timestamp.today().normalize(), periods=n_days)
    sectors = ["반도체", "2차전지", "바이오", "자동차", "금융", "인터넷", "화학", "조선", "엔터", "유통"]
    market_ret = rng.normal(0.0003, 0.011, n_days)

    rows, meta = [], []
    for i in range(n_stocks):
        ticker = f"9{i:04d}0"
        beta = rng.uniform(0.5, 1.6)
        sigma = rng.uniform(0.012, 0.04)
        # 구간별로 추세(drift)가 바뀌는 레짐 모형
        drift = np.repeat(rng.normal(0.0002, 0.0015, n_days // 125 + 1), 125)[:n_days]
        ret = beta * market_ret + drift + rng.normal(0, sigma, n_days)
        jumps = rng.random(n_days) < 0.004
        ret[jumps] += rng.normal(0, 0.12, jumps.sum())
        close = rng.uniform(3000, 200000) * np.exp(np.cumsum(ret))
        close = np.maximum(close, 100).round(-1)
        open_ = close * (1 + rng.normal(0, sigma / 3, n_days))
        high = np.maximum(open_, close) * (1 + np.abs(rng.normal(0, sigma / 2, n_days)))
        low = np.minimum(open_, close) * (1 - np.abs(rng.normal(0, sigma / 2, n_days)))
        base_vol = rng.uniform(5e4, 3e6)
        volume = (base_vol * np.exp(rng.normal(0, 0.4, n_days)) * (1 + 8 * np.abs(ret))).round()

        rows.append(pd.DataFrame({
            "ticker": ticker, "date": dates, "open": open_.round(-1), "high": high.round(-1),
            "low": low.round(-1), "close": close, "volume": volume,
            "amount": close * volume / 1e6,
        }))
        per = rng.choice([rng.uniform(3, 40), rng.uniform(-30, -1)], p=[0.85, 0.15])
        meta.append({
            "ticker": ticker, "name": f"데모종목{i + 1:02d}",
            "market": "KOSPI" if i % 3 else "KOSDAQ", "sector": sectors[i % len(sectors)],
            "per": round(per, 2), "pbr": round(rng.uniform(0.3, 6), 2), "roe": round(rng.uniform(-10, 30), 2),
            "market_cap": round(close[-1] * rng.uniform(1e7, 5e8) / 1e8),
        })
    return pd.concat(rows, ignore_index=True), pd.DataFrame(meta)


def attach_meta(snap: pd.DataFrame, meta: pd.DataFrame) -> pd.DataFrame:
    if meta is None or meta.empty:
        return snap
    cols = [c for c in meta.columns if c == "ticker" or c not in snap.columns]
    return snap.merge(meta[cols], on="ticker", how="left")
