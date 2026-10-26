"""키움 서버 없이 가짜 응답으로 수집 로직을 검증하는 테스트"""
from unittest.mock import MagicMock

import pytest

from kiwoom import KiwoomClient, KiwoomCollector, KiwoomConfig, Storage


def _resp(body, headers=None, status=200):
    r = MagicMock(status_code=status, headers=headers or {})
    r.json.return_value = body
    r.raise_for_status = lambda: None
    return r


def _fake_post(url, headers, json, timeout):
    if url.endswith("/oauth2/token"):
        return _resp({"token": "T", "expires_dt": "29991231000000", "return_code": 0})
    api = headers["api-id"]
    if api == "ka10099":
        return _resp({"return_code": 0, "list": [
            {"code": "005930", "name": "삼성전자", "lastPrice": "70000", "state": "증거금20%", "orderWarning": "0"},
            {"code": "005935", "name": "삼성전자우", "lastPrice": "60000", "state": "", "orderWarning": "0"},
            {"code": "069500", "name": "KODEX 200", "lastPrice": "30000", "state": "", "orderWarning": "0"},
            {"code": "123450", "name": "관리종목", "lastPrice": "5000", "state": "관리종목", "orderWarning": "0"},
        ]})
    if api == "ka10081":
        if headers["cont-yn"] == "N":
            return _resp({"return_code": 0, "stk_dt_pole_chart_qry": [
                {"dt": "20251024", "open_pric": "+100", "high_pric": "110", "low_pric": "-95",
                 "cur_prc": "105", "trde_qty": "1,000", "trde_prica": "10"},
                {"dt": "20251023", "open_pric": "98", "high_pric": "101", "low_pric": "97",
                 "cur_prc": "-99", "trde_qty": "900", "trde_prica": "9"},
            ]}, {"cont-yn": "Y", "next-key": "K1"})
        return _resp({"return_code": 0, "stk_dt_pole_chart_qry": [
            {"dt": "20251022", "open_pric": "90", "high_pric": "99", "low_pric": "89",
             "cur_prc": "97", "trde_qty": "800", "trde_prica": "8"},
            {"dt": "20251021", "open_pric": "90", "high_pric": "99", "low_pric": "89",
             "cur_prc": "91", "trde_qty": "800", "trde_prica": "8"},
        ]}, {"cont-yn": "Y", "next-key": "K2"})
    if api == "ka10001":
        return _resp({"return_code": 0, "stk_cd": "005930", "stk_nm": "삼성전자",
                      "cur_prc": "-70000", "per": "12.5", "pbr": "1.2", "roe": "8.1", "mac": "4000000"})
    raise AssertionError(f"unexpected api {api}")


@pytest.fixture
def collector():
    client = KiwoomClient(KiwoomConfig("key", "secret", min_interval=0))
    client.session.post = _fake_post
    return KiwoomCollector(client)


def test_filter_universe(collector):
    universe = collector.filter_universe(collector.get_stock_list(("KOSPI",)))
    assert universe["ticker"].tolist() == ["005930"]


def test_daily_ohlcv_follows_pages_and_strips_signs(collector):
    df = collector.get_daily_ohlcv("005930", start="20251022", end="20251024")
    assert df["date"].dt.strftime("%Y%m%d").tolist() == ["20251022", "20251023", "20251024"]
    assert df.loc[2, "open"] == 100 and df.loc[2, "low"] == 95 and df.loc[1, "close"] == 99
    assert df.loc[2, "volume"] == 1000


def test_basic_info(collector):
    info = collector.get_basic_info("005930")
    assert info["price"] == 70000 and info["per"] == 12.5 and info["roe"] == 8.1


def test_storage_incremental(tmp_path, collector):
    st = Storage(tmp_path)
    df = collector.get_daily_ohlcv("005930", start="20251022", end="20251024")
    st.save_daily("005930", df.iloc[:2])
    st.save_daily("005930", df.iloc[1:])
    assert len(st.load_daily("005930")) == 3
    assert st.last_date("005930").strftime("%Y%m%d") == "20251024"
