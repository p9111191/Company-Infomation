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
            "kjdaily.com"            to "광주매일신문",
            "news2day.co.kr"         to "뉴스투데이",
            "job-post.co.kr"         to "잡포스트",
            "wikitree.co.kr"         to "위키트리",
            "naver.com"              to "네이버뉴스"
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

        // [로직 추가] 캐시된 데이터가 있고 강제 새로고침이 아니라면 즉시 리스트 렌더링
        if (!forceRefresh && cachedNewsList != null) {
            contentLayout.removeAllViews()
            renderNewsList(cachedNewsList!!)
            return
        }

        // 새로 불러올 때는 스크롤 위치 초기화 + ancestor도 맨 위로
        savedListScrollY = 0
        findAncestorScrollView()?.scrollTo(0, 0)

        // 처음 불러오거나 forceRefresh 가 true 일 때만 아래 API 로직 실행
        contentLayout.removeAllViews()
        contentLayout.addView(buildLoadingLayout("뉴스를 불러오는 중..."))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val searchName = company.name.toCleanCompanyName()
                val query      = URLEncoder.encode(searchName, "UTF-8")

                val allFetched = fetchNewsItems(query, pages = 5)  // ★ 500개로 확대
                val titleFiltered = allFetched.filter {
                    it.title.contains(searchName, ignoreCase = true)
                }
                val categoryFiltered = titleFiltered.filter { item -> !item.isEntertainmentOrSports() }
                Log.d(TAG, "카테고리 필터: ${titleFiltered.size - categoryFiltered.size}개 제외(스포츠/연예) → ${categoryFiltered.size}개 남음")
                val deduplicated = categoryFiltered.deduplicateBySimilarTitle(searchName)

                // [수정] 최종 리스트를 캐시에 저장
                cachedNewsList = deduplicated.take(NEWS_DISPLAY_COUNT)

                withContext(Dispatchers.Main) {
                    contentLayout.removeAllViews()
                    if (cachedNewsList.isNullOrEmpty()) {
                        contentLayout.addView(buildEmptyView("'${company.name}' 관련 뉴스가 없습니다."))
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
                    Log.d(TAG, "페이지 ${page + 1}: ${items.length()}개")
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
                val doc = org.jsoup.Jsoup.connect(targetUrl)
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
                    .get()

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
            // 태그 / 추천 뉴스
            ".article_tags", ".recommend_news", ".popular_news",
            ".rec-keywords", ".related-news", ".rnmc-relative-news",
            ".relative-news-title-wrap",
            // 딜사이트 전용
            ".rnmc-left", ".prime-msg", ".read-news-row1",
            ".empty-rnmc", ".foot_notice",
            // 기타 로고
            "#dealsite_ci", ".dealsite_ci", ".top_logo",
            // 광주일보(kjdaily) 전용: 오른쪽 사이드바·최신뉴스 박스
            ".cont_right", ".box_timenews", ".new_news_list",
            ".section_top_view", ".floating",
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
    data class Text (val content: String)                        : ContentBlock()
    data class Image(val url: String, val caption: String = "") : ContentBlock()
}