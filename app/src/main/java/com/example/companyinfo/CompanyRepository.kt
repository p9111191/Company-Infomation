package com.example.companyinfo

/**
 * 기업 데이터 저장소
 * 실제 앱에서는 데이터베이스나 API를 사용하지만,
 * 여기서는 하드코딩된 데이터를 사용합니다.
 */
object CompanyRepository {

    /**
     * 롯데건설 주요주주 정보
     */
    private val lotteConstructionShareholders = listOf(
        Shareholder("롯데케미칼(주)", 44.02),
        Shareholder("(주)호텔롯데", 43.30),
        Shareholder("롯데알미늄(주)", 9.51)
    )

    /**
     * 롯데건설 차입금 상세 정보 (PDF 원본 데이터)
     */
    private val lotteConstructionLoans = listOf(
        // 은행 (11개)
        LoanInfo("하나은행", "은행", 245816, 242350, 0, 3466),
        LoanInfo("미쓰이스미토모은행", "은행", 200000, 200000, 0, 0),
        LoanInfo("대화은행", "은행", 158929, 125185, 0, 33744),
        LoanInfo("부산은행", "은행", 115000, 100000, 15000, 0),
        LoanInfo("산업은행", "은행", 110000, 110000, 0, 0),
        LoanInfo("우리은행", "은행", 68014, 48500, 0, 19514),
        LoanInfo("한국스탠다드차타드", "은행", 45000, 45000, 0, 0),
        LoanInfo("농협은행", "은행", 40000, 40000, 0, 0),
        LoanInfo("국민은행", "은행", 10625, 10625, 0, 0),
        LoanInfo("신한은행", "은행", 10625, 10625, 0, 0),
        LoanInfo("수협은행", "은행", 0, 0, 0, 0),

        // 비은행금융기관 (30개)
        LoanInfo("주택도시보증공사", "비은행", 7326619, 2054, 0, 7324565),
        LoanInfo("건설공제조합", "비은행", 4999733, 15693, 0, 4984040),
        LoanInfo("엔지니어링공제조합", "비은행", 472030, 0, 0, 472030),
        LoanInfo("서울보증보험", "비은행", 403462, 0, 0, 403462),
        LoanInfo("기계설비건설공제조합", "비은행", 277160, 0, 0, 277160),
        LoanInfo("한국투자증권", "비은행", 142861, 91773, 51088, 0),
        LoanInfo("롯데손해보험", "비은행", 100000, 0, 100000, 0),
        LoanInfo("KB증권", "비은행", 100000, 0, 100000, 0),
        LoanInfo("캡스톤자산운용", "비은행", 58000, 58000, 0, 0),
        LoanInfo("KB캐피탈", "비은행", 40000, 40000, 0, 0),
        LoanInfo("한국투신운용", "비은행", 38460, 0, 38460, 0),
        LoanInfo("삼성생명", "비은행", 30000, 0, 30000, 0),
        LoanInfo("신한투자증권", "비은행", 16545, 16000, 545, 0),
        LoanInfo("디비저축은행", "비은행", 12000, 12000, 0, 0),
        LoanInfo("오케이저축은행", "비은행", 10000, 10000, 0, 0),
        LoanInfo("DB자산운용", "비은행", 10000, 0, 10000, 0),
        LoanInfo("아이비케이투자증권", "비은행", 7569, 0, 7569, 0),
        LoanInfo("제이티친애저축은행", "비은행", 5000, 5000, 0, 0),
        LoanInfo("다올저축은행", "비은행", 5000, 5000, 0, 0),
        LoanInfo("고려상호저축은행", "비은행", 4000, 4000, 0, 0),
        LoanInfo("인피니티글로벌자산운용", "비은행", 3500, 3500, 0, 0),
        LoanInfo("대한상호저축은행", "비은행", 3000, 3000, 0, 0),
        LoanInfo("브이엠자산운용", "비은행", 2000, 0, 2000, 0),
        LoanInfo("타이거자산운용투자자", "비은행", 1844, 0, 1844, 0),
        LoanInfo("퀀트인자산운용", "비은행", 1000, 0, 1000, 0),
        LoanInfo("롯데오토리스", "비은행", 369, 369, 0, 0),
        LoanInfo("대신증권", "비은행", 95, 0, 95, 0),
        LoanInfo("삼성카드", "비은행", 28, 28, 0, 0),
        LoanInfo("키움증권", "비은행", 0, 0, 0, 0)
    )

