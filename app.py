"""국내 주식 가격지표 기반 추천 대시보드

실행:  streamlit run app.py
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd
import plotly.graph_objects as go
import streamlit as st
from plotly.subplots import make_subplots

from recommender import (
    FACTOR_HELP, FACTORS, FEATURE_DESCRIPTIONS, STRATEGIES, Filters, attach_meta,
    compute_features, factor_scores, latest_snapshot, load_collected, make_demo_data,
    recommend, run_backtest,
)
from recommender.scoring import apply_filters
from recommender.trendline import STATUS_BELOW, STATUS_IN, ChannelParams, channel_lines, fit_channel, weekly

st.set_page_config(page_title="주식 추천 (가격 지표)", page_icon="📈", layout="wide")


# ------------------------------------------------------------------ 데이터
@st.cache_data(show_spinner="지표 계산 중...")
def load(source: str, data_dir: str):
    daily, meta = make_demo_data() if source == "demo" else load_collected(data_dir)
    if daily.empty:
        return None, None, None
    feats = compute_features(daily)
    snap = attach_meta(latest_snapshot(feats), meta)
    return feats, snap, meta


@st.cache_data(show_spinner="추세선 계산 중...")
def channels(_feats, key, width):
    p = ChannelParams(width=width)
    out = {}
    for t, g in _feats.groupby("ticker"):
        ch = fit_channel(weekly(g), p)
        if ch is not None:
            out[t] = ch
    return out


@st.cache_data(show_spinner="백테스트 실행 중...")
def backtest(_feats, key, weights, filters, top_n, hold_days):
    f = {k: list(v) if isinstance(v, tuple) else v for k, v in filters}
    return run_backtest(_feats, dict(weights), Filters(**f), top_n, hold_days)


# ------------------------------------------------------------------ 사이드바
with st.sidebar:
    st.header("⚙️ 설정")
    data_dir = "data"
    has_data = any((Path(data_dir) / "daily").glob("*.parquet")) if (Path(data_dir) / "daily").exists() else False
    source = st.radio(
        "데이터", ["collected", "demo"], index=0 if has_data else 1,
        format_func=lambda s: "수집 데이터 (data/)" if s == "collected" else "데모 (가상 종목)",
    )
    if source == "collected" and not has_data:
        st.warning("data/daily 가 비어 있습니다. `python collect_data.py` 로 먼저 수집하세요.")

    strat_name = st.selectbox("전략", list(STRATEGIES))
    strat = STRATEGIES[strat_name]
    st.caption(strat.description)

    with st.expander("팩터 가중치 조정", expanded=False):
        weights = {}
        for k, label in FACTORS.items():
            weights[k] = st.slider(label, 0, 100, int(strat.weights.get(k, 0)), 5,
                                   key=f"w_{strat_name}_{k}", help=FACTOR_HELP[k])

    st.subheader("필터")
    min_amount = st.number_input("20일 평균 거래대금 하한 (억원)", 0.0, 10000.0, 10.0, 5.0)
    min_days = st.number_input("최소 데이터 일수", 60, 2000, 250, 10)
    use_rsi = st.checkbox("RSI 과열 종목 제외", value=False)
    max_rsi = st.slider("RSI 상한", 50, 95, 80) if use_rsi else None
    above_ma120 = st.checkbox("120일선 위 종목만", value=False)
    top_n = st.slider("추천 종목 수", 5, 50, 20)

feats, snap, meta = load(source, data_dir)
if feats is None:
    st.title("📈 가격 지표 기반 주식 추천")
    st.error("데이터가 없습니다. 사이드바에서 '데모'를 선택하거나 collect_data.py 로 데이터를 수집하세요.")
    st.stop()

markets = sorted(snap["market"].dropna().unique()) if "market" in snap else []
with st.sidebar:
    sel_markets = st.multiselect("시장", markets, default=markets) if markets else None

filters = Filters(min_amount=min_amount * 100, min_days=int(min_days), max_rsi=max_rsi,
                  above_ma120=above_ma120, markets=sel_markets or None)
if sum(weights.values()) == 0:
    st.error("가중치가 모두 0입니다. 최소 한 개 팩터에 가중치를 주세요.")
    st.stop()

top = recommend(snap, weights, filters, top_n)
name_of = (meta.set_index("ticker")["name"].to_dict()
           if meta is not None and "name" in meta else {})


def label(t: str) -> str:
    n = name_of.get(t)
    return f"{n} ({t})" if isinstance(n, str) else t


# ------------------------------------------------------------------ 헤더
st.title("📈 가격 지표 기반 주식 추천")
as_of = snap["date"].max()
c1, c2, c3, c4 = st.columns(4)
c1.metric("기준일", f"{as_of:%Y-%m-%d}")
c2.metric("전체 종목", f"{len(snap):,}")
c3.metric("필터 통과", f"{int(top['n_candidates'].iloc[0]) if len(top) else 0:,}")
c4.metric("전략", strat_name)
if source == "demo":
    st.info("데모 모드: 실제 종목이 아닌 **가상 데이터**입니다. 실제 추천은 키움 API로 데이터를 수집한 뒤 사용하세요.")

tab_rec, tab_trend, tab_detail, tab_bt, tab_help = st.tabs(
    ["🏆 추천 종목", "📐 추세선 매수권", "🔍 종목 상세", "🧪 백테스트", "📘 지표 설명"])

# ------------------------------------------------------------------ 추세선 매수권
with tab_trend:
    st.caption("주봉에서 상승 추세의 저점을 가장 많이 잇는 지지선(하단선)과 그 위 평행선(상단선)을 자동으로 긋고, "
               "현재가가 두 선 사이로 내려온 종목을 찾습니다. 지지선은 연 20% 이상 오르는 선, 저점 접점 3회 이상만 사용합니다.")
    c1, c2 = st.columns([1, 3])
    width = c1.select_slider("채널 폭 (상단선 = 지지선 × (1+폭))", [0.05, 0.08, 0.10, 0.15, 0.20], value=0.10,
                             format_func=lambda x: f"{x:.0%}")
    show = c2.radio("보기", [STATUS_IN, STATUS_BELOW, "전체"], horizontal=True)
    chs = channels(feats, source, width)
    rows = [{"ticker": t, **{k: v for k, v in ch.items() if not k.startswith("_")}} for t, ch in chs.items()
            if show == "전체" or ch["status"] == show]
    if not rows:
        st.info("조건에 맞는 종목이 없습니다.")
    else:
        tv = pd.DataFrame(rows).sort_values(["touches", "position"], ascending=[False, True])
        st.dataframe(pd.DataFrame({
            "종목": tv["ticker"].map(label), "상태": tv["status"], "현재가": tv["close"],
            "지지선": tv["support"], "상단선": tv["upper"], "지지선 대비": (tv["close"] / tv["support"] - 1) * 100,
            "접점": tv["touches"], "추세 시작": tv["start_date"].dt.date,
            "지지선 상승(연)": tv["trend_per_year"] * 100, "26주 고점 대비": tv["from_recent_high"] * 100,
        }), hide_index=True, width="stretch", column_config={
            "현재가": st.column_config.NumberColumn(format="%,d"), "지지선": st.column_config.NumberColumn(format="%,d"),
            "상단선": st.column_config.NumberColumn(format="%,d"),
            **{c: st.column_config.NumberColumn(format="%+.1f%%") for c in ("지지선 대비", "지지선 상승(연)", "26주 고점 대비")},
        })
        pick = st.selectbox("차트 보기", tv["ticker"].tolist(), format_func=label)
        L = channel_lines(chs[pick])
        cf = go.Figure(go.Candlestick(x=L["date"], open=L["open"], high=L["high"], low=L["low"], close=L["close"],
                                      name="주봉", increasing_line_color="#d62728", decreasing_line_color="#1f77b4"))
        cf.add_trace(go.Scatter(x=L["date"], y=L["support"], name="지지선", line=dict(color="red", width=2.5)))
        cf.add_trace(go.Scatter(x=L["date"], y=L["upper"], name="상단선", line=dict(color="red", width=2.5)))
        cf.update_layout(height=520, xaxis_rangeslider_visible=False, margin=dict(t=10, b=10),
                         legend=dict(orientation="h", y=1.04))
        st.plotly_chart(cf, width="stretch")

# ------------------------------------------------------------------ 추천
with tab_rec:
    if top.empty:
        st.warning("조건을 만족하는 종목이 없습니다. 필터를 완화해 보세요.")
    else:
        active = [k for k, w in weights.items() if w > 0]
        view = pd.DataFrame({
            "순위": top["rank"],
            "종목": top["ticker"].map(label),
            "시장": top.get("market"),
            "점수": top["score"],
            "종가": top["close"],
            "1M": top["ret_20"] * 100,
            "3M": top["ret_60"] * 100,
            "12M": top["ret_250"] * 100,
            "RSI": top["rsi14"],
            "52주高 대비": (top["high_52w"] - 1) * 100,
            "거래대금(억)": top["amount_20"] / 100,
            **{FACTORS[k]: top[f"factor_{k}"] for k in active},
        })
        pct = {c: st.column_config.NumberColumn(c, format="%+.1f%%") for c in ("1M", "3M", "12M", "52주高 대비")}
        fac = {FACTORS[k]: st.column_config.ProgressColumn(FACTORS[k], min_value=0, max_value=100, format="%.0f")
               for k in active}
        st.dataframe(
            view, hide_index=True, width="stretch",
            column_config={
                "점수": st.column_config.ProgressColumn("점수", min_value=0, max_value=100, format="%.1f"),
                "종가": st.column_config.NumberColumn("종가", format="%,d"),
                "RSI": st.column_config.NumberColumn("RSI", format="%.0f"),
                "거래대금(억)": st.column_config.NumberColumn("거래대금(억)", format="%,.0f"),
                **pct, **fac,
            },
        )

        st.subheader("추천 사유")
        for _, r in top.iterrows():
            with st.expander(f"{r['rank']}. {label(r['ticker'])} — {r['score']:.1f}점"):
                for s in r["reasons"]:
                    st.markdown(f"- ✅ {s}")
                for s in r["warnings"]:
                    st.markdown(f"- ⚠️ {s}")

        csv = top.assign(reasons=top["reasons"].str.join(" / "), warnings=top["warnings"].str.join(" / "))
        st.download_button("CSV 다운로드", csv.to_csv(index=False).encode("utf-8-sig"),
                           f"recommend_{as_of:%Y%m%d}_{strat_name}.csv", "text/csv")

# ------------------------------------------------------------------ 종목 상세
with tab_detail:
    tickers = top["ticker"].tolist() + sorted(set(snap["ticker"]) - set(top["ticker"]))
    t = st.selectbox("종목 선택 (추천 순 → 나머지)", tickers, format_func=label)
    period = st.radio("기간", [120, 250, 500], index=1, horizontal=True, format_func=lambda n: f"{n}거래일")
    g = feats[feats["ticker"] == t].tail(period)

    fig = make_subplots(rows=3, cols=1, shared_xaxes=True, row_heights=[0.6, 0.2, 0.2], vertical_spacing=0.03)
    fig.add_trace(go.Candlestick(x=g["date"], open=g["open"], high=g["high"], low=g["low"], close=g["close"],
                                 name="가격", increasing_line_color="#d62728", decreasing_line_color="#1f77b4"),
                  row=1, col=1)
    for n, color in ((5, "#ff7f0e"), (20, "#2ca02c"), (60, "#9467bd"), (120, "#8c564b")):
        fig.add_trace(go.Scatter(x=g["date"], y=g[f"ma{n}"], name=f"MA{n}", line=dict(width=1.2, color=color)),
                      row=1, col=1)
    fig.add_trace(go.Bar(x=g["date"], y=g["volume"], name="거래량", marker_color="#999"), row=2, col=1)
    fig.add_trace(go.Scatter(x=g["date"], y=g["rsi14"], name="RSI14", line=dict(color="#e377c2")), row=3, col=1)
    for y in (30, 70):
        fig.add_hline(y=y, line_dash="dot", line_color="gray", row=3, col=1)
    fig.update_layout(height=650, xaxis_rangeslider_visible=False, margin=dict(t=10, b=10),
                      legend=dict(orientation="h", y=1.02))
    fig.update_xaxes(rangebreaks=[dict(bounds=["sat", "mon"])])
    st.plotly_chart(fig, width="stretch")

    # 팩터 레이더: 필터 통과 후보 안에서의 백분위 (필터에서 빠진 종목은 전체 기준)
    pool = apply_filters(snap, filters)
    if t not in set(pool["ticker"]):
        pool = snap
        st.caption("※ 이 종목은 현재 필터를 통과하지 못해 전체 종목 기준으로 점수를 계산했습니다.")
    row = factor_scores(pool).set_index("ticker").loc[t]
    cols = st.columns([1, 1])
    with cols[0]:
        keys = [k for k in FACTORS if pd.notna(row[f"factor_{k}"])]
        radar = go.Figure(go.Scatterpolar(r=[row[f"factor_{k}"] for k in keys] + [row[f"factor_{keys[0]}"]],
                                          theta=[FACTORS[k] for k in keys] + [FACTORS[keys[0]]], fill="toself"))
        radar.update_layout(polar=dict(radialaxis=dict(range=[0, 100])), height=380, margin=dict(t=30, b=30),
                            showlegend=False)
        st.plotly_chart(radar, width="stretch")
    with cols[1]:
        show = ["close", "ret_20", "ret_60", "ret_120", "ret_250", "mom_12_1", "ma_align", "dist_ma20",
                "dist_ma60", "dist_ma120", "rsi14", "bb_pctb", "high_52w", "vol_20", "mdd_120",
                "vol_ratio", "amount_20"]
        show += [c for c in ("per", "pbr", "roe") if c in row.index]
        st.dataframe(pd.DataFrame({"지표": [FEATURE_DESCRIPTIONS.get(c, c.upper()) for c in show],
                                   "값": [row[c] for c in show]}).round(4),
                     hide_index=True, width="stretch", height=380)

# ------------------------------------------------------------------ 백테스트
with tab_bt:
    st.caption("매 리밸런싱일마다 **그날까지의 데이터만으로** 점수를 계산해 상위 종목을 동일비중 보유. "
               "벤치마크는 필터를 통과한 전체 후보의 동일비중입니다. 밸류 팩터는 과거 재무 데이터가 없어 제외됩니다.")
    b1, b2 = st.columns(2)
    bt_n = b1.slider("보유 종목 수", 3, 30, 10)
    hold = b2.select_slider("리밸런싱 주기 (거래일)", [5, 10, 20, 40, 60], value=20)
    if st.button("백테스트 실행", type="primary"):
        filter_key = tuple((k, tuple(v) if isinstance(v, list) else v) for k, v in vars(filters).items())
        res = backtest(feats, source, tuple(sorted(weights.items())), filter_key, bt_n, hold)
        if res.periods.empty:
            st.warning("백테스트 가능한 기간/종목이 부족합니다.")
        else:
            s = res.stats
            m = st.columns(4)
            m[0].metric("전략 CAGR", f"{s['전략 CAGR'] * 100:.1f}%",
                        f"{(s['전략 CAGR'] - s['벤치마크 CAGR']) * 100:+.1f}%p vs 벤치마크")
            m[1].metric("전략 MDD", f"{s['전략 MDD'] * 100:.1f}%", f"벤치마크 {s['벤치마크 MDD'] * 100:.1f}%",
                        delta_color="off")
            m[2].metric("초과수익 승률", f"{s['초과수익 승률'] * 100:.0f}%")
            m[3].metric("샤프(근사)", f"{s['전략 샤프(근사)']:.2f}")
            ef = go.Figure()
            ef.add_trace(go.Scatter(x=res.equity.index, y=res.equity["strategy"], name=f"전략({strat_name})",
                                    line=dict(color="#d62728", width=2.5)))
            ef.add_trace(go.Scatter(x=res.equity.index, y=res.equity["benchmark"], name="벤치마크(후보 동일비중)",
                                    line=dict(color="#7f7f7f", width=2, dash="dash")))
            ef.update_layout(height=380, yaxis_title="누적 수익 (시작=1)", margin=dict(t=10, b=10),
                             legend=dict(orientation="h", y=1.08))
            st.plotly_chart(ef, width="stretch")
            per = res.periods.assign(
                start=res.periods["start"].dt.date, end=res.periods["end"].dt.date,
                strategy=res.periods["strategy"] * 100, benchmark=res.periods["benchmark"] * 100,
                picks=res.periods["picks"].str.split(", ").map(lambda ts: ", ".join(label(x) for x in ts)),
            ).rename(columns={"start": "시작", "end": "종료", "strategy": "전략(%)", "benchmark": "벤치마크(%)",
                              "turnover": "회전율", "picks": "보유 종목"})
            st.dataframe(per.round(2), hide_index=True, width="stretch")
            st.caption("⚠️ 상장폐지 종목이 데이터에 없으면 생존편향으로 실제보다 좋게 나올 수 있습니다. "
                       "과거 성과가 미래 수익을 보장하지 않습니다.")

# ------------------------------------------------------------------ 설명
with tab_help:
    st.subheader("팩터")
    st.table(pd.DataFrame({"팩터": list(FACTORS.values()), "계산 방법": [FACTOR_HELP[k] for k in FACTORS]}))
    st.subheader("전략 프리셋")
    st.table(pd.DataFrame({
        "전략": list(STRATEGIES),
        "설명": [s.description for s in STRATEGIES.values()],
        "가중치": [", ".join(f"{FACTORS[k]} {w}" for k, w in s.weights.items()) for s in STRATEGIES.values()],
    }))
    st.subheader("지표")
    st.table(pd.DataFrame({"컬럼": list(FEATURE_DESCRIPTIONS), "설명": list(FEATURE_DESCRIPTIONS.values())}))
    st.caption("점수는 대부분 같은 날 후보 종목 간 백분위(0~100)입니다. 투자 판단의 참고용이며 투자 권유가 아닙니다.")
