package com.example.companyinfo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log

/**
 * 메인 액티비티 - 기업 리스트 화면
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CompanyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 툴바 설정
        supportActionBar?.title = "기업 정보"

        // RecyclerView 초기화
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 데이터 로드 및 어댑터 설정
        val companies = CompanyRepository.getAllCompanies()
        Log.d("Main__Activity", "로드된 기업 수: ${companies.size}")
        companies.forEach {
            Log.d("Main__Activity", "기업: ${it.name}")
        }

        adapter = CompanyAdapter(companies) { company ->
            Log.d("Main__Activity", "클릭된 기업: ${company.name}")
            // 아이템 클릭 시 상세 화면으로 이동 (기업명만 전달)
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("companyName", company.name)
            startActivity(intent)
        }

        recyclerView.adapter = adapter
    }
}
