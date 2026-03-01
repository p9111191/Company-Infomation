package com.example.companyinfo

import java.io.Serializable

// ══════════════════════════════════════════════════════════════
//  Company.kt  —  앱 전체 데이터 모델 통합 파일
//  (FinancialRecord 포함)
// ══════════════════════════════════════════════════════════════

/**
 * 연도별 재무 데이터 모델
 *
 * Company.financialRecords: List<FinancialRecord> 로 보유.
 * DetailActivity.showFinancialInfo() 에서 연도 오름차순 정렬 후 최근 3개년을 화면에 바인딩.
 *
 * Excel 시트 "재무정보" 컬럼 매핑:
 *   연도                → year
 *   총자산(백만)         → totalAssets
 *   총부채(백만)         → totalLiabilities
 *   자본금(백만)         → capitalStock
 *   자본총계(백만)       → totalEquity
 *   매출액(백만)         → revenue
 *   영업이익(백만)       → operatingProfit
 *   당기순이익(백만)     → netIncome
 *   현금영업이익(백만)   → cashOperatingProfit   (없으면 null)
 *   경상현금흐름(백만)   → operatingCashFlow     (없으면 null)
 *   투자현금흐름(백만)   → investingCashFlow     (없으면 null)
 *   영업이익률(%)        → operatingProfitMargin
 *   ROE(%)              → roe
 *   부채비율(%)          → debtRatio
 *   이자보상배수(배)     → interestCoverage      (없으면 null)
 *   차입금의존도(%)      → borrowingDependency   (없으면 null)
 */
data class FinancialRecord(
    val year: Int,

    // 재무상태표
    val totalAssets: Long,
    val totalLiabilities: Long,
    val capitalStock: Long?,
    val totalEquity: Long,

    // 손익계산서
    val revenue: Long,
    val operatingProfit: Long,
    val netIncome: Long,

    // 현금흐름분석 (데이터 없는 경우 null)
    val cashOperatingProfit: Long?,
    val operatingCashFlow: Long?,
    val investingCashFlow: Long?,

    // 재무비율
    val operatingProfitMargin: Double,
    val roe: Double,
    val debtRatio: Double,
    val interestCoverage: Double?,      // 이자보상배수 (배)
    val borrowingDependency: Double?    // 차입금의존도 (%)
) : Serializable

// ──────────────────────────────────────────────────────────────

/**
 * 기업 정보 데이터 클래스
 */
data class Company(
    val name: String,                        // 기업명
    val businessNumber: String,              // 사업자번호
    val ceo: String,                         // 대표자
    val address: String,                     // 주소
    val phone: String,                       // 전화번호
    val industry: String,                    // 업종
    val employees: Int,                      // 종업원수
    val creditRating: String,                // 신용등급
    val foundedDate: String,                 // 설립일

    // 기업 형태 정보
    val companyType: String,                 // 기업유형 (외감/주식회사)
    val companySize: String,                 // 기업규모 (대기업)
    val isListed: Boolean,                   // 상장여부
    val stockCode: String?,                  // 주식코드 (상장기업만)

    // ★ 3개년 재무 데이터 (재무정보 탭에서 사용)
    val financialRecords: List<FinancialRecord> = emptyList(),

    // 주요주주 정보
    val majorShareholders: List<Shareholder> = emptyList(),

    // 차입금 정보
    val totalLoans: Long = 0L,               // 총 차입금 (백만원)
    val bankLoans: Long = 0L,                // 은행 차입금 (백만원)
    val nonBankLoans: Long = 0L,             // 비은행 차입금 (백만원)
    val loanDetails: List<LoanInfo> = emptyList(),  // 상세 차입금 리스트

    // 최근 뉴스
    val newsItems: List<NewsItem> = emptyList()
) : Serializable {

    // ── 편의 프로퍼티: 최신 연도 레코드 기반 단일값 접근 ──────────────
    val latestFinancial: FinancialRecord?
        get() = financialRecords.maxByOrNull { it.year }

    val totalAssets: Long             get() = latestFinancial?.totalAssets ?: 0L
    val totalLiabilities: Long        get() = latestFinancial?.totalLiabilities ?: 0L
    val totalEquity: Long             get() = latestFinancial?.totalEquity ?: 0L
    val revenue: Long                 get() = latestFinancial?.revenue ?: 0L
    val operatingProfit: Long         get() = latestFinancial?.operatingProfit ?: 0L
    val netIncome: Long               get() = latestFinancial?.netIncome ?: 0L
    val debtRatio: Double             get() = latestFinancial?.debtRatio ?: 0.0
    val operatingProfitMargin: Double get() = latestFinancial?.operatingProfitMargin ?: 0.0
    val roe: Double                   get() = latestFinancial?.roe ?: 0.0
}

// ──────────────────────────────────────────────────────────────

/**
 * 주요주주 정보
 */
data class Shareholder(
    val name: String,        // 주주명
    val shareRatio: Double   // 지분율 (%)
) : Serializable

/**
 * 주가 정보
 */
data class StockInfo(
    val currentPrice: Int,   // 현재가
    val changeAmount: Int,   // 전일대비 (원)
    val changeRate: Double,  // 등락율 (%)
    val marketCap: Long,     // 시가총액 (억원)
    val per: Double,         // PER
    val pbr: Double          // PBR
) : Serializable

/**
 * 차입금 상세 정보
 */
data class LoanInfo(
    val institution: String,     // 금융기관명
    val institutionType: String, // 구분 (은행/비은행)
    val totalAmount: Long,       // 합계 (백만원)
    val loanAmount: Long,        // 대출채권 (백만원)
    val securities: Long,        // 유가증권 (백만원)
    val guarantee: Long          // 지급보증 (백만원)
) : Serializable

/**
 * 뉴스 아이템
 */
data class NewsItem(
    val title: String,
    val date: String,
    val summary: String
) : Serializable