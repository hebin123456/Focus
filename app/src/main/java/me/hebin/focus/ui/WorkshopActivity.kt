package me.hebin.focus.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.hebin.focus.R
import me.hebin.focus.data.Achievements
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.CraftEngine
import me.hebin.focus.data.DropEngine
import me.hebin.focus.data.Rarity
import me.hebin.focus.databinding.ActivityWorkshopBinding
import me.hebin.focus.databinding.ItemCraftCardBinding
import me.hebin.focus.ui.view.CardView

/**
 * 卡片工坊：分解 / 合成。
 *  - 分解：选 1 张卡（普卡除外）→ 随机得 3 张低一级稀有度卡
 *  - 合成：选 3 张同稀有度卡（可不同编号，同卡可叠加）→ 随机得 1 张高一级稀有度卡
 * 结果完全随机，无法定向凑卡。
 */
class WorkshopActivity : AppCompatActivity() {

    private enum class Mode { DECOMPOSE, SYNTHESIZE }

    private lateinit var binding: ActivityWorkshopBinding
    private lateinit var adapter: CraftAdapter
    private var mode = Mode.DECOMPOSE

    /** 持有槽位（编号, 稀有度, 数量） */
    private var slots: List<Triple<Int, Rarity, Int>> = emptyList()

    /** 选中状态：槽位下标 -> 张数（合成模式下同槽可多张） */
    private val selected = LinkedHashMap<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkshopBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CraftAdapter()
        binding.recyclerCraft.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerCraft.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == R.id.btnTabDecompose) Mode.DECOMPOSE else Mode.SYNTHESIZE
            reload()
        }
        binding.toggleMode.check(R.id.btnTabDecompose)

        binding.btnCraftAction.setOnClickListener { performCraft() }
    }

    private fun reload() {
        val repo = CollectionRepository.get(this)
        slots = when (mode) {
            Mode.DECOMPOSE -> repo.ownedSlots().filter { it.second != Rarity.COMMON }
            Mode.SYNTHESIZE -> repo.ownedSlots()
        }
        selected.clear()
        adapter.notifyDataSetChanged()

        binding.recyclerCraft.isVisible = slots.isNotEmpty()
        binding.textCraftEmpty.isVisible = slots.isEmpty()
        binding.textCraftEmpty.text = when (mode) {
            Mode.DECOMPOSE ->
                if (repo.totalCardsOwned() > 0) "没有可分解的卡片\n只有铜卡及以上可以分解"
                else "还没有卡片\n先去专注集卡吧"
            Mode.SYNTHESIZE -> "还没有卡片\n先去专注集卡吧"
        }
        binding.textCraftHint.text = when (mode) {
            Mode.DECOMPOSE -> "选择 1 张卡片分解，随机获得 3 张低一级稀有度的卡片（普卡不可分解；只剩 1 张的卡会被保护，不可用）"
            Mode.SYNTHESIZE -> "选 3 张同稀有度卡片（可不同编号，重复点同张卡可叠加）合成 1 张更高稀有度的卡片；3 张钻石合成随机钻石卡；只剩 1 张的卡会被保护，不可用"
        }
        refreshBottomBar()
    }

    private fun onSlotTap(position: Int) {
        val (id, rarity, owned) = slots[position]
        // 保护最后一张：仅剩 1 张的卡不允许分解 / 合成（避免把收藏搞没）
        if (owned <= 1) {
            Toast.makeText(
                this,
                "${CardCatalog.displayName(id)} · ${rarity.label} 只剩 1 张，已保护，不能分解或合成",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        when (mode) {
            Mode.DECOMPOSE -> {
                selected.clear()
                selected[position] = 1
            }

            Mode.SYNTHESIZE -> {
                val cur = selected[position] ?: 0
                val total = selected.values.sum()
                if (cur == 0 && total >= 3) {
                    Toast.makeText(this, "一次只能放入 3 张", Toast.LENGTH_SHORT).show()
                    return
                }
                if (cur > 0 && (total >= 3 || cur >= owned)) {
                    // 已达上限，再点一次清空该槽
                    selected.remove(position)
                } else if (cur > 0) {
                    selected[position] = cur + 1
                } else {
                    val first = selected.entries.firstOrNull()
                    if (first != null && slots[first.key].second != rarity) {
                        Toast.makeText(this, "只能选择同一种稀有度", Toast.LENGTH_SHORT).show()
                        return
                    }
                    selected[position] = 1
                }
            }
        }
        adapter.notifyDataSetChanged()
        refreshBottomBar()
    }

    private fun refreshBottomBar() {
        when (mode) {
            Mode.DECOMPOSE -> {
                binding.btnCraftAction.text = "分解"
                val sel = selected.entries.firstOrNull()
                if (sel == null) {
                    binding.textCraftSummary.text = "从上方选择 1 张要分解的卡片"
                    binding.btnCraftAction.isEnabled = false
                } else {
                    val (id, r, _) = slots[sel.key]
                    val lower = CraftEngine.decomposeTarget(r) ?: return
                    binding.textCraftSummary.text =
                        "分解 ${CardCatalog.displayName(id)} · ${r.label}\n→ 随机获得 3 张${lower.label}"
                    binding.btnCraftAction.isEnabled = true
                }
            }

            Mode.SYNTHESIZE -> {
                binding.btnCraftAction.text = "合成"
                val total = selected.values.sum()
                if (total == 0) {
                    binding.textCraftSummary.text = "从上方选择 3 张同稀有度卡片"
                    binding.btnCraftAction.isEnabled = false
                } else {
                    val r = slots[selected.keys.first()].second
                    val target = CraftEngine.synthesizeTarget(r)
                    val targetText =
                        if (r == Rarity.DIAMOND) "随机一张钻石卡（重抽压轴卡）"
                        else "1 张随机${target.label}"
                    binding.textCraftSummary.text =
                        "已选 $total / 3 张${r.label}\n→ $targetText"
                    binding.btnCraftAction.isEnabled = total == 3
                }
            }
        }
    }

    private fun performCraft() {
        val repo = CollectionRepository.get(this)
        when (mode) {
            Mode.DECOMPOSE -> {
                val sel = selected.entries.firstOrNull() ?: return
                val (id, r, _) = slots[sel.key]
                val lower = CraftEngine.decomposeTarget(r) ?: return
                AlertDialog.Builder(this)
                    .setTitle("确认分解")
                    .setMessage(
                        "将分解 ${CardCatalog.displayName(id)} · ${r.label}，" +
                            "随机获得 3 张${lower.label}。分解不可撤销，确定吗？"
                    )
                    .setPositiveButton("分解") { d, _ ->
                        d.dismiss()
                        val result = CraftEngine.decompose(repo, id, r)
                        if (result == null) {
                            Toast.makeText(this, "分解失败：卡片已不在背包", Toast.LENGTH_SHORT).show()
                        } else {
                            showResult("分解成功", result)
                        }
                        reload()
                    }
                    .setNegativeButton("再想想", null)
                    .show()
            }

            Mode.SYNTHESIZE -> {
                if (selected.values.sum() != 3) return
                val picks = selected.flatMap { (pos, n) ->
                    List(n) { slots[pos].first to slots[pos].second }
                }
                val r = picks.first().second
                val targetText =
                    if (r == Rarity.DIAMOND) "随机一张钻石卡" else "1 张随机${CraftEngine.synthesizeTarget(r).label}"
                AlertDialog.Builder(this)
                    .setTitle("确认合成")
                    .setMessage("将消耗 3 张${r.label}，获得 $targetText。合成不可撤销，确定吗？")
                    .setPositiveButton("合成") { d, _ ->
                        d.dismiss()
                        val result = CraftEngine.synthesize(repo, picks)
                        if (result == null) {
                            Toast.makeText(this, "合成失败：卡片数量不足", Toast.LENGTH_SHORT).show()
                        } else {
                            showResult("合成成功", listOf(result))
                        }
                        reload()
                    }
                    .setNegativeButton("再想想", null)
                    .show()
            }
        }
    }

    /** 工坊结果弹窗 + 顺带检查成就（合成/分解可能补齐稀有度图鉴） */
    private fun showResult(title: String, drops: List<DropEngine.Drop>) {
        Achievements.checkNew(CollectionRepository.get(this)).forEach {
            Toast.makeText(this, "成就解锁：${it.title} +${it.points} 点", Toast.LENGTH_SHORT).show()
        }

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        drops.forEach { d ->
            row.addView(CardView(this).apply {
                mode = CardView.Mode.FACE
                rarity = d.rarity
                cardNumber = d.cardId
                locked = false
                layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(10)
                }
            })
        }
        root.addView(row)
        root.addView(TextView(this).apply {
            text = drops.joinToString("、") { "${CardCatalog.displayName(it.cardId)} · ${it.rarity.label}" }
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@WorkshopActivity, R.color.textSecondary))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("收下") { d, _ -> d.dismiss() }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 适配器 ----------------

    private inner class CraftAdapter : RecyclerView.Adapter<CraftAdapter.VH>() {

        inner class VH(val b: ItemCraftCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCraftCardBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = slots.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (id, rarity, owned) = slots[position]
            val b = holder.b
            b.gridCard.mode = CardView.Mode.FACE
            b.gridCard.rarity = rarity
            b.gridCard.cardNumber = id
            b.gridCard.locked = false

            b.badgeCount.isVisible = owned > 1
            b.badgeCount.text = "×$owned"

            val sel = selected[position] ?: 0
            b.checkWrap.isVisible = sel > 0
            b.badgeSel.isVisible = sel > 1
            b.badgeSel.text = "选×$sel"

            b.root.setOnClickListener { onSlotTap(holder.bindingAdapterPosition) }
        }
    }
}
