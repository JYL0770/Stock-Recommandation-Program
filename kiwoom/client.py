"""키움증권 REST API 저수준 클라이언트.

- 접근토큰 발급/자동 갱신
- 호출 간격 제한(rate limit)
- 연속조회(cont-yn / next-key) 처리
- 네트워크 오류·429 재시도
"""
from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Iterator

import requests

logger = logging.getLogger(__name__)

REAL_DOMAIN = "https://api.kiwoom.com"
MOCK_DOMAIN = "https://mockapi.kiwoom.com"  # 모의투자 (KRX 종목만 지원)


class KiwoomAPIError(RuntimeError):
    pass


@dataclass
class KiwoomConfig:
    app_key: str
    secret_key: str
    mock: bool = False
    min_interval: float = 0.25  # 호출 간 최소 간격(초). 초당 호출 제한 대응
    timeout: float = 10.0
    max_retries: int = 3

    @classmethod
    def from_env(cls) -> "KiwoomConfig":
        try:
            from dotenv import load_dotenv

            load_dotenv()
        except ImportError:
            pass
        app_key = os.getenv("KIWOOM_APP_KEY")
        secret_key = os.getenv("KIWOOM_SECRET_KEY")
        if not app_key or not secret_key:
            raise KiwoomAPIError(
                "환경변수 KIWOOM_APP_KEY / KIWOOM_SECRET_KEY 가 설정되지 않았습니다 (.env 참고)."
            )
        return cls(
            app_key=app_key,
            secret_key=secret_key,
            mock=os.getenv("KIWOOM_MOCK", "false").lower() in ("1", "true", "yes"),
            min_interval=float(os.getenv("KIWOOM_MIN_INTERVAL", "0.25")),
        )


class KiwoomClient:
    def __init__(self, config: KiwoomConfig):
        self.config = config
        self.base_url = MOCK_DOMAIN if config.mock else REAL_DOMAIN
        self.session = requests.Session()
        self._token: str | None = None
        self._token_expires: datetime = datetime.min
        self._last_call = 0.0

    # ------------------------------------------------------------------ 토큰
    def _issue_token(self) -> None:
        """au10001: 접근토큰 발급"""
        resp = self.session.post(
            f"{self.base_url}/oauth2/token",
            headers={"Content-Type": "application/json;charset=UTF-8"},
            json={
                "grant_type": "client_credentials",
                "appkey": self.config.app_key,
                "secretkey": self.config.secret_key,
            },
            timeout=self.config.timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        if data.get("return_code", 0) != 0 or not data.get("token"):
            raise KiwoomAPIError(f"토큰 발급 실패: {data.get('return_msg', data)}")

        self._token = data["token"]
        expires_dt = data.get("expires_dt")  # 예: "20251027153000"
        try:
            self._token_expires = datetime.strptime(expires_dt, "%Y%m%d%H%M%S")
        except (TypeError, ValueError):
            self._token_expires = datetime.now() + timedelta(hours=23)
        logger.info("접근토큰 발급 완료 (만료: %s)", self._token_expires)

    def _get_token(self) -> str:
        # 만료 5분 전이면 미리 재발급
        if self._token is None or datetime.now() >= self._token_expires - timedelta(minutes=5):
            self._issue_token()
        return self._token  # type: ignore[return-value]

    # ------------------------------------------------------------------ 호출
    def _throttle(self) -> None:
        wait = self.config.min_interval - (time.monotonic() - self._last_call)
        if wait > 0:
            time.sleep(wait)
        self._last_call = time.monotonic()

    def request(
        self,
        api_id: str,
        path: str,
        body: dict[str, Any],
        cont_yn: str = "N",
        next_key: str = "",
    ) -> tuple[dict[str, Any], dict[str, str]]:
        """단건 호출. (응답 body, 연속조회 헤더) 반환"""
        for attempt in range(1, self.config.max_retries + 1):
            self._throttle()
            headers = {
                "Content-Type": "application/json;charset=UTF-8",
                "authorization": f"Bearer {self._get_token()}",
                "api-id": api_id,
                "cont-yn": cont_yn,
                "next-key": next_key,
            }
            try:
                resp = self.session.post(
                    f"{self.base_url}{path}", headers=headers, json=body,
                    timeout=self.config.timeout,
                )
            except requests.RequestException as e:
                logger.warning("[%s] 네트워크 오류 (%d/%d): %s", api_id, attempt, self.config.max_retries, e)
                time.sleep(2 ** attempt)
                continue

            if resp.status_code == 429:  # 호출 한도 초과
                logger.warning("[%s] 호출 한도 초과, %d초 후 재시도", api_id, 2 ** attempt)
                time.sleep(2 ** attempt)
                continue
            if resp.status_code == 401:  # 토큰 만료 → 재발급 후 재시도
                self._token = None
                continue

            resp.raise_for_status()
            data = resp.json()
            if data.get("return_code", 0) != 0:
                raise KiwoomAPIError(f"[{api_id}] {data.get('return_msg')} (code={data.get('return_code')})")
            cont = {
                "cont-yn": resp.headers.get("cont-yn", "N"),
                "next-key": resp.headers.get("next-key", ""),
            }
            return data, cont

        raise KiwoomAPIError(f"[{api_id}] {self.config.max_retries}회 재시도 후 실패")

    def request_all(
        self,
        api_id: str,
        path: str,
        body: dict[str, Any],
        max_pages: int = 100,
    ) -> Iterator[dict[str, Any]]:
        """연속조회를 자동으로 따라가며 페이지별 응답을 yield"""
        cont_yn, next_key = "N", ""
        for _ in range(max_pages):
            data, cont = self.request(api_id, path, body, cont_yn, next_key)
            yield data
            if cont["cont-yn"] != "Y" or not cont["next-key"]:
                break
            cont_yn, next_key = "Y", cont["next-key"]
