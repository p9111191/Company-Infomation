package com.example.companyinfo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 메인 액티비티
 *
 * 탭 구성:
 *  - [0] 개별기업 : 기존 기업 리스트 (기본값)
 *  - [1] 계열집단 : 계열집단 리스트 → 클릭 시 GroupStructureActivity
 */
class MainActivity : AppCompatActivity() {

    // ── 공통 뷰 ─────────────────────────────────────────────────────────────
    private lateinit var tabLayout: TabLayout
    private lateinit var toolbar: Toolbar
    private lateinit var progressBar: ProgressBar

    // ── 개별기업 탭 뷰 ───────────────────────────────────────────────────────
    private lateinit var companyContainer: LinearLayout
    private lateinit var recyclerViewCompany: RecyclerView
    private lateinit var emptyViewCompany: TextView
    private lateinit var headerViewCompany: LinearLayout
    private lateinit var companyAdapter: CompanyAdapter

    // ── 계열집단 탭 뷰 ───────────────────────────────────────────────────────
    private lateinit var groupContainer: LinearLayout
    private lateinit var recyclerViewGroup: RecyclerView
    private lateinit var emptyViewGroup: TextView
    private lateinit var headerViewGroup: LinearLayout
    private lateinit var groupAdapter: GroupListAdapter

    // ── 데이터 ───────────────────────────────────────────────────────────────
    private var allCompanies: List<Company> = emptyList()
    private var allGroups: List<ChaebulGroup> = emptyList()

    private val prefs by lazy { getSharedPreferences("companyinfo_prefs", MODE_PRIVATE) }
    private val KEY_URI = "last_file_uri"

