package com.example.companyinfo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 기업 리스트 RecyclerView 어댑터
 */
class CompanyAdapter(
    private val companies: List<Company>,
    private val onItemClick: (Company) -> Unit
) : RecyclerView.Adapter<CompanyAdapter.CompanyViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompanyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_company, parent, false)
        return CompanyViewHolder(view)
    }

    override fun onBindViewHolder(holder: CompanyViewHolder, position: Int) {
        val company = companies[position]
        holder.bind(company, onItemClick)
    }

    override fun getItemCount(): Int = companies.size

    /**
     * ViewHolder
     */
    class CompanyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.companyName)
        private val ceoTextView: TextView = itemView.findViewById(R.id.companyCeo)
        private val creditRatingTextView: TextView = itemView.findViewById(R.id.creditRating)
        private val revenueTextView: TextView = itemView.findViewById(R.id.revenue)
        private val totalAssetsTextView: TextView = itemView.findViewById(R.id.totalAssets)

        fun bind(company: Company, onItemClick: (Company) -> Unit) {
            nameTextView.text = company.name
            ceoTextView.text = "대표: ${company.ceo}"
            creditRatingTextView.text = "신용등급: ${company.creditRating}"
            revenueTextView.text = "매출액: ${formatAmount(company.revenue)}원"
            totalAssetsTextView.text = "자산총계: ${formatAmount(company.totalAssets)}원"

            itemView.setOnClickListener {
                onItemClick(company)
            }
        }

        /**
         * 금액 포맷팅 (백만원 -> 억원/조원)
         */
        private fun formatAmount(amount: Long): String {
            return when {
                amount >= 1000000 -> String.format("%.1f조", amount / 1000000.0)
                amount >= 10000 -> String.format("%.0f억", amount / 10000.0)
                else -> String.format("%,d백만", amount)
            }
        }
    }
}
