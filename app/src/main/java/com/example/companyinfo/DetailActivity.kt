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
 * - 기본정보 / 재무정보 / 차입금 / 뉴스 탭 구성
 * - 재무정보: 재무상태표·손익계산서·현금흐름분석·재무비율 각 3개년 표시
 * - 뉴스 관련 로직은 NewsDelegate 로 위임
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var company: Company
    private lateinit var tabLayout: TabLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var newsDelegate: NewsDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val companyName = intent.getStringExtra("companyName")
        Log.d("DetailActivity", "받은 기업명: $companyName")

        company = CompanyRepository.getCompanyByName(companyName ?: "")
            ?: run {
                Log.e("DetailActivity", "기업을 찾을 수 없습니다: $companyName")
                finish(); return
            }

        Log.d("DetailActivity", "조회된 기업: ${company.name}")

        supportActionBar?.title = company.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tabLayout     = findViewById(R.id.tabLayout)
        contentLayout = findViewById(R.id.contentLayout)

        newsDelegate = NewsDelegate(this, contentLayout, company)

        setupTabs()
        showBasicInfo()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!newsDelegate.handleBack()) {
            super.onBackPressed()
        }
    }

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
                    3 -> {
                        // 1. 상세 기사를 보고 있다면 리스트로 즉시 전환 (캐시 사용)
                        if (newsDelegate.isShowingDetail) {
                            newsDelegate.backToListIfDetail()
                        } else {
                            // 2. 리스트 상태라면(혹은 첫 진입), 캐시가 없을 때만 새로고침
                            newsDelegate.showNews(forceRefresh = false)
                        }
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                // [추가] 뉴스 탭이 이미 선택된 상태에서 다시 클릭했을 때도 리스트로 복귀
                if (tab?.position == 3) {
                    newsDelegate.backToListIfDetail()
                }
            }
        })
    }

    // ── 기본정보 ───────────────────────────────────────────────────────────

    private fun showBasicInfo() {
        Log.d("DetailActivity", "showBasicInfo 시작")
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

            val shareholderLayout = view.findViewById<LinearLayout>(R.id.shareholderLayout)
            company.majorShareholders.forEach { shareholder ->
                val sv = layoutInflater.inflate(R.layout.item_shareholder, null)
                sv.findViewById<TextView>(R.id.shareholderName).text = shareholder.name
                sv.findViewById<TextView>(R.id.shareRatio).text =
                    String.format("%.2f%%", shareholder.shareRatio)
                shareholderLayout.addView(sv)
            }

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
            Log.e("DetailActivity", "showBasicInfo 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    // ── 주가 API ───────────────────────────────────────────────────────────

    private fun fetchStockInfo(stockCode: String, view: View) {
        Log.d("DetailActivity", "fetchStockInfo - 코드: $stockCode")

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
                Log.e("DetailActivity", "주가 조회 오류: ${e.message}")
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

    // ── 재무정보 (3개년 표시) ──────────────────────────────────────────────
    //
    //  Company.financialRecords: List<FinancialRecord> 에서
    //  연도 오름차순으로 정렬한 뒤 최대 3개년을 Y1(가장 오래된) ~ Y3(최신) 에 바인딩.
    //  데이터가 없는 연도 슬롯은 "-" 로 표시.

    private fun showFinancialInfo() {
        contentLayout.removeAllViews()
        val view = layoutInflater.inflate(R.layout.layout_financial_info, null)

        // 연도 오름차순 정렬 후 최근 3개년 추출
        val records = company.financialRecords
            .sortedBy { it.year }
            .takeLast(3)

        // 슬롯 수 (최대 3, 데이터가 적으면 그만큼만)
        val count = records.size

        // ── 헬퍼: n번째(1-based) 슬롯의 레코드 반환, 없으면 null ──
        fun rec(slot: Int): FinancialRecord? = records.getOrNull(slot - 1)

        // ── 헬퍼: 숫자 → 천단위 쉼표 포맷, null 이면 "-" ──
        fun fmtLong(v: Long?): String = if (v == null) "-" else String.format("%,d", v)

        // ── 헬퍼: Double → 소수점 2자리 % 포맷, null 이면 "-" ──
        fun fmtPct(v: Double?): String = if (v == null) "-" else String.format("%.2f%%", v)

        // ── 헬퍼: Double → 소수점 2자리 배 포맷, null 이면 "-" ──
        fun fmtX(v: Double?): String = if (v == null) "-" else String.format("%.2f배", v)

        // 색: 음수=파란색, 양수/0=검은색
        fun colorFor(v: Long?, view: TextView) {
            if (v == null) return
            val colorRes = if (v < 0) android.R.color.holo_blue_dark else android.R.color.black
            view.setTextColor(resources.getColor(colorRes, null))
        }

        fun tv(id: Int) = view.findViewById<TextView>(id)

        // ════ 재무상태표 헤더 연도 ════
        // 슬롯이 비어있으면 헤더도 숨김
        listOf(
            R.id.fs_bs_year_1 to 1,
            R.id.fs_bs_year_2 to 2,
            R.id.fs_bs_year_3 to 3
        ).forEach { (id, slot) ->
            tv(id).text = rec(slot)?.year?.toString() ?: ""
            tv(id).visibility = if (slot <= count) View.VISIBLE else View.INVISIBLE
        }
        // 손익·현금흐름·재무비율 헤더는 재무상태표와 동일 연도 공유
        listOf(R.id.fs_is_year_1, R.id.fs_cf_year_1, R.id.fs_r_year_1).forEach {
            tv(it).text = rec(1)?.year?.toString() ?: ""
            tv(it).visibility = if (count >= 1) View.VISIBLE else View.INVISIBLE
        }
        listOf(R.id.fs_is_year_2, R.id.fs_cf_year_2, R.id.fs_r_year_2).forEach {
            tv(it).text = rec(2)?.year?.toString() ?: ""
            tv(it).visibility = if (count >= 2) View.VISIBLE else View.INVISIBLE
        }
        listOf(R.id.fs_is_year_3, R.id.fs_cf_year_3, R.id.fs_r_year_3).forEach {
            tv(it).text = rec(3)?.year?.toString() ?: ""
            tv(it).visibility = if (count >= 3) View.VISIBLE else View.INVISIBLE
        }

        // ════ 재무상태표 ════
        listOf(1, 2, 3).forEach { slot ->
            val r = rec(slot)
            val ids = when (slot) {
                1 -> Triple(R.id.fs_assets_1, R.id.fs_liabilities_1, R.id.fs_capstock_1) to R.id.fs_equity_1
                2 -> Triple(R.id.fs_assets_2, R.id.fs_liabilities_2, R.id.fs_capstock_2) to R.id.fs_equity_2
                else -> Triple(R.id.fs_assets_3, R.id.fs_liabilities_3, R.id.fs_capstock_3) to R.id.fs_equity_3
            }
            tv(ids.first.first).text  = fmtLong(r?.totalAssets)
            tv(ids.first.second).text = fmtLong(r?.totalLiabilities)
            tv(ids.first.third).text  = fmtLong(r?.capitalStock)
            tv(ids.second).text       = fmtLong(r?.totalEquity)
        }

        // ════ 손익계산서 ════
        listOf(1, 2, 3).forEach { slot ->
            val r = rec(slot)
            val (rId, opId, niId) = when (slot) {
                1    -> Triple(R.id.fs_revenue_1, R.id.fs_op_profit_1, R.id.fs_net_income_1)
                2    -> Triple(R.id.fs_revenue_2, R.id.fs_op_profit_2, R.id.fs_net_income_2)
                else -> Triple(R.id.fs_revenue_3, R.id.fs_op_profit_3, R.id.fs_net_income_3)
            }
            tv(rId).text  = fmtLong(r?.revenue)
            tv(opId).text = fmtLong(r?.operatingProfit)
            tv(niId).text = fmtLong(r?.netIncome)

            // 영업이익·순이익 음수면 파란색, 양수면 붉은색 강조
            colorFor(r?.operatingProfit, tv(opId))
            colorFor(r?.netIncome, tv(niId))
        }

        // ════ 현금흐름분석 ════
        listOf(1, 2, 3).forEach { slot ->
            val r = rec(slot)
            val (copId, coperaId, cinvId) = when (slot) {
                1    -> Triple(R.id.fs_cash_op_1, R.id.fs_cash_oper_1, R.id.fs_cash_invest_1)
                2    -> Triple(R.id.fs_cash_op_2, R.id.fs_cash_oper_2, R.id.fs_cash_invest_2)
                else -> Triple(R.id.fs_cash_op_3, R.id.fs_cash_oper_3, R.id.fs_cash_invest_3)
            }
            tv(copId).text    = fmtLong(r?.cashOperatingProfit)
            tv(coperaId).text = fmtLong(r?.operatingCashFlow)
            tv(cinvId).text   = fmtLong(r?.investingCashFlow)

            colorFor(r?.cashOperatingProfit, tv(copId))
            colorFor(r?.operatingCashFlow, tv(coperaId))
            colorFor(r?.investingCashFlow, tv(cinvId))
        }

        // ════ 재무비율 ════
        listOf(1, 2, 3).forEach { slot ->
            val r = rec(slot)
            val (omId, roeId, drId, icId, bdId) = when (slot) {
                1    -> Quintuple(R.id.fs_op_margin_1, R.id.fs_roe_1, R.id.fs_debt_ratio_1, R.id.fs_interest_cov_1, R.id.fs_borrow_dep_1)
                2    -> Quintuple(R.id.fs_op_margin_2, R.id.fs_roe_2, R.id.fs_debt_ratio_2, R.id.fs_interest_cov_2, R.id.fs_borrow_dep_2)
                else -> Quintuple(R.id.fs_op_margin_3, R.id.fs_roe_3, R.id.fs_debt_ratio_3, R.id.fs_interest_cov_3, R.id.fs_borrow_dep_3)
            }
            tv(omId).text  = fmtPct(r?.operatingProfitMargin)
            tv(roeId).text = fmtPct(r?.roe)
            tv(drId).text  = fmtPct(r?.debtRatio)
            tv(icId).text  = fmtX(r?.interestCoverage)
            tv(bdId).text  = fmtPct(r?.borrowingDependency)
        }

        contentLayout.addView(view)
    }

    // ── 차입금 ─────────────────────────────────────────────────────────────

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

    // ── 포맷 헬퍼 ──────────────────────────────────────────────────────────

    private fun formatNum(amount: Long)    = String.format("%,d", amount)
    private fun formatNumber(amount: Long) = String.format("%,d", amount)
    private fun formatAmount(amount: Long) = when {
        amount >= 1_000_000L -> String.format("%.2f조", amount / 1_000_000.0)
        amount >= 10_000L    -> String.format("%.0f억",  amount / 10_000.0)
        else                 -> String.format("%,d백만",  amount)
    }

    // ── 5-tuple 헬퍼 (재무비율 5개 ID 묶음용) ─────────────────────────────
    private data class Quintuple<A, B, C, D, E>(
        val first: A, val second: B, val third: C, val fourth: D, val fifth: E
    )
    private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component1() = first
    private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component2() = second
    private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component3() = third
    private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component4() = fourth
    private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component5() = fifth
}