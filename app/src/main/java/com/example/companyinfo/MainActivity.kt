package com.example.companyinfo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 메인 액티비티 - 기업 리스트 화면
 *
 * 변경 사항:
 *  - 하드코딩 데이터 제거 → xlsx 파일 로드
 *  - 옵션 메뉴: 엑셀 파일 열기 / 검색 / 새로고침 / 도움말
 *  - 마지막 선택 파일 URI SharedPreferences 에 저장 (앱 재시작 시 자동 로드)
 *  - assets/company_info.xlsx 가 있으면 최초 실행 기본 데이터로 사용
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: CompanyAdapter

    /** 전체 목록 보관 (검색 필터용) */
    private var allCompanies: List<Company> = emptyList()

    private val prefs by lazy { getSharedPreferences("companyinfo_prefs", MODE_PRIVATE) }
    private val KEY_URI = "last_file_uri"

    // ── 파일 피커 ──────────────────────────────────────────────────────────

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // 재시작 후에도 접근 가능하도록 영구 권한 획득
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                prefs.edit().putString(KEY_URI, uri.toString()).apply()
                loadFromUri(uri)
            }
        }
    }

    // ── 생명주기 ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "기업 정보 검색"

        recyclerView = findViewById(R.id.recyclerView)
        progressBar  = findViewById(R.id.progressBar)
        emptyView    = findViewById(R.id.emptyView)

        adapter = CompanyAdapter(emptyList()) { company ->
            Log.d("MainActivity", "클릭된 기업: ${company.name}")
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("companyName", company.name)   // 기존 방식 유지
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 마지막 파일 자동 로드
        val savedUri = prefs.getString(KEY_URI, null)
        if (savedUri != null) {
            loadFromUri(Uri.parse(savedUri))
        } else {
            // assets 기본 파일 시도 → 없으면 안내
            loadDefaultAssets()
        }
    }

    // ── 옵션 메뉴 ───────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        // as? 로 안전하게 캐스팅 (예외 방지)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? androidx.appcompat.widget.SearchView
        searchView?.queryHint = "기업명 / 업종 검색..."
        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                filterCompanies(q ?: ""); return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_open_excel -> { openFilePicker(); true }
        R.id.action_refresh    -> {
            val uri = prefs.getString(KEY_URI, null)
            if (uri != null) loadFromUri(Uri.parse(uri))
            else Toast.makeText(this, "먼저 엑셀 파일을 선택해주세요.", Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_help -> { showHelp(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── 내부 함수 ───────────────────────────────────────────────────────────

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
            ))
        }
        filePickerLauncher.launch(intent)
    }

    private fun loadFromUri(uri: Uri) {
        showLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            val result = CompanyRepository.loadFromUri(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                showLoading(false)
                result.onSuccess { companies ->
                    Log.d("MainActivity", "로드된 기업 수: ${companies.size}")
                    applyCompanies(companies)
                    if (companies.isNotEmpty())
                        Toast.makeText(this@MainActivity,
                            "${companies.size}개 기업 정보를 불러왔습니다.", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Log.e("MainActivity", "파일 오류: ${e.message}")
                    showError("파일 읽기 오류", e.message ?: "알 수 없는 오류")
                }
            }
        }
    }

    private fun loadDefaultAssets() {
        CoroutineScope(Dispatchers.IO).launch {
            val result = CompanyRepository.loadFromAssets(this@MainActivity, "company_info.xlsx")
            withContext(Dispatchers.Main) {
                result.onSuccess { companies ->
                    applyCompanies(companies)
                }.onFailure {
                    // 기본 파일 없음 → 안내 화면
                    setEmptyView("📂 엑셀 파일을 선택해주세요.\n\n우측 상단 메뉴(⋮) → 엑셀 파일 열기")
                }
            }
        }
    }

    private fun applyCompanies(companies: List<Company>) {
        allCompanies = companies
        adapter = CompanyAdapter(companies) { company ->
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("companyName", company.name)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
        updateEmptyState(companies.isEmpty())
    }

    private fun filterCompanies(query: String) {
        val filtered = if (query.isBlank()) allCompanies
        else allCompanies.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.industry.contains(query, ignoreCase = true) ||
                    it.ceo.contains(query, ignoreCase = true)
        }
        adapter = CompanyAdapter(filtered) { company ->
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("companyName", company.name)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
        updateEmptyState(filtered.isEmpty())
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            recyclerView.visibility = View.GONE
            emptyView.visibility    = View.GONE
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) setEmptyView("검색 결과가 없습니다.")
        else {
            emptyView.visibility    = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun setEmptyView(msg: String) {
        emptyView.text       = msg
        emptyView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun showError(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$msg\n\n엑셀 파일 형식을 확인하거나 도움말을 참고해주세요.")
            .setPositiveButton("확인", null)
            .setNeutralButton("도움말") { _, _ -> showHelp() }
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("📋 엑셀 파일 작성 가이드")
            .setMessage("""
                ■ 파일 형식: .xlsx (Excel 2007 이상)
                
                ■ 필수 시트 구성
                  1. 기업기본정보
                     기업명 / 사업자번호 / 대표자 / 설립일 / 업종
                     종업원수 / 기업유형 / 기업규모 / 주소 / 전화번호
                     신용등급 / 상장여부(Y/N) / 종목코드
                     자산총계(백만) / 부채총계(백만) / 자본총계(백만)
                     매출액(백만) / 영업이익(백만) / 당기순이익(백만)
                     부채비율(%) / 영업이익률(%) / ROE(%)
                     총차입금(백만) / 은행차입금(백만) / 비은행차입금(백만)
                  
                  2. 주주정보
                     기업명 | 주주명 | 지분율(%)
                  
                  3. 차입금정보
                     기업명 | 금융기관명 | 구분(은행/비은행)
                     합계 | 대출 | 유가증권 | 보증  (백만원 단위)
                  
                  4. 뉴스정보
                     기업명 | 뉴스제목 | 날짜(YYYY-MM-DD) | 내용요약
                
                ■ 공통 주의사항
                  - 1행 헤더는 수정 금지
                  - 기업명은 모든 시트에서 완전히 동일하게 입력
                  - 숫자 필드에 단위 텍스트 입력 금지
                
                ■ 사용법
                  메뉴(⋮) → 엑셀 파일 열기 → 파일 선택
                  이후 새로고침으로 재로드 가능
            """.trimIndent())
            .setPositiveButton("확인", null)
            .show()
    }
}
