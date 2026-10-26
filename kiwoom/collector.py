"""주식 추천용 데이터 조회 로직.

- get_stock_list   : 종목 목록 (ka10099)
- get_daily_ohlcv  : 일봉 OHLCV (ka10081, 수정주가)
- get_basic_info   : 기본정보 / 재무지표 PER·PBR·ROE 등 (ka10001)

응답 필드명은 키움 REST API 문서 기준입니다. 문서가 바뀌면 아래 *_FIELDS 매핑만 고치면 됩니다.
"""
from __future__ import annotations

import logging
from datetime import date, datetime

import pandas as pd

from .client import KiwoomClient

logger = logging.getLogger(__name__)

MARKETS = {"KOSPI": "0", "KOSDAQ": "10"}

STOCK_LIST_FIELDS = {
    "code": "ticker",
    "name": "name",
    "marketName": "market",
    "upName": "sector",
    "listCount": "listed_shares",
    "lastPrice": "last_price",
    "regDay": "listing_date",
    "auditInfo": "audit_info",
    "state": "state",
    "orderWarning": "order_warning",
}

DAILY_FIELDS = {
    "dt": "date",
    "open_pric": "open",
    "high_pric": "high",
    "low_pric": "low",
    "cur_prc": "close",
    "trde_qty": "volume",
    "trde_prica": "amount",  # 거래대금 (백만원)
}

BASIC_INFO_FIELDS = {
    "stk_cd": "ticker",
    "stk_nm": "name",
    "cur_prc": "price",
    "mac": "market_cap",  # 시가총액 (억원)
    "per": "per",
    "pbr": "pbr",
    "eps": "eps",
    "bps": "bps",
    "roe": "roe",
    "ev": "ev",
    "sale_amt": "sales",
    "bus_pro": "operating_profit",
    "cup_nga": "net_income",
    "for_exh_rt": "foreign_ratio",
    "flo_stk": "float_shares",
    "250hgst": "high_250d",
    "250lwst": "low_250d",
}


def _to_number(value) -> float | None:
    """키움 응답의 '+70000', '-1,500', '' 같은 문자열을 숫자로 변환"""
    if value is None:
        return None
    s = str(value).strip().replace(",", "")
    if s in ("", "-", "+"):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _rename(rows: list[dict], mapping: dict[str, str]) -> pd.DataFrame:
    df = pd.DataFrame(rows)
    if df.empty:
        return pd.DataFrame(columns=list(mapping.values()))
    keep = [c for c in mapping if c in df.columns]
    return df[keep].rename(columns=mapping)


class KiwoomCollector:
    def __init__(self, client: KiwoomClient):
        self.client = client

    # ------------------------------------------------------------ 종목 목록
    def get_stock_list(self, markets: tuple[str, ...] = ("KOSPI", "KOSDAQ")) -> pd.DataFrame:
        frames = []
        for m in markets:
            rows: list[dict] = []
            for page in self.client.request_all("ka10099", "/api/dostk/stkinfo", {"mrkt_tp": MARKETS[m]}):
                rows.extend(page.get("list", []))
            df = _rename(rows, STOCK_LIST_FIELDS)
            df["market"] = m
            frames.append(df)
            logger.info("%s 종목 %d개 조회", m, len(df))

        out = pd.concat(frames, ignore_index=True)
        for col in ("listed_shares", "last_price"):
            if col in out:
                out[col] = out[col].map(_to_number)
        return out

    @staticmethod
    def filter_universe(stocks: pd.DataFrame, min_price: float = 1000) -> pd.DataFrame:
        """추천 대상에서 ETF/ETN/스팩/우선주/관리·경고 종목 등 제외"""
        df = stocks.copy()
        name = df["name"].fillna("")
        mask = ~name.str.contains(r"스팩|ETN|ETF|KODEX|TIGER|KBSTAR|ARIRANG|HANARO|SOL |ACE ", regex=True)
        mask &= df["ticker"].str[-1] == "0"  # 우선주(코드 끝자리 5,7,9 등) 제외
        for col, bad in (("state", "관리|정지|정리"), ("order_warning", "[1-9]")):
            if col in df:
                mask &= ~df[col].fillna("").astype(str).str.contains(bad, regex=True)
        if "last_price" in df:
            mask &= df["last_price"].fillna(0) >= min_price
        return df[mask].reset_index(drop=True)

    # ------------------------------------------------------------ 일봉
    def get_daily_ohlcv(
        self,
        ticker: str,
        start: str | date = "20150101",
        end: str | date | None = None,
        adjusted: bool = True,
    ) -> pd.DataFrame:
        """일봉 OHLCV. end(기준일)부터 과거로 연속조회하며 start 이전에서 멈춤"""
        start_s = _fmt_date(start)
        end_s = _fmt_date(end or datetime.now().date())
        body = {"stk_cd": ticker, "base_dt": end_s, "upd_stkpc_tp": "1" if adjusted else "0"}

        rows: list[dict] = []
        for page in self.client.request_all("ka10081", "/api/dostk/chart", body, max_pages=200):
            chunk = page.get("stk_dt_pole_chart_qry", [])
            if not chunk:
                break
            rows.extend(chunk)
            if min(r.get("dt", "99999999") for r in chunk) <= start_s:
                break

        df = _rename(rows, DAILY_FIELDS)
        if df.empty:
            return df
        for col in ("open", "high", "low", "close", "volume", "amount"):
            # 가격에 붙는 +/- 는 등락 부호이므로 절대값 사용
            df[col] = df[col].map(_to_number).abs()
        df["date"] = pd.to_datetime(df["date"], format="%Y%m%d")
        df = df[(df["date"] >= pd.Timestamp(start_s)) & (df["date"] <= pd.Timestamp(end_s))]
        df = df.drop_duplicates("date").sort_values("date").reset_index(drop=True)
        df.insert(0, "ticker", ticker)
        return df

    # ------------------------------------------------------------ 기본정보/재무
    def get_basic_info(self, ticker: str) -> dict:
        data, _ = self.client.request("ka10001", "/api/dostk/stkinfo", {"stk_cd": ticker})
        info = {new: data.get(old) for old, new in BASIC_INFO_FIELDS.items()}
        for k, v in info.items():
            if k not in ("ticker", "name"):
                info[k] = _to_number(v)
        for k in ("price", "high_250d", "low_250d"):
            if info.get(k) is not None:
                info[k] = abs(info[k])
        info["ticker"] = info.get("ticker") or ticker
        info["as_of"] = datetime.now().strftime("%Y-%m-%d")
        return info

    def get_basic_info_bulk(self, tickers: list[str]) -> pd.DataFrame:
        rows = []
        for i, t in enumerate(tickers, 1):
            try:
                rows.append(self.get_basic_info(t))
            except Exception as e:  # 한 종목 실패가 전체를 멈추지 않도록
                logger.warning("기본정보 조회 실패 %s: %s", t, e)
            if i % 100 == 0:
                logger.info("기본정보 %d/%d", i, len(tickers))
        return pd.DataFrame(rows)


def _fmt_date(d: str | date) -> str:
    if isinstance(d, (date, datetime)):
        return d.strftime("%Y%m%d")
    return str(d).replace("-", "")
