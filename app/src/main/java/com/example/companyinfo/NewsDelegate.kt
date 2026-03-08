package com.example.companyinfo

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

/**
 * 네이버 뉴스 검색 및 본문 표시를 담당하는 Delegate 클래스
 *
 * [변경 사항 v3]
 *  1. 주요 언론사 필터 완전 제거 → 모든 출처 허용
 *  2. 언론사 도메인 DB 대폭 확장 (표시 전용)
 *  3. 중복 제거 강화:
 *     - stopword에서 내용어("대표", "참여", "진행" 등) 제거 → 의미 없는 연결어만 남김
 *     - 1단계: 키워드 교집합 >= 2
 *     - 2단계: 키워드가 부족할 경우 trigram Jaccard >= 0.35 로 보완
 *  4. fetchNewsItems() 공통 헬퍼 유지
 */
class NewsDelegate(
    private val activity      : AppCompatActivity,
    private val contentLayout : LinearLayout,
    private val company       : Company
) {
    private var cachedNewsList: List<NaverNewsItem>? = null
    /** ancestor ScrollView 기준 목록 스크롤 위치 (상세→목록 복귀 시 복원) */
    private var savedListScrollY: Int = 0
    // ── 상수 ────────────────────────────────────────────────────────────────
    companion object {
        val NAVER_CLIENT_ID     = BuildConfig.NAVER_CLIENT_ID
        val NAVER_CLIENT_SECRET = BuildConfig.NAVER_CLIENT_SECRET
        const val NEWS_DISPLAY_COUNT = 50
        private const val TAG        = "NewsDelegate"

        /**
         * 도메인 → 언론사명 매핑 (표시 전용 – 필터링과 무관)
         * 미등록 도메인은 URL에서 자동 추출합니다.
         */
        private val PRESS_DOMAIN_MAP = mapOf(
            // 통신사
            "yna.co.kr"              to "연합뉴스",
            "newsis.com"             to "뉴시스",
            "news1.kr"               to "뉴스1",
            // 경제지
            "mk.co.kr"               to "매일경제",
            "hankyung.com"           to "한국경제",
            "sedaily.com"            to "서울경제",
            "etnews.com"             to "전자신문",
            "thebell.co.kr"          to "더벨",
            "edaily.co.kr"           to "이데일리",
            "etoday.co.kr"           to "이투데이",
            "inews24.com"            to "아이뉴스24",
            "fnnews.com"             to "파이낸셜뉴스",
            "mt.co.kr"               to "머니투데이",
            "moneys.mt.co.kr"        to "머니S",
            "bizwatch.co.kr"         to "비즈워치",
            "wowtv.co.kr"            to "한국경제TV",
            "asiae.co.kr"            to "아시아경제",
            "asiatoday.co.kr"        to "아시아투데이",
            "newspim.com"            to "뉴스핌",
            "fntoday.co.kr"          to "파이낸스투데이",
            "thevaluenews.co.kr"     to "더밸류뉴스",
            "sisajournal-e.com"      to "시사저널이코노미",
            "joongangenews.com"      to "중앙이코노미뉴스",
            "enewstoday.co.kr"       to "이뉴스투데이",
            "arunews.com"            to "한국주택경제",
            "koreastocknews.com"     to "증권경제신문",
            "biztribune.co.kr"       to "비즈트리뷴",
            "heraldcorp.com"         to "헤럴드경제",
            "fntimes.com"            to "한국금융",
            "seoulfn.com"            to "서울파이낸스",
            "niceeconomy.co.kr"      to "나이스경제",
            "geconomy.co.kr"         to "지이코노미",
            "businesspost.co.kr"     to "비지니스포스트",
            "financialreview.co.kr"  to "파이낸셜리뷰",
            "econonews.co.kr"        to "이코노뉴스",
            "economytalk.kr"         to "이코노미톡뉴스",
            "ajunews.com"            to "아주경제",
            "getnews.co.kr"          to "글로벌경제신문",
            // 종합일간지
            "chosun.com"             to "조선일보",
            "joins.com"              to "중앙일보",
            "joongang.co.kr"         to "중앙일보",
            "donga.com"              to "동아일보",
            "hani.co.kr"             to "한겨레",
            "khan.co.kr"             to "경향신문",
            "ohmynews.com"           to "오마이뉴스",
            "pressian.com"           to "프레시안",
            "mediatoday.co.kr"       to "미디어오늘",
            "dailian.co.kr"          to "데일리안",
            "newdaily.co.kr"         to "뉴데일리",
            "gukjenews.com"          to "국제뉴스",
            "newsfreezone.co.kr"     to "뉴스프리존",
            "digitaltoday.co.kr"     to "디지털투데이",
            "news2day.co.kr"         to "뉴스투데이",
            "segyebiz.com"           to "세계비즈",
            "thefirstmedia.net"      to "더퍼스트",
            "businessplus.kr"        to "비즈니스플러스",
            "newsworker.co.kr"       to "뉴스워커",
            "bloter.net"             to "블로터",
            "seoul.co.kr"            to "서울신문",
            "mediapen.com"           to "미디어펜",
            "segye.com"              to "세계일보",
            // 방송
            "kbs.co.kr"              to "KBS",
            "mbc.co.kr"              to "MBC",
            "sbs.co.kr"              to "SBS",
            "ytn.co.kr"              to "YTN",
            "mtn.co.kr"              to "MTN",
            "tvchosun.com"           to "TV조선",
            "jtbc.co.kr"             to "JTBC",
            // 지역·전문지
            "busan.com"              to "부산일보",
            "imaeil.com"             to "매일신문",
            "knnews.co.kr"           to "경남신문",
            "labortoday.co.kr"       to "매일노동뉴스",
            "nspna.com"              to "NSP통신",
            "dnews.co.kr"            to "대한경제",
            "pinpointnews.co.kr"     to "핀포인트뉴스",
            "sentv.co.kr"            to "서울경제TV",
            "kjdaily.com"            to "광주매일신문",
            "jndn.com"               to "전남매일",
            "news2day.co.kr"         to "뉴스투데이",
            "job-post.co.kr"         to "잡포스트",
            "wikitree.co.kr"         to "위키트리",
            "naver.com"              to "네이버뉴스",
            "bbsi.co.kr"             to "불교뉴스",
            "mediafine.co.kr"        to "미디어파인",
            "ikld.kr"                to "국토일보",
            "srtimes.kr"             to "SR타임스",
            "4th.kr"                 to "포쓰저널",
            "newstnt.com"            to "뉴스티앤티",
            "m-i.kr"                 to "매일일보",
            "enetnews.co.kr"         to "이넷뉴스",
            "ktnews.com"             to "한국석유신문",
            "asiaa.co.kr"            to "아시아에이",
            "sisaon.co.kr"           to "시사오늘",
            "shinailbo.co.kr"        to "신아일보",
            "wsobi.com"              to "여성소비자신문",
            "daily.hankooki.com"     to "데일리한국",
            "kukinews.com"           to "쿠키뉴스",
            "newsworks.co.kr"        to "뉴스웍스",
            "dealsite.co.kr"         to "딜사이트",
            "jeonmin.co.kr"          to "전민일보",
            "siminsori.com"          to "시민소리",
            "jnilbo.com"             to "전남일보",
            "polinews.co.kr"         to "폴리뉴스",
            "sidae.com"              to "시대",
            "catchnews.kr"           to "CatchNews"
        )

        /**
         * 중복 판정 기준 1 – 키워드 교집합
         *
         * [핵심 수정] stopword에서 "대표", "참여", "진행" 등 내용어를 제거했으므로
         * 이 단어들이 시그니처에 포함되어 threshold 2를 쉽게 충족합니다.
         * 예) "오일근 대표 안전교육 참여" vs "오일근 대표 임직원 안전교육"
         *     → 교집합 {오일근, 대표, 안전} >= 2 → 중복 처리 ✓
         */
        private const val KEYWORD_THRESHOLD = 2

        /**
         * 중복 판정 기준 2 – trigram Jaccard 유사도 (키워드 보완용)
         * 키워드 수가 적어도 trigram 문자열이 많이 겹치면 중복으로 판단합니다.
         */
        private const val TRIGRAM_THRESHOLD = 0.35

        /**
         * 진짜 의미 없는 연결어·조사만 stopword로 지정합니다.
         * ❌ 제거됨: "대표", "실시", "개최", "진행", "참여", "현장"  ← 내용어이므로 복원
         * ✅ 유지됨: 순수 접속어/시간어 등 판별에 도움이 안 되는 단어만
         */
        private val STOP_WORDS = setOf(
            "관련", "통해", "위해", "위한", "대한", "따른",
            "이후", "지난", "오늘", "내일", "이번", "해당",
            "기자", "뉴스", "제공", "자료"
        )
    }

    var isShowingDetail: Boolean = false
        private set

    /** dp → px 변환 */
    private fun Int.dp(): Int =
        (this * activity.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * contentLayout의 상위 계층에서 첫 번째 ScrollView를 찾아 반환합니다.
     * contentLayout은 LinearLayout이므로 스크롤은 외부 ScrollView가 담당합니다.
     */
    private fun findAncestorScrollView(): android.widget.ScrollView? {
        var v: android.view.ViewParent? = contentLayout.parent
        repeat(12) {
            when (v) {
                is android.widget.ScrollView -> return v as android.widget.ScrollView
                is android.view.View         -> v = (v as android.view.View).parent
                else                         -> return null
            }
        }
        return null
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * 회사명으로 뉴스 검색 → 제목 필터 → 중복 제거 → 목록 표시
     * (언론사 필터 없음 – 모든 출처 허용)
     */
    fun showNews(forceRefresh: Boolean = false) {
        isShowingDetail = false

        if (!forceRefresh && cachedNewsList != null) {
            contentLayout.removeAllViews()
            renderNewsList(cachedNewsList!!)
            return
        }

        savedListScrollY = 0
        findAncestorScrollView()?.scrollTo(0, 0)

        contentLayout.removeAllViews()
        contentLayout.addView(buildLoadingLayout("뉴스를 불러오는 중..."))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 이름 정제 (주식회사 등 제거)
                val searchName = company.name.toCleanCompanyName()
                // 2. 영문 발음 변환 (에스케이 -> SK)
                val engName = searchName.translateToEnglishName()

                // 3. 검색은 영문 변환명으로 수행 (기사 양이 더 많음)
                val query = URLEncoder.encode(engName, "UTF-8")
                val allFetched = fetchNewsItems(query, pages = 5)

                // 4. 필터링: 한글명 OR 영문명 둘 중 하나라도 제목에 포함되면 허용
                val titleFiltered = allFetched.filter {
                    it.title.contains(searchName, ignoreCase = true) ||
                            it.title.contains(engName, ignoreCase = true)
                }

                val categoryFiltered = titleFiltered.filter { item -> !item.isEntertainmentOrSports() }

                // 5. 중복 제거 시에도 기준 이름을 engName으로 전달하여 정확도 향상
                val deduplicated = categoryFiltered.deduplicateBySimilarTitle(engName)

                cachedNewsList = deduplicated.take(NEWS_DISPLAY_COUNT)

                withContext(Dispatchers.Main) {
                    contentLayout.removeAllViews()
                    if (cachedNewsList.isNullOrEmpty()) {
                        contentLayout.addView(buildEmptyView("'${searchName}' 관련 뉴스가 없습니다."))
                    } else {
                        renderNewsList(cachedNewsList!!)
                    }
                }
            } catch (e: Exception) {
                showError("뉴스를 불러올 수 없습니다.")
            }
        }
    }

    // 3. 기사 상세에서 탭 클릭 시 리스트로 즉시 복귀하는 함수 (재검색 방지용)
    fun backToListIfDetail() {
        if (isShowingDetail && cachedNewsList != null) {
            isShowingDetail = false
            contentLayout.removeAllViews()
            renderNewsList(cachedNewsList!!)
        }
    }

    fun handleBack(): Boolean {
        return if (isShowingDetail) { showNews(); true } else false
    }

    // ── 공통 API 호출 헬퍼 ───────────────────────────────────────────────────

    private fun fetchNewsItems(encodedQuery: String, pages: Int): List<NaverNewsItem> {
        val result = mutableListOf<NaverNewsItem>()
        repeat(pages) { page ->
            val start  = page * 100 + 1
            val apiUrl = "https://openapi.naver.com/v1/search/news.json" +
                    "?query=$encodedQuery&display=100&sort=date&start=$start"
            try {
                val conn = (URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("X-Naver-Client-Id",     NAVER_CLIENT_ID)
                    setRequestProperty("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
                    connectTimeout = 5_000
                    readTimeout    = 5_000
                }
                if (conn.responseCode == 200) {
                    val json  = JSONObject(conn.inputStream.bufferedReader().readText())
                    val items = json.getJSONArray("items")
                    // Log.d(TAG, "페이지 ${page + 1}: ${items.length()}개")
                    for (i in 0 until items.length()) {
                        val obj     = items.getJSONObject(i)
                        val rawDate = obj.optString("pubDate")
                        result.add(NaverNewsItem(
                            title       = Html.fromHtml(obj.optString("title"),       Html.FROM_HTML_MODE_COMPACT).toString(),
                            link        = obj.optString("originallink").ifEmpty { obj.optString("link") },
                            naverLink   = obj.optString("link"),
                            pubDate     = rawDate.toFormattedDate(),
                            pubDateRaw  = rawDate,
                            description = Html.fromHtml(obj.optString("description"), Html.FROM_HTML_MODE_COMPACT).toString()
                        ))
                    }
                } else {
                    Log.e(TAG, "API 오류 (페이지 ${page + 1}): ${conn.responseCode}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "fetchNewsItems 페이지 ${page + 1} 예외: ${e.message}")
            }
        }
        return result
    }

    // ── 언론사명 추출 (표시 전용) ────────────────────────────────────────────

    /**
     * 원본 링크에서 언론사명을 반환합니다.
     * PRESS_DOMAIN_MAP에 없으면 URL 두 번째 레벨 도메인을 대문자로 표시합니다.
     * 예) "https://www.abcnews.co.kr/..." → "ABCNEWS"
     */
    private fun String.extractPressName(): String {
        PRESS_DOMAIN_MAP.entries.firstOrNull { (domain, _) -> contains(domain) }
            ?.let { return it.value }
        // 미등록 도메인: 두 번째 레벨 도메인 추출
        return runCatching {
            val host = URL(this).host.removePrefix("www.")
            host.split(".").first().uppercase()
        }.getOrDefault("기타")
    }

    // ── 강화된 중복 제거 ──────────────────────────────────────────────────────

    /**
     * 이중 조건으로 중복을 판단합니다.
     *
     * 조건 A (키워드 교집합):
     *   - 제목에서 회사명·순수 접속어만 제거한 뒤 2글자 이상 단어 집합 생성
     *   - 기존 기사와 교집합 >= KEYWORD_THRESHOLD (2)이면 중복
     *
     * 조건 B (trigram Jaccard):
     *   - 키워드 수가 3개 미만으로 적을 때 보완
     *   - 공백을 제거한 제목의 3글자 연속 문자열 집합으로 Jaccard 계산
     *   - 유사도 >= TRIGRAM_THRESHOLD (0.35)이면 중복
     *
     * A 또는 B 중 하나라도 충족하면 중복으로 제거합니다.
     */
    private fun List<NaverNewsItem>.deduplicateBySimilarTitle(
        companyName: String
    ): List<NaverNewsItem> {
        data class Signature(val keywords: Set<String>, val trigrams: Set<String>)

        val kept      = mutableListOf<NaverNewsItem>()
        val keptSigs  = mutableListOf<Signature>()

        for (item in this) {
            val keywords = item.title.createKeywords(companyName)
            val trigrams = item.title.createTrigrams(companyName)
            val sig      = Signature(keywords, trigrams)

            val isDuplicate = keptSigs.any { existing ->
                // 조건 A: 키워드 교집합
                val keywordOverlap = keywords.intersect(existing.keywords).size >= KEYWORD_THRESHOLD
                // 조건 B: trigram Jaccard (키워드가 3개 미만이거나 A 실패 시 보완)
                val trigramSimilar = run {
                    val inter = trigrams.intersect(existing.trigrams).size.toDouble()
                    val union  = trigrams.union(existing.trigrams).size.toDouble()
                    if (union == 0.0) false else (inter / union) >= TRIGRAM_THRESHOLD
                }
                keywordOverlap || trigramSimilar
            }

            if (!isDuplicate) {
                kept.add(item)
                keptSigs.add(sig)
            } else {
                // Log.d(TAG, "중복 제거: ${item.title}")
            }
        }
        return kept
    }

    /**
     * 키워드 집합 추출
     * - 회사명 제거 후 2글자 이상 의미 있는 단어만 유지
     * - STOP_WORDS는 순수 접속어·시간어만 포함 (내용어 제외)
     */
    private fun String.createKeywords(companyName: String): Set<String> {
        val pureName = companyName.toCleanCompanyName()
        return this
            .replace(pureName, "", ignoreCase = true)
            .replace(Regex("""\[.*?\]|\(.*?\)|<.*?>"""), " ")
            .replace(Regex("""[^가-힣a-zA-Z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }
            .filter { it !in STOP_WORDS }
            .toSet()
    }

    /**
     * trigram 집합 추출 (공백·특수문자 제거 후 3글자 슬라이딩 윈도우)
     * 예) "안전교육강화" → {"안전교", "전교육", "교육강", "육강화"}
     */
    private fun String.createTrigrams(companyName: String): Set<String> {
        val pureName = companyName.toCleanCompanyName()
        val normalized = this
            .replace(pureName, "", ignoreCase = true)
            .replace(Regex("""[^가-힣a-zA-Z0-9]"""), "")
        if (normalized.length < 3) return setOf(normalized)
        return (0..normalized.length - 3)
            .map { normalized.substring(it, it + 3) }
            .toSet()
    }

    // ── 뉴스 목록 렌더링 ─────────────────────────────────────────────────────
    // contentLayout은 외부 ScrollView 안에 있으므로 내부 ScrollView를 만들지 않고
    // 아이템을 직접 contentLayout에 추가합니다. 스크롤은 ancestor ScrollView가 담당합니다.

    private fun renderNewsList(newsList: List<NaverNewsItem>) {
        newsList.forEach { news ->
            val pressName   = news.link.extractPressName()
            val displayDate = news.pubDateRaw.toDisplayDate()
            val fullText    = "${news.title}   $displayDate   $pressName"

            val spannable = SpannableString(fullText).apply {
                val titleEnd = news.title.length
                val dateEnd  = titleEnd + 3 + displayDate.length

                // 제목: 진한 검정 (일반체)
                setSpan(ForegroundColorSpan(Color.parseColor("#212121")),
                    0, titleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                // 날짜: 회색 + 작게
                setSpan(ForegroundColorSpan(Color.parseColor("#888888")),
                    titleEnd, dateEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f),
                    titleEnd, dateEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                // 언론사: 파란색 + 작게
                setSpan(ForegroundColorSpan(Color.parseColor("#1976D2")),
                    dateEnd, fullText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f),
                    dateEnd, fullText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            contentLayout.addView(TextView(activity).apply {
                text      = spannable
                textSize  = 15f
                maxLines  = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
                setOnClickListener {
                    // ★ 클릭 시점에 ancestor ScrollView의 scrollY를 즉시 저장
                    savedListScrollY = findAncestorScrollView()?.scrollY ?: 0
                    // Log.d(TAG, "목록 스크롤 위치 저장: $savedListScrollY")
                    showNewsDetail(news)
                }
                val outValue = android.util.TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            })

            // 구분선
            contentLayout.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(6.dp(), 0, 6.dp(), 0) }
                setBackgroundColor(0xFFE0E0E0.toInt())
            })
        }

        // ★ 상세 → 목록 복귀 시 ancestor ScrollView 스크롤 위치 복원
        if (savedListScrollY > 0) {
            val targetY = savedListScrollY
            val sv = findAncestorScrollView()
            sv?.post { sv.scrollTo(0, targetY) }
        }
    }

    // ── 뉴스 상세 보기 (네이버 뉴스 본문 추출 최적화) ───────────────────────────

    private fun showNewsDetail(news: NaverNewsItem) {
        isShowingDetail = true

        contentLayout.removeAllViews()
        contentLayout.addView(buildLoadingLayout("네이버 뉴스 분석 중..."))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // [검증 1] 어떤 링크가 들어오는지 로그로 확인
                Log.d(TAG, "원본 링크(originallink): ${news.link}")
                Log.d(TAG, "네이버 링크(link): ${news.naverLink}")

                // [로직 수정 v3] 네이버가 본문을 직접 호스팅하는 경우 naverLink 우선 사용
                val naverLink = news.naverLink
                val isNaverHosted = naverLink.contains(".naver.com") &&
                        !naverLink.contains("search.naver.com") &&
                        !naverLink.contains("news.naver.com/main") &&
                        !naverLink.contains("news.naver.com/section")
                val targetUrl = if (isNaverHosted) {
                    Log.d(TAG, "결정된 타겟: 네이버 호스팅 기사 (${naverLink})")
                    naverLink
                } else {
                    Log.d(TAG, "결정된 타겟: 언론사 원문 주소 (${news.link})")
                    news.link
                }

                // ★ Accept-Encoding을 명시하지 않아 Jsoup 기본값(압축 없음) 사용
                //   → GZIPInputStream + SSL 복호화 충돌(BAD_DECRYPT) 방지
                // ★ Connection: close → keep-alive 문제로 연결이 끊기는 사이트 대응
                val jsoupConn = org.jsoup.Jsoup.connect(targetUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Connection", "close")   // ★ keep-alive EOF 방지
                    .referrer("https://search.naver.com/search.naver")
                    .followRedirects(true)
                    .ignoreHttpErrors(true)           // ★ HTTP 오류 코드 무시
                    .ignoreContentType(true)          // ★ Content-Type 무시
                    .maxBodySize(0)                   // ★ 응답 크기 제한 해제
                    .timeout(15000)

                // ★ SSL BAD_DECRYPT 발생 사이트 → TLSv1.2 강제 + 압축 완전 비활성화
                //   Android 내장 com.android.okhttp 가 TLS 1.3 AEAD cipher 와
                //   충돌할 때 발생. SSLContext("TLSv1.2")로 협상 범위를 제한해 해결.
                val SSL_PROBLEM_SITES = listOf("pinpointnews.co.kr")
                if (SSL_PROBLEM_SITES.any { targetUrl.contains(it) }) {
                    Log.d(TAG, "SSL 호환 모드 적용: $targetUrl")
                    jsoupConn
                        .header("Accept-Encoding", "identity")   // 압축 완전 비활성화
                        .sslSocketFactory(createCompatibleSslSocketFactory())
                }

                val doc = jsoupConn.get()

                // [검증 2] HTML 문서가 정상적으로 로드되었는지 확인
                Log.d(TAG, "문서 로드 완료. Title: ${doc.title()}")

                // [검증 3] 본문 영역 탐색 (네이버 전용 → 언론사 전용 → 범용 순)
                val bodyEl = selectArticleBody(doc, targetUrl)

                // 공통 노이즈 요소 제거 (selectArticleBody 내에서도 처리되나 이중 보호)
                bodyEl?.select("script, style, header, footer, .menu, .button, .ad")?.remove()

                // [MTN 전용] 동영상 기사 감지 → 영상 안내 메시지 + 텍스트 추출
                val isMtnVideoArticle = targetUrl.contains("mtn.co.kr") &&
                        (doc.selectFirst("video, iframe[src*='youtube'], iframe[src*='mtn'], .vod_wrap, #vod_area, .video_area") != null
                                || doc.title().contains("동영상") || doc.title().contains("VOD"))

                val blocks: List<ContentBlock>
                if (isMtnVideoArticle) {
                    Log.d(TAG, "MTN 동영상 기사 감지 → 텍스트 추출 모드")
                    val videoBlocks = mutableListOf<ContentBlock>()
                    // 영상 안내 메시지
                    videoBlocks.add(ContentBlock.Text("📺 이 기사는 동영상 콘텐츠입니다.\n앱에서 직접 재생할 수 없습니다. 원문 링크를 통해 시청해 주세요."))
                    // 기사 내 텍스트(자막·설명)가 있으면 함께 표시
                    val textBody = bodyEl?.let { extractContentBlocks(it) }
                        ?.filterIsInstance<ContentBlock.Text>()
                        ?.filter { it.content.length > 30 }
                        ?: emptyList()
                    videoBlocks.addAll(textBody)
                    blocks = videoBlocks
                } else {
                    blocks = extractContentBlocks(bodyEl!!)
                } // end MTN video check

                val fullTitle = run {
                    val raw = doc.title().trim()
                    val parts = raw.split(Regex(""" [|<>\-–—:]{1,2} """))
                    val best  = parts.maxByOrNull { it.trim().length }?.trim() ?: raw
                    if (best.length >= 10) best else news.title
                }

                withContext(Dispatchers.Main) {
                    contentLayout.removeAllViews()
                    if (blocks.isEmpty()) {
                        Log.w(TAG, "화면에 표시할 내용이 없음")
                        showError("기사 본문 내용을 찾을 수 없습니다.")
                    } else {
                        renderNewsDetail(news, blocks, fullTitle)
                    }
                }

            } catch (e: Exception) {
                // [검증 4] 에러 발생 시 로그
                Log.e(TAG, "상세 보기 로딩 중 치명적 에러: ${e.message}", e)
                showError("기사를 불러오는 중 오류가 발생했습니다: ${e.localizedMessage}")
            }
        }
    }

    // ── 기사 상세 렌더링 ─────────────────────────────────────────────────────

    private fun renderNewsDetail(news: NaverNewsItem, blocks: List<ContentBlock>, articleTitle: String) {
        val ancestorSv = findAncestorScrollView()
        ancestorSv?.scrollTo(0, 0)
        val detailHeight = ancestorSv?.height
            ?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                detailHeight
            )
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val layout = LinearLayout(activity).apply {
            orientation  = LinearLayout.VERTICAL
            setPadding(8.dp(), 8.dp(), 8.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(TextView(activity).apply {
            text = articleTitle
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 4.dp(), 0, 2.dp())
            maxLines = Int.MAX_VALUE
            ellipsize = null
        })

        val pressName   = news.link.extractPressName()
        val displayDate = news.pubDateRaw.toDisplayDate()
        layout.addView(TextView(activity).apply {
            text = "$displayDate  $pressName"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 8.dp())
        })

        layout.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .also { it.setMargins(0, 0, 0, 6.dp()) }
            setBackgroundColor(0xFFDDDDDD.toInt())
        })

        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text -> {
                    if (block.content.isNotBlank()) {
                        layout.addView(TextView(activity).apply {
                            text     = block.content
                            textSize = 15f
                            setLineSpacing(0f, 1.3f)
                            setPadding(0, 0, 0, 3.dp())
                        })
                    }
                }
                is ContentBlock.Image -> {
                    val imageView = ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.setMargins(0, 8.dp(), 0, 4.dp()) }
                        adjustViewBounds = true
                        scaleType        = ImageView.ScaleType.FIT_CENTER
                    }
                    layout.addView(imageView)

                    val captionView: TextView? = if (block.caption.isNotBlank()) {
                        TextView(activity).apply {
                            text = block.caption
                            textSize = 11f
                            setTextColor(Color.GRAY)
                            gravity = Gravity.CENTER
                            setPadding(0, 2.dp(), 0, 8.dp())
                        }.also { layout.addView(it) }
                    } else null

                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            val imgConn = (URL(block.url).openConnection() as java.net.HttpURLConnection).apply {
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                                setRequestProperty("Referer",    "https://news.naver.com")
                                connectTimeout = 8_000
                                readTimeout    = 8_000
                            }
                            val bitmap = BitmapFactory.decodeStream(imgConn.inputStream)
                            withContext(Dispatchers.Main) {
                                if (bitmap != null) imageView.setImageBitmap(bitmap)
                                else {
                                    layout.removeView(imageView)
                                    captionView?.let { layout.removeView(it) }
                                }
                            }
                        }.onFailure {
                            withContext(Dispatchers.Main) {
                                layout.removeView(imageView)
                                captionView?.let { layout.removeView(it) }
                            }
                        }
                    }
                }
            }
        }

        scrollView.addView(layout)
        contentLayout.addView(scrollView)
    }

    // ── SSL 호환 소켓 팩토리 ─────────────────────────────────────────────────
    /**
     * Android 내장 com.android.okhttp 가 TLS 1.3 AEAD cipher 와 충돌(BAD_DECRYPT)하는
     * 사이트를 위해 TLSv1.2 범위로 협상을 제한한 SSLSocketFactory 를 반환합니다.
     * 인증서 검증은 기존 시스템 TrustManager 를 그대로 사용합니다.
     */
    private fun createCompatibleSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        return try {
            val sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
            sc.init(null, null, java.security.SecureRandom())
            sc.socketFactory
        } catch (e: Exception) {
            Log.w(TAG, "TLSv1.2 SSLContext 생성 실패, 기본값 사용: ${e.message}")
            javax.net.ssl.SSLContext.getDefault().socketFactory
        }
    }

    // ── 본문 영역 선택 헬퍼 ──────────────────────────────────────────────────

    /**
     * 우선순위: 네이버 전용 → 언론사 전용 → 범용 HTML5 → body 전체(최후)
     *
     * 딜사이트: .content-area 가 여러 개(.rnmc-right1, .rnmc-right2)로 분할되어 있으므로
     *   모두 병합한 임시 Element를 반환합니다.
     * MTN: #article_body 또는 .article_body
     * 기타 언론사: <article>, [itemprop=articleBody], 공통 클래스명 순으로 탐색합니다.
     */
    private fun selectArticleBody(
        doc: org.jsoup.nodes.Document,
        url: String
    ): Element {
        // ── 1. 네이버 전용 (뉴스·스포츠·연예 등 모든 섹션) ────────────────────
        doc.selectFirst("#dic_area")?.let {
            Log.d(TAG, "본문 감지: #dic_area (최신 네이버 뉴스)"); return it
        }
        doc.selectFirst("#articleBodyContents")?.let {
            Log.d(TAG, "본문 감지: #articleBodyContents (구형 네이버 뉴스)"); return it
        }
        doc.selectFirst("#newsct_article")?.let {
            Log.d(TAG, "본문 감지: #newsct_article (모바일 네이버 뉴스)"); return it
        }
        doc.selectFirst(".news_end_content")?.let {
            Log.d(TAG, "본문 감지: .news_end_content (네이버 스포츠)"); return it
        }
        doc.selectFirst("#newsEndContents")?.let {
            Log.d(TAG, "본문 감지: #newsEndContents (네이버 스포츠 구형)"); return it
        }
        doc.selectFirst(".ArticleContent")?.let {
            Log.d(TAG, "본문 감지: .ArticleContent (네이버 스포츠)"); return it
        }
        doc.selectFirst("#articeBody")?.let {
            Log.d(TAG, "본문 감지: #articeBody (네이버 연예)"); return it
        }

        // ── 2. 언론사별 전용 셀렉터 ──────────────────────────────────────────
        when {
            // ─ 쿠키뉴스 (kukinews.com)
            // 구조: #article(itemprop=articleBody) > #articleContent(실제 본문)
            //        + .view-footer(Copyright) + .articleToolBox(폰트크기 UI) + ...
            // [itemprop=articleBody] 범용 셀렉터가 #article 전체를 잡으면
            // Copyright·폰트크기 UI가 통째로 딸려오므로 #articleContent만 반환
            url.contains("kukinews.com") -> {
                Log.d(TAG, "본문 감지: 쿠키뉴스 #articleContent")
                doc.selectFirst("#articleContent")?.let { return it }
                // fallback: #article 잡은 뒤 노이즈 형제 제거
                doc.selectFirst("#article")?.let { el ->
                    val clone = el.clone()
                    clone.select(".view-footer, .articleToolBox, .contentReporterInfo, " +
                            ".reporterInfo, .articleRating, script, style").remove()
                    return clone
                }
            }

            // ─ Newsdak CMS 계열 (뉴스프리존, 시사저널e, 디지털투데이, 잡포스트 등)
            // 공통 id: #article-view-content-div  /  공통 속성: itemprop=articleBody
            url.contains("newsfreezone.co.kr")
                    || url.contains("sisajournal-e.com")
                    || url.contains("sisajournal.com")
                    || url.contains("digitaltoday.co.kr")
                    || url.contains("job-post.co.kr")
                    || url.contains("etoday.co.kr")
                    || url.contains("fnnews.com")
                    || url.contains("newspim.com")
                    || url.contains("inews24.com")
                    || url.contains("bizwatch.co.kr") -> {
                Log.d(TAG, "본문 감지: Newsdak CMS 계열 #article-view-content-div")
                (doc.selectFirst("#article-view-content-div")
                    ?: doc.selectFirst("[itemprop=articleBody]"))
                    ?.let { return it }
            }

            // ─ 더벨 (thebell.co.kr)
            // 구조: .viewBox > .viewHead + #article_main(.viewSection) + .reference + .linkBox + .newsADBox + .linkNews
            // #article_main 만 선택하면 형제 노이즈는 포함 안 됨 → 직접 반환
            url.contains("thebell.co.kr") -> {
                Log.d(TAG, "본문 감지: 더벨")
                // .viewBox 전체 클론 후 불필요 형제 제거 → #article_main 텍스트 온전히 유지
                val viewBox = doc.selectFirst(".viewBox")
                if (viewBox != null) {
                    val clone = viewBox.clone()
                    clone.select(".viewHead, .reference, .linkBox, .newsADBox, .linkNews, " +
                            ".article_content_banner, .article_title_banner, script, style").remove()
                    return clone
                }
                // fallback
                doc.selectFirst("#article_main")?.let { return it }
                doc.selectFirst(".viewSection")?.let { return it }
            }

            // ─ 광주일보 (kjdaily.com)
            // 구형 HTML: <div id=content> (Jsoup은 id="content" 로 파싱)
            url.contains("kjdaily.com") -> {
                Log.d(TAG, "본문 감지: 광주일보")
                val el = doc.selectFirst("#content") ?: doc.selectFirst(".cont_left")
                if (el != null) {
                    // 형제 사이드바 노이즈 제거
                    el.select(".box_timenews, .new_news_list, .section_top_view, " +
                            ".floating, [class*='ad'], [id*='ad']").remove()
                    return el
                }
            }

            // ─ 대한경제 (dnews.co.kr)
            // 구조: .view_contents.innerNews > .newsCont > div.text (실제 본문)
            //       .newsCont 하위에 .dateFont(날짜/SNS), .journalist_view_more(기자정보),
            //       .journalist_name 등 노이즈 존재 → div.text 만 직접 반환
            url.contains("dnews.co.kr") -> {
                Log.d(TAG, "본문 감지: 대한경제 div.text")
                val el = doc.selectFirst("div.text")
                    ?: doc.selectFirst(".newsCont")
                if (el != null) {
                    el.select(
                        ".dateFont, .btnSocial, .btnFont, .btnPrint, .journalist_view_more, " +
                                ".journalist_img, .journalist_name, .journalist_email, .journalist_link, " +
                                ".journalist_position, .sub_title, script, style"
                    ).remove()
                    return el
                }
            }

            // ─ 서울경제TV (sentv.co.kr)
            // 구조: /article/view/sentv... URL 패턴
            //   section_1 : 제목·날짜·공유버튼 (노이즈)
            //   section_2 .inner :
            //     .s-tit      : 부제목 (노이즈)
            //     #newsView   : ★ 실제 기사 본문 (edit-txt)
            //     .reporter_wrap : 기자 프로필 (노이즈)
            //     .copy-noti  : 저작권 고지 (노이즈)
            //   section_3   : 관련뉴스 (노이즈)
            url.contains("sentv.co.kr") -> {
                Log.d(TAG, "본문 감지: 서울경제TV #newsView")
                val el = doc.selectFirst("#newsView")
                    ?: doc.selectFirst(".edit-txt")
                    ?: doc.selectFirst(".view_txt")
                    ?: doc.selectFirst(".article_txt")
                    ?: doc.selectFirst(".article_body")
                    ?: doc.selectFirst(".view_content")
                if (el != null) {
                    el.select(
                        ".reporter_wrap, .reporter_info, .rel_news, .related_news, " +
                                ".section_3, .bt-area, .s-tit, .copy-noti, " +
                                ".view_sns, .view_util, .ad_box, [class*='ad_'], " +
                                "script, style"
                    ).remove()
                    return el
                }
            }

            // ─ 전남매일 (jndn.com)
            // 구조: .cont_left > div#content(실제 기사 본문) + .article_footer + .box_timenews + .article_hot
            //       .cont_right(사이드바) - 형제 요소이므로 #content만 선택 후 노이즈 제거
            url.contains("jndn.com") -> {
                Log.d(TAG, "본문 감지: 전남매일 #content")
                val el = doc.selectFirst("#content")
                    ?: doc.selectFirst(".cont_left")
                if (el != null) {
                    el.select(
                        ".article_footer, .box_timenews, .new_news_list, .new_news_list_ttl, " +
                                ".article_hot, .cont_right, .floating, .paging_news, " +
                                "[class*='ad'], [id*='ad'], script, style"
                    ).remove()
                    return el
                }
            }

            // ─ 광주매일신문 (gjdaily.com / 기존 kjdaily와 다른 사이트)
            // Newdak CMS 또는 자체 CMS 사용. 주요 셀렉터 우선순위 시도
            url.contains("gjdaily.com") -> {
                Log.d(TAG, "본문 감지: 광주매일신문")
                (doc.selectFirst("#article-view-content-div")
                    ?: doc.selectFirst("[itemprop=articleBody]")
                    ?: doc.selectFirst(".article-view-content")
                    ?: doc.selectFirst(".article_view_content")
                    ?: doc.selectFirst("#articleViewCon")
                    ?: doc.selectFirst(".article-body")
                    ?: doc.selectFirst("#view_content")
                    ?: doc.selectFirst(".view_content"))
                    ?.let { return it }
            }

            // ─ 에너지뉴스 (energynews.co.kr) 및 기타 에너지/전력/산업 전문지
            // energynews.co.kr = Newdak CMS (#article-view-content-div 사용 가능성 높음)
            url.contains("energynews.co.kr")
                    || url.contains("enewstoday.co.kr")
                    || url.contains("electimes.com")
                    || url.contains("e2news.com")
                    || url.contains("industrynews.co.kr")
                    || url.contains("energy-news.co.kr")
                    || url.contains("energy.co.kr") -> {
                Log.d(TAG, "본문 감지: 에너지/전력 전문지")
                (doc.selectFirst("#article-view-content-div")
                    ?: doc.selectFirst("[itemprop=articleBody]")
                    ?: doc.selectFirst(".article-view-content")
                    ?: doc.selectFirst(".article_body")
                    ?: doc.selectFirst(".article-body")
                    ?: doc.selectFirst("#article_body")
                    ?: doc.selectFirst(".news_content")
                    ?: doc.selectFirst("#news_content"))
                    ?.let { return it }
            }
            // ─ 뉴스투데이 (news2day.co.kr)
            // 구조: .view_con > .view_con_wrap x2 (첫째 빈 div, 둘째에 본문 HTML 내장)
            url.contains("news2day.co.kr") -> {
                Log.d(TAG, "본문 감지: 뉴스투데이 .view_con_wrap")
                val wraps = doc.select(".view_con_wrap")
                (wraps.firstOrNull { it.text().length > 50 }
                    ?: doc.selectFirst(".view_con"))
                    ?.let { return it }
            }

            url.contains("dealsite.co.kr") || url.contains("paxnetnews.com") -> {
                Log.d(TAG, "본문 감지: 딜사이트 .content-area 병합")
                val contentAreas = doc.select(".content-area")
                if (contentAreas.isNotEmpty()) {
                    // .rnmc-left / 관련기사 / 광고 div 제거 후 병합
                    val merged = org.jsoup.nodes.Element("div")
                    contentAreas.forEach { area ->
                        val clone = area.clone()
                        clone.select(
                            ".rnmc-left, .rnmc-relative-news, .rec-keywords, " +
                                    ".prime-msg, [id*='dablewidget'], script, style"
                        ).remove()
                        merged.appendChild(clone)
                    }
                    return merged
                }
            }

            // ─ 광주MBC (kjmbc.co.kr)
            // 구조: .news-article > header(제목·기자) + .news-article-body(★ 실제 본문)
            //   .news-article-body 내 노이즈:
            //     .daum-banner  : 다음 채널 배너
            //     .tag          : Copyright 고지 + 공유·인쇄 버튼
            //     .profile      : 기자 프로필
            //     .news-comment : 댓글 영역
            //   aside.news-aside : 많이 본 뉴스 / 최신뉴스 사이드바 (형제 요소)
            url.contains("kjmbc.co.kr") -> {
                Log.d(TAG, "본문 감지: 광주MBC .news-article-body")
                val el = doc.selectFirst(".news-article-body")
                    ?: doc.selectFirst(".news-article")
                if (el != null) {
                    el.select(
                        ".daum-banner, .tag, .profile, .news-comment, " +
                                ".pageFunction, aside, .news-aside, " +
                                "script, style"
                    ).remove()
                    return el
                }
            }

            // MTN (mtn.co.kr) - 동영상 기사는 호출 전에 분기, 여기서는 텍스트 추출용
            url.contains("mtn.co.kr") -> {
                Log.d(TAG, "본문 감지: MTN 전용")
                (doc.selectFirst("#articlebody")
                    ?: doc.selectFirst(".articlebody")
                    ?: doc.selectFirst("#article_body")
                    ?: doc.selectFirst(".article_body")
                    ?: doc.selectFirst(".article-body")
                    ?: doc.selectFirst(".news_text")
                    ?: doc.selectFirst(".view_text"))
                    ?.let { return it }
            }

            // 이데일리
            url.contains("edaily.co.kr") -> {
                doc.selectFirst(".news_body")?.let { Log.d(TAG, "이데일리 .news_body"); return it }
            }

            // 머니투데이
            url.contains("mt.co.kr") -> {
                doc.selectFirst(".newsView")?.let { Log.d(TAG, "머니투데이 .newsView"); return it }
            }

            // 비즈워치
            url.contains("bizwatch.co.kr") -> {
                doc.selectFirst(".article-content")?.let { Log.d(TAG, "비즈워치 .article-content"); return it }
            }

            // 메트로서울 (metroseoul.co.kr)
            url.contains("metroseoul.co.kr") -> {
                Log.d(TAG, "본문 감지: 메트로서울")
                val container = doc.selectFirst("[data-layout-area=ARTICLE_CONTENT]")
                    ?: doc.selectFirst(".article-txt-contents")
                if (container != null) {
                    val clone = container.clone()
                    clone.select(
                        ".relation_keyword, .under-byline, .reporter_underLine," +
                                ".reporter_area, .under-sns-area, .article-copyright," +
                                ".sub_news_title, .sns-share-layer, .sns-txtsize-layer," +
                                "script, style, ins"
                    ).remove()
                    return clone
                }
            }

            // 연합뉴스
            url.contains("yna.co.kr") -> {
                doc.selectFirst(".article-txt")?.let { Log.d(TAG, "연합뉴스 .article-txt"); return it }
            }
        }

        // ── 3. 범용 셀렉터 (우선순위 순) ────────────────────────────────────
        val genericSelectors = listOf(
            "article[itemprop=articleBody]", "[itemprop=articleBody]",
            "#article-view-content-div",
            "article.article-veiw-body", "article.article-view-body",
            "#articleBody", "#article_body", "#articeBody",
            ".article-body", ".article_body", ".articleBody",
            ".article-content", ".article_content", ".articleContent",
            ".article-view-content", ".article_view_content",
            ".news-body", ".news_body", ".newsBody",
            ".news-content", ".news_content",
            ".view_content", ".view-content",
            ".cont_article", ".article-text", ".article_text",
            ".news_view", ".news-view",
            ".read_body", ".news_view_content",
            ".entry-content", ".post-content",
            "article"
        )
        for (sel in genericSelectors) {
            doc.selectFirst(sel)?.let {
                Log.d(TAG, "본문 감지 (범용): $sel")
                return it
            }
        }

        Log.e(TAG, "본문 감지 실패: 표준 태그 없음. 전체 Body 사용 시도.")
        return doc.body()
    }

    // ── 본문 파싱 ────────────────────────────────────────────────────────────

    private fun extractContentBlocks(element: Element): List<ContentBlock> {
        val blocks     = mutableListOf<ContentBlock>()

        val noiseSelectors = listOf(
            // 공통 구조 태그
            "header", "footer", "nav", "aside", "script", "style", "iframe", "noscript",
            // 사이드바 / 메뉴
            ".sidebar", ".menu", ".gnb", ".lnb", ".snb",
            ".top_menu", ".bottom_info", ".footer_info",
            ".header-sitemap-wrap", ".header-search-more-wrap",
            ".header-bottom", ".nav-thispage",
            // SNS / 공유
            ".sns", ".share", ".article_social", ".social_group", ".utility",
            ".share-btns-wrap", ".share-btns-wrap-top",
            ".news-info-and-share", ".news-info-top-3news-wrap",
            ".btn-facebook1", ".btn-twitter1", ".btn-share1",
            // 광고 / 배너
            ".ads", ".banner", ".ad_area", ".ad_wrap", ".ad_container",
            ".ad-article-top", "[class*='ad-']", "[id*='ad_']", "[id*='dablewidget']",
            "[class*='swiper']", ".bkn-list",
            // 댓글 / 저작권 / 기자
            ".reply", ".comment", ".article_bottom", ".copyright", ".byline", ".reporter",
            ".nis-reporter-name",
            // 저작권 고지 · 기자정보 (시민의소리 등 Newdak/자체CMS 계열)
            // 구조 예: <div class="view-copyright">저작권자 © ...</div>
            //          <div class="view-editors">기자 프로필 · 다른기사 보기</div>
            ".view-copyright", ".view-editors",
            // 유사 패턴 (타 언론사 동일 계열)
            ".article-copyright", ".news-copyright", ".news_copyright",
            ".view-reporter", ".view_reporter", ".reporter-info", ".reporter_info",
            // 태그 / 추천 뉴스
            ".article_tags", ".recommend_news", ".popular_news",
            ".rec-keywords", ".related-news", ".rnmc-relative-news",
            ".relative-news-title-wrap",
            // 딜사이트 전용
            ".rnmc-left", ".prime-msg", ".read-news-row1",
            ".empty-rnmc", ".foot_notice",
            // 기타 로고
            "#dealsite_ci", ".dealsite_ci", ".top_logo",
            // 대한경제(dnews.co.kr) 전용: 기자정보·날짜·SNS 영역
            ".journalist_view_more", ".dateFont", ".btnSocial", ".btnPrint",
            // 광주MBC(kjmbc.co.kr) 전용: 다음배너·저작권·기자프로필·댓글·사이드바
            ".daum-banner", ".news-comment", ".news-aside",
            // 서울경제TV(sentv.co.kr) 전용: 기자 프로필·관련기사·저작권·공유버튼·부제목
            ".reporter_wrap", ".reporter_info", ".rel_news", ".view_sns", ".view_util",
            ".copy-noti", ".s-tit", ".section_3", ".bt-area",
            // 광주일보(kjdaily) 및 전남매일(jndn) 공통: 오른쪽 사이드바·최신뉴스 박스
            ".cont_right", ".box_timenews", ".new_news_list", ".new_news_list_ttl",
            ".section_top_view", ".floating",
            // 전남매일(jndn.com) 전용: 기사하단 네비·인기기사
            ".article_footer", ".article_hot", ".paging_news",
            // 뉴스투데이(news2day) 전용: 관련기사·광고
            ".related_news", ".article_foot", ".art_etc",
            "[class*='adsbyaiinad']", "[class*='adsbygoogle']"
        )
        noiseSelectors.forEach { element.select(it).remove() }

        val textBuffer = StringBuilder()

        fun flushText() {
            val cleaned = textBuffer.toString()
                .replace('\u00A0', ' ')          // &nbsp; → 공백
                .replace(Regex("[ \t]+"), " ")
                .replace(Regex(" ?\n ?"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .lines().joinToString("\n") { it.trim() }
                .trim()
            // 텍스트 내용 기반 필터링 (메뉴성 단어가 포함된 짧은 문구 삭제)
            if (cleaned.isNotEmpty()) {
                val isMenuText = cleaned.length < 60 &&
                        cleaned.contains(Regex(
                            "페이스북|트위터|카카오톡|로그인|회원가입|섹션|뉴스랭킹|포럼|전체메뉴" +
                                    "|오피니언|URL복사|스크랩|키워드알림|구독한|인쇄|글자크기" +
                                    "|이 기사는.+유료콘텐츠|딜사이트 플러스|ⓒ|Copyright"
                        ))
                if (!isMenuText) {
                    blocks.add(ContentBlock.Text(cleaned))
                }
            }
            textBuffer.clear()
        }

        val blockTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "blockquote", "figcaption")

        element.traverse(object : NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                when {
                    node is TextNode -> {
                        val t = node.text()
                        if (t.isNotBlank()) {
                            if (textBuffer.isNotEmpty() && !textBuffer.last().isWhitespace())
                                textBuffer.append(' ')
                            textBuffer.append(t.trim())
                        }
                    }
                    node is Element -> when (node.tagName()) {
                        "br"  -> textBuffer.append('\n')
                        "img" -> {
                            val src = node.absUrl("src").ifEmpty { node.attr("src") }
                            if (src.startsWith("http") && !isAdImage(src, node)) {
                                flushText()
                                blocks.add(ContentBlock.Image(src, node.attr("alt").trim()))
                            }
                        }
                        in blockTags -> {
                            if (textBuffer.isNotEmpty() && !textBuffer.endsWith("\n\n"))
                                textBuffer.append("\n\n")
                        }
                    }
                }
            }

            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is Element && node.tagName() in blockTags) {
                    if (!textBuffer.endsWith("\n\n")) textBuffer.append("\n\n")
                }
            }
        })

        flushText()

        // 추출된 텍스트 중 노이즈 문구 추가 필터링
        val filteredBlocks = blocks.filter { block ->
            if (block is ContentBlock.Text) {
                val content = block.content.trim()
                // 10자 미만 + 메뉴성 단어 → 제거
                val isTooShortMenu = content.length <= 10 &&
                        content.contains(Regex("로그인|회원가입|뉴스랭킹|오피니언|전체메뉴|인쇄|공유"))
                // "ⓒ 저작권" / "무단전재" 등 저작권 고지 한 줄 → 제거
                val isCopyright = content.contains(Regex("ⓒ|무단전재|재배포.?금지|All Rights Reserved"))
                        && content.length < 120
                // 기자 이름만 있는 짧은 줄 (예: "최유라 기자") → 제거
                val isBylineOnly = content.matches(Regex("""^[가-힣]{2,5}\s*기자$"""))
                !isTooShortMenu && !isCopyright && !isBylineOnly
            } else true
        }

        return filteredBlocks
    }

    // ── 광고 이미지 판별 ─────────────────────────────────────────────────────

    private fun isAdImage(src: String, img: Element): Boolean {
        val adPatterns = listOf(
            "doubleclick", "googlesyndication", "adnxs", "moatads",
            "adsystem", "adservice", "google-analytics", "googletagmanager",
            "facebook.com/tr", "naver.com/ad", "nbad.naver",
            "beacon", "tracker", "1x1", "pixel.gif", "pixel.png"
        )
        if (adPatterns.any { src.contains(it, ignoreCase = true) }) return true
        val w = img.attr("width").toIntOrNull()  ?: 0
        val h = img.attr("height").toIntOrNull() ?: 0
        if ((w in 1..30) || (h in 1..30)) return true
        return false
    }

    // ── UI 헬퍼 ──────────────────────────────────────────────────────────────

    private fun buildLoadingLayout(message: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation  = LinearLayout.VERTICAL
            gravity      = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400)
            addView(ProgressBar(activity))
            addView(TextView(activity).apply {
                text     = message
                textSize = 14f
                gravity  = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            })
        }

    private fun buildEmptyView(message: String): TextView =
        TextView(activity).apply {
            text     = message
            textSize = 14f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400)
        }

    private fun showError(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            contentLayout.removeAllViews()
            contentLayout.addView(buildEmptyView(message))
        }
    }

    // ── 로그 요약 ────────────────────────────────────────────────────────────

    private fun logSummary(total: Int, titleFiltered: Int, deduplicated: Int) {
        Log.d(TAG, "============================================")
        Log.d(TAG, "수집 총계  : ${total}개")
        Log.d(TAG, "제목 필터  : ${total - titleFiltered}개 제외 → ${titleFiltered}개 남음")
        Log.d(TAG, "중복 제거  : ${titleFiltered - deduplicated}개 제외 → ${deduplicated}개 남음")
        Log.d(TAG, "최종 표시  : ${minOf(deduplicated, NEWS_DISPLAY_COUNT)}개")
        Log.d(TAG, "============================================")
    }

    // ── 날짜 포맷 헬퍼 ───────────────────────────────────────────────────────

    private fun String.toFormattedDate(): String = runCatching {
        val sdf  = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
        val date = sdf.parse(this) ?: return this
        SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(date)
    }.getOrDefault(this)

    private fun String.toDisplayDate(): String = runCatching {
        val sdf  = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
        val date = sdf.parse(this) ?: return ""
        val kst  = TimeZone.getTimeZone("Asia/Seoul")
        val calItem = Calendar.getInstance(kst).also { it.time = date }
        val calNow  = Calendar.getInstance(kst)
        val isToday = calItem.get(Calendar.YEAR)        == calNow.get(Calendar.YEAR) &&
                calItem.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)
        if (isToday) {
            SimpleDateFormat("HH:mm", Locale.KOREA).also { it.timeZone = kst }.format(date)
        } else {
            SimpleDateFormat("MM-dd", Locale.KOREA).also { it.timeZone = kst }.format(date)
        }
    }.getOrDefault("")
}

// ── 확장 함수 ────────────────────────────────────────────────────────────────

private fun String.toCleanCompanyName(): String =
    replace(Regex("""\(주\)|\(유\)|\(재\)|\(사\)|\(합\)"""), "").trim()

// ── 데이터 모델 ───────────────────────────────────────────────────────────────

private fun NaverNewsItem.isEntertainmentOrSports(): Boolean {
    val naverPatterns = listOf(
        "sports.naver.com", "entertain.naver.com", "star.naver.com",
        "/kbaseball/", "/baseball/", "/basketball/", "/football/",
        "/soccer/", "/volleyball/", "/golf/", "/tennis/", "/esports/",
        "/racing/", "/celeb/", "/movie/", "/music/"
    )
    if (naverPatterns.any { naverLink.contains(it, ignoreCase = true) }) return true
    val originalPatterns = listOf(
        "isplus.com", "spotvnews.co.kr", "sports.chosun.com", "sports.donga.com",
        "xportsnews.com", "osen.co.kr", "tenasia.hankyung.com", "starin.edaily.co.kr",
        "star.mt.co.kr", "heraldpop.com", "mydaily.co.kr", "topstarnews.net",
        "tvdaily.asiae.co.kr", "enews24.net", "newsen.com", "sportsseoul.com",
        "sportsworld.co.kr", "sportsworldi.com", "mksports.co.kr", "kusports.com",
        "dailysportshankook.co.kr",  // ★ 한국스포츠 (도메인 수정)
        "/sports/", "/sport/", "/entertain/", "/entertainment/", "/celeb/", "/star/"
    )
    if (originalPatterns.any { link.contains(it, ignoreCase = true) }) return true
    return false
}

data class NaverNewsItem(
    val title      : String,
    val link       : String,
    val naverLink  : String,
    val pubDate    : String,
    val pubDateRaw : String,
    val description: String
)

sealed class ContentBlock {
    data class Text (val content: String)                       : ContentBlock()
    data class Image(val url: String, val caption: String = "") : ContentBlock()
}

private fun String.translateToEnglishName(): String {
    val nameMap = mapOf(
        "에스케이" to "SK",
        "지에스" to "GS",
        "엘에스" to "LS",
        "아이엠" to "IM",
        "케이피엑스" to "KPX",
        "에이치디" to "HD" // 필요시 추가
    )
    var result = this
    nameMap.forEach { (korean, english) ->
        result = result.replace(korean, english)
    }
    return result
}