    /**
     * 롯데건설 뉴스 (예시)
     */
    private val lotteConstructionNews = listOf(
        NewsItem(
            "롯데건설, 2024년 매출 7조 8천억원 달성",
            "2026-02-10",
            "롯데건설이 2024년 연간 매출 7조 8,622억원을 기록하며 전년 대비 15.6% 성장했다."
        ),
        NewsItem(
            "롯데건설, ESG 경영 강화",
            "2026-01-25",
            "롯데건설이 친환경 건설 기술 개발에 투자를 확대하며 ESG 경영을 강화하고 있다."
        ),
        NewsItem(
            "롯데캐슬 브랜드, 고객 만족도 1위",
            "2026-01-15",
            "롯데건설의 아파트 브랜드 '롯데캐슬'이 고객 만족도 조사에서 1위를 차지했다."
        ),
        NewsItem(
            "롯데건설, 해외 프로젝트 수주 확대",
            "2025-12-28",
            "롯데건설이 동남아시아 지역 인프라 프로젝트 수주에 성공하며 해외 사업을 확대하고 있다."
        )
    )

    /**
     * 롯데건설 데이터
     */
    private val lotteConstruction = Company(
        name = "롯데건설(주)",
        businessNumber = "114-81-16377",
        ceo = "오일근",
        address = "(06515) 서울 서초구 잠원로14길 29 (잠원동)",
        phone = "02-3480-9114",
        industry = "아파트건설업",
        employees = 4275,
        creditRating = "a",
        foundedDate = "1959-02-03",

        // 기업 형태 정보 (신규)
        companyType = "외감/주식회사",
        companySize = "대기업",
        isListed = false,              // ← true에서 false로 변경
        stockCode = null,               // ← "004000"에서 null로 변경

        // 재무정보 (2024년 기준)
        totalAssets = 8333306,
        totalLiabilities = 5576109,
        totalEquity = 2757196,
        revenue = 7862299,
        operatingProfit = 177066,
        netIncome = 23809,

        // 재무비율
        debtRatio = 202.24,
        operatingProfitMargin = 2.25,
        roe = 0.89,

        // 주요주주 (신규)
        majorShareholders = lotteConstructionShareholders,

        // 차입금 정보
        totalLoans = 15074284,
        bankLoans = 1004009,
        nonBankLoans = 14070275,
        loanDetails = lotteConstructionLoans,

        // 뉴스
        newsItems = lotteConstructionNews
    )

