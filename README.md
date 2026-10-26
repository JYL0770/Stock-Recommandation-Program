# Stock-Recommandation-Program

Python + Machine Learning / Deep Learning 기반 주식 추천 프로그램.

## 진행 상황

- [x] 데이터 수집 (키움증권 REST API)
- [ ] 피처 엔지니어링 / 라벨링
- [ ] ML 베이스라인 (LightGBM)
- [ ] DL 모델 (LSTM / Transformer)
- [ ] 백테스트
- [ ] 추천 출력 / 대시보드

## 구조

```
├── kiwoom/
│   ├── client.py      # 토큰 발급·갱신, 호출 간격 제한, 연속조회, 재시도
│   ├── collector.py   # 종목목록(ka10099), 일봉(ka10081), 기본정보·재무(ka10001)
│   └── storage.py     # 종목별 parquet 저장, 증분 업데이트
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

## 테스트

```bash
pip install -r requirements-dev.txt
python -m pytest -q tests
```
