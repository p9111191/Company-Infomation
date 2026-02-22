package com.example.companyinfo

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ExcelReader
 *
 * xlsx 파일에서 기업 정보를 읽어 List<Company> 를 반환합니다.
 * 기존 Company / Shareholder / LoanInfo / NewsItem 데이터 클래스를 그대로 사용합니다.
 *
 * ■ 엑셀 시트 구조
 *   1. 기업기본정보 (1행: 헤더, 2행~: 데이터)
 *      기업명 | 사업자번호 | 대표자 | 설립일 | 업종 | 종업원수 | 기업유형 | 기업규모 |
 *      주소 | 전화번호 | 신용등급 | 상장여부(Y/N) | 종목코드 |
 *      자산총계(백만) | 부채총계(백만) | 자본총계(백만) |
 *      매출액(백만) | 영업이익(백만) | 당기순이익(백만) |
 *      부채비율(%) | 영업이익률(%) | ROE(%) |
 *      총차입금(백만) | 은행차입금(백만) | 비은행차입금(백만)
 *
 *   2. 주주정보    : 기업명 | 주주명 | 지분율(%)
 *   3. 차입금정보  : 기업명 | 금융기관명 | 구분(은행/비은행) | 합계 | 대출 | 유가증권 | 보증
 *   4. 뉴스정보    : 기업명 | 뉴스제목 | 날짜(YYYY-MM-DD) | 내용요약
 */
class ExcelReader(private val context: Context) {

    /** Uri(파일 피커)로부터 로드 */
    fun readFromUri(uri: Uri): Result<List<Company>> = runCatching {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("파일을 열 수 없습니다.")
        parseExcel(stream)
    }

    /** assets 폴더의 기본 샘플 파일 로드 */
    fun readFromAssets(fileName: String): Result<List<Company>> = runCatching {
        parseExcel(context.assets.open(fileName))
    }

    // ── 파싱 메인 ──────────────────────────────────────────────────────────

