package com.stockrec.core

/**
 * 추천 대상 종목 목록.
 * 기본은 네이버에서 시가총액 상위 종목을 받아오고, 실패하면 아래 기본 목록을 씁니다.
 * (종목명은 시세 데이터에서 다시 받아오므로 여기 이름은 대체용)
 */
object Universe {
    private val EXCLUDE = Regex("""스팩|ETN|ETF|KODEX|TIGER|KBSTAR|RISE |ARIRANG|HANARO|SOL |ACE |PLUS |리츠$""")

    /** ETF/ETN/스팩/우선주 제외 (Python KiwoomCollector.filter_universe 와 같은 규칙) */
    fun isCommonStock(ticker: String, name: String): Boolean =
        ticker.length == 6 && ticker.last() == '0' && !EXCLUDE.containsMatchIn(name)

    val DEFAULT: List<StockInfo> = listOf(
        // KOSPI
        "005930" to "삼성전자", "000660" to "SK하이닉스", "373220" to "LG에너지솔루션", "207940" to "삼성바이오로직스",
        "005380" to "현대차", "000270" to "기아", "068270" to "셀트리온", "005490" to "POSCO홀딩스",
        "035420" to "NAVER", "051910" to "LG화학", "006400" to "삼성SDI", "105560" to "KB금융",
        "055550" to "신한지주", "012330" to "현대모비스", "028260" to "삼성물산", "035720" to "카카오",
        "003670" to "포스코퓨처엠", "066570" to "LG전자", "086790" to "하나금융지주", "032830" to "삼성생명",
        "015760" to "한국전력", "034730" to "SK", "003550" to "LG", "017670" to "SK텔레콤",
        "030200" to "KT", "096770" to "SK이노베이션", "009150" to "삼성전기", "018260" to "삼성에스디에스",
        "010130" to "고려아연", "011200" to "HMM", "329180" to "HD현대중공업", "012450" to "한화에어로스페이스",
        "042660" to "한화오션", "009540" to "HD한국조선해양", "010140" to "삼성중공업", "034020" to "두산에너빌리티",
        "138040" to "메리츠금융지주", "316140" to "우리금융지주", "024110" to "기업은행", "000810" to "삼성화재",
        "033780" to "KT&G", "090430" to "아모레퍼시픽", "251270" to "넷마블", "036570" to "엔씨소프트",
        "259960" to "크래프톤", "352820" to "하이브", "323410" to "카카오뱅크", "377300" to "카카오페이",
        "011170" to "롯데케미칼", "010950" to "S-Oil", "009830" to "한화솔루션", "000100" to "유한양행",
        "128940" to "한미약품", "302440" to "SK바이오사이언스", "047050" to "포스코인터내셔널", "064350" to "현대로템",
        "267260" to "HD현대일렉트릭", "010120" to "LS ELECTRIC", "006260" to "LS", "001040" to "CJ",
        "097950" to "CJ제일제당", "271560" to "오리온", "004020" to "현대제철", "086280" to "현대글로비스",
        "000720" to "현대건설", "028050" to "삼성E&A", "047810" to "한국항공우주", "079550" to "LIG넥스원",
        "011790" to "SKC", "402340" to "SK스퀘어", "161390" to "한국타이어앤테크놀로지", "078930" to "GS",
        "036460" to "한국가스공사", "029780" to "삼성카드", "006800" to "미래에셋증권", "005830" to "DB손해보험",
        "039490" to "키움증권", "071050" to "한국금융지주", "021240" to "코웨이", "139480" to "이마트",
        "023530" to "롯데쇼핑", "282330" to "BGF리테일", "035250" to "강원랜드", "003490" to "대한항공",
        "241560" to "두산밥캣", "042700" to "한미반도체", "000150" to "두산", "267250" to "HD현대",
        "003230" to "삼양식품", "011070" to "LG이노텍", "034220" to "LG디스플레이", "032640" to "LG유플러스",
        "051900" to "LG생활건강", "000880" to "한화", "088350" to "한화생명", "272210" to "한화시스템",
        "298040" to "효성중공업", "001450" to "현대해상", "069960" to "현대백화점", "000120" to "CJ대한통운",
        "016360" to "삼성증권", "005940" to "NH투자증권",
    ).map { (t, n) -> StockInfo(t, n, "KOSPI") } + listOf(
        // KOSDAQ
        "247540" to "에코프로비엠", "086520" to "에코프로", "196170" to "알테오젠", "028300" to "HLB",
        "263750" to "펄어비스", "293490" to "카카오게임즈", "035900" to "JYP Ent.", "041510" to "에스엠",
        "122870" to "와이지엔터테인먼트", "145020" to "휴젤", "214150" to "클래시스", "058470" to "리노공업",
        "039030" to "이오테크닉스", "357780" to "솔브레인", "240810" to "원익IPS", "036930" to "주성엔지니어링",
        "112040" to "위메이드", "141080" to "리가켐바이오", "214450" to "파마리서치", "277810" to "레인보우로보틱스",
        "095340" to "ISC", "067310" to "하나마이크론", "253450" to "스튜디오드래곤", "403870" to "HPSP",
        "068760" to "셀트리온제약", "084370" to "유진테크", "035760" to "CJ ENM", "237690" to "에스티팜",
        "098460" to "고영", "000250" to "삼천당제약", "085660" to "차바이오텍", "310210" to "보로노이",
        "086900" to "메디톡스", "064760" to "티씨케이", "222800" to "심텍", "090460" to "비에이치",
        "005290" to "동진쎄미켐", "131970" to "두산테스나", "089030" to "테크윙", "101490" to "에스앤에스텍",
        "195940" to "HK이노엔", "053800" to "안랩", "018290" to "브이티", "257720" to "실리콘투",
        "383310" to "에코프로에이치엔", "348370" to "엔켐", "078600" to "대주전자재료", "060280" to "큐렉소",
    ).map { (t, n) -> StockInfo(t, n, "KOSDAQ") }

