package com.example.companyinfo

import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * 그룹 지배구조도 PDF 뷰어 액티비티
 *
 * assets/group/{그룹명}.pdf 파일을 로드하여 표시
 * PDF.js (로컬 assets/pdfjs)를 사용하여 웹뷰에서 오프라인 PDF 렌더링
 *
 * ── 수정 이력 ──────────────────────────────────────────────────────────────
 * [FIX 1] loadWithPdfJs(CDN) → loadWithLocalPdfJs(로컬) 로 변경 (오프라인 지원)
 * [FIX 2] WebView에 allowUniversalAccessFromFileURLs / allowFileAccessFromFileURLs 추가
 *         (file:// ↔ file:// 간 크로스 오리진 차단 해제)
 * [FIX 3] viewer 경로를 assets 폴더 구조에 맞게 수정
 *         (pdfjs/web/viewer.html)
 * [FIX 4] 상세 로그 및 WebView Console 로그 추가 (원인 추적용)
 * [FIX 5] assets 파일 목록 로그 추가 (파일 존재 여부 사전 확인)
 * [FIX 6] PDF 90도 회전 지원 — PDF.js pagesRotation API JS 주입
 *         가로 방향 PDF를 세로 화면에서 읽기 편하게 회전
 */
class GroupStructureActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var pdfFileName: String
    private var rotationApplied = false  // 회전+스케일 최초 1회만 실행

    companion object {
        const val EXTRA_GROUP_NAME = "group_name"
        private const val TAG = "GroupStructureAct"

        /**
         * [FIX 6] PDF 회전 각도 설정
         * 가로 방향 PDF를 세로로 읽기 위해 90도 회전
         * 값: 0 | 90 | 180 | 270
         */
        private const val PDF_ROTATION_DEGREES = 90
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_structure)

        pdfFileName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: run {
            Log.e(TAG, "EXTRA_GROUP_NAME 이 전달되지 않았습니다")
            Toast.makeText(this, "그룹명이 없습니다", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "onCreate: pdfFileName = $pdfFileName")

        // ── assets 구조 덤프 (디버그용) ──────────────────────────────────────
        logAssetsStructure()

        supportActionBar?.apply {
            title = "$pdfFileName 그룹 지배구조"
            setDisplayHomeAsUpEnabled(true)
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        loadPdfFromAssets()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── WebView 설정 ─────────────────────────────────────────────────────────

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true

            // [FIX 2] file:// 간 크로스 오리진 허용 → PDF.js가 로컬 PDF를 읽을 수 있게 함
            @Suppress("SetJavaScriptEnabled")
            allowFileAccessFromFileURLs = true
            @Suppress("SetJavaScriptEnabled")
            allowUniversalAccessFromFileURLs = true

            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // [FIX 4] WebView 콘솔 로그를 Logcat으로 리다이렉트
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val level = when (it.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        ConsoleMessage.MessageLevel.WARNING -> "WARN "
                        else -> "INFO "
                    }
                    Log.d("$TAG/JS", "[$level] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                Log.d(TAG, "페이지 로딩 진행률: $newProgress%")
                progressBar.progress = newProgress
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                Log.d(TAG, "URL 로딩 요청: ${request?.url}")
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "페이지 로딩 시작: $url")
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "페이지 로딩 완료: $url")
                progressBar.visibility = View.GONE

                // [FIX 6] viewer.html 최초 로드 시에만 PDF 회전 적용 (재진입 방지)
                if (url?.contains("viewer.html") == true && !rotationApplied) {
                    rotationApplied = true
                    injectPdfRotation(view)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WebView 오류: code=${error?.errorCode}, desc=${error?.description}, url=${request?.url}")
                // 메인 프레임 오류일 때만 에러 UI 표시
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    showError("페이지 로딩 실패 (code: ${error?.errorCode})")
                }
            }
        }

        Log.d(TAG, "WebView 설정 완료")
    }

    // ── PDF 회전 ─────────────────────────────────────────────────────────────

    /**
     * [FIX 6] PDF.js의 pagesRotation API를 JS로 주입하여 PDF 회전
     *
     * PDF.js viewer는 PDFViewerApplication 전역 객체를 제공하며,
     * initializedPromise 완료 후 pdfViewer.pagesRotation 을 설정하면
     * 모든 페이지가 지정 각도로 회전됨.
     *
     * 주의: onPageFinished 는 HTML 파싱 완료 시점이므로 PDF 렌더링이
     *       아직 진행 중일 수 있음 → polling 으로 pdfDocument 로드 대기
     */
    private fun injectPdfRotation(view: WebView?) {
        val degrees = PDF_ROTATION_DEGREES
        // [주의] Kotlin 트리플쿼트 내에서 $degrees 를 직접 보간 → JS에 숫자(90)로 삽입됨
        // ${'$'}{degrees} 방식은 JS에 미해석 "${degrees}" 문자열로 들어가 SyntaxError 발생
        val js = "(function(){" +
                "function tryRotate(r){" +
                "if(r<=0){console.log('[Rotation] timeout');return;}" +
                "if(typeof PDFViewerApplication!=='undefined'&&" +
                "PDFViewerApplication.pdfViewer&&" +
                "PDFViewerApplication.pdfDocument){" +
                "PDFViewerApplication.pdfViewer.pagesRotation=$degrees;" +
                "console.log('[Rotation] done');" +
                // CSS: 여백 제거
                "(function(){" +
                "var s=document.createElement('style');" +
                "s.textContent='#viewerContainer{padding:0!important;margin:0!important;top:0!important;}#viewer{margin:0!important;}.page,.pdfViewer .page{margin:0!important;border:none!important;box-shadow:none!important;}';" +
                "document.head.appendChild(s);" +
                "})();" +
                // 회전 후 150ms 뒤 page-height 스케일 1회만 설정, 이후 폴링 완전 종료
                "setTimeout(function(){" +
                "PDFViewerApplication.pdfViewer.currentScaleValue='page-height';" +
                "setTimeout(function(){" +
                "var c=document.getElementById('viewerContainer');" +
                "if(c){c.scrollLeft=c.scrollWidth;c.scrollTop=0;}" +
                "},300);" +
                "},150);" +
                "return;" +  // 성공 즉시 폴링 종료
                "}else{setTimeout(function(){tryRotate(r-1);},300);}" +
                "}" +
                "tryRotate(40);" +
                "})();"

        Log.d(TAG, "[FIX 6] PDF 회전 JS 주입: ${degrees}도 / JS snippet: $js")
        view?.evaluateJavascript(js, null)
    }

    // ── PDF 로드 ─────────────────────────────────────────────────────────────

    /**
     * assets/group/{그룹명}.pdf → 캐시 복사 → 로컬 PDF.js 뷰어로 로드
     */
    private fun loadPdfFromAssets() {
        val assetPath = "group/$pdfFileName.pdf"
        Log.d(TAG, "PDF 로드 시작: assets/$assetPath")

        try {
            // 1. assets 파일 존재 확인
            val assetFiles = assets.list("group") ?: emptyArray()
            Log.d(TAG, "assets/group/ 내 파일 목록: ${assetFiles.toList()}")

            if (!assetFiles.contains("$pdfFileName.pdf")) {
                Log.e(TAG, "파일 없음: assets/$assetPath (목록: ${assetFiles.toList()})")
                showError("PDF 파일을 찾을 수 없습니다: $pdfFileName.pdf\n(assets/group/ 폴더 확인 필요)")
                return
            }

            // 2. assets에서 캐시로 복사
            val cacheFile = File(cacheDir, "$pdfFileName.pdf")
            assets.open(assetPath).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "캐시 복사 완료: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")

            // 3. [FIX 1] 로컬 PDF.js로 로드 (오프라인)
            loadWithLocalPdfJs("file://${cacheFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "PDF 로드 실패: $assetPath", e)
            progressBar.visibility = View.GONE
            showError("PDF 로드 오류: ${e.message}")
        }
    }

    /**
     * [FIX 3] 로컬 PDF.js 뷰어로 로드 (오프라인 완전 지원)
     *
     * assets 폴더 구조 기준:
     *   assets/pdfjs/web/viewer.html   ← 뷰어 HTML
     *   assets/pdfjs/build/pdf.mjs     ← PDF.js 코어
     *
     * viewer.html 에서 file:// PDF를 열려면 allowUniversalAccessFromFileURLs=true 필수
     */
    private fun loadWithLocalPdfJs(pdfUrl: String) {
        // [FIX 3] pdfjs/web/viewer.html 경로 (assets 구조 이미지 참고)
        val encodedPdfUrl = URLEncoder.encode(pdfUrl, "UTF-8")
        val viewerUrl = "file:///android_asset/pdfjs/web/viewer.html?file=$encodedPdfUrl"

        // PDF.js가 localStorage에 저장한 이전 줌/스크롤 상태 초기화
        // 이 호출이 없으면 마지막 크기/위치로 복원되어 초기 설정이 무시됨
        android.webkit.WebStorage.getInstance().deleteAllData()
        Log.d(TAG, "WebStorage 초기화 완료")
        Log.d(TAG, "LocalPdfJs 로드: $viewerUrl")
        Log.d(TAG, "  └─ PDF URL: $pdfUrl")

        // pdfjs viewer.html 파일 존재 여부 확인
        val pdfJsFiles = assets.list("pdfjs/web") ?: emptyArray()
        Log.d(TAG, "assets/pdfjs/web/ 파일 목록: ${pdfJsFiles.toList()}")

        if (!pdfJsFiles.contains("viewer.html")) {
            Log.e(TAG, "viewer.html 없음 — assets/pdfjs/web/ 경로 확인 필요")
            showError("PDF 뷰어 파일이 없습니다\nassets/pdfjs/web/viewer.html 확인 필요")
            return
        }

        webView.loadUrl(viewerUrl)
    }

    // ── 미사용 메서드 (참고용 보존) ──────────────────────────────────────────

    /** CDN PDF.js — 온라인 환경에서만 동작 (오프라인 불가) */
    @Suppress("unused")
    private fun loadWithPdfJs(pdfUrl: String) {
        val encodedUrl = URLEncoder.encode(pdfUrl, "UTF-8")
        val viewerUrl = "https://mozilla.github.io/pdf.js/web/viewer.html?file=$encodedUrl"
        Log.d(TAG, "[CDN] loadWithPdfJs: $viewerUrl")
        webView.loadUrl(viewerUrl)
    }

    /** Google Docs Viewer — 인터넷 필요, 로컬 file:// URL 지원 안 됨 */
    @Suppress("unused")
    private fun loadWithGoogleDocs(pdfFile: File) {
        val encodedPath = URLEncoder.encode("file://${pdfFile.absolutePath}", "UTF-8")
        val viewerUrl = "https://docs.google.com/gview?embedded=1&url=$encodedPath"
        Log.d(TAG, "[GoogleDocs] $viewerUrl")
        webView.loadUrl(viewerUrl)
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private fun showError(message: String) {
        Log.e(TAG, "showError: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        progressBar.visibility = View.GONE

        val html = """
            <html>
            <body style="display:flex;justify-content:center;align-items:center;
                         height:100vh;margin:0;font-family:sans-serif;background:#f5f5f5;">
                <div style="text-align:center;color:#666;padding:24px;">
                    <div style="font-size:48px;">📄</div>
                    <h3 style="color:#333;margin:16px 0 8px;">PDF를 불러올 수 없습니다</h3>
                    <p style="font-size:14px;line-height:1.6;">${message.replace("\n", "<br>")}</p>
                    <p style="font-size:11px;color:#999;margin-top:16px;">
                        경로: assets/group/$pdfFileName.pdf
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
        webView.loadData(html, "text/html", "UTF-8")
    }

    /**
     * [FIX 4] assets 폴더 구조 전체를 Logcat에 출력 (디버그용)
     */
    private fun logAssetsStructure() {
        Log.d(TAG, "── assets 폴더 구조 ──────────────────")
        for (folder in listOf("group", "pdfjs", "pdfjs/build", "pdfjs/web")) {
            try {
                val files = assets.list(folder) ?: emptyArray()
                Log.d(TAG, "assets/$folder/ → ${files.toList()}")
            } catch (e: Exception) {
                Log.w(TAG, "assets/$folder/ 접근 실패: ${e.message}")
            }
        }
        Log.d(TAG, "────────────────────────────────────")
    }

    // ── 생명주기 ─────────────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
        try {
            val deleted = File(cacheDir, "$pdfFileName.pdf").delete()
            Log.d(TAG, "임시 파일 삭제: $deleted ($pdfFileName.pdf)")
        } catch (e: Exception) {
            Log.w(TAG, "임시 파일 삭제 실패", e)
        }
    }
}