    private fun parseExcel(stream: InputStream): List<Company> {
        val wb = WorkbookFactory.create(stream)

        // ── 1. 기업기본정보 ──
        val sheet1 = wb.getSheet("기업기본정보") ?: wb.getSheetAt(0)
        ?: error("'기업기본정보' 시트가 없습니다.")

        // 헤더 → 컬럼 인덱스 맵
        val header = sheet1.getRow(0) ?: error("헤더 행이 없습니다.")
        val col = mutableMapOf<String, Int>()
        header.forEach { col[it.str().trim()] = it.columnIndex }

        fun idx(vararg keys: String) = keys.map { col[it] ?: -1 }.firstOrNull { it >= 0 } ?: -1

        val basics = mutableListOf<Company>()
        for (ri in 1..sheet1.lastRowNum) {
            val row = sheet1.getRow(ri) ?: continue
            val name = row.str(idx("기업명"))
            if (name.isBlank()) continue

            val isListed = row.str(idx("상장여부(Y/N)", "상장여부")).uppercase() == "Y"
            val stockCode = row.str(idx("종목코드")).takeIf { it.isNotBlank() }

            basics.add(
                Company(
                    name            = name,
                    businessNumber  = row.str(idx("사업자번호")),
                    ceo             = row.str(idx("대표자")),
                    address         = row.str(idx("주소")),
                    phone           = row.str(idx("전화번호")),
                    industry        = row.str(idx("업종")),
                    employees       = row.num(idx("종업원수")).toInt(),
                    creditRating    = row.str(idx("신용등급")),
                    foundedDate     = row.str(idx("설립일")),
                    companyType     = row.str(idx("기업유형")),
                    companySize     = row.str(idx("기업규모")),
                    isListed        = isListed,
                    stockCode       = stockCode,
                    totalAssets     = row.num(idx("자산총계(백만)", "자산총계")).toLong(),
                    totalLiabilities= row.num(idx("부채총계(백만)", "부채총계")).toLong(),
                    totalEquity     = row.num(idx("자본총계(백만)", "자본총계")).toLong(),
                    revenue         = row.num(idx("매출액(백만)", "매출액")).toLong(),
                    operatingProfit = row.num(idx("영업이익(백만)", "영업이익")).toLong(),
                    netIncome       = row.num(idx("당기순이익(백만)", "당기순이익")).toLong(),
                    debtRatio       = row.num(idx("부채비율(%)", "부채비율")),
                    operatingProfitMargin = row.num(idx("영업이익률(%)", "영업이익률")),
                    roe             = row.num(idx("ROE(%)", "ROE")),
                    totalLoans      = row.num(idx("총차입금(백만)", "총차입금")).toLong(),
                    bankLoans       = row.num(idx("은행차입금(백만)", "은행차입금")).toLong(),
                    nonBankLoans    = row.num(idx("비은행차입금(백만)", "비은행차입금")).toLong(),
                    // 연결 데이터는 아래에서 병합
                    majorShareholders = emptyList(),
                    loanDetails     = emptyList(),
                    newsItems       = emptyList()
                )
            )
        }

        // ── 2. 주주정보 ──
        val shMap = mutableMapOf<String, MutableList<Shareholder>>()
        wb.getSheet("주주정보")?.let { sh ->
            for (ri in 1..sh.lastRowNum) {
                val row = sh.getRow(ri) ?: continue
                val co   = row.str(0); val nm = row.str(1)
                if (co.isBlank() || nm.isBlank()) continue
                shMap.getOrPut(co) { mutableListOf() }
                    .add(Shareholder(nm, row.num(2)))
            }
        }

        // ── 3. 차입금정보 ──
        val lnMap = mutableMapOf<String, MutableList<LoanInfo>>()
        wb.getSheet("차입금정보")?.let { sh ->
            for (ri in 1..sh.lastRowNum) {
                val row = sh.getRow(ri) ?: continue
                val co  = row.str(0); val inst = row.str(1)
                if (co.isBlank() || inst.isBlank()) continue
                lnMap.getOrPut(co) { mutableListOf() }.add(
                    LoanInfo(
                        institution     = inst,
                        institutionType = row.str(2),
                        totalAmount     = row.num(3).toLong(),
                        loanAmount      = row.num(4).toLong(),
                        securities      = row.num(5).toLong(),
                        guarantee       = row.num(6).toLong()
                    )
                )
            }
        }

        // ── 4. 뉴스정보 ──
        val nwMap = mutableMapOf<String, MutableList<NewsItem>>()
        wb.getSheet("뉴스정보")?.let { sh ->
            for (ri in 1..sh.lastRowNum) {
                val row = sh.getRow(ri) ?: continue
                val co  = row.str(0); val title = row.str(1)
                if (co.isBlank() || title.isBlank()) continue
                nwMap.getOrPut(co) { mutableListOf() }.add(
                    NewsItem(title, row.str(2), row.str(3))
                )
            }
        }

        wb.close()

        // ── 5. 병합 ──
        return basics.map { co ->
            co.copy(
                majorShareholders = shMap[co.name] ?: emptyList(),
                loanDetails       = lnMap[co.name] ?: emptyList(),
                newsItems         = nwMap[co.name] ?: emptyList()
            )
        }
    }

    // ── 셀 읽기 헬퍼 확장함수 ──────────────────────────────────────────────

    private fun Cell.str(): String = when (cellType) {
        CellType.STRING  -> stringCellValue.trim()
        CellType.NUMERIC -> {
            if (DateUtil.isCellDateFormatted(this)) {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(dateCellValue as Date)
            } else {
                val d = numericCellValue
                if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
            }
        }
        CellType.BOOLEAN -> booleanCellValue.toString()
        CellType.FORMULA -> runCatching { stringCellValue.trim() }
            .getOrElse { runCatching { numericCellValue.toString() }.getOrDefault("") }
        else -> ""
    }

    private fun Row.str(colIdx: Int): String {
        if (colIdx < 0) return ""
        return getCell(colIdx)?.str() ?: ""
    }

    private fun Row.num(colIdx: Int): Double {
        if (colIdx < 0) return 0.0
        val cell = getCell(colIdx) ?: return 0.0
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING  -> cell.stringCellValue.replace(",", "").trim().toDoubleOrNull() ?: 0.0
            CellType.FORMULA -> runCatching { cell.numericCellValue }.getOrDefault(0.0)
            else -> 0.0
        }
    }
}
