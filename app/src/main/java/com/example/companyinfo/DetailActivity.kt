package com.example.companyinfo

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import android.util.Log


/**
 * 기업 상세 정보 액티비티
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var company: Company
    private lateinit var tabLayout: TabLayout
    private lateinit var contentLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        // Intent로부터 기업명 받기
        val companyName = intent.getStringExtra("companyName")
        Log.d("Detail__Activity", "받은 기업명: $companyName")

        // Repository에서 조회
        company = CompanyRepository.getCompanyByName(companyName ?: "")
            ?: run {
                Log.e("Detail__Activity", "기업을 찾을 수 없습니다: $companyName")
                Log.d("Detail__Activity", "사용 가능한 기업 목록: ${CompanyRepository.getAllCompanies().map { it.name }}")
                finish()
                return
            }

        Log.d("Detail__Activity", "조회된 기업: ${company.name}")

        // 툴바 설정
        supportActionBar?.title = company.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // View 초기화
        tabLayout = findViewById(R.id.tabLayout)
        contentLayout = findViewById(R.id.contentLayout)

        // 탭 설정
        setupTabs()

        // 초기 화면 표시 (기본정보)
        showBasicInfo()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * 탭 설정
     */
    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("기본정보"))
        tabLayout.addTab(tabLayout.newTab().setText("재무정보"))
        tabLayout.addTab(tabLayout.newTab().setText("차입금"))
        tabLayout.addTab(tabLayout.newTab().setText("뉴스"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showBasicInfo()
                    1 -> showFinancialInfo()
                    2 -> showLoanInfo()
                    3 -> showNews()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /**
     * 기본정보 화면
     */
    private fun showBasicInfo() {
        Log.d("Detail__Activity", "showBasicInfo 시작")
        contentLayout.removeAllViews()

        try {
            val view = layoutInflater.inflate(R.layout.layout_basic_info, null)
            Log.d("Detail__Activity", "레이아웃 inflate 성공")

            // 기본 정보
            view.findViewById<TextView>(R.id.companyName).text = company.name
            view.findViewById<TextView>(R.id.businessNumber).text = "사업자번호: ${company.businessNumber}"
            view.findViewById<TextView>(R.id.ceo).text = "대표자: ${company.ceo}"
            view.findViewById<TextView>(R.id.foundedDate).text = "설립일: ${company.foundedDate}"
            view.findViewById<TextView>(R.id.address).text = "주소: ${company.address}"
            view.findViewById<TextView>(R.id.phone).text = "전화번호: ${company.phone}"
            view.findViewById<TextView>(R.id.industry).text = "업종: ${company.industry}"
            view.findViewById<TextView>(R.id.employees).text = "종업원수: ${String.format("%,d명", company.employees)}"
            view.findViewById<TextView>(R.id.creditRating).text = "신용등급: ${company.creditRating}"

            Log.d("Detail__Activity", "기본 정보 설정 완료")

            // 기업유형 및 규모 (신규)
            view.findViewById<TextView>(R.id.companyType).text = "기업유형: ${company.companyType}"
            view.findViewById<TextView>(R.id.companySize).text = "기업규모: ${company.companySize}"

            Log.d("Detail__Activity", "기업 형태 정보 설정 완료")

            // 주요주주 정보 (신규)
            val shareholderLayout = view.findViewById<LinearLayout>(R.id.shareholderLayout)
            Log.d("Detail__Activity", "주요주주 수: ${company.majorShareholders.size}")

            company.majorShareholders.forEach { shareholder ->
                val shareholderView = layoutInflater.inflate(R.layout.item_shareholder, null)
                shareholderView.findViewById<TextView>(R.id.shareholderName).text = shareholder.name
                shareholderView.findViewById<TextView>(R.id.shareRatio).text = String.format("%.2f%%", shareholder.shareRatio)
                shareholderLayout.addView(shareholderView)
            }

            Log.d("Detail__Activity", "주요주주 정보 설정 완료")

            // 주가정보 (상장기업만)
            val stockCode = company.stockCode
            val stockInfoCard = view.findViewById<View>(R.id.stockInfoCard)

            if (company.isListed && stockCode != null && stockCode.isNotEmpty()) {
                Log.d("Detail__Activity", "상장기업 - 주가 정보 조회 시작 (코드: $stockCode)")
                stockInfoCard.visibility = View.VISIBLE
                fetchStockInfo(stockCode, view)
            } else {
                Log.d("Detail__Activity", "비상장기업 또는 주식코드 없음 - 주가 정보 숨김")
                Log.d("Detail__Activity", "isListed: ${company.isListed}, stockCode: $stockCode")
                stockInfoCard.visibility = View.GONE
            }

            contentLayout.addView(view)
            Log.d("Detail__Activity", "contentLayout에 view 추가 완료")
            Log.d("Detail__Activity", "contentLayout 자식 뷰 수: ${contentLayout.childCount}")

        } catch (e: Exception) {
            Log.e("Detail__Activity", "showBasicInfo 오류: ${e.message}")
            e.printStackTrace()
        }


    }


    /**
     * 주가 정보 조회 (네이버금융)
     */
    private fun fetchStockInfo(stockCode: String, view: View) {
        Log.d("Detail__Activity", "fetchStockInfo 시작 - 주식코드: $stockCode")

        // 로딩 표시
        view.findViewById<TextView>(R.id.stockLoadingText).apply {
            visibility = View.VISIBLE
            text = "주가 정보를 불러오는 중..."
        }
        view.findViewById<LinearLayout>(R.id.stockDataLayout).visibility = View.GONE

        // 백그라운드 스레드에서 주가 조회
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 기본 정보 API
                val basicUrl = "https://m.stock.naver.com/api/stock/$stockCode/basic"
                Log.d("Detail__Activity", "기본 정보 API: $basicUrl")

                val basicResponse = URL(basicUrl).readText()
                val basicJson = JSONObject(basicResponse)

                // 현재가, 등락 정보
                val closePriceStr = basicJson.optString("closePrice", "0").replace(",", "")
                val currentPrice = closePriceStr.toIntOrNull() ?: 0

                val changeAmountStr = basicJson.optString("compareToPreviousClosePrice", "0").replace(",", "")
                val changeAmount = changeAmountStr.toIntOrNull() ?: 0

                val changeRateStr = basicJson.optString("fluctuationsRatio", "0")
                val changeRate = changeRateStr.toDoubleOrNull() ?: 0.0

                Log.d("Detail__Activity", "현재가: $currentPrice, 등락: $changeAmount ($changeRate%)")

                // 2. 투자지표 API 시도
                var marketCap = 0L
                var per = 0.0
                var pbr = 0.0

                try {
                    val investUrl = "https://m.stock.naver.com/api/stock/$stockCode/investment"
                    Log.d("Detail__Activity", "투자지표 API: $investUrl")

                    val investResponse = URL(investUrl).readText()
                    val investJson = JSONObject(investResponse)

                    Log.d("Detail__Activity", "투자지표 API 키 목록: ${investJson.keys().asSequence().toList()}")

                    // 시가총액
                    val marketCapStr = investJson.optString("marketValue", "0").replace(",", "")
                    marketCap = (marketCapStr.toLongOrNull() ?: 0) / 100000000

                    // PER, PBR
                    val perStr = investJson.optString("per", "0")
                    per = perStr.toDoubleOrNull() ?: 0.0

                    val pbrStr = investJson.optString("pbr", "0")
                    pbr = pbrStr.toDoubleOrNull() ?: 0.0

                    Log.d("Detail__Activity", "투자지표 - 시가총액: ${marketCap}억, PER: $per, PBR: $pbr")

                } catch (e: Exception) {
                    Log.w("Detail__Activity", "투자지표 API 실패 (무시): ${e.message}")
                    // 투자지표 실패해도 기본 주가 정보는 표시
                }

                // UI 업데이트 (메인 스레드)
                withContext(Dispatchers.Main) {
                    view.findViewById<TextView>(R.id.stockLoadingText).visibility = View.GONE
                    view.findViewById<LinearLayout>(R.id.stockDataLayout).visibility = View.VISIBLE

                    // 현재가
                    view.findViewById<TextView>(R.id.currentPrice).text = String.format("%,d원", currentPrice)

                    // 등락
                    val changeText = String.format("%s%,d원 (%.2f%%)",
                        if (changeAmount > 0) "▲" else if (changeAmount < 0) "▼" else "",
                        Math.abs(changeAmount),
                        Math.abs(changeRate)
                    )
                    view.findViewById<TextView>(R.id.priceChange).apply {
                        text = changeText
                        setTextColor(when {
                            changeAmount > 0 -> resources.getColor(android.R.color.holo_red_dark, null)
                            changeAmount < 0 -> resources.getColor(android.R.color.holo_blue_dark, null)
                            else -> resources.getColor(android.R.color.black, null)
                        })
                    }

                    // 시가총액
                    if (marketCap > 0) {
                        view.findViewById<TextView>(R.id.marketCap).apply {
                            text = "시가총액: ${String.format("%,d억원", marketCap)}"
                            visibility = View.VISIBLE
                        }
                    } else {
                        view.findViewById<TextView>(R.id.marketCap).visibility = View.GONE
                    }

                    // PER
                    if (per > 0) {
                        view.findViewById<TextView>(R.id.per).apply {
                            text = "PER: ${String.format("%.2f", per)}"
                            visibility = View.VISIBLE
                        }
                    } else {
                        view.findViewById<TextView>(R.id.per).visibility = View.GONE
                    }

                    // PBR
                    if (pbr > 0) {
                        view.findViewById<TextView>(R.id.pbr).apply {
                            text = "PBR: ${String.format("%.2f", pbr)}"
                            visibility = View.VISIBLE
                        }
                    } else {
                        view.findViewById<TextView>(R.id.pbr).visibility = View.GONE
                    }

                    Log.d("Detail__Activity", "UI 업데이트 완료")
                }

            } catch (e: java.net.UnknownHostException) {
                Log.e("Detail__Activity", "네트워크 연결 실패: ${e.message}")
                showStockError(view, "네트워크에 연결할 수 없습니다")
            } catch (e: Exception) {
                Log.e("Detail__Activity", "주가 조회 오류: ${e.message}")
                e.printStackTrace()
                showStockError(view, "주가 정보를 불러올 수 없습니다")
            }
        }
    }

    /**
     * 주가 조회 오류 표시
     */
    private fun showStockError(view: View, message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            view.findViewById<TextView>(R.id.stockLoadingText).apply {
                text = message
                visibility = View.VISIBLE
            }
            view.findViewById<LinearLayout>(R.id.stockDataLayout).visibility = View.GONE
        }
    }

    /**
     * 재무정보 화면
     */
    private fun showFinancialInfo() {
        contentLayout.removeAllViews()
        val view = layoutInflater.inflate(R.layout.layout_financial_info, null)
        // 모든 데이터가 XML에 하드코딩되어 있으므로 추가 작업 불필요
        contentLayout.addView(view)
    }

    /**
     * 차입금 정보 화면
     */
    private fun showLoanInfo() {
        contentLayout.removeAllViews()
        val view = layoutInflater.inflate(R.layout.layout_loan_info, null)

        // 차입금 요약
        view.findViewById<TextView>(R.id.totalLoans).text = formatAmount(company.totalLoans)
        view.findViewById<TextView>(R.id.bankLoans).text = formatAmount(company.bankLoans)
        view.findViewById<TextView>(R.id.nonBankLoans).text = formatAmount(company.nonBankLoans)

        // 차입금 상세 리스트 (1줄 테이블 형식)
        val loanListLayout = view.findViewById<LinearLayout>(R.id.loanListLayout)
        company.loanDetails.forEach { loan ->
            val itemView = layoutInflater.inflate(R.layout.item_loan, null)

            // 금융기관명
            itemView.findViewById<TextView>(R.id.institutionName).text = loan.institution

            // 합계
            itemView.findViewById<TextView>(R.id.totalAmount).text = formatNumber(loan.totalAmount)

            // 대출
            itemView.findViewById<TextView>(R.id.loanAmount).text = formatNumber(loan.loanAmount)

            // 유가증권
            itemView.findViewById<TextView>(R.id.securities).text = formatNumber(loan.securities)

            // 보증
            itemView.findViewById<TextView>(R.id.guarantee).text = formatNumber(loan.guarantee)

            loanListLayout.addView(itemView)

            // 구분선 추가
            val divider = View(this)
            divider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
            divider.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            loanListLayout.addView(divider)
        }

        contentLayout.addView(view)
    }

    /**
     * 숫자 포맷팅 (천단위 콤마, 백만원 단위)
     */
    private fun formatNumber(amount: Long): String {
        return String.format("%,d", amount)
    }

    /**
     * 금액 포맷팅
     */
    private fun formatAmount(amount: Long): String {
        return when {
            amount >= 1000000 -> String.format("%.2f조", amount / 1000000.0)
            amount >= 10000 -> String.format("%.0f억", amount / 10000.0)
            else -> String.format("%,d백만", amount)
        }
    }

    /**
     * 뉴스 화면
     */
    private fun showNews() {
        contentLayout.removeAllViews()
        val view = layoutInflater.inflate(R.layout.layout_news, null)

        val newsListLayout = view.findViewById<LinearLayout>(R.id.newsListLayout)
        company.newsItems.forEach { news ->
            val itemView = layoutInflater.inflate(R.layout.item_news, null)

            itemView.findViewById<TextView>(R.id.newsTitle).text = news.title
            itemView.findViewById<TextView>(R.id.newsDate).text = news.date
            itemView.findViewById<TextView>(R.id.newsSummary).text = news.summary

            newsListLayout.addView(itemView)
        }

        contentLayout.addView(view)
    }
}
