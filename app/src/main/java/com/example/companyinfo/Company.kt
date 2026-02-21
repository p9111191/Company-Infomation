package com.example.companyinfo

import java.io.Serializable

/**
 * 기업 정보 데이터 클래스
 */
data class Company(
    val name: String,                    // 기업명
    val businessNumber: String,          // 사업자번호
    val ceo: String,                     // 대표자
    val address: String,                 // 주소
    val phone: String,                   // 전화번호
    val industry: String,                // 업종
    val employees: Int,                  // 종업원수
    val creditRating: String,            // 신용등급
    val foundedDate: String,             // 설립일

    // 기업 형태 정보 (신규 추가)
    val companyType: String,             // 기업유형 (외감/주식회사)
    val companySize: String,             // 기업규모 (대기업)
    val isListed: Boolean,               // 상장여부
    val stockCode: String?,              // 주식코드 (상장기업만)

    // 재무정보
    val totalAssets: Long,               // 자산총계 (백만원)
    val totalLiabilities: Long,          // 부채총계 (백만원)
    val totalEquity: Long,               // 자본총계 (백만원)
    val revenue: Long,                   // 매출액 (백만원)
    val operatingProfit: Long,           // 영업이익 (백만원)
    val netIncome: Long,                 // 당기순이익 (백만원)

    // 재무비율
    val debtRatio: Double,               // 부채비율 (%)
    val operatingProfitMargin: Double,   // 영업이익률 (%)
    val roe: Double,                     // ROE (%)

    // 주요주주 정보 (신규 추가)
    val majorShareholders: List<Shareholder>,

    // 차입금 정보
    val totalLoans: Long,                // 총 차입금 (백만원)
    val bankLoans: Long,                 // 은행 차입금 (백만원)
    val nonBankLoans: Long,              // 비은행 차입금 (백만원)
    val loanDetails: List<LoanInfo>,     // 상세 차입금 리스트

    // 최근 뉴스
    val newsItems: List<NewsItem>
) : Serializable

/**
 * 주요주주 정보 (신규 추가)
 */
data class Shareholder(
    val name: String,                    // 주주명
    val shareRatio: Double               // 지분율 (%)
) : Serializable

/**
 * 주가 정보 (신규 추가)
 */
data class StockInfo(
    val currentPrice: Int,               // 현재가
    val changeAmount: Int,               // 전일대비 (원)
    val changeRate: Double,              // 등락율 (%)
    val marketCap: Long,                 // 시가총액 (억원)
    val per: Double,                     // PER
    val pbr: Double                      // PBR
) : Serializable

/**
 * 차입금 상세 정보
 */
data class LoanInfo(
    val institution: String,             // 금융기관명
    val institutionType: String,         // 구분 (은행/비은행)
    val totalAmount: Long,               // 합계 (백만원)
    val loanAmount: Long,                // 대출채권 (백만원)
    val securities: Long,                // 유가증권 (백만원)
    val guarantee: Long                  // 지급보증 (백만원)
) : Serializable

/**
 * 뉴스 아이템
 */
data class NewsItem(
    val title: String,
    val date: String,
    val summary: String
) : Serializable
