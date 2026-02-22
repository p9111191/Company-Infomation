package com.example.companyinfo

import android.content.Context
import android.net.Uri

/**
 * CompanyRepository
 *
 * - loadFromExcel() 로 xlsx를 읽어 메모리에 캐시합니다.
 * - 캐시가 없을 경우 assets/company_info.xlsx 를 기본값으로 사용합니다.
 * - 기존 코드가 사용하는 getAllCompanies() / getCompanyByName() 인터페이스를 유지합니다.
 */
object CompanyRepository {

    private var cachedCompanies: List<Company> = emptyList()
    private var isLoaded = false

    // ── 외부 Excel 파일 로드 (MainActivity 파일 피커에서 호출) ──────────────

    fun loadFromUri(context: Context, uri: Uri): Result<List<Company>> {
        val result = ExcelReader(context).readFromUri(uri)
        result.onSuccess { list ->
            cachedCompanies = list
            isLoaded = true
        }
        return result
    }

    // ── assets 기본 파일 로드 ──────────────────────────────────────────────

    fun loadFromAssets(context: Context, fileName: String = "company_info.xlsx"): Result<List<Company>> {
        val result = ExcelReader(context).readFromAssets(fileName)
        result.onSuccess { list ->
            cachedCompanies = list
            isLoaded = true
        }
        return result
    }

    // ── 기존 인터페이스 유지 ──────────────────────────────────────────────

    fun getAllCompanies(): List<Company> = cachedCompanies

    fun getCompanyByName(name: String): Company? =
        cachedCompanies.find { it.name == name }

    fun isDataLoaded(): Boolean = isLoaded

    fun clear() {
        cachedCompanies = emptyList()
        isLoaded = false
    }

    // ── 디버그용 ──────────────────────────────────────────────────────────

    fun printAllCompanyNames() {
        cachedCompanies.forEach { println("등록된 기업: '${it.name}'") }
    }
}
