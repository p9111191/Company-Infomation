package com.example.companyinfo

import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

/**
 * 네이버 뉴스 검색 및 본문 표시를 담당하는 Delegate 클래스
 *
 * [변경 사항 v6]
 *  1. 돋보기 아이콘을 뉴스 탭 행에 배치할 수 있도록 createSearchIconView() 공개 메서드 제공
 *  2. 뉴스 목록 내 검색 헤더 라벨 제거 (탭 행의 돋보기만 사용)
 *  3. 키워드 검색 시 제목에 검색어가 포함된 기사만 표시
 *  4. 검색 모드에서 뒤로가기 시 기업명 뉴스로 복귀
 */
class NewsDelegate(
    private val activity: AppCompatActivity,
    private val contentLayout: LinearLayout,
    private val company: Company
) {
    // ── Firebase 언론사 맵 ───────────────────────────────────────────────────
    private val pressMapRef = FirebaseDatabase
        .getInstance("https://company-info-6a83a-default-rtdb.asia-southeast1.firebasedatabase.app")
        .reference.child("press_map")  // ⚠️ asia-southeast1 리전은 URL 명시 필수
    private val dynamicPressMap = mutableMapOf<String, String>()

    private var cachedNewsList: List<NaverNewsItem>? = null
    private var savedListScrollY: Int = 0

    /** 현재 상세 보기 중인 기사의 인덱스 (-1 = 상세 보기 아님) */
    private var currentDetailIndex: Int = -1

    /** 현재 활성화된 목록 (검색 모드면 검색 결과, 아니면 기업 뉴스) */
    private val currentActiveList: List<NaverNewsItem>
        get() = if (customSearchKeyword != null) cachedSearchNewsList ?: emptyList()
        else cachedNewsList ?: emptyList()

    // ── 검색 관련 상태 ────────────────────────────────────────────────────────
    /** null이면 기업명 모드, non-null이면 사용자 검색어 모드 */
    private var customSearchKeyword: String? = null
    private var cachedSearchNewsList: List<NaverNewsItem>? = null

    // Parser 인스턴스
    private val detailParser = NewsDetailParser()

    companion object {
        val NAVER_CLIENT_ID = BuildConfig.NAVER_CLIENT_ID
        val NAVER_CLIENT_SECRET = BuildConfig.NAVER_CLIENT_SECRET
        const val NEWS_DISPLAY_COUNT = 50
        private const val TAG     = "NewsDelegate"
        private const val TAG_FB  = "NewsDelegate_FB"   // Firebase 전용 태그

        private val PRESS_DOMAIN_MAP = mapOf(
            "yna.co.kr"              to "연합뉴스",
            "newsis.com"             to "뉴시스",
            "news1.kr"               to "뉴스1",
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
            "meconomynews.com"       to "시장경제",
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
            "kbs.co.kr"              to "KBS",
            "mbc.co.kr"              to "MBC",
            "sbs.co.kr"              to "SBS",
            "ytn.co.kr"              to "YTN",
            "mtn.co.kr"              to "MTN",
            "tvchosun.com"           to "TV조선",
            "jtbc.co.kr"             to "JTBC",
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
            "moneys.co.kr"           to "시대",
            "catchnews.kr"           to "CatchNews",
            "lkp.news"               to "리버티코리아포스트",
            "news.tf.co.kr"          to "더팩트",
            "traveltimes.co.kr"      to "여행신문",
            "thereport.co.kr"        to "더리포트",
            "e2news.com"             to "이투뉴스",
            "ttlnews.com"            to "퍼블릭뉴스통신",
            "newsway.co.kr"          to "뉴스웨이",
            "dkilbo.com"             to "대경일보",
            "newsdream.kr"           to "뉴스드림",
            "g-enews.com"            to "글로벌이코노믹",
            "namdonews.com"          to "남도일도",
            "ggilbo.com"             to "금강일보",
            "public25.com"           to "퍼블릭경제",
            "ngonews.kr"             to "한국NGO신문",
            "newsroad.co.kr"         to "뉴스로드",
            "updownnews.co.kr"       to "업다운뉴스",
            "sisaweek.com"           to "시사위크",
            "snmnews.com"            to "철강금속신문",
            "the-stock.kr"           to "더스탁",
            "theguru.co.kr"          to "TheGuru",
            "newstown.co.kr"         to "뉴스타운",
            "dtnews24.com"           to "디트뉴스",
            "daejonilbo.com"         to "대전일보",
            "worktoday.co.kr"        to "워크투데이",
            "bntnews.co.kr"          to "BntNews",
            "cbci.co.kr"             to "CBC뉴스",
            "dealsitetv.com"         to "딜사이트TV",
            "koreaittimes.com"       to "IT타임즈",
            "hellot.net"             to "헬로티",
            "ksg.co.kr"              to "코리아쉬핑가제트",
            "klnews.co.kr"           to "물류신문",
            "100ssd.co.kr"           to "백세시대",
            "robotzine.co.kr"        to "로봇기술",
            "mtnews.net"             to "기계신문",
            "dailylog.co.kr"         to "데일리로그",
            "headlinejeju.co.kr"     to "헤드라인제주",
            "kookje.co.kr"           to "국제신문",
            "engdaily.com"           to "엔지니어링데일리",
            "newsprime.co.kr"        to "프라임경제",
            "sisacast.kr"            to "시사캐스트",
            "bizwork.co.kr"          to "비즈워크",
            "ferrotimes.com"         to "페로타임즈",
            "ebn.co.kr"              to "이비뉴스",
            "financialpost"          to "파이낸셜포스트",
            "hankooki.com"           to "한국아이",
            "cstimes.com"            to "컨슈머타임스",
            "ceoscoredaily.com"      to "CEO스코어데일리",
            "finomy.com"             to "현대경제신문",
            "dynews.co.kr"           to "동양일보",
            "ccdn.co.kr"             to "충청매일",
            "joongdo.co.kr"          to "중도일보",
            "youthdaily.co.kr"       to "청년일보",
            "ccdailynews.com"        to "충청일보",
            "bzeronews.com"          to "공뉴스",
            "smarttoday.co.kr"       to "스마트투데이",
            "aptn.co.kr"             to "아파트관리신문",
            "energydaily.co.kr"      to "에너지데일리",
            "hinews.co.kr"           to "하이뉴스",
            "topdaily.kr"            to "탑데일리",
            "ekn.kr"                 to "에너지경제",
            "seoulwire.com"          to "서울와이어",
            "kpinews.kr"             to "KPI뉴스",
            "ksilbo.co.kr"           to "경상일보",
            "energy-news.co.kr"      to "에너지신문",
            "globalepic.co.kr"       to "글로벌에픽",
            "lcnews.co.kr"           to "라이센스뉴스",
            "smartfn.co.kr"          to "스마트비즈",
            "lawissue.co.kr"         to "로이슈",
            "discoverynews.kr"       to "디스커버리뉴스",
            "dailypop.kr"            to "데일리팝",
            "sateconomy.co.kr"       to "토요경제",
            "economist.co.kr"        to "이코노미스트",
            "dailysecu.com"          to "데일리시큐",
            "megaeconomy.co.kr"      to "메가경제",
            "seoultimes.news"        to "서울타임즈뉴스",
            "econovill.com"          to "이코노믹리뷰",
            "newsbrite.net"          to "뉴스브라이트",
            "wemakenews.co.kr"       to "위메이크뉴스",
            "siminilbo.co.kr"        to "시민일보",
            "ezyeconomy.com"         to "이지경제",
            "mcnews.co.kr"           to "매일건설신문",
            "safetimes.co.kr"        to "세이프타임즈",
            "newsmaker.or.kr"        to "뉴스메이커",
            "breaknews.com"          to "브레이크뉴스",
            "s-journal.co.kr"        to "S-저널",
            "ilyo.co.kr"             to "일요신문",
            "thelec.kr"              to "디일렉",
            "huffingtonpost.kr"      to "허프포스트",
            "goodkyung.com"          to "굿모닝경제",
            "asiatime.co.kr"         to "아시아타임즈",
            "areyou.co.kr"           to "아유경제",
            "kmib.co.kr"             to "국민일보",
            "naeil.com"              to "내일신문",
            "weeklytoday.com"        to "위클리오늘",
            "kpenews.com"            to "한국정경신문",
            "handmk.com"             to "핸드메이커",
            "sportsq.co.kr"          to "스포츠Q",
            "woodkorea.co.kr"        to "한국목재신문",
            "itbiznews.com"          to "IT비즈뉴스",
            "newstopkorea.com"       to "뉴스탑",
            "press9.kr"              to "프레스9",
            "whitepaper.co.kr"       to "화이트페이퍼",
            "newsclaim.co.kr"        to "뉴스클레임",
            "mhj21.com"              to "문화저널21",
            "wikileaks-kr.org"       to "위키리크스",
            "newsfc.co.kr"           to "금융소비자뉴스"
        )

        private const val KEYWORD_THRESHOLD = 2
        private const val TRIGRAM_THRESHOLD = 0.35

        private val STOP_WORDS = setOf(
            "관련", "통해", "위해", "위한", "대한", "따른",
            "이후", "지난", "오늘", "내일", "이번", "해당",
            "기자", "뉴스", "제공", "자료"
        )
    }

    var isShowingDetail: Boolean = false
        private set

    private fun Int.dp(): Int =
        (this * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun findAncestorScrollView(): android.widget.ScrollView? {
        var v: android.view.ViewParent? = contentLayout.parent
        repeat(12) {
            when (v) {
                is android.widget.ScrollView -> return v as android.widget.ScrollView
                is android.view.View -> v = (v as android.view.View).parent
                else -> return null
            }
        }
        return null
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * DetailActivity.setupTabs() 에서 호출합니다.
     * TabLayout 내부 SlidingTabStrip(첫 번째 자식 ViewGroup)에
     * 🔍 아이콘을 직접 추가합니다.
     * → tabLayout.parent를 건드리지 않으므로 기존 레이아웃이 깨지지 않습니다.
     */
    fun attachSearchIconToTabRow(tabLayout: com.google.android.material.tabs.TabLayout) {
        // TabLayout의 첫 번째 자식이 SlidingTabStrip (내부 LinearLayout)
        val tabStrip = tabLayout.getChildAt(0) as? android.view.ViewGroup ?: return
        if (tabStrip.findViewWithTag<View>("news_search_icon") != null) return  // 중복 방지

        val sizePx = 40.dp()
        tabStrip.addView(TextView(activity).apply {
            tag  = "news_search_icon"
            text = "🔍"
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(sizePx, LinearLayout.LayoutParams.MATCH_PARENT)
            val outValue = android.util.TypedValue()
            activity.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, outValue, true
            )
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { showSearchDialog() }
        })
    }

    /**
     * 기사 상세 화면에서 인접 기사로 이동합니다.
     *  delta = -1 → 목록 위(이전) 기사
     *  delta = +1 → 목록 아래(다음) 기사
     */
    fun navigateArticle(delta: Int) {
        if (!isShowingDetail) return
        val list = currentActiveList
        val newIndex = currentDetailIndex + delta
        if (newIndex < 0) {
            android.widget.Toast.makeText(
                activity, "첫 번째 기사입니다.", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (newIndex >= list.size) {
            android.widget.Toast.makeText(
                activity, "마지막 기사입니다.", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        showNewsDetail(list[newIndex], newIndex)
    }

    fun showNews(forceRefresh: Boolean = false) {
        isShowingDetail = false
        customSearchKeyword = null

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
                val searchName = company.name.toCleanCompanyName()
                val engName = searchName.translateToEnglishName()
                val query = URLEncoder.encode(engName, "UTF-8")
                val allFetched = fetchNewsItems(query, pages = 5)

                val titleFiltered = allFetched.filter { item ->
                    val title = item.title
                    if (searchName == "호텔롯데") {
                        (title.contains("롯데", ignoreCase = true) && title.contains("호텔", ignoreCase = true)) ||
                                title.contains(engName, ignoreCase = true)
                    } else {
                        title.contains(searchName, ignoreCase = true) ||
                                title.contains(engName, ignoreCase = true)
                    }
                }
                // 제외사이트 목록
                val categoryFiltered = titleFiltered.filter { item ->
                    !item.isEntertainmentOrSports()
                            && !item.link.contains("itooza.com", ignoreCase = true)
                            && !item.link.contains("ftimes.kr", ignoreCase = true)
                            && !item.link.contains("gamemeca.com", ignoreCase = true)
                            && !item.link.contains("gameshot.net", ignoreCase = true)
                }
                val deduplicated = categoryFiltered.deduplicateBySimilarTitle(engName)

                logSummary(allFetched.size, titleFiltered.size, deduplicated.size)

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

    /**
     * 사용자가 입력한 [keyword]로 뉴스를 검색합니다.
     * 제목에 [keyword]가 포함된 기사만 표시합니다.
     */
    fun showNewsWithKeyword(keyword: String) {
        if (keyword.isBlank()) return

        isShowingDetail = false
        customSearchKeyword = keyword
        cachedSearchNewsList = null

        savedListScrollY = 0
        findAncestorScrollView()?.scrollTo(0, 0)

        contentLayout.removeAllViews()
        contentLayout.addView(buildLoadingLayout("'$keyword' 검색 중..."))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val query = URLEncoder.encode(keyword, "UTF-8")
                val allFetched = fetchNewsItems(query, pages = 3)

                // 제목에 검색어가 포함된 기사만 필터링
                val titleFiltered = allFetched.filter { item ->
                    val title = item.title
                    if (keyword == "호텔롯데") {
                        title.contains("호텔롯데") && title.contains("롯데호텔")
                    } else {
                        // 일반 검색어는 기존 방식 유지
                        title.contains(keyword, ignoreCase = true)
                    }
                }

                Log.d(TAG, "검색어: '$keyword', 수집: ${allFetched.size}개, 제목필터 후: ${titleFiltered.size}개")

                val categoryFiltered = titleFiltered.filter { item ->
                    !item.isEntertainmentOrSports() && !item.link.contains("itooza.com", ignoreCase = true)
                }
                val deduplicated = categoryFiltered.deduplicateBySimilarTitle(keyword)

                cachedSearchNewsList = deduplicated.take(NEWS_DISPLAY_COUNT)

                withContext(Dispatchers.Main) {
                    contentLayout.removeAllViews()
                    if (cachedSearchNewsList.isNullOrEmpty()) {
                        contentLayout.addView(buildEmptyView("'$keyword' 관련 뉴스가 없습니다."))
                    } else {
                        renderNewsList(cachedSearchNewsList!!)
                    }
                }
            } catch (e: Exception) {
                showError("뉴스를 불러올 수 없습니다.")
            }
        }
    }

    fun backToListIfDetail() {
        if (isShowingDetail) {
            isShowingDetail = false
            contentLayout.removeAllViews()
            val keyword = customSearchKeyword
            if (keyword != null && cachedSearchNewsList != null) {
                renderNewsList(cachedSearchNewsList!!)
            } else if (cachedNewsList != null) {
                renderNewsList(cachedNewsList!!)
            }
        }
    }

    fun handleBack(): Boolean {
        return when {
            isShowingDetail -> {
                backToListIfDetail()
                true
            }
            customSearchKeyword != null -> {
                // 검색 모드 → 기업 뉴스로 복귀
                showNews()
                true
            }
            else -> false
        }
    }

    // ── 검색 다이얼로그 ──────────────────────────────────────────────────────

    private fun showSearchDialog() {
        val editText = EditText(activity).apply {
            hint = "검색어를 입력하세요"
            isSingleLine = true
            customSearchKeyword?.let { setText(it) }
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 8.dp(), 24.dp(), 0)
            addView(editText)
        }

        AlertDialog.Builder(activity)
            .setTitle("뉴스 검색")
            .setView(container)
            .setPositiveButton("검색") { _, _ ->
                val keyword = editText.text.toString().trim()
                Log.d("NewsDelegate", "검색 버튼 클릭됨. editText: '$editText'") // 로그 추가
                Log.d("NewsDelegate", "검색 버튼 클릭됨. 입력값: '$keyword'") // 로그 추가
                if (keyword.isNotEmpty()) showNewsWithKeyword(keyword)
            }
            .setNegativeButton("취소", null)
            .apply {
                // 검색 모드일 때 기업 뉴스로 돌아가는 버튼 제공
                if (customSearchKeyword != null) {
                    setNeutralButton("기업 뉴스로") { _, _ -> showNews() }
                }
            }
            .show()

        // 키보드 자동 표시
        editText.postDelayed({
            editText.requestFocus()
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    // ── 공통 API 호출 헬퍼 ───────────────────────────────────────────────────

    private fun fetchNewsItems(encodedQuery: String, pages: Int): List<NaverNewsItem> {
        val result = mutableListOf<NaverNewsItem>()
        repeat(pages) { page ->
            val start = page * 100 + 1
            val apiUrl = "https://openapi.naver.com/v1/search/news.json" +
                    "?query=$encodedQuery&display=100&sort=date&start=$start"
            try {
                val conn = (URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("X-Naver-Client-Id", NAVER_CLIENT_ID)
                    setRequestProperty("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val items = json.getJSONArray("items")
                    for (i in 0 until items.length()) {
                        val obj = items.getJSONObject(i)
                        val rawDate = obj.optString("pubDate")
                        result.add(NaverNewsItem(
                            title = Html.fromHtml(obj.optString("title"), Html.FROM_HTML_MODE_COMPACT).toString(),
                            link = obj.optString("originallink").ifEmpty { obj.optString("link") },
                            naverLink = obj.optString("link"),
                            pubDate = rawDate.toFormattedDate(),
                            pubDateRaw = rawDate,
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

    // ── Firebase 언론사 맵 로드 ──────────────────────────────────────────────

    /**
     * Firebase Realtime Database에서 press_map 노드를 실시간으로 구독합니다.
     * Activity 생성 시점(또는 NewsDelegate 초기화 직후)에 한 번 호출하세요.
     */
    fun initFirebasePressMap() {
        // ✅ 호출 확인 — 이 로그가 안 보이면 initFirebasePressMap() 자체가 불리지 않은 것
        Log.d(TAG_FB, "━━━━ initFirebasePressMap() 호출됨 ━━━━")
        Log.d(TAG_FB, "  pressMapRef 경로: ${pressMapRef.path}")

        pressMapRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // ✅ 이 로그가 안 보이면 Firebase 연결 자체가 안 된 것
                Log.d(TAG_FB, "━━━━ onDataChange 수신 ━━━━")
                Log.d(TAG_FB, "  snapshot 존재 여부: ${snapshot.exists()}")
                Log.d(TAG_FB, "  snapshot 자식 수  : ${snapshot.childrenCount}")

                if (!snapshot.exists()) {
                    Log.w(TAG_FB, "  ⚠️ press_map 노드가 Firebase에 존재하지 않음 — 콘솔에서 데이터 확인 필요")
                    return
                }

                dynamicPressMap.clear()
                snapshot.children.forEach { child ->
                    val domain = child.key?.replace("_", ".") ?: return@forEach
                    dynamicPressMap[domain] = child.value.toString()
                }
                Log.d(TAG_FB, "  ✅ dynamicPressMap 업데이트 완료: ${dynamicPressMap.size}개")
                // Log.d(TAG_FB, "  신규 추가 도메인(Firebase 전용): ${dynamicPressMap.keys.filter { it !in PRESS_DOMAIN_MAP }.joinToString()}")
            }

            override fun onCancelled(error: DatabaseError) {
                // ✅ 이 로그가 보이면 Firebase 권한/네트워크 문제
                Log.e(TAG_FB, "━━━━ onCancelled — Firebase 로드 실패 ━━━━")
                Log.e(TAG_FB, "  code   : ${error.code}")
                Log.e(TAG_FB, "  message: ${error.message}")
                Log.e(TAG_FB, "  details: ${error.details}")
            }
        })
    }

    /**
     * 새로 발견한 언론사 도메인과 이름을 Firebase에 저장합니다.
     * 도메인의 점(.)은 Firebase 키에서 허용되지 않으므로 (_)로 치환합니다.
     */
    private fun saveNewPressToFirebase(domain: String, name: String) {
        val safeKey = domain.replace(".", "_")
        Log.d(TAG_FB, "[saveNewPress] 저장 시도 → key=$safeKey, name=$name")
        pressMapRef.child(safeKey).setValue(name)
            .addOnSuccessListener {
                Log.d(TAG_FB, "[saveNewPress] ✅ 저장 완료: $domain → $name")
                // 로컬 맵에도 즉시 반영 (onDataChange 대기 없이 바로 적용)
                dynamicPressMap[domain] = name
                Log.d(TAG_FB, "[saveNewPress] dynamicPressMap 즉시 반영 완료 (현재 ${dynamicPressMap.size}개)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG_FB, "[saveNewPress] ❌ 저장 실패: $domain — ${e.message}")
            }
    }

    // ── 언론사명 추출 ────────────────────────────────────────────────────────

    private fun String.extractPressName(title: String = "", description: String = ""): String {
        val url = this
        /* Log.d(TAG, "────────────────────────────────────────")
        Log.d(TAG, "[extractPressName] URL     : $url")
        Log.d(TAG, "[extractPressName] title   : $title")
        Log.d(TAG, "[extractPressName] dynamicPressMap 크기: ${dynamicPressMap.size}") */

        // 1순위: Firebase에서 실시간으로 받아온 dynamicPressMap 검색
        val dynamicHit = dynamicPressMap.entries.firstOrNull { (domain, _) -> url.contains(domain) }
        if (dynamicHit != null) {
            return dynamicHit.value
        } else {
        }

        // 2순위: 하드코딩된 정적 맵(PRESS_DOMAIN_MAP) 검색
        val staticHit = PRESS_DOMAIN_MAP.entries.firstOrNull { (domain, _) -> url.contains(domain) }
        if (staticHit != null) {
            return staticHit.value
        }

        // 3순위: 제목/설명에서 언론사 브래킷 패턴으로 유추
        if (title.isNotBlank() || description.isNotBlank()) {
            val regex = Regex("""\[([가-힣a-zA-Z0-9]+(?:신문|일보|뉴스|통신|방송|경제|미디어|투데이|타임즈|저널|TV|tv))]""")
            val matchInTitle = regex.find(title)
            val matchInDesc  = regex.find(description)

            val match = matchInTitle ?: matchInDesc
            if (match != null) {
                val foundName = match.groupValues[1]
                Log.d(TAG, "[extractPressName] ✅ 3순위 정규식 HIT → foundName=$foundName")
                // 도메인 추출 후 Firebase에 자동 등록
                runCatching {
                    val domain = URL(url).host.removePrefix("www.")
                    Log.d(TAG, "[extractPressName] Firebase 저장 시도 → domain=$domain, name=$foundName")
                    if (domain.isNotBlank()) {
                        saveNewPressToFirebase(domain, foundName)
                    }
                }.onFailure { e ->
                    Log.e(TAG, "[extractPressName] 도메인 추출 실패: ${e.message}")
                }
                return foundName
            } else {
                Log.w(TAG, "[extractPressName] ❌ 3순위 정규식 MISS — 브래킷 언론사명 패턴 없음")
            }
        } else {
            Log.w(TAG, "[extractPressName] ❌ 3순위 스킵 — title/description 모두 비어있음")
        }

        // 최종 폴백: 호스트명 첫 번째 파트를 대문자로 사용
        val fallback = runCatching {
            val host = URL(url).host.removePrefix("www.")
            host.split(".").first().uppercase()
        }.getOrDefault("기타")
        Log.w(TAG, "[extractPressName] ⚠️ 최종 폴백 → $fallback (Firebase 미등록 도메인)")
        return fallback
    }

    // ── 강화된 중복 제거 ─────────────────────────────────────────────────────

    private fun List<NaverNewsItem>.deduplicateBySimilarTitle(
        companyName: String
    ): List<NaverNewsItem> {
        data class Signature(val keywords: Set<String>, val trigrams: Set<String>)

        val kept = mutableListOf<NaverNewsItem>()
        val keptSigs = mutableListOf<Signature>()

        for (item in this) {
            val keywords = item.title.createKeywords(companyName)
            val trigrams = item.title.createTrigrams(companyName)
            val sig = Signature(keywords, trigrams)

            val isDuplicate = keptSigs.any { existing ->
                val keywordOverlap = keywords.intersect(existing.keywords).size >= KEYWORD_THRESHOLD
                val trigramSimilar = run {
                    val inter = trigrams.intersect(existing.trigrams).size.toDouble()
                    val union = trigrams.union(existing.trigrams).size.toDouble()
                    if (union == 0.0) false else (inter / union) >= TRIGRAM_THRESHOLD
                }
                keywordOverlap || trigramSimilar
            }

            if (!isDuplicate) {
                kept.add(item)
                keptSigs.add(sig)
            }
        }
        return kept
    }

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

    // ── 로그 요약 ────────────────────────────────────────────────────────────

    private fun logSummary(total: Int, titleFiltered: Int, deduplicated: Int) {
        Log.d(TAG, "============================================")
        Log.d(TAG, "수집 총계  : ${total}개")
        Log.d(TAG, "제목 필터  : ${total - titleFiltered}개 제외 → ${titleFiltered}개 남음")
        Log.d(TAG, "중복 제거  : ${titleFiltered - deduplicated}개 제외 → ${deduplicated}개 남음")
        Log.d(TAG, "최종 표시  : ${minOf(deduplicated, NEWS_DISPLAY_COUNT)}개")
        Log.d(TAG, "============================================")
    }

    // ── 뉴스 목록 렌더링 ─────────────────────────────────────────────────────

    /**
     * 제목에 공란이 없을 때 모바일에서 줄바꿈이 되지 않는 문제 해결:
     * 콤마(,), 앤드(&), 중간마침표(·) 뒤에 Zero-Width Space(\u200B)를 삽입해
     * 해당 위치에서 줄바꿈 기회를 만듭니다.
     */
    private fun String.insertLineBreakOpportunities(): String =
        this
            .replace(",", ",\u200B")
            .replace("&", "&\u200B")
            .replace("·", "·\u200B")

    private fun renderNewsList(newsList: List<NaverNewsItem>) {
        newsList.forEachIndexed { index, news ->
            val pressName = news.link.extractPressName(news.title, news.description)
            val displayDate = news.pubDateRaw.toDisplayDate()
            val wrappableTitle = news.title.insertLineBreakOpportunities()
            val fullText = "${wrappableTitle}   $displayDate   $pressName"

            val spannable = SpannableString(fullText).apply {
                val titleEnd = wrappableTitle.length
                val dateEnd = titleEnd + 3 + displayDate.length

                setSpan(ForegroundColorSpan(Color.parseColor("#212121")),
                    0, titleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                setSpan(ForegroundColorSpan(Color.parseColor("#888888")),
                    titleEnd, dateEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f),
                    titleEnd, dateEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                setSpan(ForegroundColorSpan(Color.parseColor("#1976D2")),
                    dateEnd, fullText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f),
                    dateEnd, fullText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            contentLayout.addView(TextView(activity).apply {
                text = spannable
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
                setOnClickListener {
                    savedListScrollY = findAncestorScrollView()?.scrollY ?: 0
                    showNewsDetail(news, index)
                }
                val outValue = android.util.TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            })

            contentLayout.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(6.dp(), 0, 6.dp(), 0) }
                setBackgroundColor(0xFFE0E0E0.toInt())
            })
        }

        if (savedListScrollY > 0) {
            val targetY = savedListScrollY
            val sv = findAncestorScrollView()
            sv?.post { sv.scrollTo(0, targetY) }
        }
    }

    // ── 뉴스 상세 보기 ───────────────────────────────────────────────────────

    private fun showNewsDetail(news: NaverNewsItem, index: Int = currentDetailIndex) {
        currentDetailIndex = index
        isShowingDetail = true

        contentLayout.removeAllViews()
        contentLayout.addView(buildLoadingLayout("네이버 뉴스 분석 중..."))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "원본 링크: ${news.link}")
                Log.d(TAG, "네이버 링크: ${news.naverLink}")

                val naverLink = news.naverLink
                val isNaverHosted = naverLink.contains(".naver.com") &&
                        !naverLink.contains("search.naver.com") &&
                        !naverLink.contains("news.naver.com/main") &&
                        !naverLink.contains("news.naver.com/section")
                val primaryUrl  = if (isNaverHosted) naverLink else news.link
                val fallbackUrl = if (isNaverHosted) news.link  else null

                var targetUrl = primaryUrl
                var doc = detailParser.loadArticleDocument(primaryUrl)

                if (doc == null && !fallbackUrl.isNullOrEmpty() && fallbackUrl != primaryUrl) {
                    Log.w(TAG, "1차 URL 로드 실패, fallback 시도: $fallbackUrl")
                    doc = detailParser.loadArticleDocument(fallbackUrl)
                    if (doc != null) targetUrl = fallbackUrl
                }

                if (doc == null) {
                    withContext(Dispatchers.Main) { showError("문서를 불러올 수 없습니다.") }
                    return@launch
                }

                // ── doc에서 언론사명 추출 후 Firebase 저장 ─────────────────────
                runCatching {
                    val domain = URL(news.link).host.removePrefix("www.")
                    val alreadyKnown = dynamicPressMap.containsKey(domain) ||
                            PRESS_DOMAIN_MAP.entries.any { (d, _) -> news.link.contains(d) }
                    if (!alreadyKnown && domain.isNotBlank()) {
                        // 1순위: og:site_name 메타태그 (가장 정확)
                        val ogSiteName = doc.selectFirst("meta[property=og:site_name]")
                            ?.attr("content")?.trim()
                            ?.takeIf { it.length in 2..20 }

                        // 2순위: doc.title() 마지막 구분자 뒤 텍스트
                        val docTitle = doc.title().trim()
                        val pressFromTitle = docTitle
                            .split(Regex("""\s*[-|<>–—]\s*"""))
                            .map { it.trim() }
                            .filter { it.isNotBlank() && it.length in 2..20 }
                            .lastOrNull()

                        // 3순위: 본문에서 [언론사=기자] 패턴 탐색
                        // 예: "[소셜밸류=소민영 기자]" → "소셜밸류"
                        // ^ 없이 전체 텍스트에서 첫 번째 매칭을 찾음
                        val pressFromBody = doc.body()?.text()?.let { bodyText ->
                            Regex("""\[([가-힣a-zA-Z0-9]{2,15})[=\s][가-힣a-zA-Z0-9\s]{2,10}기자""")
                                .find(bodyText)
                                ?.groupValues?.get(1)
                                ?.takeIf { it.length in 2..15 }
                        }

                        val pressName = ogSiteName ?: pressFromTitle ?: pressFromBody

                        Log.d(TAG_FB, "[언론사 추출] domain=$domain")
                        Log.d(TAG_FB, "[언론사 추출] og:site_name=$ogSiteName")
                        Log.d(TAG_FB, "[언론사 추출] pressFromBody=$pressFromBody")
                        Log.d(TAG_FB, "[언론사 추출] docTitle=$docTitle")
                        Log.d(TAG_FB, "[언론사 추출] 최종 후보=$pressName")

                        if (pressName != null && pressName.length >= 2) {
                            saveNewPressToFirebase(domain, pressName)
                        } else {
                            Log.w(TAG_FB, "[언론사 추출] 추출 실패 — 후보 없음 (domain=$domain)")
                        }
                    } else {
                        Log.d(TAG_FB, "[언론사 추출] 스킵 — 이미 등록된 도메인: $domain")
                    }
                }.onFailure { e ->
                    Log.e(TAG_FB, "[언론사 추출] 오류: ${e.message}")
                }
                // ──────────────────────────────────────────────────────────────

                val bodyEl = detailParser.selectArticleBody(doc, targetUrl) ?: run {
                    withContext(Dispatchers.Main) { showError("본문 영역을 찾을 수 없습니다.") }
                    return@launch
                }

                val isMtnVideoArticle = detailParser.isMtnVideoArticle(doc, targetUrl)

                val blocks: List<ContentBlock> = if (isMtnVideoArticle) {
                    Log.d(TAG, "MTN 동영상 기사 감지 → 텍스트 추출 모드")
                    val videoBlocks = mutableListOf<ContentBlock>()
                    videoBlocks.add(ContentBlock.Text("📺 이 기사는 동영상 콘텐츠입니다.\n앱에서 직접 재생할 수 없습니다. 원문 링크를 통해 시청해 주세요."))
                    val textBody = detailParser.extractContentBlocks(bodyEl)
                        .filterIsInstance<ContentBlock.Text>()
                        .filter { it.content.length > 30 }
                    videoBlocks.addAll(textBody)
                    videoBlocks
                } else {
                    detailParser.extractContentBlocks(bodyEl)
                }

                val fullTitle = run {
                    val raw = doc.title().trim()
                    val parts = raw.split(Regex(""" [|<>\-–—:]{1,2} """))
                    val best = parts.maxByOrNull { it.trim().length }?.trim() ?: raw
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
                Log.e(TAG, "상세 보기 로딩 중 에러: ${e.message}", e)
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
            orientation = LinearLayout.VERTICAL
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

        val pressName = news.link.extractPressName(news.title, news.description)
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
                            text = block.content
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
                        scaleType = ImageView.ScaleType.FIT_CENTER
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
                        val bitmap = NewsDetailParser.loadImageBitmap(block.url)
                        withContext(Dispatchers.Main) {
                            if (bitmap != null) {
                                imageView.setImageBitmap(bitmap)
                            } else {
                                layout.removeView(imageView)
                                captionView?.let { layout.removeView(it) }
                            }
                        }
                    }
                }
            }
        }

        // ── 기사 하단 네비게이션 힌트 바 ──────────────────────────────────────
        // 이전/다음 기사가 있을 때만 해당 방향 버튼을 활성화합니다.
        val list = currentActiveList
        val hasPrev = currentDetailIndex > 0
        val hasNext = currentDetailIndex < list.size - 1

        val navBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 8.dp(), 0, 8.dp()) }
        }

        // ← 이전 기사 버튼
        navBar.addView(TextView(activity).apply {
            text = "← 이전 기사"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setTextColor(if (hasPrev) android.graphics.Color.parseColor("#1976D2")
            else        android.graphics.Color.parseColor("#BDBDBD"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (hasPrev) android.graphics.Color.parseColor("#E3F2FD")
                else        android.graphics.Color.parseColor("#F5F5F5"))
                cornerRadius = 8 * activity.resources.displayMetrics.density
                setStroke(1, if (hasPrev) android.graphics.Color.parseColor("#90CAF9")
                else        android.graphics.Color.parseColor("#E0E0E0"))
            }
            if (hasPrev) setOnClickListener { navigateArticle(-1) }
        })

        // 가운데 구분 여백
        navBar.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
        })

        // 다음 기사 → 버튼
        navBar.addView(TextView(activity).apply {
            text = "다음 기사 →"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setTextColor(if (hasNext) android.graphics.Color.parseColor("#1976D2")
            else        android.graphics.Color.parseColor("#BDBDBD"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (hasNext) android.graphics.Color.parseColor("#E3F2FD")
                else        android.graphics.Color.parseColor("#F5F5F5"))
                cornerRadius = 8 * activity.resources.displayMetrics.density
                setStroke(1, if (hasNext) android.graphics.Color.parseColor("#90CAF9")
                else        android.graphics.Color.parseColor("#E0E0E0"))
            }
            if (hasNext) setOnClickListener { navigateArticle(1) }
        })

        layout.addView(navBar)

        // 하단 여백
        layout.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24.dp()
            )
        })

        scrollView.addView(layout)
        contentLayout.addView(scrollView)
    }

    // ── UI 헬퍼 ──────────────────────────────────────────────────────────────

    private fun buildLoadingLayout(message: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400)
            addView(ProgressBar(activity))
            addView(TextView(activity).apply {
                text = message
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            })
        }

    private fun buildEmptyView(message: String): TextView =
        TextView(activity).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400)
        }

    private fun showError(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            contentLayout.removeAllViews()
            contentLayout.addView(buildEmptyView(message))
        }
    }

    // ── 날짜 포맷 헬퍼 ───────────────────────────────────────────────────────

    private fun String.toFormattedDate(): String = runCatching {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
        val date = sdf.parse(this) ?: return this
        SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(date)
    }.getOrDefault(this)

    private fun String.toDisplayDate(): String = runCatching {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
        val date = sdf.parse(this) ?: return ""
        val kst = TimeZone.getTimeZone("Asia/Seoul")
        val calItem = Calendar.getInstance(kst).also { it.time = date }
        val calNow = Calendar.getInstance(kst)
        val isToday = calItem.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
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
        "dailysportshankook.co.kr",
        "/sports/", "/sport/", "/entertain/", "/entertainment/", "/celeb/", "/star/"
    )
    if (originalPatterns.any { link.contains(it, ignoreCase = true) }) return true
    return false
}

data class NaverNewsItem(
    val title: String,
    val link: String,
    val naverLink: String,
    val pubDate: String,
    val pubDateRaw: String,
    val description: String
)

sealed class ContentBlock {
    data class Text(val content: String) : ContentBlock()
    data class Image(val url: String, val caption: String = "") : ContentBlock()
}

private fun String.translateToEnglishName(): String {
    val nameMap = mapOf(
        "에스케이" to "SK",
        "지에스" to "GS",
        "엘에스" to "LS",
        "아이엠" to "IM",
        "케이피엑스" to "KPX",
        "에이치디" to "HD",
        "에이치앤지" to "H&G",
        "에이치엘" to "HL",
        "한화위탁관리부동산투자회사" to "한화리츠",
        "한화에어로스페이스" to "한화에어로",
        "아난티코브" to "아난티"
    )
    var result = this
    nameMap.forEach { (korean, english) ->
        result = result.replace(korean, english)
    }
    return result
}