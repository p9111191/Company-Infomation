package com.example.companyinfo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 계열집단 목록 RecyclerView 어댑터
 */
class GroupListAdapter(
    private var groups: List<ChaebulGroup>,
    private val onItemClick: (ChaebulGroup) -> Unit
) : RecyclerView.Adapter<GroupListAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRank: TextView         = view.findViewById(R.id.tvGroupRank)
        val tvGroupName: TextView    = view.findViewById(R.id.tvGroupName)
        val tvOwner: TextView        = view.findViewById(R.id.tvOwnerName)
        val tvCompanyCount: TextView = view.findViewById(R.id.tvCompanyCount)
        val tvAssets: TextView       = view.findViewById(R.id.tvTotalAssets)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]

        holder.tvRank.text         = group.rank.toString()
        holder.tvGroupName.text    = group.groupName
        holder.tvOwner.text        = group.ownerName
        holder.tvCompanyCount.text = "${group.companyCount}개"

        // 자산총액: 엑셀 값은 십억원 단위 → 1,000으로 나누면 조원
        // 예) 9,978.0 십억 ÷ 1,000 = 9.978 → "9.98조원"
        val jo = group.totalAssets / 1000.0
        holder.tvAssets.text = "${"%.2f".format(jo)}조원"

        holder.itemView.setOnClickListener { onItemClick(group) }
    }

    override fun getItemCount() = groups.size

    fun updateData(newGroups: List<ChaebulGroup>) {
        groups = newGroups
        notifyDataSetChanged()
    }
}