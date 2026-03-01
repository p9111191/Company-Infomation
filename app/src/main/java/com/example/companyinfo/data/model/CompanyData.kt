package com.example.companyinfo.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CompanyData(
    val id: String,
    val name: String,
    val businessNumber: String,
    val ceo: String,
    val foundedDate: String,
    val industry: String,
    val employees: Int,
    val companyType: String,
    val companySize: String,
    val address: String,
    val phone: String,
    val creditRating: String,
    val isListed: Boolean = false,
    val revenue: Long,
    val totalAssets: Long
) : Parcelable {

    fun getFormattedRevenue(): String = "%,d억원".format(revenue / 100)
    fun getFormattedAssets(): String = "%,d억원".format(totalAssets / 100)
    fun getFormattedEmployees(): String = "%,d명".format(employees)
}