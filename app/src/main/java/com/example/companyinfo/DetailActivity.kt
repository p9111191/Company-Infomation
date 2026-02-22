package com.example.companyinfo

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL

/**
 * 기업 상세 정보 액티비티
 *
 * 변경 사항:
 *  - showFinancialInfo() : XML 하드코딩 → Company 객체 데이터로 동적 바인딩
 *  - 나머지 로직(주주, 차입금, 뉴스, 주가 API)은 기존 코드 그대로 유지
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var company: Company
    private lateinit var tabLayout: TabLayout
    private lateinit var contentLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val companyName = intent.getStringExtra("companyName")
        Log.d("Detail__Activity", "받은 기업명: $companyName")

        company = CompanyRepository.getCompanyByName(companyName ?: "")
            ?: run {
                Log.e("Detail__Activity", "기업을 찾을 수 없습니다: $companyName")
                finish(); return
            }

        Log.d("Detail__Activity", "조회된 기업: ${company.name}")

        supportActionBar?.title = company.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tabLayout     = findViewById(R.id.tabLayout)
        contentLayout = findViewById(R.id.contentLayout)

        setupTabs()
        showBasicInfo()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── 탭 설정 ────────────────────────────────────────────────────────────

    private fun setupTabs() {
        listOf("기본정보", "재무정보", "차입금", "뉴스").forEach {
            tabLayout.addTab(tabLayout.newTab().setText(it))
        }
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

    // ── 기본정보 (기존 코드 유지) ───────────────────────────────────────────

    private fun showBasicInfo() {
        Log.d("Detail__Activity", "showBasicInfo 시작")
        contentLayout.removeAllViews()

        try {
            val view = layoutInflater.inflate(R.layout.layout_basic_info, null)

            view.findViewById<TextView>(R.id.companyName).text    = company.name
            view.findViewById<TextView>(R.id.businessNumber).text = "사업자번호: ${company.businessNumber}"
            view.findViewById<TextView>(R.id.ceo).text            = "대표자: ${company.ceo}"
            view.findViewById<TextView>(R.id.foundedDate).text    = "설립일: ${company.foundedDate}"
            view.findViewById<TextView>(R.id.address).text        = "주소: ${company.address}"
            view.findViewById<TextView>(R.id.phone).text          = "전화번호: ${company.phone}"
            view.findViewById<TextView>(R.id.industry).text       = "업종: ${company.industry}"
            view.findViewById<TextView>(R.id.employees).text      = "종업원수: ${String.format("%,d명", company.employees)}"
            view.findViewById<TextView>(R.id.creditRating).text   = "신용등급: ${company.creditRating}"
            view.findViewById<TextView>(R.id.companyType).text    = "기업유형: ${company.companyType}"
            view.findViewById<TextView>(R.id.companySize).text    = "기업규모: ${company.companySize}"

            // 주요주주
            val shareholderLayout = view.findViewById<LinearLayout>(R.id.shareholderLayout)
            company.majorShareholders.forEach { shareholder ->
                val sv = layoutInflater.inflate(R.layout.item_shareholder, null)
                sv.findViewById<TextView>(R.id.shareholderName).text = shareholder.name
                sv.findViewById<TextView>(R.id.shareRatio).text =
                    String.format("%.2f%%", shareholder.shareRatio)
                shareholderLayout.addView(sv)
            }

            // 주가정보 (상장기업만)
            val stockInfoCard = view.findViewById<View>(R.id.stockInfoCard)
            val stockCode = company.stockCode
            if (company.isListed && !stockCode.isNullOrEmpty()) {
                stockInfoCard.visibility = View.VISIBLE
                fetchStockInfo(stockCode, view)
            } else {
                stockInfoCard.visibility = View.GONE
            }

            contentLayout.addView(view)
        } catch (e: Exception) {
            Log.e("Detail__Activity", "showBasicInfo 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    // ── 주가 API (기존 코드 유지) ───────────────────────────────────────────

    private fun fetchStockInfo(stockCode: String, view: View) {
        Log.d("Detail__Activity", "fetchStockInfo - 코드: $stockCode")

        view.findViewById<TextView>(R.id.stockLoadingText).apply {
            visibility = View.VISIBLE; text = "주가 정보를 불러오는 중..."
        }
        view.findViewById<LinearLayout>(R.id.stockDataLayout).visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val basicJson = JSONObject(
                    URL("https://m.stock.naver.com/api/stock/$stockCode/basic").readText()
                )
                val currentPrice = basicJson.optString("closePrice", "0").replace(",", "").toIntOrNull() ?: 0
                val changeAmount = basicJson.optString("compareToPreviousClosePrice", "0").replace(",", "").toIntOrNull() ?: 0
                val changeRate   = basicJson.optString("fluctuationsRatio", "0").toDoubleOrNull() ?: 0.0

                var marketCap = 0L; var per = 0.0; var pbr = 0.0
                runCatching {
                    val investJson = JSONObject(
                        URL("https://m.stock.naver.com/api/stock/$stockCode/investment").readText()
                    )
                    marketCap = (investJson.optString("marketValue", "0").replace(",", "").toLongOrNull() ?: 0) / 100000000
                    per = investJson.optString("per", "0").toDoubleOrNull() ?: 0.0
                    pbr = investJson.optString("pbr", "0").toDoubleOrNull() ?: 0.0
                }

                withContext(Dispatchers.Main) {
                    view.findViewById<TextView>(R.id.stockLoadingText).visibility = View.GONE
                    view.findViewById<LinearLayout>(R.id.stockDataLayout).visibility = View.VISIBLE
                    view.findViewById<TextView>(R.id.currentPrice).text =
                        String.format("%,d원", currentPrice)
                    view.findViewById<TextView>(R.id.priceChange).apply {
                        text = String.format("%s%,d원 (%.2f%%)",
                            if (changeAmount > 0) "▲" else if (changeAmount < 0) "▼" else "",
                            Math.abs(changeAmount), Math.abs(changeRate))
                        setTextColor(when {
                            changeAmount > 0 -> resources.getColor(android.R.color.holo_red_dark, null)
                            changeAmount < 0 -> resources.getColor(android.R.color.holo_blue_dark, null)
                            else             -> resources.getColor(android.R.color.black, null)
                        })
                    }
                    view.findViewById<TextView>(R.id.marketCap).apply {
                        visibility = if (marketCap > 0) View.VISIBLE else View.GONE
                        text = "시가총액: ${String.format("%,d억원", marketCap)}"
                    }
                    view.findViewById<TextView>(R.id.per).apply {
                        visibility = if (per > 0) View.VISIBLE else View.GONE
                        text = "PER: ${String.format("%.2f", per)}"
                    }
                    view.findViewById<TextView>(R.id.pbr).apply {
                        visibility = if (pbr > 0) View.VISIBLE else View.GONE
                        text = "PBR: ${String.format("%.2f", pbr)}"
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                showStockError(view, "네트워크에 연결할 수 없습니다")
            } catch (e: Exception) {
                Log.e("Detail__Activity", "주가 조회 오류: ${e.message}")
                showStockError(view, "주가 정보를 불러올 수 없습니다")
            }
        }
    }

    private fun showStockError(view: View, message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            view.findViewById<TextView>(R.id.stockLoadingText).apply {
                text = message; visibility = View.VISIBLE
            }
            view.findViewById<LinearLayout>(R.id.stockDataLayout).visibility = View.GONE
        }
    }

    // ── 재무정보 (★ XML 하드코딩 → Company 객체 동적 바인딩으로 변경) ─────────

    private fun showFinancialInfo() {
        contentLayout.removeAllViews()
        val view = layoutInflater.inflate(R.layout.layout_financial_info, null)

        fun tv(id: Int) = view.findViewById<TextView>(id)

        // ── 재무상태표 ──
        tv(R.id.fs_total_assets).text        = formatNum(company.totalAssets)
        tv(R.id.fs_total_liabilities).text   = formatNum(company.totalLiabilities)
        tv(R.id.fs_total_equity).text        = formatNum(company.totalEquity)

        // ── 손익계산서 ──
        tv(R.id.fs_revenue).text             = formatNum(company.revenue)
        tv(R.id.fs_operating_profit).text    = formatNum(company.operatingProfit)
        tv(R.id.fs_net_income).text          = formatNum(company.netIncome)

        // ── 재무비율 ──
        tv(R.id.fs_debt_ratio).text          = String.format("%.2f%%", company.debtRatio)
        tv(R.id.fs_op_margin).text           = String.format("%.2f%%", company.operatingProfitMargin)
        tv(R.id.fs_roe).text                 = String.format("%.2f%%", company.roe)

        contentLayout.addView(view)
    }

    // ── 차입금 (기존 코드 유지) ─────────────────────────────────────────────

    private fun showLoanInfo() {
        contentLayout.removeAllViews()
        val view = layoutInflater.inflate(R.layout.layout_loan_info, null)

        view.findViewById<TextView>(R.id.totalLoans).text   = formatAmount(company.totalLoans)
        view.findViewById<TextView>(R.id.bankLoans).text    = formatAmount(company.bankLoans)
        view.findViewById<TextView>(R.id.nonBankLoans).text = formatAmount(company.nonBankLoans)

        val loanListLayout = view.findViewById<LinearLayout>(R.id.loanListLayout)
        company.loanDetails.forEach { loan ->
            val itemView = layoutInflater.inflate(R.layout.item_loan, null)
            itemView.findViewById<TextView>(R.id.institutionName).text = loan.institution
            itemView.findViewById<TextView>(R.id.totalAmount).text     = formatNumber(loan.totalAmount)
            itemView.findViewById<TextView>(R.id.loanAmount).text      = formatNumber(loan.loanAmount)
            itemView.findViewById<TextView>(R.id.securities).text      = formatNumber(loan.securities)
            itemView.findViewById<TextView>(R.id.guarantee).text       = formatNumber(loan.guarantee)
            loanListLayout.addView(itemView)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            }
            loanListLayout.addView(divider)
        }

        contentLayout.addView(view)
    }

    // ── 뉴스 (기존 코드 유지) ──────────────────────────────────────────────

    private fun showNews() {
        contentLayout.removeAllViews()
        val view = layoutInflater.inflate(R.layout.layout_news, null)
        val newsListLayout = view.findViewById<LinearLayout>(R.id.newsListLayout)

        company.newsItems.forEach { news ->
            val itemView = layoutInflater.inflate(R.layout.item_news, null)
            itemView.findViewById<TextView>(R.id.newsTitle).text   = news.title
            itemView.findViewById<TextView>(R.id.newsDate).text    = news.date
            itemView.findViewById<TextView>(R.id.newsSummary).text = news.summary
            newsListLayout.addView(itemView)
        }

        contentLayout.addView(view)
    }

    // ── 포맷 헬퍼 ──────────────────────────────────────────────────────────

    private fun formatNum(amount: Long) = String.format("%,d", amount)

    private fun formatNumber(amount: Long) = String.format("%,d", amount)

    private fun formatAmount(amount: Long) = when {
        amount >= 1_000_000L -> String.format("%.2f조", amount / 1_000_000.0)
        amount >= 10_000L    -> String.format("%.0f억",  amount / 10_000.0)
        else                 -> String.format("%,d백만",  amount)
    }
}
