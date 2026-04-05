package com.example.companyinfo

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import kotlin.math.abs

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
    private lateinit var gestureDetector: GestureDetector

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
        newsDelegate.initFirebasePressMap()  // Firebase 언론사 맵 실시간 구독 시작

        setupSwipeGesture()
        setupTabs()
        showBasicInfo()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** Activity 레벨에서 터치 이벤트를 가로채 GestureDetector에 전달합니다. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ── 스와이프 제스처 설정 ────────────────────────────────────────────────

    /**
     * 뉴스 상세 보기 화면에서 좌우 스와이프로 인접 기사를 탐색합니다.
     *  - 왼쪽 스와이프 (←) : 목록 위(이전) 기사
     *  - 오른쪽 스와이프 (→) : 목록 아래(다음) 기사
     *
     * dispatchTouchEvent 에서 호출되므로 ScrollView 가 터치를 소비해도
     * Activity 레벨에서 항상 이벤트를 수신합니다.
     */
    private fun setupSwipeGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_MIN_DISTANCE = 120    // px: 최소 이동 거리
            private val SWIPE_MIN_VELOCITY = 200    // px/s: 최소 속도

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // 뉴스 상세 화면일 때만 동작
                if (!newsDelegate.isShowingDetail) return false

                val diffX = e2.x - (e1?.x ?: return false)
                val diffY = e2.y - (e1?.y ?: return false)

                // 수평 스와이프 조건: 가로 이동 > 세로 이동, 거리·속도 임계값 충족
                if (abs(diffX) <= abs(diffY)) return false
                if (abs(diffX) < SWIPE_MIN_DISTANCE) return false
                if (abs(velocityX) < SWIPE_MIN_VELOCITY) return false

                return if (diffX > 0) {
                    // → 오른쪽 스와이프 → 목록 아래(다음) 기사
                    newsDelegate.navigateArticle(-1)
                    true
                } else {
                    // ← 왼쪽 스와이프 → 목록 위(이전) 기사
                    newsDelegate.navigateArticle(1)
                    true
                }
            }
        })
    }

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

        // 탭 행 오른쪽 끝에 돋보기 아이콘 추가
        newsDelegate.attachSearchIconToTabRow(tabLayout)
    }

    // ── 기본정보 ───────────────────────────────────────────────────────────

    private fun showBasicInfo() {
        Log.d("DetailActivity", "showBasicInfo 시작")
        contentLayout.removeAllViews()

        try {
            val view = layoutInflater.inflate(R.layout.layout_basic_info, null)

            view.findViewById<TextView>(R.id.companyName).text    = company.name
            setupGroupName(view)  // ✅ 1행: 그룹 있으면 버튼, 없으면 "기본 정보" 텍스트
            setupCreditRatingBadge(view)  // ✅ 기업명 옆 신용등급 배지
            view.findViewById<TextView>(R.id.businessNumber).text = "사업자번호: ${company.businessNumber}"
            view.findViewById<TextView>(R.id.ceo).text            = "대표자: ${company.ceo}"
            view.findViewById<TextView>(R.id.foundedDate).text    = "설립일: ${company.foundedDate}"
            view.findViewById<TextView>(R.id.address).text        = "주소: ${company.address}"
            view.findViewById<TextView>(R.id.phone).text          = "전화번호: ${company.phone}"
            view.findViewById<TextView>(R.id.industry).text       = "업종: ${company.industry}"
            view.findViewById<TextView>(R.id.employees).text      = "종업원수: ${String.format("%,d명", company.employees)}"
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

    /**
     * ✅ 1행 표시:
     *  - 소속그룹 없음 → "기본 정보" 텍스트(basicInfoTitle) 표시
     *  - 소속그룹 있음 → basicInfoTitle 숨기고 "OO 계열" 입체 버튼(groupBadgeButton) 표시
     *    클릭 시 지배구조도(GroupStructureActivity)로 이동
     */
    private fun setupGroupName(view: View) {
        val basicInfoTitle   = view.findViewById<TextView>(R.id.basicInfoTitle)
        val groupBadgeButton = view.findViewById<android.widget.Button>(R.id.groupBadgeButton)

        if (company.groupName.isNullOrBlank()) {
            // 그룹 없음 → 기본 정보 텍스트만 표시
            basicInfoTitle.visibility   = View.VISIBLE
            groupBadgeButton.visibility = View.GONE
            return
        }

        // 그룹 있음 → 입체 버튼으로 "OO 계열" 표시
        basicInfoTitle.visibility   = View.GONE
        groupBadgeButton.visibility = View.VISIBLE
        groupBadgeButton.apply {
            text = "${company.groupName} 계열"
            setBackgroundColor(resources.getColor(R.color.primary, null))
            setOnClickListener { openGroupStructure(company.groupName!!) }
        }
    }

    /**
     * ✅ 기업명 옆 신용등급 배지 표시
     *  - 신용등급이 있으면 "[A]" 형태로 creditRatingBadge에 표시
     *  - 없으면 gone 유지
     */
    private fun setupCreditRatingBadge(view: View) {
        val badge = view.findViewById<TextView>(R.id.creditRatingBadge)
        val rating = company.creditRating
        if (rating.isNullOrBlank()) {
            badge.visibility = View.GONE
        } else {
            badge.text       = rating.uppercase()
            badge.visibility = View.VISIBLE
        }
    }

    /**
     * 그룹 지배구조 PDF 뷰어 열기
     */
    private fun openGroupStructure(groupName: String) {
        val intent = android.content.Intent(this, GroupStructureActivity::class.java).apply {
            putExtra(GroupStructureActivity.EXTRA_GROUP_NAME, groupName)
        }
        startActivity(intent)
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
                // ─── 다음금융 API: 현재가 / 등락폭 / 시가총액 / PER / PBR ──────────
                // symbolCode 형식: A + 6자리 종목코드 (예: A005930)
                // Referer 헤더 필수 — 없으면 403 반환
                val symbolCode = "A$stockCode"
                val quoteJson  = fetchDaumQuote(symbolCode)

                val currentPrice = quoteJson.optDouble("tradePrice", 0.0).toInt()
                val changeAmount = quoteJson.optDouble("changePrice", 0.0).toInt()
                val changeRate   = quoteJson.optDouble("changeRate",  0.0) * 100.0  // 소수 → %

                // 시가총액: 원 단위 → 억 원
                val marketCap = (quoteJson.optLong("marketCap", 0L)) / 100_000_000L
                val per       = quoteJson.optDouble("per", 0.0)
                val pbr       = quoteJson.optDouble("pbr", 0.0)

                withContext(Dispatchers.Main) {
                    view.findViewById<TextView>(R.id.stockLoadingText).visibility = View.GONE
                    view.findViewById<LinearLayout>(R.id.stockDataLayout).visibility = View.VISIBLE

                    view.findViewById<TextView>(R.id.currentPrice).text =
                        String.format("%,d원", currentPrice)

                    view.findViewById<TextView>(R.id.priceChange).apply {
                        text = String.format(
                            "%s%,d원 (%.2f%%)",
                            if (changeAmount > 0) "▲" else if (changeAmount < 0) "▼" else "",
                            Math.abs(changeAmount),
                            Math.abs(changeRate)
                        )
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

    /**
     * 다음금융 내부 API에서 종목 시세를 조회합니다.
     *
     * 호출 URL:
     *   https://finance.daum.net/api/quote/{symbolCode}
     *   symbolCode = "A" + 6자리 종목코드 (예: A005930)
     *
     * 필수 헤더:
     *   Referer: https://finance.daum.net   ← 없으면 403 응답
     *   User-Agent: 일반 브라우저 UA
     *
     * 주요 응답 필드:
     *   tradePrice   (Double) 현재가
     *   changePrice  (Double) 전일 대비 등락폭 (절댓값)
     *   changeRate   (Double) 등락률 (소수, 예: 0.0123 → 1.23%)
     *   change       (String) "RISE" | "FALL" | "EVEN" → 부호 판단에 활용
     *   marketCap    (Long)   시가총액 (원 단위)
     *   per          (Double) PER
     *   pbr          (Double) PBR
     */
    private fun fetchDaumQuote(symbolCode: String): JSONObject {
        // ❌ 기존 (잘못됨): /api/quote/{code}      → 단수 "quote"
        // ✅ 수정 후:        /api/quotes/{code}     → 복수 "quotes" (웹 URL과 동일)
        val url = "https://finance.daum.net/api/quotes/$symbolCode"

        val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout    = 5_000
            setRequestProperty("Referer",    "https://finance.daum.net/quotes/$symbolCode")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            // 디버깅용: 응답 코드와 함께 오류 메시지도 로그에 출력
            val errBody = runCatching {
                conn.errorStream?.bufferedReader()?.readText() ?: "no body"
            }.getOrDefault("read failed")
            conn.disconnect()
            Log.e("DetailActivity", "다음금융 오류 [$responseCode]: $errBody")
            throw Exception("다음금융 API 응답 오류: HTTP $responseCode")
        }

        val raw = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        Log.d("DetailActivity", "다음금융 응답: ${raw.take(300)}") // 앞 300자만 로그 출력

        val root = JSONObject(raw)
        return if (root.has("data")) root.getJSONObject("data") else root
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

    // 은행 순서 정의 (목록에 없는 기관은 비은행으로 분류)
    private val BANK_ORDER = listOf(
        "부산은행", "경남은행", "아이엠뱅크", "국민은행", "신한은행", "하나은행",
        "우리은행", "농협은행", "기업은행", "수협은행", "광주은행", "전북은행", "제주은행", "산업은행", "수출입은행"
    )

    private fun showLoanInfo() {
        contentLayout.removeAllViews()

        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val bankSet = BANK_ORDER.toSet()

        // 합계 0 제외
        val validLoans = company.loanDetails.filter { it.totalAmount > 0 }

        // institutionType 필드("은행"/"비은행")로 정확하게 분류
        val (rawBankLoans, nonBankLoans) = validLoans.partition { it.institutionType == "은행" }

        // 은행 섹션: BANK_ORDER 순서대로 먼저, 목록에 없는 은행은 맨 뒤에 순서 없이
        val orderedBanks   = rawBankLoans.filter { it.institution in bankSet }
            .sortedBy { BANK_ORDER.indexOf(it.institution) }
        val unorderedBanks = rawBankLoans.filter { it.institution !in bankSet }
        val bankLoans      = orderedBanks + unorderedBanks

        val bankTotal    = bankLoans.sumOf { it.totalAmount }
        val nonBankTotal = nonBankLoans.sumOf { it.totalAmount }

        val outerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 좌, 위, 우, 아래 여백 지정
            setPadding(2.dp(), 6.dp(), 2.dp(), 6.dp())
        }

        outerLayout.addView(createLoanSection("은행 차입금", bankTotal, bankLoans))
        outerLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12.dp())
        })
        outerLayout.addView(createLoanSection("비은행 차입금", nonBankTotal, nonBankLoans))

        val scrollView = android.widget.ScrollView(this).apply {
            addView(outerLayout)
        }
        contentLayout.addView(scrollView)
    }

    /**
     * 은행/비은행 박스 섹션 생성
     */
    private fun createLoanSection(title: String, total: Long, loans: List<LoanInfo>): LinearLayout {
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // 색상 상수 (진한 컬러)
        val colorPrimary   = android.graphics.Color.parseColor("#1A237E")   // 진한 남색 (제목)
        val colorHeader    = android.graphics.Color.parseColor("#283593")   // 헤더 배경
        val colorHeaderTxt = android.graphics.Color.WHITE
        val colorNameTxt   = android.graphics.Color.parseColor("#1A237E")   // 기관명
        val colorTotalTxt  = android.graphics.Color.parseColor("#212121")   // 합계 금액
        val colorSubTxt    = android.graphics.Color.parseColor("#424242")   // 나머지 열
        val colorDivider   = android.graphics.Color.parseColor("#9E9E9E")
        val colorBorder    = android.graphics.Color.parseColor("#3949AB")
        val colorTitleBg   = android.graphics.Color.parseColor("#E8EAF6")   // 제목 배경

        // 외곽 박스 (테두리)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                setStroke(2, colorBorder)
                cornerRadius = 8 * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── 제목 행 ──
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8.dp(), 7.dp(), 8.dp(), 7.dp())
            setBackgroundColor(colorTitleBg)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(colorTitleBg)
                cornerRadii = floatArrayOf(8*dp, 8*dp, 8*dp, 8*dp, 0f, 0f, 0f, 0f)
            }
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colorPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val totalTv = TextView(this).apply {
            text = formatAmountWithUnit(total)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colorPrimary)
            gravity = android.view.Gravity.END
        }
        titleRow.addView(titleTv)
        titleRow.addView(totalTv)
        box.addView(titleRow)

        // 제목/헤더 구분선
        box.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(colorBorder)
        })

        // ── 헤더 행 ──
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6.dp(), 5.dp(), 6.dp(), 5.dp())
            setBackgroundColor(colorHeader)
        }
        fun headerTv(text: String, weight: Float, gravity: Int = android.view.Gravity.END) = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colorHeaderTxt)
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
        headerRow.addView(headerTv("(단위: 백만원)", 2.5f, android.view.Gravity.START))
        headerRow.addView(headerTv("합계", 1.2f))
        headerRow.addView(headerTv("대출채권", 1.2f))
        headerRow.addView(headerTv("유가증권", 1.2f))
        headerRow.addView(headerTv("보증 등", 1.2f))
        box.addView(headerRow)

        // ── 데이터 행 ──
        loans.forEachIndexed { idx, loan ->
            // 구분선
            box.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(colorDivider)
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4.dp(), 5.dp(), 4.dp(), 5.dp())
                // 짝수 행 살짝 다른 배경
                setBackgroundColor(if (idx % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#F5F5F5"))
            }

            fun cellTv(txt: String, weight: Float, color: Int, bold: Boolean = false, gravity: Int = android.view.Gravity.END) =
                TextView(this).apply {
                    text = txt
                    textSize = 11.5f
                    if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(color)
                    this.gravity = gravity
                    setPadding(0, 0, 2.dp(), 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                }

            row.addView(cellTv(displayInstitutionName(loan.institution).replace("(주)",""), 2.5f, colorNameTxt, bold = true, gravity = android.view.Gravity.START))
            row.addView(cellTv(formatNumber(loan.totalAmount), 1.2f, colorTotalTxt, bold = true))
            row.addView(cellTv(formatNumber(loan.loanAmount),  1.2f, colorSubTxt))
            row.addView(cellTv(formatNumber(loan.securities),  1.2f, colorSubTxt))
            row.addView(cellTv(formatNumber(loan.guarantee),   1.2f, colorSubTxt))
            box.addView(row)
        }

        if (loans.isEmpty()) {
            box.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(colorDivider)
            })
            box.addView(TextView(this).apply {
                text = "데이터 없음"
                textSize = 13f
                setTextColor(colorSubTxt)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16.dp(), 0, 16.dp())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }

        return box
    }

    // ── 포맷 헬퍼 ──────────────────────────────────────────────────────────

    private fun formatNumber(amount: Long) = String.format("%,d", amount)
    /** 섹션 제목 옆 합계 표시용 (단위 포함) */
    private fun formatAmountWithUnit(amount: Long) = when {
        amount >= 1_000_000L -> String.format("%.2f조원", amount / 1_000_000.0)
        amount >= 100L       -> String.format("%,.0f억원",  amount / 100.0)
        else                 -> String.format("%,d백만원",  amount)
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

private fun displayInstitutionName(name: String): String = when (name) {
    "한국스탠다드차타드" -> "SC은행"
    "신한라이프_(구)신한생명" -> "신한라이프"
    "비앤피파리바카디프생" -> "카디프생명보험"
    "현대인베스트먼트자산" -> "현대인베스트운용"
    "인피니티글로벌자산운용" -> "인피니티글로벌운용"
    "타이거자산운용투자자" -> "타이거자산운용"
    "중국농업은행주식유한" -> "중국농업은행"
    else -> name
}