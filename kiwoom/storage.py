"""수집 데이터 로컬 저장 (종목별 parquet, 증분 업데이트 지원)"""
from __future__ import annotations

from pathlib import Path

import pandas as pd


class Storage:
    def __init__(self, root: str | Path = "data"):
        self.root = Path(root)
        (self.root / "daily").mkdir(parents=True, exist_ok=True)

    def _daily_path(self, ticker: str) -> Path:
        return self.root / "daily" / f"{ticker}.parquet"

    def load_daily(self, ticker: str) -> pd.DataFrame | None:
        p = self._daily_path(ticker)
        return pd.read_parquet(p) if p.exists() else None

    def last_date(self, ticker: str) -> pd.Timestamp | None:
        df = self.load_daily(ticker)
        return None if df is None or df.empty else df["date"].max()

    def save_daily(self, ticker: str, new: pd.DataFrame) -> pd.DataFrame:
        old = self.load_daily(ticker)
        df = new if old is None else pd.concat([old, new])
        # 수정주가가 바뀌었을 수 있으므로 같은 날짜는 최신 수집분으로 덮어씀
        df = df.drop_duplicates("date", keep="last").sort_values("date").reset_index(drop=True)
        df.to_parquet(self._daily_path(ticker), index=False)
        return df

    def save_table(self, name: str, df: pd.DataFrame) -> Path:
        p = self.root / f"{name}.parquet"
        df.to_parquet(p, index=False)
        return p

    def load_table(self, name: str) -> pd.DataFrame | None:
        p = self.root / f"{name}.parquet"
        return pd.read_parquet(p) if p.exists() else None

    def load_all_daily(self) -> pd.DataFrame:
        """모델 학습용: 전체 종목 일봉을 하나의 DataFrame으로"""
        files = sorted((self.root / "daily").glob("*.parquet"))
        if not files:
            return pd.DataFrame()
        return pd.concat((pd.read_parquet(f) for f in files), ignore_index=True)