    // ── 파일 피커 ────────────────────────────────────────────────────────────

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                prefs.edit().putString(KEY_URI, uri.toString()).apply()
                loadAllFromUri(uri)
            }
        }
    }

    // ── 생명주기 ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 시스템 ActionBar 대신 커스텀 Toolbar 사용 (타이틀 없이 아이콘만)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""
        invalidateOptionsMenu()

        // 공통
        tabLayout    = findViewById(R.id.tabLayout)
        progressBar  = findViewById(R.id.progressBar)

        // 개별기업 탭
        companyContainer  = findViewById(R.id.companyContainer)
        recyclerViewCompany = findViewById(R.id.recyclerView)
        emptyViewCompany  = findViewById(R.id.emptyView)
        headerViewCompany = findViewById(R.id.listHeader)

        // 계열집단 탭
        groupContainer  = findViewById(R.id.groupContainer)
        recyclerViewGroup = findViewById(R.id.recyclerViewGroup)
        emptyViewGroup  = findViewById(R.id.emptyViewGroup)
        headerViewGroup = findViewById(R.id.listHeaderGroup)

        // 어댑터 초기화
        companyAdapter = CompanyAdapter(emptyList()) { company ->
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("companyName", company.name)
            startActivity(intent)
        }
        recyclerViewCompany.layoutManager = LinearLayoutManager(this)
        recyclerViewCompany.adapter = companyAdapter

        groupAdapter = GroupListAdapter(emptyList()) { group ->
            openGroupStructure(group)
        }
        recyclerViewGroup.layoutManager = LinearLayoutManager(this)
        recyclerViewGroup.adapter = groupAdapter

        // 탭 선택 리스너
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showCompanyTab()
                    1 -> showGroupTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // 기본 탭: 개별기업(0)
        showCompanyTab()

        // 데이터 로드
        val savedUri = prefs.getString(KEY_URI, null)
        if (savedUri != null) {
            loadAllFromUri(Uri.parse(savedUri))
        } else {
            loadDefaultAssets()
        }
    }

    // ── 탭 전환 ──────────────────────────────────────────────────────────────

    private fun showCompanyTab() {
        companyContainer.visibility = View.VISIBLE
        groupContainer.visibility   = View.GONE
    }

    private fun showGroupTab() {
        companyContainer.visibility = View.GONE
        groupContainer.visibility   = View.VISIBLE
    }

    // ── 옵션 메뉴 ─────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView

        searchView?.apply {
            queryHint = "기업명 / 업종 검색..."
            maxWidth = Integer.MAX_VALUE

            // SearchView 확장/축소 리스너 설정
            setOnSearchClickListener {
                // 검색창 열릴 때: TabLayout 숨기고 Toolbar 전체 폭 사용
                expandSearchView()
            }

            setOnCloseListener {
                // 검색창 닫힐 때: 원래 상태로 복원
                collapseSearchView()
                false
            }

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = false
                override fun onQueryTextChange(q: String?): Boolean {
                    when (tabLayout.selectedTabPosition) {
                        0 -> filterCompanies(q ?: "")
                        1 -> filterGroups(q ?: "")
                    }
                    return true
                }
            })
        }

        // SearchView의 닫기 버튼 클릭도 처리
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                expandSearchView()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                collapseSearchView()
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_open_excel -> { openFilePicker(); true }
        R.id.action_refresh    -> {
            val uri = prefs.getString(KEY_URI, null)
            if (uri != null) loadAllFromUri(Uri.parse(uri))
            else Toast.makeText(this, "먼저 엑셀 파일을 선택해주세요.", Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_help -> { showHelp(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── SearchView 확장/축소 처리 ─────────────────────────────────────────────

    private fun expandSearchView() {
        // TabLayout 숨기고 Toolbar가 전체 폭 차지하도록
        tabLayout.visibility = View.GONE

        // Toolbar의 layout_weight를 0으로 설정하고 width를 match_parent로
        val toolbarParams = toolbar.layoutParams as LinearLayout.LayoutParams
        toolbarParams.weight = 0f
        toolbarParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        toolbar.layoutParams = toolbarParams

        // 부모 레이아웃 다시 그리기
        toolbar.parent.requestLayout()
    }

    private fun collapseSearchView() {
        // 원래 상태로 복원: TabLayout 보이고 Toolbar는 30% 폭
        tabLayout.visibility = View.VISIBLE

        val toolbarParams = toolbar.layoutParams as LinearLayout.LayoutParams
        toolbarParams.weight = 3f
        toolbarParams.width = 0
        toolbar.layoutParams = toolbarParams

        // 부모 레이아웃 다시 그리기
        toolbar.parent.requestLayout()

        // 검색어 초기화 및 전체 목록 복원
        filterCompanies("")
        filterGroups("")
    }

    // ── 데이터 로드 ───────────────────────────────────────────────────────────

    /** URI에서 기업 + 계열집단 동시 로드 */
    private fun loadAllFromUri(uri: Uri) {
        showLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            val companyResult = CompanyRepository.loadFromUri(this@MainActivity, uri)
            val groupResult   = runCatching {
                ExcelReader(this@MainActivity).readGroupsFromUri(uri).getOrThrow()
            }
            withContext(Dispatchers.Main) {
                showLoading(false)
                companyResult.onSuccess { companies ->
                    Log.d("MainActivity", "로드된 기업 수: ${companies.size}")
                    applyCompanies(companies)
                    if (companies.isNotEmpty())
                        Toast.makeText(this@MainActivity,
                            "${companies.size}개 기업 정보를 불러왔습니다.", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Log.e("MainActivity", "파일 오류: ${e.message}")
                    showError("파일 읽기 오류", e.message ?: "알 수 없는 오류")
                }
                groupResult.onSuccess { groups ->
                    Log.d("MainActivity", "로드된 계열집단 수: ${groups.size}")
                    applyGroups(groups)
                }.onFailure { e ->
                    Log.w("MainActivity", "계열집단 로드 실패: ${e.message}")
                }
            }
        }
    }

    private fun loadDefaultAssets() {
        CoroutineScope(Dispatchers.IO).launch {
            val companyResult = CompanyRepository.loadFromAssets(this@MainActivity, "company_info.xlsx")
            val groupResult   = runCatching {
                ExcelReader(this@MainActivity).readGroupsFromAssets("company_info.xlsx").getOrThrow()
            }
            withContext(Dispatchers.Main) {
                companyResult.onSuccess { companies ->
                    applyCompanies(companies)
                }.onFailure {
                    setEmptyViewCompany("📂 엑셀 파일을 선택해주세요.\n\n우측 상단 메뉴(⋮) → 엑셀 파일 열기")
                }
                groupResult.onSuccess { groups ->
                    applyGroups(groups)
                }.onFailure {
                    setEmptyViewGroup("📂 엑셀 파일을 선택해주세요.")
                }
            }
        }
    }

    // ── 데이터 적용 ───────────────────────────────────────────────────────────

    private fun applyCompanies(companies: List<Company>) {
        allCompanies = companies
        companyAdapter = CompanyAdapter(companies) { company ->
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("companyName", company.name)
            startActivity(intent)
        }
        recyclerViewCompany.adapter = companyAdapter
        updateEmptyStateCompany(companies.isEmpty())
    }

    private fun applyGroups(groups: List<ChaebulGroup>) {
        allGroups = groups
        groupAdapter.updateData(groups)
        updateEmptyStateGroup(groups.isEmpty())
    }

    private fun filterCompanies(query: String) {
        val filtered = if (query.isBlank()) allCompanies
        else allCompanies.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.industry.contains(query, ignoreCase = true) ||
                    it.ceo.contains(query, ignoreCase = true)
        }
        companyAdapter = CompanyAdapter(filtered) { company ->
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("companyName", company.name)
            startActivity(intent)
        }
        recyclerViewCompany.adapter = companyAdapter
        updateEmptyStateCompany(filtered.isEmpty())
    }

    private fun filterGroups(query: String) {
        val filtered = if (query.isBlank()) allGroups
        else allGroups.filter {
            it.groupName.contains(query, ignoreCase = true) ||
                    it.ownerName.contains(query, ignoreCase = true)
        }
        groupAdapter.updateData(filtered)
        updateEmptyStateGroup(filtered.isEmpty())
    }

    // ── 계열집단 → 지배구조도 이동 ───────────────────────────────────────────

    private fun openGroupStructure(group: ChaebulGroup) {
        // 기존 GroupStructureActivity는 EXTRA_GROUP_NAME(String)만 받아
        // assets/group/{그룹명}.pdf 를 로컬 PDF.js로 표시합니다.
        val intent = Intent(this, GroupStructureActivity::class.java)
        intent.putExtra(GroupStructureActivity.EXTRA_GROUP_NAME, group.groupName)
        startActivity(intent)
    }

    // ── UI 상태 헬퍼 ─────────────────────────────────────────────────────────

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            recyclerViewCompany.visibility = View.GONE
            emptyViewCompany.visibility    = View.GONE
            recyclerViewGroup.visibility   = View.GONE
            emptyViewGroup.visibility      = View.GONE
        }
    }

    private fun updateEmptyStateCompany(isEmpty: Boolean) {
        if (isEmpty) setEmptyViewCompany("검색 결과가 없습니다.")
        else {
            emptyViewCompany.visibility    = View.GONE
            recyclerViewCompany.visibility = View.VISIBLE
        }
    }

    private fun updateEmptyStateGroup(isEmpty: Boolean) {
        if (isEmpty) setEmptyViewGroup("계열집단 데이터가 없습니다.")
        else {
            emptyViewGroup.visibility    = View.GONE
            recyclerViewGroup.visibility = View.VISIBLE
        }
    }

    private fun setEmptyViewCompany(msg: String) {
        emptyViewCompany.text       = msg
        emptyViewCompany.visibility = View.VISIBLE
        recyclerViewCompany.visibility = View.GONE
    }

    private fun setEmptyViewGroup(msg: String) {
        emptyViewGroup.text       = msg
        emptyViewGroup.visibility = View.VISIBLE
        recyclerViewGroup.visibility = View.GONE
    }

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
                     기업명 / 소속그룹 / 사업자번호 / 대표자 / 설립일 / 업종
                     종업원수 / 기업유형 / 기업규모 / 주소 / 전화번호
                     신용등급 / 상장여부(Y/N) / 종목코드
                     총차입금(백만) / 은행차입금(백만) / 비은행차입금(백만)
                  
                  2. 재무정보
                     기업명 | 구분 | 연도 | 총자산(백만) | ... (생략)
                  
                  3. 주주정보
                     기업명 | 주주명 | 지분율(%)
                  
                  4. 차입금정보
                     기업명 | 금융기관명 | 구분(은행/비은행) | 합계 | 대출 | 유가증권 | 보증
                  
                  5. 계열집단
                     1행: 대헤더 (순위/기업집단명/동일인/계열회사수/공정자산총액)
                     2행: 소헤더 (연도 표시 — 수정 금지)
                     3행~: A=순위, D=기업집단명, E=동일인, F=계열회사수, H=공정자산총액
                
                ■ 공통 주의사항
                  - 소속그룹 필드는 계열집단의 기업집단명과 동일하게 입력
                  - 1행 헤더는 수정 금지
                  - 숫자 필드에 단위 텍스트 입력 금지
                
                ■ 사용법
                  메뉴(⋮) → 엑셀 파일 열기 → 파일 선택
                  이후 새로고침으로 재로드 가능
            """.trimIndent())
            .setPositiveButton("확인", null)
            .show()
    }
}