    /**
     * 한화 차입금 상세 정보 (PDF 원본 데이터)
     */
    private val hanwhaLoans = listOf(
        // 은행 (17개)
        LoanInfo("수출입은행", "은행", 1050749, 1050749, 0, 0),
        LoanInfo("신한은행", "은행", 842736, 750000, 40000, 52736),
        LoanInfo("국민은행", "은행", 769832, 688971, 49906, 30955),
        LoanInfo("하나은행", "은행", 595881, 414120, 13342, 168419),
        LoanInfo("우리은행", "은행", 572015, 502821, 0, 69194),
        LoanInfo("농협은행", "은행", 300000, 300000, 0, 0),
        LoanInfo("산업은행", "은행", 189373, 159396, 0, 29977),
        LoanInfo("중국공상은행", "은행", 100000, 100000, 0, 0),
        LoanInfo("광주은행", "은행", 50000, 50000, 0, 0),
        LoanInfo("부산은행", "은행", 50000, 50000, 0, 0),
        LoanInfo("수협은행", "은행", 50000, 50000, 0, 0),
        LoanInfo("엠유에프지은행", "은행", 50000, 50000, 0, 0),
        LoanInfo("중국광대은행", "은행", 40000, 40000, 0, 0),
        LoanInfo("중국농업은행주식유한", "은행", 50000, 50000, 0, 0),
        LoanInfo("중국은행", "은행", 50000, 50000, 0, 0),
        LoanInfo("대화은행", "은행", 34114, 34114, 0, 0),
        LoanInfo("아이엠뱅크", "은행", 5000, 5000, 0, 0),

        // 비은행금융기관 (주요)
        LoanInfo("건설공제조합", "비은행", 4396539, 20946, 0, 4375593),
        LoanInfo("주택도시보증공사", "비은행", 1516171, 0, 0, 1516171),
        LoanInfo("서울보증보험", "비은행", 776277, 0, 0, 776277),
        LoanInfo("엔지니어링공제조합", "비은행", 496500, 0, 0, 496500),
        LoanInfo("한국투자증권", "비은행", 148431, 125552, 22879, 0),
        LoanInfo("신한투자증권", "비은행", 140144, 0, 140144, 0),
        LoanInfo("한국증권금융", "비은행", 120000, 0, 120000, 0),
        LoanInfo("KB증권", "비은행", 73102, 0, 73102, 0),
        LoanInfo("농협(지역)", "비은행", 70000, 70000, 0, 0),
        LoanInfo("KB라이프생명", "비은행", 50000, 0, 50000, 0),
        LoanInfo("유리자산운용", "비은행", 45000, 0, 45000, 0),
        LoanInfo("미래에셋증권", "비은행", 41110, 0, 41110, 0),
        LoanInfo("삼성생명", "비은행", 20000, 0, 20000, 0),
        LoanInfo("삼성증권", "비은행", 20000, 0, 20000, 0),
        LoanInfo("우리자산운용", "비은행", 20000, 0, 20000, 0),
        LoanInfo("유진자산운용", "비은행", 20000, 0, 20000, 0),
        LoanInfo("KB자산운용", "비은행", 20000, 0, 20000, 0),
        LoanInfo("교보증권", "비은행", 20000, 0, 20000, 0),
        LoanInfo("DB금융투자", "비은행", 20000, 0, 20000, 0),
        LoanInfo("동양생명보험", "비은행", 19988, 0, 19988, 0),
        LoanInfo("다올자산운용(주)", "비은행", 16000, 0, 16000, 0),
        LoanInfo("이스트스프링자산운용", "비은행", 10000, 0, 10000, 0),
        LoanInfo("케이비손해보험", "비은행", 10000, 0, 10000, 0),
        LoanInfo("키움증권", "비은행", 10000, 0, 10000, 0),
        LoanInfo("트러스톤자산운용", "비은행", 10000, 0, 10000, 0),
        LoanInfo("하나자산운용", "비은행", 10000, 0, 10000, 0),
        LoanInfo("수협(지역)", "비은행", 10000, 10000, 0, 0),
        LoanInfo("DB생명", "비은행", 10000, 0, 10000, 0),
        LoanInfo("비엔케이자산운용(주)", "비은행", 10000, 0, 10000, 0),
        LoanInfo("NH투자증권", "비은행", 10100, 0, 10100, 0),
        LoanInfo("하이투자증권", "비은행", 7272, 0, 7272, 0),
        LoanInfo("대신증권", "비은행", 5956, 0, 5956, 0),
        LoanInfo("삼성투신운용", "비은행", 5000, 0, 5000, 0),
        LoanInfo("칸서스자산운용", "비은행", 2500, 0, 2500, 0),
        LoanInfo("대신자산운용", "비은행", 2500, 0, 2500, 0),
        LoanInfo("유안타증권", "비은행", 1232, 0, 1232, 0),
        LoanInfo("한국투신운용", "비은행", 1000, 0, 1000, 0),
        LoanInfo("알파플러스자산운용", "비은행", 1000, 0, 1000, 0),
        LoanInfo("현대캐피탈(주)", "비은행", 783, 783, 0, 0),
        LoanInfo("글로벌원자산운용", "비은행", 500, 0, 500, 0),
        LoanInfo("아이트러스트자산운용", "비은행", 400, 0, 400, 0),
        LoanInfo("롯데오토리스", "비은행", 293, 293, 0, 0),
        LoanInfo("오라이언자산운용", "비은행", 51, 0, 51, 0),
        LoanInfo("오릭스캐피탈코리아", "비은행", 133, 133, 0, 0),
        LoanInfo("신영증권", "비은행", 100, 0, 100, 0),
        LoanInfo("아이비케이투자증권", "비은행", 97, 0, 97, 0),
        LoanInfo("메리츠증권", "비은행", 26, 0, 26, 0)
    )

