package me.hebin.focus.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.databinding.ActivityCollectionBinding

/** 图鉴页：101 卡网格 + 收集进度 */
class CollectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = CollectionRepository.get(this)
        val collected = repo.collectedCount()

        binding.textProgress.text = "已收集 $collected / ${CardCatalog.TOTAL}"
        binding.progressCollection.max = CardCatalog.TOTAL
        binding.progressCollection.progress = collected
        binding.textOwnedTotal.text = "持有卡片 ${repo.totalCardsOwned()} 张（含各稀有度与重复）"

        binding.recyclerCards.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerCards.adapter = CardGridAdapter(repo)

        binding.btnBack.setOnClickListener { finish() }
    }
}
