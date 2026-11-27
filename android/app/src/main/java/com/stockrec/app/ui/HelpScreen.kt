package com.stockrec.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stockrec.core.Factor
import com.stockrec.core.STRATEGIES

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

        SectionTitle("점수순위 탭: 지표 점수")
        Text(
            "1. 종목별 일봉(약 2년)으로 수익률·이동평균·RSI·MACD·볼린저밴드·52주 고저·변동성·거래량 지표를 계산합니다.\n" +
                "2. 거래대금·데이터 일수·주가 등 필터를 통과한 종목만 후보로 남깁니다.\n" +
                "3. 지표를 8개 팩터로 묶어, 후보 종목들 사이의 순위를 0~100점(백분위)으로 바꿉니다.\n" +
                "4. 전략별 가중치로 종합점수를 내고 상위 종목을 추천합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionTitle("팩터")
        for (f in Factor.entries) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.label, fontWeight = FontWeight.Bold)
                    Text(f.help, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionTitle("전략 프리셋")
        for (s in STRATEGIES) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name, fontWeight = FontWeight.Bold)
                    Text(s.description, style = MaterialTheme.typography.bodySmall)
                    Text(s.weights.entries.joinToString(" · ") { "${it.key.label} ${it.value.toInt()}" },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionTitle("데이터 출처")
        Text(
            "실시간 모드는 네이버 금융의 공개 시세(수정주가 일봉)를 받아 휴대폰 안에서 직접 계산합니다. " +
                "별도 서버나 API 키가 필요 없고, 받은 데이터는 휴대폰에만 저장됩니다. " +
                "공식 Open API가 아니라서 형식이 바뀌면 동작하지 않을 수 있으며, 개인적인 참고 용도로만 사용하세요.\n\n" +
                "실시간 모드에는 PER·PBR·ROE가 없어 밸류 팩터는 데모 모드에서만 동작합니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("유의 사항")
        Text(
            "이 앱의 점수는 과거 가격 지표만으로 계산한 참고용 순위이며 투자 권유가 아닙니다. " +
                "뉴스·실적·수급 등은 반영되지 않습니다. 모든 투자 판단과 책임은 본인에게 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }
}
