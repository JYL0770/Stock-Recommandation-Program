package com.stockrec.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(contentPadding: PaddingValues) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SectionTitle("추천 탭: 추세선 채널 매수권")
        Text(
            "1. 지지선(하단선): 상승 추세 중 저점을 가장 많이 잇는 우상향 직선. 연 20% 이상 오르고 저점이 3번 이상 닿아야 하며, " +
                "추세 기간 동안 종가가 선을 크게 깨지 않았어야 합니다.\n" +
                "2. 상단선: 지지선과 평행하게 일정 폭(기본 10%) 위.\n" +
                "3. 마지막 종가가 지지선 ~ 상단선 사이면 그 차트는 매수권.\n" +
                "4. 종목 일봉, 종목 주봉, 지수 일봉, 지수 주봉 4개 차트가 모두 매수권일 때만 추천합니다. " +
                "지수는 코스피 종목은 코스피, 코스닥 종목은 코스닥 지수를 봅니다.\n" +
                "일봉은 최근 1년(3개월 이상 이어진 추세), 주봉은 최근 3년(반년 이상 이어진 추세)에서 선을 찾습니다.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionTitle("비교 지수")
        Text(
            "국내: 코스피 종목 → 코스피, 코스닥 종목 → 코스닥\n" +
                "해외: 나스닥 상장 종목 → 나스닥 종합, NYSE 상장 종목 → S&P 500\n" +
                "장외시장(OTC·핑크시트) 종목은 대표 지수가 없어 S&P 500 을 기준으로 봅니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("데이터 출처")
        Text(
            "국내: 네이버 금융 공개 시세(수정주가 일봉). 기본은 코스피·코스닥 시가총액 상위 종목.\n" +
                "해외: Yahoo Finance 공개 시세(분할 반영 일봉). 기본은 미국 대형주와 OTC·핑크시트 종목(해외 대형주 ADR, " +
                "패니메이·프레디맥 등)이며, 설정에서 원하는 심볼을 직접 추가할 수 있습니다.\n\n" +
                "별도 서버나 API 키 없이 휴대폰 안에서 직접 계산하며, 받은 데이터는 휴대폰에만 저장됩니다. " +
                "두 출처 모두 공식 Open API가 아니라서 형식이 바뀌면 동작하지 않을 수 있으니 개인적인 참고 용도로만 사용하세요. " +
                "핑크시트 종목은 거래가 드물어 시세가 불규칙할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("유의 사항")
        Text(
            "추세선은 과거 가격만으로 기계적으로 그은 것이며 투자 권유가 아닙니다. " +
                "뉴스·실적·수급 등은 반영되지 않습니다. 모든 투자 판단과 책임은 본인에게 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }
}
