package com.example.companyinfo

import android.graphics.BitmapFactory
import android.util.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.net.URL
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * 뉴스 기사 본문 파싱을 전담하는 Parser 클래스
 *
 * [주요 기능]
 * - URL로부터 HTML 문서 로드
 * - 언론사별 최적화된 본문 영역 선택
 * - 본문에서 텍스트/이미지 블록 추출
 * - SSL 호환성 처리
 */
class NewsDetailParser {
    companion object {
        private const val TAG = "NewsDetailParser"

        /**
         * SSL BAD_DECRYPT 문제 해결을 위한 호환성 소켓 팩토리
         * Android 내장 TLS 1.3 충돌 시 TLSv1.2로 제한
         */
        fun createCompatibleSslSocketFactory(): SSLSocketFactory {
            return try {
                val sc = SSLContext.getInstance("TLSv1.2")
                sc.init(null, null, java.security.SecureRandom())
                sc.socketFactory
            } catch (e: Exception) {
                Log.w(TAG, "TLSv1.2 SSLContext 생성 실패, 기본값 사용: ${e.message}")
                SSLContext.getDefault().socketFactory
            }
        }

        /**
         * 이미지 로드 (IO 작업)
         */
        fun loadImageBitmap(imageUrl: String): android.graphics.Bitmap? {
            return runCatching {
                val imgConn = (URL(imageUrl).openConnection() as java.net.HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                    setRequestProperty("Referer", "https://news.naver.com")
                    connectTimeout = 8_000
                    readTimeout = 8_000

                    // SSL 관련 설정 추가: HTTPS일 경우 호환 소켓 팩토리 적용
                    if (this is javax.net.ssl.HttpsURLConnection) {
                        sslSocketFactory = createCompatibleSslSocketFactory()
                    }
                }
                BitmapFactory.decodeStream(imgConn.inputStream)
            }.getOrNull()
        }
    }

    /**
     * Android 9+ HTTP 평문 차단 대응:
     * http:// URL 은 https:// 로 업그레이드 후 먼저 시도하고,
     * 실패 시 원본 http:// URL 로 재시도합니다.
     */
    fun loadArticleDocument(url: String): Document? {
        // 더벨 free URL → front newsview URL 로 변환
        // 예: /free/content/ArticleView.asp?key=XXX → /front/newsview.asp?key=XXX
        val normalizedUrl = if (url.contains("thebell.co.kr") &&
            url.contains("/free/content/ArticleView.asp")) {
            url.replace("/free/content/ArticleView.asp", "/front/newsview.asp")
                .also { Log.d(TAG, "더벨 URL 변환: $url → $it") }
        } else url

        val httpsUrl = if (normalizedUrl.startsWith("http://"))
            normalizedUrl.replaceFirst("http://", "https://") else normalizedUrl
        val urlsToTry = if (httpsUrl != normalizedUrl) listOf(httpsUrl, normalizedUrl) else listOf(normalizedUrl)

        for (tryUrl in urlsToTry) {
            val doc = tryLoadDocument(tryUrl)
            if (doc != null) return doc
        }
        return null
    }

    private fun buildJsoupConn(url: String) = org.jsoup.Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
        .header("Connection", "close")
        .referrer("https://search.naver.com/search.naver")
        .followRedirects(true)
        .ignoreHttpErrors(true)
        .ignoreContentType(true)
        .maxBodySize(0)
        .timeout(15000)

    private fun tryLoadDocument(url: String): Document? {
        // 1차: 기본 TLS 시도
        val firstResult = runCatching {
            Log.d(TAG, "문서 로드 시도: $url")
            buildJsoupConn(url).get().also {
                Log.d(TAG, "문서 로드 완료. Title: ${it.title()}")
            }
        }

        if (firstResult.isSuccess) return firstResult.getOrNull()

        val firstError = firstResult.exceptionOrNull()

        // SSLProtocolException(BAD_DECRYPT 등) → TLS 1.2 호환 소켓으로 재시도
        // 도메인 목록 관리 없이 SSL 오류 종류로 자동 판별
        if (firstError is javax.net.ssl.SSLProtocolException ||
            firstError is javax.net.ssl.SSLHandshakeException) {
            Log.w(TAG, "SSL 오류 감지, TLS 1.2 호환 모드로 재시도: $url (${firstError.message?.take(80)})")
            return runCatching {
                buildJsoupConn(url)
                    .header("Accept-Encoding", "identity")
                    .sslSocketFactory(createCompatibleSslSocketFactory())
                    .get().also {
                        Log.d(TAG, "TLS 1.2 재시도 성공. Title: ${it.title()}")
                    }
            }.getOrElse { e ->
                Log.e(TAG, "TLS 1.2 재시도도 실패 ($url): ${e.javaClass.simpleName} - ${e.message}")
                null
            }
        }

        Log.e(TAG, "문서 로드 실패 ($url): ${firstError?.javaClass?.simpleName} - ${firstError?.message}")
        return null
    }

    /**
     * 본문 영역 선택 (언론사별 최적화)
     */
    fun selectArticleBody(doc: Document, url: String): Element? {
        // 1. 네이버 전용 셀렉터
        val naverSelectors = listOf(
            "#dic_area" to "최신 네이버 뉴스",
            "#articleBodyContents" to "구형 네이버 뉴스",
            "#newsct_article" to "모바일 네이버 뉴스",
            ".news_end_content" to "네이버 스포츠",
            "#newsEndContents" to "네이버 스포츠 구형",
            ".ArticleContent" to "네이버 스포츠",
            "#articeBody" to "네이버 연예"
        )
        for ((selector, desc) in naverSelectors) {
            doc.selectFirst(selector)?.let {
                Log.d(TAG, "본문 감지: $selector ($desc)")
                return it
            }
        }

        // 2. 언론사별 전용 처리
        return selectByPressSpecific(doc, url) ?: selectByGenericSelector(doc)
    }