    /** 미국 대형주 (NYSE·NASDAQ). 시장 구분은 시세 응답의 거래소 정보로 자동 설정 */
    val US_LARGE: List<String> = """
        AAPL MSFT NVDA GOOGL AMZN META AVGO TSLA BRK-B LLY JPM V UNH XOM MA COST HD PG JNJ WMT
        NFLX ABBV BAC CRM ORCL CVX KO MRK AMD PEP TMO ADBE LIN ACN MCD CSCO ABT WFC IBM GE
        PM DHR INTU QCOM TXN CAT AMGN VZ ISRG NOW PFE GS DIS SPGI RTX UNP CMCSA AMAT NEE T
        LOW BKNG HON UBER PGR AXP ETN SYK BLK MS C TJX PLD BSX VRTX LRCX ADI MU PANW KLAC
        SCHW MDT ADP CB DE REGN GILD SBUX BMY ANET TMUS SO INTC LMT BA MO DUK SNPS CDNS PLTR
        SHOP MELI PYPL ABNB CRWD NKE MRVL COIN ARM TSM ASML BABA PDD SONY TM NVO SAP
    """.trim().split(Regex("\\s+"))

    /** 장외시장(OTC·핑크시트): 해외 대형주 ADR 과 미국 OTC 종목 */
    val US_OTC: List<String> = """
        NSRGY RHHBY TCEHY LVMUY NTDOY SFTBY ADDYY BAYRY BASFY DTEGY SIEGY VWAGY IDEXY AIQUY LRLCY
        HESAY DANOY CFRUY ESLOY SAFRY AXAHY ALIZY MURGY RYCEY GLNCY BNPQY OLCLY SHECY KDDIY
        DSCSY ATEYY CHGCY DSNKY TKOMY XIACY BYDDY MPNGY HNNMY VLVLY ATLKY EADSY RNMBY HEINY ADRNY
        PROSY NPSNY CSLLY SBGSY DSDVY TSCDY FANUY HTHIY SMCAY DNZOY FNMA FMCC
    """.trim().split(Regex("\\s+"))
}