    /**
     * 한화 주요주주 정보
     */
    private val hanwhaShareholders = listOf(
        Shareholder("한화에너지(주)", 18.80),
        Shareholder("김승연", 10.53),
        Shareholder("김동관", 8.65)
    )

    /**
     * 한화 뉴스 (예시)
     */
    private val hanwhaNews = listOf(
        NewsItem(
            "한화, 2024년 매출 5조 8천억원 기록",
            "2026-02-10",
            "한화가 2024년 연간 매출 5조 8,825억원을 기록했다."
        ),
        NewsItem(
            "한화, 방산 부문 성장세 지속",
            "2026-01-28",
            "한화가 방산 및 화약 부문에서 안정적인 성장을 이어가고 있다."
        ),
        NewsItem(
            "한화, 친환경 에너지 사업 확대",
            "2026-01-15",
            "한화가 친환경 에너지 사업에 투자를 확대하며 사업 포트폴리오를 다각화하고 있다."
        ),
        NewsItem(
            "한화, 영업이익 3,082억원 달성",
            "2025-12-20",
            "한화가 2024년 영업이익 3,082억원을 기록하며 전년 대비 63% 성장했다."
        )
    )

    /**
     * 한화 데이터
     */
    private val hanwha = Company(
        name = "(주)한화",
        businessNumber = "202-81-16825",
        ceo = "김동관",
        address = "(04541) 서울 중구 청계천로86 (장교동)",
        phone = "02-729-1114",
        industry = "화약및불꽃제품 제조업",
        employees = 3317,
        creditRating = "a+",
        foundedDate = "1952-10-28",

        // 기업 형태 정보
        companyType = "유가증권시장/주식회사",
        companySize = "대기업",
        isListed = true,
        stockCode = "000880",  // 한화 주식코드

        // 재무정보 (2024년 기준)
        totalAssets = 10042069,
        totalLiabilities = 6629598,
        totalEquity = 3412471,
        revenue = 5882542,
        operatingProfit = 308229,
        netIncome = 197354,

        // 재무비율
        debtRatio = 194.28,
        operatingProfitMargin = 5.24,
        roe = 5.79,

        // 주요주주
        majorShareholders = hanwhaShareholders,

        // 차입금 정보
        totalLoans = 12967905,
        bankLoans = 4799700,
        nonBankLoans = 8168205,
        loanDetails = hanwhaLoans,

        // 뉴스
        newsItems = hanwhaNews
    )

    /**
     * 전체 기업 리스트
     */
    fun getAllCompanies(): List<Company> {
        return listOf(lotteConstruction, hanwha)
    }
    /**
     * 특정 기업 조회
     */
    fun getCompanyByName(name: String): Company? {
        return getAllCompanies().find { it.name == name }
    }

    /**
     * 디버깅용: 전체 기업 이름 출력
     */
    fun printAllCompanyNames() {
        getAllCompanies().forEach { company ->
            println("등록된 기업: '${company.name}'")
        }
    }
}