    /**
     * 언론사별 특화 본문 선택
     */
    private fun selectByPressSpecific(doc: Document, url: String): Element? {
        return when {
            // hinews.co.kr 전용 처리 - selectByPressSpecific() 함수에 추가
            url.contains("hinews.co.kr") -> {
                Log.d(TAG, "본문 감지: 하이뉴스 hinews.co.kr")

                // 1차: .detailCont (실제 기사 본문)
                doc.selectFirst(".detailCont")?.let { content ->
                    val clone = content.clone()
                    // 광고 및 노이즈 제거
                    clone.select(".hinews_pc_ads740x300px, .hinews_pc_ads700x300px, script, style").remove()
                    return clone
                }

                // 2차: .gmv2c_con01.detailCont (상위 컨테이너)
                doc.selectFirst(".gmv2c_con01.detailCont")?.let { container ->
                    val clone = container.clone()
                    clone.select(".hinews_pc_ads740x300px, .hinews_pc_ads700x300px, script, style").remove()
                    return clone
                }

                // 3차: .gmv2c (더 상위 컨테이너)
                doc.selectFirst(".gmv2c")?.let { gmv2c ->
                    val clone = gmv2c.clone()
                    clone.select(".gmv2dv1, .gmv2c_p01, .hinews_pc_ads740x300px, .hinews_pc_ads700x300px, script, style").remove()
                    return clone
                }

                null
            }

            // CNB뉴스 (cnbnews.com) 전용 처리
            // - 실제 본문: div#news_body_area.smartOutput
            // - 광고/노이즈: .btn_allarticle (다른기사 보기), .util/.util2 (SNS/인쇄 버튼),
            //               .viewsubject, #commentPane, banner_click.php 배너 div
            url.contains("cnbnews.com") -> {
                Log.d(TAG, "본문 감지: CNB뉴스 cnbnews.com")

                // 1차: #news_body_area (순수 기사 본문 — 내부에 광고 없음)
                doc.selectFirst("#news_body_area")?.let { body ->
                    val clone = body.clone()
                    clone.select("script, style, noscript").remove()
                    return clone
                }

                // 2차: .cnt_view.news_body_area (상위 컨테이너, fallback)
                doc.selectFirst(".cnt_view.news_body_area")?.let { container ->
                    val clone = container.clone()
                    clone.select(
                        // 기자 다른기사 보기 링크
                        ".btn_allarticle, " +
                                // 인쇄·메일·SNS 공유 버튼
                                ".util, .util2, " +
                                // 제목/날짜 메타 영역 (본문 상단 중복)
                                ".viewsubject, " +
                                // 댓글 영역
                                "#commentPane, " +
                                // banner_click.php 경유 배너 div
                                "div:has(> a[href*='banner_click.php']), " +
                                // 공통 노이즈
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                null
            }

            // newsprime.co.kr / datanews.co.kr 전용 처리 (동일 CMS)
            url.contains("newsprime.co.kr") || url.contains("datanews.co.kr") -> {
                val pressName = if (url.contains("datanews.co.kr")) "데이터뉴스 datanews.co.kr" else "프라임경제 newsprime.co.kr"
                Log.d(TAG, "본문 감지: $pressName")

                // datanews 공통 배너 노이즈 셀렉터
                val datanewsBannerNoise = "[id*='banner_base_'], [id*='banner_contents_'], " +
                        ".section_h3_v2, .left-wing-banner, .right-wing-banner, " +
                        "script, style"

                // 1차: #news_body_area (실제 기사 본문)
                doc.selectFirst("#news_body_area")?.let { body ->
                    val clone = body.clone()
                    // 노이즈 제거
                    clone.select(".util2, .bottom_byline, $datanewsBannerNoise").remove()
                    return clone
                }

                // 2차: .cnt_view.news_body_area (상위 컨테이너)
                doc.selectFirst(".cnt_view.news_body_area")?.let { container ->
                    val clone = container.clone()
                    clone.select(".util, .viewsubject, .util2, .bottom_byline, .btn_top, $datanewsBannerNoise").remove()
                    return clone
                }

                // 3차: .section_0 (전체 컨테이너)
                doc.selectFirst(".section_0")?.let { section ->
                    val clone = section.clone()
                    clone.select(".c011_arv, .util, .viewsubject, .util2, .bottom_byline, .btn_top, $datanewsBannerNoise").remove()
                    return clone
                }

                null
            }

            // yeogie.com 전용 처리 - selectByPressSpecific() 함수에 추가
            url.contains("yeogie.com") || url.contains("robotzine.co.kr") -> {
                Log.d(TAG, "본문 감지: yeogie.com / robotzine.co.kr")

                // 1차: #article (실제 본문)
                doc.selectFirst("#article")?.let { article ->
                    val clone = article.clone()
                    // 노이즈 제거
                    clone.select("script, style").remove()
                    return clone
                }

                // 2차: #content.detail (상위 컨테이너)
                doc.selectFirst("#content.detail")?.let { content ->
                    val clone = content.clone()
                    clone.select(".header, .addthis_sharing_toolbox, .zoom, .press, .copyright, .control, .banners, .recents, .comment, #subscription, script, style").remove()
                    return clone
                }

                null
            }

            // 쿠키뉴스
            url.contains("kukinews.com") -> {
                Log.d(TAG, "본문 감지: 쿠키뉴스 #articleContent")
                doc.selectFirst("#articleContent") ?: doc.selectFirst("#article")?.apply {
                    val clone = clone()
                    clone.select(".view-footer, .articleToolBox, .contentReporterInfo, " +
                            ".reporterInfo, .articleRating, script, style").remove()
                    return clone
                }
            }

            // Newsdak CMS 계열
            url.contains("newsfreezone.co.kr") ||
                    url.contains("sisajournal-e.com") ||
                    url.contains("sisajournal.com") ||
                    url.contains("digitaltoday.co.kr") ||
                    url.contains("job-post.co.kr") ||
                    url.contains("etoday.co.kr") ||
                    url.contains("fnnews.com") ||
                    url.contains("newspim.com") ||
                    url.contains("inews24.com") ||
                    url.contains("bizwatch.co.kr") ||
                    url.contains("biztribune.co.kr") ||
                    url.contains("skbroadband.com") ||
                    url.contains("newstof.com") ||
                    url.contains("snmnews.com") ||
                    url.contains("wsobi.com") ||
                    url.contains("financialreview.co.kr") ||
                    url.contains("sisacast.kr") ||
                    url.contains("worktoday.co.kr") ||
                    url.contains("koreaittimes.com") ||
                    url.contains("mtnews.net") ||
                    url.contains("finomy.com") ||
                    url.contains("dynews.co.kr") ||
                    url.contains("ccdailynews.com") ||
                    url.contains("smartfn.co.kr") ||
                    url.contains("smartbizn.com") ||
                    url.contains("newsmaker.or.kr") ||
                    url.contains("areyou.co.kr") ||
                    url.contains("wikileaks-kr.org") ||
                    url.contains("techholic.co.kr") ||
                    url.contains("jemin.com") ||
                    url.contains("newstopkorea.com") -> {
                Log.d(TAG, "본문 감지: Newsdak CMS 계열 #article-view-content-div")
                doc.selectFirst("#article-view-content-div")
                    ?: doc.selectFirst("[itemprop=articleBody]")
            }

            // 미주 한국일보 (koreatimes.com) 전용 처리
            // - 실제 본문: div#print_arti (div.news_area#FontSize 하위)
            // - 광고/노이즈: .web-inside-ad, .article_slick (슬라이드 배너),
            //               div.bn_468x60_1 (하단 배너), script, style
            url.contains("koreatimes.com") -> {
                Log.d(TAG, "본문 감지: 미주 한국일보 koreatimes.com")

                // 1차: #print_arti (실제 기사 본문)
                doc.selectFirst("#print_arti")?.let { printArti ->
                    val clone = printArti.clone()
                    clone.select(
                        // 슬라이드 광고 배너 (동방여행사·한솔보험 등 인아티클 광고)
                        ".web-inside-ad, .article_slick, " +
                                // 하단 468x60 배너 영역
                                ".bn_468x60_1, .bn_468x60_2, " +
                                // 광고 관련 범용 선택자
                                "[class*='ad_'], [id*='ad_'], " +
                                "ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                // 공통 노이즈
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                // 2차: #FontSize (news_area 전체, #print_arti 없을 때 fallback)
                doc.selectFirst("#FontSize")?.let { fontSize ->
                    val clone = fontSize.clone()
                    clone.select(
                        ".web-inside-ad, .article_slick, " +
                                ".bn_468x60_1, .bn_468x60_2, " +
                                "[class*='ad_'], [id*='ad_'], " +
                                "ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                ".pPop, .popup_overlay, " +
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                // 3차: .news_area (최상위 컨테이너 fallback)
                doc.selectFirst(".news_area")?.let { newsArea ->
                    val clone = newsArea.clone()
                    clone.select(
                        ".web-inside-ad, .article_slick, " +
                                ".bn_468x60_1, .bn_468x60_2, " +
                                "[class*='ad_'], [id*='ad_'], " +
                                "ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                ".pPop, .popup_overlay, " +
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                null
            }

            // 오토타임즈 (autotimes.co.kr) 전용 처리
            // - 실제 본문: div#ct
            // - 광고/노이즈: banner_view.php 배너, 관련기사, 기자정보, 투표, SNS 버튼
            url.contains("autotimes.co.kr") -> {
                Log.d(TAG, "본문 감지: 오토타임즈 autotimes.co.kr")

                // 1차: div#ct (실제 기사 본문)
                doc.selectFirst("#ct")?.let { ct ->
                    val clone = ct.clone()
                    clone.select("script, style").remove()
                    return clone
                }

                // 2차: .article_area (본문 영역 컨테이너) — #ct 없을 때 fallback
                doc.selectFirst(".article_area")?.let { area ->
                    val clone = area.clone()
                    clone.select(
                        // 광고/배너
                        "img[src*='banner_view.php'], .detail_banner_area, " +
                                "[class*='banner'], [id*='banner'], " +
                                // 관련기사·기자정보·투표·SNS
                                ".detail_related_article, .detail_reporter, " +
                                ".detail_vote_area, .detail_buttons, " +
                                // 결제/유료기사 UI
                                "form[name='payform'], .pay_area, " +
                                // 공통 노이즈
                                "script, style"
                    ).remove()
                    return clone
                }

                null
            }

            // 디지틀조선TV (dizzotv.com / businessnews.chosun.com)
            // - 실제 본문: .txtBox  (div.left_con > div.news_wrap > div.txtBox)
            // - 광고/노이즈: adsbygoogle ins, .banner_box, .news_conBan(모바일배너),
            //               .best_viewArea, .today_group, .right_con, .copyright,
            //               .right_gruop(공유버튼), .reporter
            url.contains("dizzotv.com") || url.contains("businessnews.chosun.com") -> {
                Log.d(TAG, "본문 감지: 디지틀조선TV dizzotv.com / businessnews.chosun.com")

                // 1차: .txtBox (순수 기사 본문 영역)
                doc.selectFirst(".txtBox")?.let { txtBox ->
                    val clone = txtBox.clone()
                    clone.select(
                        // Google AdSense 광고 태그
                        "ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                // 배너 영역
                                ".banner_box, .news_conBan, [class*='banner'], " +
                                // 공통 노이즈
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                // 2차: .news_wrap (txtBox 상위 컨테이너) — fallback
                doc.selectFirst(".news_wrap")?.let { wrap ->
                    val clone = wrap.clone()
                    clone.select(
                        "ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                ".banner_box, .news_conBan, [class*='banner'], " +
                                ".best_viewArea, .today_group, .copyright, " +
                                ".right_gruop, .reporter, " +
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                null
            }

            // 더밸류뉴스
            url.contains("thevaluenews.co.kr") -> {
                Log.d(TAG, "본문 감지: 더밸류뉴스")
                // 실제 기사 본문은 #viewContent > .fr-view 안에 있음
                val frView = doc.selectFirst("#viewContent .fr-view")
                    ?: doc.selectFirst(".fr-view")
                    ?: doc.selectFirst("#viewContent")
                    ?: doc.selectFirst(".viewContent")

                frView?.let { body ->
                    val clone = body.clone()
                    // 광고 스크립트, SNS 버튼, 구독폼, 관련기사, 태그, 기자프로필 제거
                    clone.select(
                        "script, style, noscript, iframe," +
                                ".snsWrap, .pageControl, .likeContentWrap," +
                                ".relatedNews, dl.relatedNews," +
                                ".keywords, dl.keywords," +
                                ".stb_subscribe, #stb_subscribe," +
                                ".writerProfile," +
                                ".replyWrite," +
                                "[class*='banner'], [id*='banner']," +
                                ".topArticleBanner, .topArticleBanner2," +
                                ".registModifyDate," +
                                ".else-area"
                    ).remove()
                    return clone
                }
                null
            }

            // 더벨
            url.contains("thebell.co.kr") -> {
                Log.d(TAG, "본문 감지: 더벨")
                val thebellNoise = ".viewHead, .reference, .linkBox, .newsADBox, .linkNews, " +
                        ".article_content_banner, .article_title_banner, " +
                        ".headBox, .optionIcon, script, style"

                // 1) .viewBox 전체 영역 (front/newsview.asp 구조)
                doc.selectFirst(".viewBox")?.let { viewBox ->
                    val clone = viewBox.clone()
                    clone.select(thebellNoise).remove()
                    return clone
                }

                // 2) #article_main 본문 div
                doc.selectFirst("#article_main")?.let { articleMain ->
                    val clone = articleMain.clone()
                    clone.select("script, style").remove()
                    return clone
                }

                // 3) .viewSection fallback
                doc.selectFirst(".viewSection")?.let { viewSection ->
                    val clone = viewSection.clone()
                    clone.select(thebellNoise).remove()
                    return clone
                }

                // 4) og:description 기반 메타 텍스트를 synthetic 엘리먼트로 반환
                val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
                    ?: doc.selectFirst("meta[name=description]")?.attr("content")
                if (!ogDesc.isNullOrBlank()) {
                    Log.w(TAG, "더벨: 본문 셀렉터 없음, og:description 사용")
                    val synth = Element("div")
                    synth.appendElement("p").text(ogDesc)
                    return synth
                }

                Log.e(TAG, "더벨: 모든 셀렉터 실패")
                null
            }

            // 광주일보
            url.contains("kjdaily.com") -> {
                Log.d(TAG, "본문 감지: 광주일보")
                (doc.selectFirst("#content") ?: doc.selectFirst(".cont_left"))?.apply {
                    select(".box_timenews, .new_news_list, .section_top_view, " +
                            ".floating, [class*='ad'], [id*='ad']").remove()
                    return this
                }
            }

            // 대한경제
            url.contains("dnews.co.kr") -> {
                Log.d(TAG, "본문 감지: 대한경제")
                (doc.selectFirst("div.text") ?: doc.selectFirst(".newsCont"))?.apply {
                    select(
                        ".dateFont, .btnSocial, .btnFont, .btnPrint, .journalist_view_more, " +
                                ".journalist_img, .journalist_name, .journalist_email, .journalist_link, " +
                                ".journalist_position, .sub_title, script, style"
                    ).remove()
                    return this
                }
            }

            // 서울경제TV
            url.contains("sentv.co.kr") -> {
                Log.d(TAG, "본문 감지: 서울경제TV")
                (doc.selectFirst("#newsView")
                    ?: doc.selectFirst(".edit-txt")
                    ?: doc.selectFirst(".view_txt")
                    ?: doc.selectFirst(".article_txt")
                    ?: doc.selectFirst(".article_body")
                    ?: doc.selectFirst(".view_content"))?.apply {
                    select(
                        ".reporter_wrap, .reporter_info, .rel_news, .related_news, " +
                                ".section_3, .bt-area, .s-tit, .copy-noti, " +
                                ".view_sns, .view_util, .ad_box, [class*='ad_'], " +
                                "script, style"
                    ).remove()
                    return this
                }
            }

            // 전남매일
            url.contains("jndn.com") -> {
                Log.d(TAG, "본문 감지: 전남매일")
                (doc.selectFirst("#content") ?: doc.selectFirst(".cont_left"))?.apply {
                    select(
                        ".article_footer, .box_timenews, .new_news_list, .new_news_list_ttl, " +
                                ".article_hot, .cont_right, .floating, .paging_news, " +
                                "[class*='ad'], [id*='ad'], script, style"
                    ).remove()
                    return this
                }
            }

            // 광주매일신문
            url.contains("gjdaily.com") -> {
                Log.d(TAG, "본문 감지: 광주매일신문")
                doc.selectFirst("#article-view-content-div")
                    ?: doc.selectFirst("[itemprop=articleBody]")
                    ?: doc.selectFirst(".article-view-content")
                    ?: doc.selectFirst(".article_view_content")
                    ?: doc.selectFirst("#articleViewCon")
                    ?: doc.selectFirst(".article-body")
                    ?: doc.selectFirst("#view_content")
                    ?: doc.selectFirst(".view_content")
            }

            // 에너지/전문지 계열
            url.contains("energynews.co.kr") ||
                    url.contains("enewstoday.co.kr") ||
                    url.contains("electimes.com") ||
                    url.contains("e2news.com") ||
                    url.contains("industrynews.co.kr") ||
                    url.contains("energy-news.co.kr") ||
                    url.contains("energy.co.kr") -> {
                Log.d(TAG, "본문 감지: 에너지/전력 전문지")
                doc.selectFirst("#article-view-content-div")
                    ?: doc.selectFirst("[itemprop=articleBody]")
                    ?: doc.selectFirst(".article-view-content")
                    ?: doc.selectFirst(".article_body")
                    ?: doc.selectFirst(".article-body")
                    ?: doc.selectFirst("#article_body")
                    ?: doc.selectFirst(".news_content")
                    ?: doc.selectFirst("#news_content")
            }

            // 뉴스투데이
            url.contains("news2day.co.kr") -> {
                Log.d(TAG, "본문 감지: 뉴스투데이")
                val wraps = doc.select(".view_con_wrap")
                wraps.firstOrNull { it.text().length > 50 } ?: doc.selectFirst(".view_con")
            }

            // 딜사이트/팍스넷
            url.contains("dealsite.co.kr") || url.contains("paxnetnews.com") -> {
                Log.d(TAG, "본문 감지: 딜사이트 .content-area 병합")
                val contentAreas = doc.select(".content-area")
                if (contentAreas.isNotEmpty()) {
                    val merged = Element("div")
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
                null
            }

            // 광주MBC
            url.contains("kjmbc.co.kr") -> {
                Log.d(TAG, "본문 감지: 광주MBC")
                (doc.selectFirst(".news-article-body") ?: doc.selectFirst(".news-article"))?.apply {
                    select(
                        ".daum-banner, .tag, .profile, .news-comment, " +
                                ".pageFunction, aside, .news-aside, " +
                                "script, style"
                    ).remove()
                    return this
                }
            }

            // 톱데일리 (topdaily.kr) 전용 처리
            // - 실제 본문: section.article-body (p.subtitle + div.content)
            // - 하단 제거 대상: .hashtags, p.copyright, .byline-bottom,
            //                  .series-news, .stock-news, .related-news, .main-news
            url.contains("topdaily.kr") -> {
                Log.d(TAG, "본문 감지: 톱데일리 topdaily.kr")

                // 1차: section.article-body (부제목 + 본문 포함)
                doc.selectFirst("section.article-body")?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        // 해시태그
                        ".hashtags, " +
                                // 저작권 표시
                                "p.copyright, " +
                                // 하단 기자 정보
                                ".byline-bottom, " +
                                // 시리즈 기사 목록
                                ".series-news, " +
                                // 관련종목
                                ".stock-news, " +
                                // 관련뉴스
                                ".related-news, " +
                                // 주요뉴스
                                ".main-news, " +
                                // 공통 노이즈
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                // 2차: div.content (순수 본문만, fallback)
                doc.selectFirst("div.content")?.let { content ->
                    val clone = content.clone()
                    clone.select("script, style, noscript").remove()
                    return clone
                }

                null
            }

            // MTN
            url.contains("mtn.co.kr") -> {
                Log.d(TAG, "본문 감지: MTN")
                doc.selectFirst("#articlebody")
                    ?: doc.selectFirst(".articlebody")
                    ?: doc.selectFirst("#article_body")
                    ?: doc.selectFirst(".article_body")
                    ?: doc.selectFirst(".article-body")
                    ?: doc.selectFirst(".news_text")
                    ?: doc.selectFirst(".view_text")
            }

            // 글로벌이코노믹
            url.contains("g-enews.com") -> {
                Log.d(TAG, "본문 감지: 글로벌이코노믹 .vtxt.detailCont")
                (doc.selectFirst(".vtxt.detailCont")
                    ?: doc.selectFirst("[itemprop=articleBody]"))?.apply {
                    val clone = clone()
                    clone.select(".mimg_img, .mimg_open, .vbtm, .mad, script, style").remove()
                    return clone
                }
            }

            // 이데일리
            url.contains("edaily.co.kr") -> {
                doc.selectFirst(".news_body")?.also {
                    Log.d(TAG, "이데일리 .news_body")
                }
            }

            // 머니투데이
            url.contains("mt.co.kr") -> {
                doc.selectFirst(".newsView")?.also {
                    Log.d(TAG, "머니투데이 .newsView")
                }
            }

            // 비즈워치
            url.contains("bizwatch.co.kr") -> {
                doc.selectFirst(".article-content")?.also {
                    Log.d(TAG, "비즈워치 .article-content")
                }
            }

            // 메트로서울
            url.contains("metroseoul.co.kr") -> {
                Log.d(TAG, "본문 감지: 메트로서울")
                (doc.selectFirst("[data-layout-area=ARTICLE_CONTENT]")
                    ?: doc.selectFirst(".article-txt-contents"))?.apply {
                    val clone = clone()
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
                doc.selectFirst(".article-txt")?.also {
                    Log.d(TAG, "연합뉴스 .article-txt")
                }
            }

            // 코리아쉬핑가제트 (ksg.co.kr)
            // 본문 셀렉터: #newsContent.editor_txt
            // ─────────────────────────────────────────────────────────────────
            // 페이지 광고 구조 (HTML 분석 기준):
            //   · Google AdSense page-level ads (enable_page_level_ads:true)
            //     → ins.adsbygoogle / [class*='adsbygoogle'] / [id*='adsbygoogle']
            //   · #sub_banner(.web_none) — 상단 배너 (veson 광고 등)
            //   · .view_banner           — 본문 하단 배너 슬롯
            //   · .thumbAd / .thumb_ad   — 우측 썸네일 광고
            //   · .rightBannerPop        — 헤더 우측 팝업 배너
            //   · .top_event_wrap        — 헤더 상단 이벤트 배너
            // ─────────────────────────────────────────────────────────────────
            // #newsContent 자체는 순수 기사 본문이므로 형제 노이즈는 미포함.
            // 단, Google page-level ads 가 본문 내부에 동적 삽입될 수 있으므로
            // 아래 선택자들을 방어적으로 제거.
            url.contains("ksg.co.kr") -> {
                Log.d(TAG, "본문 감지: 코리아쉬핑가제트 #newsContent")
                (doc.selectFirst("#newsContent")
                    ?: doc.selectFirst(".editor_txt"))?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        // 스크립트·스타일
                        "script, style, noscript, iframe," +
                                // Google AdSense (page-level ads 포함)
                                "ins.adsbygoogle, ins[class*='adsbygoogle']," +
                                "[class*='adsbygoogle'], [id*='adsbygoogle']," +
                                // 배너·광고 영역
                                "#sub_banner, .sub_banner," +
                                ".view_banner," +
                                ".thumbAd, .thumb_ad," +
                                ".rightBannerPop," +
                                ".top_event_wrap, .top_event," +
                                ".ad_area, .ad_wrap, .ad_container," +
                                "[class*='ad_'], [id*='ad_']," +
                                "[class*='banner'], [id*='banner']," +
                                // 공유·인쇄·SNS 버튼
                                ".view_link_wrap, .view_link, .view_link_sns," +
                                // 댓글
                                ".all_comment_wrap, .comment_write," +
                                // SNS·구독·스케줄 사이드
                                ".sns_wrap, .sns, .subscription_new_wrap," +
                                ".schedule_search_wrap, .pc_ship_navigation_wrap," +
                                ".schedulePopup, .shop_job_wrap," +
                                // 각종 버튼
                                ".btn_advert, .btn_news, .btn_freeboard," +
                                ".btn_forwarding, .btn_recommendationsite," +
                                // 우측 전체 컨테이너
                                ".fr_cont"
                    ).remove()
                    // 기자 서명 제거: "< 홍길동 기자 email@ksg.co.kr >" 형식
                    // filterNoiseBlocks 의 isBylineOnly 패턴과 불일치하므로 직접 제거
                    clone.select("p").filter { p ->
                        val t = p.text()
                        t.contains("기자") && t.contains("@ksg.co.kr")
                    }.forEach { it.remove() }
                    return clone
                }
            }

            // 아시아타임즈 (asiatime.co.kr)
            // ─────────────────────────────────────────────────────────────────
            // 페이지 구조 분석:
            //   · 기사 본문 컨테이너: [data-layout-area="ARTICLE_LEFT"]
            //     └ .article_txt_container > .col-12 에 실제 <p>/<figure> 포함
            //   · 소제목(부제):     .sub_article_titleArea  (유지)
            //   · 모바일 광고:      .m_article_ad_01 ~ _08
            //   · PC 광고:          .pc_article_ad_01, .articlePcAd_01/.03
            //   · 인라인 광고 ins:  ins.adsbygoogle / [class*='adsbygoogle']
            //   · 기자 정보:        .reporter_info_container
            //   · ArticleVO 덤프:   .article_bnr_wrap (input[type=hidden]에 대용량 JSON 포함)
            //   · 광고 배너 래퍼:   .under_ad01, .article_under_banner, .m_article_ad_04
            //   · 댓글 영역:        .reply_txt_area, #commentList
            //   · 관련기사:         .relation_news_container, .related_article_wrap
            //   · 우측 사이드바:    .section_right_container (selectByGenericSelector 차단용)
            // ─────────────────────────────────────────────────────────────────
            url.contains("asiatime.co.kr") -> {
                Log.d(TAG, "본문 감지: 아시아타임즈 [data-layout-area=ARTICLE_LEFT]")
                doc.selectFirst("[data-layout-area=ARTICLE_LEFT]")?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        // 스크립트·스타일
                        "script, style, noscript, iframe," +
                                // 모바일 광고 div
                                ".m_article_ad_01, .m_article_ad_02, .m_article_ad_03," +
                                ".m_article_ad_04, .m_article_ad_05, .m_article_ad_06," +
                                ".m_article_ad_07, .m_article_ad_08," +
                                // PC 광고 div
                                ".pc_article_ad_01, .pc_article_ad_02, .pc_article_ad_03," +
                                ".articlePcAd_01, .articlePcAd_03," +
                                // 인라인 AdSense ins 태그
                                "ins.adsbygoogle, ins[class*='adsbygoogle']," +
                                "[class*='adsbygoogle'], [id*='adsbygoogle']," +
                                // 배너 래퍼
                                ".under_ad01, .article_under_banner," +
                                // 기자 정보 박스
                                ".reporter_info_container," +
                                // ArticleVO 대용량 hidden input 포함 래퍼
                                ".article_bnr_wrap," +
                                // 관련기사
                                ".relation_news_container, .related_article_wrap," +
                                // 댓글 영역
                                ".reply_txt_area, #commentList," +
                                // 기타 hidden 입력값 (siteStoryId, siteViewTitle 등)
                                "input[type=hidden]"
                    ).remove()
                    return clone
                }
                null
            }

            // 매일건설신문 (mcnews.co.kr)
            // ─────────────────────────────────────────────────────────────────
            // 페이지 구조 분석:
            //   · 실제 기사 본문:  div#textinput
            //   · 중복 타이틀:     .article_head  (기사 본문 안에 타이틀 영역이 한 번 더 삽입됨)
            //   · 상단 배너:       .banner_top
            //   · 동영상 영역:     .movie_data    (비어 있어도 제거)
            //   · 저작권 표시:     .news_read_copyright
            //   · 관련기사:        .read_r_news
            //   · 기자 소개:       .kija_intro
            //   · 하단 SNS/옵션:   .read_option_bottom
            //   · 하단 배너:       .banner_bottom
            // ─────────────────────────────────────────────────────────────────
            url.contains("mcnews.co.kr") -> {
                Log.d(TAG, "본문 감지: 매일건설신문 #textinput")
                doc.selectFirst("#textinput")?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        // 스크립트·스타일
                        "script, style, noscript, iframe," +
                                // 상단 배너 및 동영상 영역
                                ".banner_top, .movie_data," +
                                // 기사 타이틀 영역 (본문 안에 중복 삽입된 것)
                                ".article_head," +
                                // 저작권 표시
                                ".news_read_copyright," +
                                // 관련기사 목록
                                ".read_r_news," +
                                // 기자 소개 박스
                                ".kija_intro," +
                                // 하단 SNS 공유·인쇄 버튼 영역
                                ".read_option_bottom," +
                                // 하단 배너
                                ".banner_bottom"
                    ).remove()
                    return clone
                }
                null
            }

            // 내일신문 (naeil.com)
            // ─────────────────────────────────────────────────────────────────
            // 페이지 구조 분석:
            //   · 기사 본문:       .article-view           (순수 <p> 목록)
            //   · 본문 컨테이너:   .article-view-main      (.article-view + .reporter-news-more + .major-news 포함)
            //   · 주요기사 목록:   .major-news             (하단 관련기사 4건)
            //   · 기자 더보기:     .reporter-news-more     (기자별 기사 링크)
            //   · 오디오 재생기:   .audioFrame             (TTS MP3 플레이어)
            //   · TTS 스크립트:    script (infoTtsData JSON)
            //   · 우측 사이드바:   .right-sub-conts        (오피니언·인터뷰·많이 본 뉴스)
            //   · SNS 공유 버튼:   .sns-area-wrap, .popup-sns-wrap
            //   · 헤더 영역:       .article-header         (날짜·공유버튼 포함)
            // ─────────────────────────────────────────────────────────────────
            url.contains("naeil.com") -> {
                Log.d(TAG, "본문 감지: 내일신문 naeil.com")

                // 1차: .article-view (순수 본문 <p> 만 포함)
                doc.selectFirst(".article-view")?.let { body ->
                    val clone = body.clone()
                    clone.select("script, style, noscript").remove()
                    return clone
                }

                // 2차: .article-view-main (본문 컨테이너, 노이즈 제거 후 반환)
                doc.selectFirst(".article-view-main")?.let { container ->
                    val clone = container.clone()
                    clone.select(
                        "script, style, noscript," +
                                ".major-news," +           // 주요기사 목록
                                ".reporter-news-more," +   // 기자 더보기 링크
                                ".audioFrame"              // TTS 오디오 플레이어
                    ).remove()
                    return clone
                }

                // 3차: .left-main-news (더 상위 컨테이너)
                doc.selectFirst(".left-main-news")?.let { leftMain ->
                    val clone = leftMain.clone()
                    clone.select(
                        "script, style, noscript," +
                                ".major-news, .reporter-news-more, .audioFrame," +
                                ".sns-area-wrap, .popup-sns-wrap, .font-popup"
                    ).remove()
                    return clone
                }

                null
            }

            // 경상매일신문 (ksmnews.co.kr)
            // ─────────────────────────────────────────────────────────────────
            // 페이지 구조 분석:
            //   · 기사 본문이 JS AJAX로 로드됨 (Jsoup은 JS 미실행 → 정적 HTML은 빈 div)
            //   · 동적 로드 URL: /data/newsText/news/{idx/1000}/{idx}.json
            //   · JSON 필드: view_content_body (기사 본문 HTML)
            //   · 정적 HTML: <div id="view_content_body"></div> (항상 비어있음)
            //   · idx 추출: URL 파라미터 ?idx=585051
            // ─────────────────────────────────────────────────────────────────
            url.contains("ksmnews.co.kr") -> {
                Log.d(TAG, "본문 감지: 경상매일신문 ksmnews.co.kr (AJAX JSON 방식)")

                // URL에서 idx 추출
                val idx = Regex("[?&]idx=(\\d+)").find(url)?.groupValues?.get(1)

                if (idx != null) {
                    try {
                        val folder = (idx.toLong() / 1000).toString()
                        val jsonUrl = "https://www.ksmnews.co.kr/data/newsText/news/$folder/$idx.json"
                        Log.d(TAG, "ksmnews JSON 요청: $jsonUrl")

                        val jsonBody = buildJsoupConn(jsonUrl)
                            .ignoreContentType(true)
                            .execute()
                            .body()

                        val bodyHtml = org.json.JSONObject(jsonBody).optString("view_content_body", "")

                        if (bodyHtml.isNotBlank()) {
                            val synth = Element("div")
                            synth.append(bodyHtml)
                            // 광고/노이즈 제거
                            synth.select(
                                "script, style, noscript, iframe," +
                                        ".photo_img," +
                                        "[class*='ad'], [id*='ad']"
                            ).remove()
                            Log.d(TAG, "ksmnews JSON 본문 추출 성공 (${bodyHtml.length}자)")
                            return synth
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ksmnews JSON 요청 실패: ${e.message}")
                    }
                }

                // fallback: 정적 HTML에서 articleBody 시도
                doc.selectFirst("[itemprop=articleBody]")
                    ?: doc.selectFirst("#view_content_body")
            }

            // 더파워(thepowernews.co.kr) 전용 처리
            url.contains("thepowernews.co.kr") -> {
                Log.d(TAG, "본문 감지: 더파워 thepowernews.co.kr")

                // 1차: 실제 본문 영역 (기존 코드에서 이미 잘 잡히는 부분)
                doc.selectFirst(".gmv2c_con01.detailCont")?.let { content ->
                    val clone = content.clone()

                    // 광고·노이즈·관련기사·기자 더보기·저작권 등 모두 제거
                    clone.select(
                        ".gmv2c_p01, .gmv2c_more01, .gm9d, .gm4d, " +           // 사이드 및 하단 섹션
                                "script, style, noscript, iframe, .article_con_img figure figcaption," + // 불필요한 캡션 (필요시 유지)
                                ".gmvtool01, .gnb, .footer, .ent_menu, .t3d, .mf_top," + // 헤더/메뉴/푸터
                                "[class*='ad'], [id*='ad'], .sns, .share, .banner"       // 광고 관련
                    ).remove()

                    // 불필요한 빈 태그나 메뉴성 텍스트도 filterNoiseBlocks에서 걸러짐
                    return clone
                }

                // 2차 fallback
                doc.selectFirst(".gmv2c")?.let { container ->
                    val clone = container.clone()
                    clone.select(".gmv2c_p01, .gmv2c_more01, .gm9d, .gm4d, script, style, .gmvtool01").remove()
                    return clone
                }

                null
            }

            // 뉴스토마토(newstomato.com) 전용 처리
            url.contains("newstomato.com") -> {
                Log.d(TAG, "본문 감지: 뉴스토마토 newstomato.com")

                // 1차: 실제 본문 영역
                doc.selectFirst("#ctl00_ContentPlaceHolder1_WebNewsView_ltContentDiv")?.let { content ->
                    val clone = content.clone()

                    // 🔥 노이즈 제거 (핵심)
                    clone.select(
                        // 광고 및 스크립트
                        "script, style, noscript, iframe, ins.adsbygoogle," +

                                // 상단/하단 불필요 영역
                                ".rc_bn, .adwrap, .rns_bn, .rn_sider, .rn_footer," +

                                // 기자/프로필/댓글
                                ".pf_wrap_ln, .wh_comment_wrap," +

                                // 관련기사 / 인기뉴스 / 추천
                                ".s5_tit, .s_case5, .newsrhythm_cont," +
                                ".top_news_time_cont, .popular_news_cont, .watch_together_cont," +

                                // 기타 텍스트 노이즈
                                ".desc, p[id*='credit']," +

                                // SNS / 날짜 / 컨트롤
                                ".rns_controll, .rnsc_sns, .rn_sdate, .rn_sti_case"
                    ).remove()

                    // 🔧 뉴스토마토는 <p> 대신 <div>로 단락을 구분함
                    // blockTags에 div가 없으므로 \n\n이 삽입되지 않아 단락이 붙어버림
                    // → div를 p로 변환하여 extractContentBlocks()의 단락 구분 로직이 동작하도록 처리
                    clone.select("div").forEach { it.tagName("p") }

                    return clone
                }

                // 2차 fallback
                doc.selectFirst(".rns_text")?.let { textArea ->
                    val clone = textArea.clone()
                    clone.select(
                        "script, style, .desc, p[id*='credit']"
                    ).remove()
                    clone.select("div").forEach { it.tagName("p") }
                    return clone
                }

                null
            }

            // 비즈한국(bizhankook.com) 전용 처리
            // ─────────────────────────────────────────────────────────────────
            // 기사 본문: section.viewContWrap (itemprop="articleBody")
            // 하단 노이즈:
            //   · .journalist          — 기자 이름/이메일 박스
            //   · p.hotClickTit        — [핫클릭] 제목
            //   · p.relationList       — 핫클릭 관련기사 목록
            //   · p.copyinfo           — 저작권 문구
            //   · .btnlist             — "전체 리스트 보기" 버튼
            //   · #commentSkeleton     — 댓글 로딩 스켈레톤
            //   · #commentContent      — 댓글 영역
            //   · .anotherArticle      — 주요기사/인기기사 추천 블록
            //   · .pcaddNew03 등       — 광고 영역
            // ─────────────────────────────────────────────────────────────────
            url.contains("bizhankook.com") -> {
                Log.d(TAG, "본문 감지: 비즈한국 bizhankook.com section.viewContWrap")

                doc.selectFirst("section.viewContWrap")?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        // 스크립트·스타일
                        "script, style, noscript, iframe," +
                                // 기자 정보
                                ".journalist," +
                                // [핫클릭] 제목 및 관련기사
                                "p.hotClickTit, p.relationList," +
                                // 저작권 문구
                                "p.copyinfo," +
                                // 전체 리스트 보기 버튼
                                ".btnlist," +
                                // 댓글 영역
                                "#commentSkeleton, #commentContent, .commentWrap," +
                                // 주요기사/인기기사 추천 블록
                                ".anotherArticle," +
                                // 광고 영역
                                ".pcaddNew01, .pcaddNew02, .pcaddNew03, .pcaddNew04, .pcaddNew05," +
                                "[class*='adsbygoogle'], ins.adsbygoogle"
                    ).remove()
                    return clone
                }

                null
            }

            // 아주경제(ajunews.com) 전용 처리
            // ─────────────────────────────────────────────────────────────────
            // 기사 본문: #articleBody (itemprop="articleBody")
            // 하단 노이즈:
            //   · .keyword_tag    — #뉴욕페스티벌 등 키워드 태그
            //   · .news_like_bad  — 좋아요/나빠요 버튼
            //   · .byline         — 기자 프로필 (하단)
            //   · p.copy          — 저작권 문구 (© 아주경제 무단전재·재배포 금지)
            //   · .related_news   — 본문 인라인 관련기사 박스
            // ─────────────────────────────────────────────────────────────────
            url.contains("ajunews.com") -> {
                Log.d(TAG, "본문 감지: 아주경제 ajunews.com #articleBody")

                doc.selectFirst("#articleBody")?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        // 스크립트·스타일
                        "script, style, noscript, iframe," +
                                // 키워드 태그 (#뉴욕페스티벌 등)
                                ".keyword_tag," +
                                // 좋아요/나빠요 버튼
                                ".news_like_bad," +
                                // 하단 기자 프로필 박스
                                ".byline," +
                                // 저작권 문구
                                "p.copy," +
                                // 본문 인라인 관련기사
                                ".related_news," +
                                // 광고 영역
                                "[class*='atc_ad'], [id*='dcamp_ad'], [id*='dablewidget']," +
                                "ins.adsbygoogle, ins[class*='adsbygoogle']"
                    ).remove()
                    return clone
                }

                null
            }

            // 뷰어스 (theviewers.co.kr)
            // ─────────────────────────────────────────────────────────────────
            // 문제: [itemprop=articleBody]가 페이지 상단의 display:none div를 잡아
            //       <p> 없이 텍스트만 있는 덩어리를 반환 → 단락 구분 불가
            // 해결: .article-body 를 직접 타겟 (실제 <p> 태그 포함)
            // ─────────────────────────────────────────────────────────────────
            url.contains("theviewers.co.kr") -> {
                Log.d(TAG, "본문 감지: 뷰어스 theviewers.co.kr")

                // 1차: .article-body (실제 <p> 본문 영역)
                doc.selectFirst(".article-body")?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        "script, style, noscript, iframe," +
                                ".vote-container," +               // 좋아요/싫어요 버튼
                                "ins.adsbygoogle, ins[class*='adsbygoogle']"
                    ).remove()
                    return clone
                }

                // 2차 fallback: .article-main-content
                doc.selectFirst(".article-main-content")?.let { content ->
                    val clone = content.clone()
                    clone.select(
                        "script, style, noscript, iframe," +
                                ".vote-container," +
                                ".writer-info-container," +        // 기자 프로필
                                "ins.adsbygoogle, ins[class*='adsbygoogle']"
                    ).remove()
                    return clone
                }

                null
            }

            // ─────────────────────────────────────────────────────────────────
            // 뉴스웨이 (newsway.co.kr)
            // ─────────────────────────────────────────────────────────────────
            // 문제: 범용 `article` 셀렉터가 관련기사·기자정보·광고·태그 등
            //       노이즈를 포함한 전체 article 블록을 반환함
            // 해결: 실제 본문인 #view-text 를 직접 타겟하고 인아티클 노이즈 제거
            // ─────────────────────────────────────────────────────────────────
            url.contains("newsway.co.kr") -> {
                Log.d(TAG, "본문 감지: 뉴스웨이 newsway.co.kr")

                // 1차: #view-text (실제 기사 본문)
                doc.selectFirst("#view-text")?.let { viewText ->
                    val clone = viewText.clone()
                    clone.select(
                        // 인아티클 Google AdSense 광고
                        ".googlead, ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                // 관련기사 팝업 (드로어)
                                "#drawer, " +
                                // 관련 태그
                                ".view-tag, " +
                                // 모바일 광고 리스트
                                ".mobile-ad-list, .pc-ad-list, " +
                                // 공통 노이즈
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                // 2차: .view-area article (상위 컨테이너, fallback)
                doc.selectFirst(".view-area article")?.let { article ->
                    val clone = article.clone()
                    clone.select(
                        ".googlead, ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                "#drawer, .view-tag, " +
                                ".mobile-ad-list, .pc-ad-list, " +
                                // 기자 정보 및 다른기사
                                ".view-bottom, " +
                                // 관련기사 목록
                                ".view-related, " +
                                // 저작권
                                ".view-copyright, " +
                                // 댓글
                                "#view-comment, " +
                                "script, style, noscript"
                    ).remove()
                    return clone
                }

                null
            }

            // 뉴스클레임 (newsclaim.co.kr) 전용 처리
            url.contains("newsclaim.co.kr") -> {
                Log.d(TAG, "본문 감지: 뉴스클레임 newsclaim.co.kr")

                // 1차: 실제 기사 본문 영역 (#article-view-content-div)
                doc.selectFirst("#article-view-content-div")?.let { body ->
                    val clone = body.clone()

                    // 불필요한 하단/노이즈 요소 제거
                    clone.select(
                        // 광고 영역
                        "ins.adsbygoogle, ins[class*='adsbygoogle'], " +
                                ".ad-template, [class*='ad_'], [id*='AD'], " +
                                ".naver_stand, " +                    // 네이버 뉴스스탠드 배너
                                // 주요기사 섹션
                                "article.relation, .relation, " +
                                // 저작권/기자 정보
                                ".article-copy, .writer, .tag-group, " +
                                // SNS 공유 버튼들
                                ".article-view-sns, .social-group, " +
                                // 하단 관련 기사/많이본 기사/섹션 주요기사 등
                                ".box-skin, #skin-best_S1N5, #skin-1, #skin-2, #skin-4, #skin-33, " +
                                // 댓글 영역 전체
                                "#comment, .comment-write, #comment-list, " +
                                // 기타 노이즈
                                "script, style, noscript, iframe, " +
                                ".clearfix, .sticky-article, " +
                                "[class*='banner'], [id*='banner'], " +
                                ".btn-comment, .reveal"
                    ).remove()

                    // [뉴스클레임] 프리픽스나 불필요한 짧은 문구 제거 (선택적)
                    clone.select("p").forEach { p ->
                        val text = p.text().trim()
                        if (text.startsWith("[뉴스클레임]") && text.length < 50) {
                            p.remove()
                        }
                        // 투자판단 참고용 알림 문구 제거
                        if (text.contains("본 기사는 투자판단의 참고용") ||
                            text.contains("투자손실에 대한 책임은 없습니다")) {
                            p.remove()
                        }
                    }

                    clone.select("p:empty, p:matches(^\\s*$)").remove()

                    return clone
                }

                // 2차 fallback: .article-veiw-body (본문 컨테이너)
                doc.selectFirst(".article-veiw-body")?.let { body ->
                    val clone = body.clone()
                    clone.select(
                        "ins.adsbygoogle, .ad-template, article.relation, .article-copy, " +
                                ".writer, .tag-group, .article-view-sns, .naver_stand, " +
                                ".box-skin, #comment, script, style, noscript"
                    ).remove()
                    return clone
                }

                null
            }

            else -> null
        }
    }

    /**
     * 범용 셀렉터로 본문 선택
     */
    private fun selectByGenericSelector(doc: Document): Element? {
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

    /**
     * 본문 Element에서 텍스트/이미지 블록 추출
     */
    fun extractContentBlocks(element: Element): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()

        // 노이즈 제거
        removeNoiseElements(element)

        val textBuffer = StringBuilder()

        fun flushText() {
            val cleaned = textBuffer.toString()
                .replace('\u00A0', ' ')
                .replace(Regex("[ \t]+"), " ")
                .replace(Regex(" ?\n ?"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .lines().joinToString("\n") { it.trim() }
                .trim()

            if (cleaned.isNotEmpty() && !isMenuText(cleaned)) {
                blocks.add(ContentBlock.Text(cleaned))
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
        return filterNoiseBlocks(blocks)
    }

    /**
     * 노이즈 요소 제거
     */
    private fun removeNoiseElements(element: Element) {
        val noiseSelectors = listOf(
            "header", "footer", "nav", "aside", "script", "style", "iframe", "noscript",
            ".sidebar", ".menu", ".gnb", ".lnb", ".snb",
            ".top_menu", ".bottom_info", ".footer_info",
            ".header-sitemap-wrap", ".header-search-more-wrap",
            ".header-bottom", ".nav-thispage",
            ".sns", ".share", ".article_social", ".social_group", ".utility",
            ".share-btns-wrap", ".share-btns-wrap-top",
            ".news-info-and-share", ".news-info-top-3news-wrap",
            ".btn-facebook1", ".btn-twitter1", ".btn-share1",
            ".ads", ".banner", ".ad_area", ".ad_wrap", ".ad_container",
            ".ad-article-top", "[class*='ad-']", "[id*='ad_']", "[id*='dablewidget']",
            "[class*='swiper']", ".bkn-list",
            ".reply", ".comment", ".article_bottom", ".copyright", ".byline", ".reporter",
            ".nis-reporter-name",
            ".view-copyright", ".view-editors",
            ".article-copyright", ".news-copyright", ".news_copyright",
            ".view-reporter", ".view_reporter", ".reporter-info", ".reporter_info",
            ".article_tags", ".recommend_news", ".popular_news",
            ".rec-keywords", ".related-news", ".rnmc-relative-news",
            ".relative-news-title-wrap",
            ".rnmc-left", ".prime-msg", ".read-news-row1",
            ".empty-rnmc", ".foot_notice",
            "#dealsite_ci", ".dealsite_ci", ".top_logo",
            ".journalist_view_more", ".dateFont", ".btnSocial", ".btnPrint",
            ".daum-banner", ".news-comment", ".news-aside",
            ".reporter_wrap", ".reporter_info", ".rel_news", ".view_sns", ".view_util",
            ".copy-noti", ".s-tit", ".section_3", ".bt-area",
            ".cont_right", ".box_timenews", ".new_news_list", ".new_news_list_ttl",
            ".section_top_view", ".floating",
            ".article_footer", ".article_hot", ".paging_news",
            ".related_news", ".article_foot", ".art_etc",
            "[class*='adsbyaiinad']", "[class*='adsbygoogle']",
            "ins[class*='adsbygoogle']",            // Google AdSense <ins> 태그
            "[id*='sub_banner']", "[class*='thumbAd']", "[class*='view_banner']",
            "[class*='sub_banner']",                // ksg 배너 영역
            "[id*='banner_base_']", "[id*='banner_contents_']", // datanews.co.kr 배너
            ".mimg_img",  // g-enews 등 이미지 팝업 확대용 숨김 div (display:none 이지만 Jsoup은 무시)
            // koreatimes.com 인아티클 슬라이드 광고 배너
            ".web-inside-ad", ".article_slick",
            ".bn_468x60_1", ".bn_468x60_2"
        )
        noiseSelectors.forEach { element.select(it).remove() }
    }

    /**
     * 메뉴성 텍스트 판별
     */
    private fun isMenuText(text: String): Boolean {
        return text.length < 60 && text.contains(Regex(
            "페이스북|트위터|카카오톡|로그인|회원가입|섹션|뉴스랭킹|포럼|전체메뉴" +
                    "|오피니언|URL복사|스크랩|키워드알림|구독한|인쇄|글자크기" +
                    "|이 기사는.+유료콘텐츠|딜사이트 플러스|ⓒ|Copyright"
        ))
    }

    /**
     * 광고 이미지 판별
     */
    private fun isAdImage(src: String, img: Element): Boolean {
        val adPatterns = listOf(
            "doubleclick", "googlesyndication", "adnxs", "moatads",
            "adsystem", "adservice", "google-analytics", "googletagmanager",
            "facebook.com/tr", "naver.com/ad", "nbad.naver",
            "beacon", "tracker", "1x1", "pixel.gif", "pixel.png",
            "banner_click.php"  // cnbnews.com 배너 경유 URL
        )
        if (adPatterns.any { src.contains(it, ignoreCase = true) }) return true

        val w = img.attr("width").toIntOrNull() ?: 0
        val h = img.attr("height").toIntOrNull() ?: 0
        return (w in 1..30) || (h in 1..30)
    }

    /**
     * 추출된 블록에서 노이즈 필터링
     */
    private fun filterNoiseBlocks(blocks: List<ContentBlock>): List<ContentBlock> {
        val seenImageUrls = mutableSetOf<String>()

        return blocks.filter { block ->
            when (block) {
                is ContentBlock.Image -> {
                    // 동일 URL 이미지 중복 제거 (썸네일+본문 이미지 반복 방지)
                    seenImageUrls.add(block.url)
                }
                is ContentBlock.Text -> {
                    val content = block.content.trim()
                    val isTooShortMenu = content.length <= 10 &&
                            content.contains(Regex("로그인|회원가입|뉴스랭킹|오피니언|전체메뉴|인쇄|공유"))
                    val isCopyright = content.contains(Regex("ⓒ|무단전재|재배포.?금지|All Rights Reserved")) &&
                            content.length < 120
                    val isBylineOnly = content.matches(Regex("""^[가-힣]{2,5}\s*기자$"""))
                    val isBiztribuneByline = content.matches(Regex("""^\[비즈트리뷴=.{2,10}\s*기자\]$"""))
                    // ksg.co.kr 기자 서명: "< 홍길동 기자 email@ksg.co.kr >" 형식
                    val isKsgByline = content.contains("기자") &&
                            content.contains("@ksg.co.kr") &&
                            content.length < 60
                    !isTooShortMenu && !isCopyright && !isBylineOnly && !isBiztribuneByline && !isKsgByline
                }
            }
        }
    }

    /**
     * MTN 동영상 기사 감지
     */
    fun isMtnVideoArticle(doc: Document, url: String): Boolean {
        return url.contains("mtn.co.kr") &&
                (doc.selectFirst("video, iframe[src*='youtube'], iframe[src*='mtn'], .vod_wrap, #vod_area, .video_area") != null
                        || doc.title().contains("동영상") || doc.title().contains("VOD"))
    }
}