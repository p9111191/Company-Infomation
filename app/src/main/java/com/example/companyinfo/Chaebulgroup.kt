package com.example.companyinfo

import java.io.Serializable

/**
 * 계열집단 데이터 모델
 *
 * Excel 시트 "계열집단" 컬럼 매핑 (3행부터 데이터):
 *   A열(col 0) → rank         순위(2025년)
 *   D열(col 3) → groupName    기업집단명
 *   E열(col 4) → ownerName    동일인
 *   F열(col 5) → companyCount 계열회사수(2025년)
 *   H열(col 7) → totalAssets  공정자산총액(2025년, 십억원)
 */
data class ChaebulGroup(
    val rank: Int,               // 순위
    val groupName: String,       // 기업집단명
    val ownerName: String,       // 동일인
    val companyCount: Int,       // 계열회사수
    val totalAssets: Double      // 공정자산총액 (십억원)
) : Serializable