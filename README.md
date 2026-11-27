# Stock-Recommandation-Program

Python + Machine Learning / Deep Learning 기반 주식 추천 프로그램.

## 진행 상황

- [x] 데이터 수집 (키움증권 REST API)
- [x] 가격 지표 계산 (이동평균·RSI·MACD·볼린저·52주 고저·변동성·거래량)
- [x] 팩터 점수 기반 추천 (전략 프리셋 + 가중치 조정 + 추천 사유)
- [x] 간이 백테스트
- [x] 추천 대시보드 (Streamlit) / CLI
- [x] Android 앱 (휴대폰에서 실시간 시세로 직접 계산)
- [ ] 뉴스·이슈 등 비가격 정보 반영
- [ ] ML 베이스라인 (LightGBM)
- [ ] DL 모델 (LSTM / Transformer)

## 구조

```
├── kiwoom/
│   ├── client.py      # 토큰 발급·갱신, 호출 간격 제한, 연속조회, 재시도
│   ├── collector.py   # 종목목록(ka10099), 일봉(ka10081), 기본정보·재무(ka10001)
│   └── storage.py     # 종목별 parquet 저장, 증분 업데이트
├── recommender/
│   ├── indicators.py  # 일봉 → 기술적 지표 (미래 데이터 누수 없음)
│   ├── scoring.py     # 팩터 점수(0~100 백분위) · 전략 프리셋 · 필터 · 추천 사유
│   ├── backtest.py    # 리밸런싱 백테스트 (전략 vs 후보 동일비중)
│   └── data.py        # 수집 데이터 로드 / 데모용 가상 데이터
├── app.py             # Streamlit 대시보드
├── recommend.py       # 추천 CLI
├── collect_data.py    # 수집 실행 스크립트
└── tests/             # 가짜 응답 기반 테스트 (API 키 불필요)
```

## 시작하기

```bash
pip install -r requirements.txt
cp .env.example .env   # 키움 REST API 앱키/시크릿키 입력 (https://openapi.kiwoom.com)

python collect_data.py --tickers 005930 000660   # 특정 종목만
python collect_data.py --limit 50 --start 20200101
python collect_data.py                            # 코스피+코스닥 전체 (2015년~)
```

- 키움 REST API는 사용 신청 시 등록한 IP에서만 호출됩니다.
- 수집 데이터는 `data/` 에 저장되며 Git에는 올라가지 않습니다 (`.env` 도 마찬가지).
- 학습용 데이터 로드: `Storage("data").load_all_daily()`

## 추천 앱 실행

```bash
streamlit run app.py                         # 대시보드 (data/ 가 비어 있으면 데모 데이터로 시작)
python recommend.py --demo                   # API 키 없이 가상 데이터로 체험
python recommend.py --strategy 추세추종 --top 30 --max-rsi 80 --csv out.csv
python recommend.py --backtest
```

### 추천 방식

1. **지표 계산** — 종목별 일봉에서 수익률(1·3·6·12개월, 12-1 모멘텀), 이동평균 정배열/이격도/기울기,
   RSI(14), MACD, 볼린저 %B, 52주 고가 대비 위치, 변동성, 최대낙폭, 거래량 비율, 거래대금을 계산
2. **필터** — 거래대금(기본 10억↑), 데이터 일수(250일↑), 주가(1,000원↑), 선택적으로 RSI 상한 / 120일선 위
3. **팩터 점수(0~100)** — 후보 종목 간 백분위로 환산

   | 팩터 | 내용 |
   |---|---|
   | 추세 | 정배열 정도 · 60일선 이격도 · 20일선 기울기 |
   | 모멘텀 | 12-1개월 모멘텀 · 6개월 · 3개월 수익률 |
   | 신고가 근접 | 52주 최고가 대비 위치 |
   | 안정성 | 낮은 변동성 · 낮은 최대낙폭 |
   | 과열 회피 | RSI 55 부근일수록 높음 (절대 점수) |
   | 거래량 증가 | 5일/60일 평균 거래량 비율 |
   | 단기 낙폭(반등) | 1개월 수익률·RSI·%B 가 낮을수록 높음 |
   | 밸류 | 저PER·저PBR·고ROE (`basic_info` 수집 시) |

4. **전략 프리셋** — 균형형 / 추세추종 / 눌림목 / 저변동 안정형 / 가치+모멘텀 (대시보드에서 가중치 직접 조정 가능)
5. **종합점수** 상위 N개 + 추천 사유 / 주의 사항 출력

> 투자 판단 참고용이며 투자 권유가 아닙니다. 백테스트는 생존편향 등의 한계가 있습니다.

## Android 앱

휴대폰에서 바로 쓰는 앱입니다. 서버나 API 키 없이 **네이버 금융 공개 시세**를 받아 휴대폰 안에서 같은 추천 엔진으로 계산합니다.
(키움 REST API 는 등록한 고정 IP 에서만 호출되어 휴대폰에서 직접 쓰기 어렵기 때문)

**설치**: 휴대폰 브라우저로 [최신 APK](https://github.com/JYL0770/Stock-Recommandation-Program/releases/download/android-latest/stock-recommender.apk) 를 받아 실행
→ "알 수 없는 앱 설치" 허용 (Android 8.0 이상). 코드가 바뀌면 GitHub Actions 가 APK 를 자동으로 다시 빌드해 같은 주소에 올립니다.

| 화면 | 내용 |
|---|---|
| 추천 | 전략 선택, 순위·점수·수익률·RSI, 추천 사유/주의 사항 |
| 종목 상세 | 가격·이동평균·거래량·RSI 차트, 팩터 점수, 판단 근거, 지표표 |
| 백테스트 | 보유 종목 수·리밸런싱 주기 설정, 전략 vs 벤치마크 수익 곡선 |
| 설정 | 데이터(실시간/데모), 종목 범위(시총 상위 N·직접 추가), 팩터 가중치, 필터 |

```
android/
├── core/   # 순수 Kotlin 추천 엔진 (Python recommender/ 포팅) + 네이버 시세 클라이언트
│           #   ParityTest: Python 과 같은 지표·점수·추천·백테스트 결과인지 검증
└── app/    # Jetpack Compose UI
```

```bash
cd android
./gradlew :core:test              # 엔진 테스트 (Android SDK 불필요)
./gradlew :app:assembleRelease    # APK 빌드 (Android SDK 필요, 또는 Android Studio 로 android/ 열기)
python scripts/export_parity_fixtures.py   # Python 엔진을 바꿨다면 검증 픽스처 재생성
```

- 실시간 모드에는 PER·PBR·ROE 가 없어 밸류 팩터는 제외되고 나머지 가중치로 계산됩니다.
- 네이버 시세는 공식 Open API 가 아니므로 형식이 바뀌면 동작이 멈출 수 있습니다 (개인 참고용).

## 테스트

```bash
pip install -r requirements-dev.txt
python -m pytest -q tests
```
