package com.example.companyinfo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 기업 리스트 RecyclerView 어댑터
 *
 * 변경 사항:
 *  - 2행 레이아웃: 1행(기업명·대표자·사업자번호·신용등급), 2행(주소)
 *  - 기업명에서 (주), 주식회사 등 기업형태 문자 제거 (cleanName 으로 통합)
 *  - 신용등급 대문자 표시
 *  - 어댑터 생성 시 기업명 가나다 오름차순 정렬
 */
class CompanyAdapter(
    companies: List<Company>,
    private val onItemClick: (Company) -> Unit
) : RecyclerView.Adapter<CompanyAdapter.CompanyViewHolder>() {

    // 기업형태 제거 후 가나다 오름차순 정렬
    private val companies = companies.sortedBy { cleanName(it.name) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompanyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_company, parent, false)
        return CompanyViewHolder(view)
    }

    override fun onBindViewHolder(holder: CompanyViewHolder, position: Int) {
        holder.bind(companies[position], onItemClick)
    }

    override fun getItemCount(): Int = companies.size

    // ── Companion: 기업명 정리 (정렬 + 화면 표시 공용) ────────────────────
    companion object {
        /**
         * 기업명에서 기업형태 표기 제거
         * 예) (주)롯데건설 → 롯데건설 / 롯데건설(주) → 롯데건설
         *     주식회사 한화 → 한화 / 한화에어로스페이스(주) → 한화에어로스페이스
         */
        fun cleanName(name: String): String {
            val patterns = listOf(
                Regex("^\\(주\\)\\s*"),        Regex("\\s*\\(주\\)$"),
                Regex("^\\(유\\)\\s*"),        Regex("\\s*\\(유\\)$"),
                Regex("^\\(합\\)\\s*"),        Regex("\\s*\\(합\\)$"),
                Regex("^\\(합자\\)\\s*"),      Regex("\\s*\\(합자\\)$"),
                Regex("^주식회사\\s*"),         Regex("\\s*주식회사$"),
                Regex("^유한회사\\s*"),         Regex("\\s*유한회사$"),
                Regex("^합명회사\\s*"),         Regex("\\s*합명회사$"),
                Regex("^합자회사\\s*"),         Regex("\\s*합자회사$"),
                Regex("^유한책임회사\\s*"),     Regex("\\s*유한책임회사$")
            )
            var result = name.trim()
            for (p in patterns) result = result.replace(p, "")
            return result.trim()
        }
    }

    // ── ViewHolder ─────────────────────────────────────────────────────────
    class CompanyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameTextView:         TextView = itemView.findViewById(R.id.companyName)
        private val ceoTextView:          TextView = itemView.findViewById(R.id.companyCeo)
        private val businessNumberView:   TextView = itemView.findViewById(R.id.businessNumber)
        private val creditRatingTextView: TextView = itemView.findViewById(R.id.creditRating)
        private val addressTextView:      TextView = itemView.findViewById(R.id.address)

        fun bind(company: Company, onItemClick: (Company) -> Unit) {
            nameTextView.text         = cleanName(company.name)   // companion 의 cleanName 재사용
            ceoTextView.text          = company.ceo
            businessNumberView.text   = company.businessNumber
            creditRatingTextView.text = company.creditRating.uppercase()
            addressTextView.text      = company.address

            itemView.setOnClickListener { onItemClick(company) }
        }
    }
}