package me.hebin.focus.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.databinding.ActivityCollectionBinding

/**
 * 图鉴页：101 卡网格 + 收集进度 + 筛选。
 * 筛选维度：收集状态（全部/未收集/已收集）× 类别（5 类）。
 * 有筛选时副标题切换为筛选结果统计，总进度保持可见。
 */
class CollectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionBinding
    private lateinit var adapter: CardGridAdapter

    /** 筛选状态 */
    private var filterCollectedOnly = false
    private var filterMissingOnly = false
    private var filterCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = CollectionRepository.get(this)
        val collected = repo.collectedCount()

        adapter = CardGridAdapter(repo)
        binding.recyclerCards.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerCards.adapter = adapter

        binding.textProgress.text = "已收集 $collected / ${CardCatalog.TOTAL}"
        binding.progressCollection.max = CardCatalog.TOTAL
        binding.progressCollection.progress = collected

        binding.btnBack.setOnClickListener { finish() }

        buildStatusChips()
        buildCategoryChips()
        applyFilter()
    }

    /** 收集状态：全部 / 未收集 / 已收集 */
    private fun buildStatusChips() {
        val options = listOf(
            "全部" to (false to false),
            "未收集" to (true to false),
            "已收集" to (false to true)
        )
        options.forEachIndexed { i, (label, state) ->
            val chip = makeChip(label, i == 0)
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    filterMissingOnly = state.first
                    filterCollectedOnly = state.second
                    applyFilter()
                }
            }
            binding.chipStatus.addView(chip)
        }
    }

    /** 类别：全部 + 图鉴 5 类 */
    private fun buildCategoryChips() {
        val cats = listOf<String?>(null) + listOf(
            CardCatalog.CAT_MAMMAL, CardCatalog.CAT_BIRD, CardCatalog.CAT_OCEAN,
            CardCatalog.CAT_REPTILE, CardCatalog.CAT_INSECT
        )
        cats.forEachIndexed { i, c ->
            val chip = makeChip(if (c == null) "全部类别" else "${c}类", i == 0)
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    filterCategory = c
                    applyFilter()
                }
            }
            binding.chipCategory.addView(chip)
        }
    }

    private fun makeChip(label: String, checked: Boolean): Chip =
        Chip(this).apply {
            text = label
            isCheckable = true
            id = View.generateViewId()
            isChecked = checked
        }

    /** 该编号是否已收集（持有任意稀有度任意张） */
    private fun isOwned(id: Int): Boolean =
        CollectionRepository.get(this).ownedCounts(id).values.sum() > 0

    /** 应用筛选：更新网格 + 副标题统计 + 空态 */
    private fun applyFilter() {
        val repo = CollectionRepository.get(this)

        val result = (1..CardCatalog.TOTAL).filter { id ->
            val statusOk = (!filterCollectedOnly || isOwned(id)) &&
                (!filterMissingOnly || !isOwned(id))
            val catOk = filterCategory == null || CardCatalog.categoryOfId(id) == filterCategory
            statusOk && catOk
        }
        adapter.submit(result)

        val hasFilter = filterCollectedOnly || filterMissingOnly || filterCategory != null
        binding.textOwnedTotal.text = if (!hasFilter) {
            "持有卡片 ${repo.totalCardsOwned()} 张（含各稀有度与重复）"
        } else {
            val cat = filterCategory?.let { "${it}类 · " } ?: ""
            val ownedInResult = result.count { isOwned(it) }
            when {
                filterMissingOnly -> "${cat}未收集：${result.size} 张"
                filterCollectedOnly -> "${cat}已收集：$ownedInResult 张"
                else -> "${cat}共 ${result.size} 张（已收集 $ownedInResult）"
            }
        }

        binding.recyclerCards.isVisible = result.isNotEmpty()
        binding.textFilterEmpty.isVisible = result.isEmpty()
    }
}
