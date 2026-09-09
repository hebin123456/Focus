package me.hebin.focus.ui

import android.animation.ObjectAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import me.hebin.focus.R
import me.hebin.focus.data.Achievements
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CardDef
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.CraftEngine
import me.hebin.focus.data.DropEngine
import me.hebin.focus.data.ItemCatalog
import me.hebin.focus.data.Rarity
import me.hebin.focus.data.ShopStore
import me.hebin.focus.databinding.ActivityWorkshopBinding
import me.hebin.focus.databinding.ItemCraftCardBinding
import me.hebin.focus.databinding.ItemPickCardBinding
import me.hebin.focus.ui.view.CardView
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 卡片工坊：分解 / 合成。
 *  - 分解：选 1 张卡（普卡除外）→ 随机得 3 张低一级稀有度卡
 *  - 合成：选任意 3 张卡（编号、稀有度都可不同，同卡可叠加）→ 随机得 1 张
 *          比其中最高稀有度更高一级的卡（3 张全钻石 = 重抽随机钻石卡）
 * 结果完全随机，无法定向凑卡。
 */
class WorkshopActivity : AppCompatActivity() {

    private enum class Mode { DECOMPOSE, SYNTHESIZE, CONVERT }

    private lateinit var binding: ActivityWorkshopBinding
    private lateinit var adapter: CraftAdapter
    private var mode = Mode.DECOMPOSE

    /** 持有槽位（编号, 稀有度, 数量） */
    private var slots: List<Triple<Int, Rarity, Int>> = emptyList()

    /** 筛选后的展示槽位 */
    private var shown: List<Triple<Int, Rarity, Int>> = emptyList()

    /** 筛选状态：null = 全部 */
    private var filterRarity: Rarity? = null
    private var filterCategory: String? = null

    /** 选中状态：槽位下标 -> 张数（合成模式下同槽可多张） */
    private val selected = LinkedHashMap<Int, Int>()

    /** 演出动画时间轴 */
    private val craftHandler = Handler(Looper.getMainLooper())

    /** 演出播放中（防重复触发） */
    private var animating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        binding = ActivityWorkshopBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.pad(binding.root)

        adapter = CraftAdapter()
        binding.recyclerCraft.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerCraft.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.btnTabDecompose -> Mode.DECOMPOSE
                R.id.btnTabSynthesize -> Mode.SYNTHESIZE
                else -> Mode.CONVERT
            }
            reload()
        }
        binding.toggleMode.check(R.id.btnTabDecompose)

        binding.btnCraftAction.setOnClickListener { performCraft() }
        binding.btnStoneAction.setOnClickListener { performStoneAction() }
        binding.btnConvertRandom.setOnClickListener { performConvertRandom() }
        binding.btnConvertPick.setOnClickListener { performConvertPick() }
        binding.btnUpgrade.setOnClickListener { performUpgrade() }
    }

    override fun onDestroy() {
        craftHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reload() {
        val repo = CollectionRepository.get(this)
        slots = when (mode) {
            Mode.DECOMPOSE -> repo.ownedSlots().filter { it.second != Rarity.COMMON }
            Mode.SYNTHESIZE, Mode.CONVERT -> repo.ownedSlots()
        }
        // 分解模式不涉及普卡，停在普卡筛选会一无所获，自动重置
        if (mode == Mode.DECOMPOSE && filterRarity == Rarity.COMMON) filterRarity = null
        selected.clear()
        applyFilter()
        refreshFilterChips()

        binding.textCraftHint.text = when (mode) {
            Mode.DECOMPOSE -> "选择 1 张卡片分解，随机获得 3 张低一级稀有度的卡片（普卡不可分解；只剩 1 张的卡会被保护，不可用）\n持有分解石可定向指定产物"
            Mode.SYNTHESIZE -> "选任意 3 张卡片合成：编号、稀有度都可以不同（重复点同张卡可叠加），产物是比其中最高稀有度更高一级的卡；3 张全钻石 = 重抽随机钻石卡；合成不限制最后一张（确认时会提示）\n持有合成石可定向指定目标"
            Mode.CONVERT -> "选 1 张卡片，用转换石换成随机的另一张同品质卡，或用超级转换石换成指定的同品质卡；升级石可把卡片升 1 级稀有度；只剩 1 张的卡会被保护，不可用"
        }
        refreshBottomBar()
    }

    /** 按筛选条件过滤展示槽位 */
    private fun applyFilter() {
        // shown 顺序随筛选变化，selected 存的是 shown 下标，筛选变化必须清空防止错位
        selected.clear()
        shown = slots.filter { (id, r, _) ->
            (filterRarity == null || r == filterRarity) &&
                (filterCategory == null || CardCatalog.categoryOfId(id) == filterCategory)
        }
        adapter.notifyDataSetChanged()
        binding.recyclerCraft.isVisible = shown.isNotEmpty()
        when {
            slots.isEmpty() -> {
                binding.textCraftEmpty.isVisible = true
                binding.textCraftEmpty.text = when (mode) {
                    Mode.DECOMPOSE ->
                        if (CollectionRepository.get(this).totalCardsOwned() > 0) "没有可分解的卡片\n只有铜卡及以上可以分解"
                        else "还没有卡片\n先去专注集卡吧"
                    Mode.SYNTHESIZE, Mode.CONVERT -> "还没有卡片\n先去专注集卡吧"
                }
            }

            shown.isEmpty() -> {
                binding.textCraftEmpty.isVisible = true
                binding.textCraftEmpty.text = "没有符合筛选的卡片\n换个条件试试"
            }

            else -> binding.textCraftEmpty.isVisible = false
        }
        refreshBottomBar()
    }

    /** 刷新筛选 Chip（稀有度 + 类别），仅在无筛选时重建避免打断选中态 */
    private fun refreshFilterChips() {
        val rareChips = binding.chipRarity
        val catChips = binding.chipCategory

        fun buildChips(
            group: ChipGroup,
            values: List<Any?>,
            cur: Any?,
            labelOf: (Any?) -> String,
            onPick: (Any?) -> Unit
        ) {
            group.removeAllViews()
            values.forEachIndexed { i, v ->
                val chip = Chip(this).apply {
                    text = labelOf(v)
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = i == 0 && cur == null
                }
                chip.setOnCheckedChangeListener { _, checked -> if (checked) onPick(v) }
                group.addView(chip)
            }
            // 当前有筛选时勾上对应项
            if (cur != null) {
                values.forEachIndexed { i, v ->
                    if (i > 0 && v == cur) group.check(group.getChildAt(i).id)
                }
            }
        }

        buildChips(
            rareChips,
            listOf(null) + Rarity.entries.toList(),
            filterRarity,
            { v -> (v as? Rarity)?.label ?: "全部稀有度" },
            { v -> filterRarity = v as? Rarity; applyFilter() }
        )

        val categories = slots.map { CardCatalog.categoryOfId(it.first) }.distinct()
        buildChips(
            catChips,
            listOf(null) + categories,
            filterCategory,
            { v -> if (v == null) "全部类别" else "${v}类" },
            { v -> filterCategory = v as? String; applyFilter() }
        )
    }

    private fun onSlotTap(position: Int) {
        if (position < 0 || position >= shown.size) return
        val (id, rarity, owned) = shown[position]
        // 保护最后一张：仅剩 1 张的卡不允许分解 / 转换（避免把收藏搞没）；
        // 合成放开限制——3 张不同卡各 1 张也能合成，确认弹窗会提示"最后一张"
        if (owned <= 1 && mode != Mode.SYNTHESIZE) {
            Toast.makeText(
                this,
                "${CardCatalog.displayName(id)} · ${rarity.label} 只剩 1 张，已保护，不能分解或转换",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        when (mode) {
            Mode.DECOMPOSE, Mode.CONVERT -> {
                // 分解 / 转换都是单选
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
                    // 任意编号 / 稀有度都可以混选（产物按最高稀有度升级）
                    selected[position] = 1
                }
            }
        }
        adapter.notifyDataSetChanged()
        refreshBottomBar()
    }

    /**
     * 合成确认提示：picks 中哪些（编号, 稀有度）会被消耗到最后一张。
     * 合成允许用单张卡，这里在确认弹窗里明确警示"图鉴将暂时失去它们"。
     */
    private fun lastCopyWarning(picks: List<Pair<Int, Rarity>>): String {
        val repo = CollectionRepository.get(this)
        val last = picks.groupBy({ it.first to it.second })
            .count { (slot, list) -> (repo.ownedCounts(slot.first)[slot.second] ?: 0) <= list.size }
        return if (last > 0)
            "\n⚠️ 其中有 $last 种卡会用到最后一张，合成后图鉴将暂时失去它们。"
        else ""
    }

    private fun refreshBottomBar() {
        val shop = ShopStore.get(this)
        when (mode) {
            Mode.DECOMPOSE -> {
                binding.btnCraftAction.isVisible = true
                binding.btnCraftAction.text = "分解"
                binding.rowConvertStones.isVisible = false
                val sel = selected.entries.firstOrNull()
                if (sel == null) {
                    binding.textCraftSummary.text = "从上方选择 1 张要分解的卡片"
                    binding.btnCraftAction.isEnabled = false
                } else {
                    val (id, r, _) = shown[sel.key]
                    val lower = CraftEngine.decomposeTarget(r) ?: return
                    binding.textCraftSummary.text =
                        "分解 ${CardCatalog.displayName(id)} · ${r.label}\n→ 随机获得 3 张${lower.label}"
                    binding.btnCraftAction.isEnabled = true
                }

                // 分解石：定向分解入口（持有才显示）
                val stones = shop.itemCount(ItemCatalog.ID_SPLIT)
                binding.btnStoneAction.isVisible = stones > 0
                if (stones > 0) {
                    binding.btnStoneAction.text = "定向分解 · 分解石 ×$stones"
                    binding.btnStoneAction.isEnabled = sel != null
                }
            }

            Mode.SYNTHESIZE -> {
                binding.btnCraftAction.isVisible = true
                binding.btnCraftAction.text = "合成"
                binding.rowConvertStones.isVisible = false
                val total = selected.values.sum()
                if (total == 0) {
                    binding.textCraftSummary.text = "从上方选择任意 3 张卡片（稀有度可以混搭）"
                    binding.btnCraftAction.isEnabled = false
                } else {
                    val r = CraftEngine.highestRarity(
                        selected.keys.map { shown[it].first to shown[it].second }
                    ) ?: Rarity.COMMON
                    val target = CraftEngine.synthesizeTarget(r)
                    val targetText =
                        if (r == Rarity.DIAMOND) "随机一张钻石卡（重抽压轴卡）"
                        else "1 张随机${target.label}"
                    binding.textCraftSummary.text =
                        "已选 $total / 3 张（最高 ${r.label}）\n→ $targetText"
                    binding.btnCraftAction.isEnabled = total == 3
                }

                // 合成石：定向合成入口（持有才显示）
                val stones = shop.itemCount(ItemCatalog.ID_FORGE)
                binding.btnStoneAction.isVisible = stones > 0
                if (stones > 0) {
                    binding.btnStoneAction.text = "定向合成 · 合成石 ×$stones"
                    binding.btnStoneAction.isEnabled = total == 3
                }
            }

            Mode.CONVERT -> {
                binding.btnCraftAction.isVisible = false
                binding.btnStoneAction.isVisible = false

                val sel = selected.entries.firstOrNull()
                val swap = shop.itemCount(ItemCatalog.ID_SWAP)
                val mega = shop.itemCount(ItemCatalog.ID_MEGA_SWAP)
                val up = shop.itemCount(ItemCatalog.ID_UPGRADE)
                binding.rowConvertStones.isVisible = swap > 0 || mega > 0 || up > 0
                binding.btnConvertRandom.isVisible = swap > 0
                binding.btnConvertPick.isVisible = mega > 0
                binding.btnUpgrade.isVisible = up > 0
                if (swap > 0) {
                    binding.btnConvertRandom.text = "随机转换 · ×$swap"
                    binding.btnConvertRandom.isEnabled = sel != null
                }
                if (mega > 0) {
                    binding.btnConvertPick.text = "定向转换 · ×$mega"
                    binding.btnConvertPick.isEnabled = sel != null
                }
                if (up > 0) {
                    binding.btnUpgrade.text = "升级 · 升级石 ×$up"
                    // 钻石卡不可升级；只剩 1 张的卡受保护
                    binding.btnUpgrade.isEnabled = sel != null &&
                        shown[sel.key].second != Rarity.DIAMOND
                }

                binding.textCraftSummary.text = when {
                    sel == null && swap == 0 && mega == 0 && up == 0 ->
                        "转换 = 同品质卡片互换\n升级石 = 卡片升 1 级稀有度\n道具可去商店购买"
                    sel == null -> "从上方选择 1 张要转换 / 升级的卡片"
                    else -> {
                        val (id, r, _) = shown[sel.key]
                        if (r == Rarity.DIAMOND) {
                            "已选 ${CardCatalog.displayName(id)} · 钻石\n钻石卡已是最高稀有度，不可升级，仅可转换"
                        } else {
                            val up2 = CraftEngine.upgradeTarget(r)!!
                            "已选 ${CardCatalog.displayName(id)} · ${r.label}\n可转换为同品质另一张，或升级为${up2.label}"
                        }
                    }
                }
            }
        }
    }

    private fun performCraft() {
        if (animating) return
        val repo = CollectionRepository.get(this)
        when (mode) {
            Mode.DECOMPOSE -> {
                val sel = selected.entries.firstOrNull() ?: return
                val (id, r, _) = shown[sel.key]
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
                            reload()
                        } else {
                            // 先刷新数据（覆盖层之下），再播放碎裂演出，结束后弹结果
                            reload()
                            playDecomposeAnim(id, r, result) {
                                showResult("分解成功", result)
                            }
                        }
                    }
                    .setNegativeButton("再想想", null)
                    .show()
            }

            Mode.SYNTHESIZE -> {
                if (selected.values.sum() != 3) return
                val picks = selected.flatMap { (pos, n) ->
                    List(n) { shown[pos].first to shown[pos].second }
                }
                val r = CraftEngine.highestRarity(picks) ?: return
                val targetText =
                    if (r == Rarity.DIAMOND) "随机一张钻石卡" else "1 张随机${CraftEngine.synthesizeTarget(r).label}"
                AlertDialog.Builder(this)
                    .setTitle("确认合成")
                    .setMessage(
                        "将消耗这 3 张卡片（其中最高 ${r.label}），获得 $targetText。" +
                            lastCopyWarning(picks) +
                            "\n合成不可撤销，确定吗？"
                    )
                    .setPositiveButton("合成") { d, _ ->
                        d.dismiss()
                        val result = CraftEngine.synthesize(repo, picks)
                        if (result == null) {
                            Toast.makeText(this, "合成失败：卡片数量不足", Toast.LENGTH_SHORT).show()
                            reload()
                        } else {
                            reload()
                            playSynthesizeAnim(picks, result) {
                                showResult("合成成功", listOf(result))
                            }
                        }
                    }
                    .setNegativeButton("再想想", null)
                    .show()
            }

            Mode.CONVERT -> return // 转换走道具按钮，无普通操作
        }
    }

    // ---------------- 道具定向操作 ----------------

    /** 分解石 / 合成石：弹目标选择器后二次确认执行 */
    private fun performStoneAction() {
        if (animating) return
        when (mode) {
            Mode.DECOMPOSE -> {
                val sel = selected.entries.firstOrNull() ?: return
                val (id, r, _) = shown[sel.key]
                val lower = CraftEngine.decomposeTarget(r) ?: return
                showTargetPicker("选择分解产物 · ${lower.label}", lower) { targetId ->
                    val name = CardCatalog.displayName(targetId)
                    AlertDialog.Builder(this)
                        .setTitle("确认定向分解")
                        .setMessage(
                            "将分解 ${CardCatalog.displayName(id)} · ${r.label}，" +
                                "获得 3 张指定的${lower.label}「$name」，并消耗 1 个分解石。确定吗？"
                        )
                        .setPositiveButton("分解") { d, _ ->
                            d.dismiss()
                            if (!ShopStore.get(this).consumeItem(ItemCatalog.ID_SPLIT)) {
                                Toast.makeText(this, "分解石不足", Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }
                            val result = CraftEngine.decomposeInto(CollectionRepository.get(this), id, r, targetId)
                            if (result == null) {
                                Toast.makeText(this, "分解失败：卡片已不在背包", Toast.LENGTH_SHORT).show()
                                reload()
                            } else {
                                reload()
                                playDecomposeAnim(id, r, result) { showResult("定向分解成功", result) }
                            }
                        }
                        .setNegativeButton("再想想", null)
                        .show()
                }
            }

            Mode.SYNTHESIZE -> {
                if (selected.values.sum() != 3) return
                val picks = selected.flatMap { (pos, n) ->
                    List(n) { shown[pos].first to shown[pos].second }
                }
                val r = CraftEngine.highestRarity(picks) ?: return
                val targetR = CraftEngine.synthesizeTarget(r)
                showTargetPicker("选择合成目标 · ${targetR.label}", targetR) { targetId ->
                    val name = CardCatalog.displayName(targetId)
                    AlertDialog.Builder(this)
                        .setTitle("确认定向合成")
                        .setMessage(
                            "将消耗这 3 张卡片（其中最高 ${r.label}），合成指定的${targetR.label}「$name」，并消耗 1 个合成石。" +
                                lastCopyWarning(picks) +
                                "\n确定吗？"
                        )
                        .setPositiveButton("合成") { d, _ ->
                            d.dismiss()
                            if (!ShopStore.get(this).consumeItem(ItemCatalog.ID_FORGE)) {
                                Toast.makeText(this, "合成石不足", Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }
                            val result = CraftEngine.synthesizeInto(CollectionRepository.get(this), picks, targetId)
                            if (result == null) {
                                Toast.makeText(this, "合成失败：卡片数量不足", Toast.LENGTH_SHORT).show()
                                reload()
                            } else {
                                reload()
                                playSynthesizeAnim(picks, result) { showResult("定向合成成功", listOf(result)) }
                            }
                        }
                        .setNegativeButton("再想想", null)
                        .show()
                }
            }

            Mode.CONVERT -> Unit
        }
    }

    /** 转换石：1 张卡 → 随机同品质另一张 */
    private fun performConvertRandom() {
        if (animating) return
        val sel = selected.entries.firstOrNull() ?: return
        val (id, r, _) = shown[sel.key]
        AlertDialog.Builder(this)
            .setTitle("确认随机转换")
            .setMessage(
                "将 ${CardCatalog.displayName(id)} · ${r.label} 转换成随机的另一张${r.label}（必定不同卡），" +
                    "并消耗 1 个转换石。确定吗？"
            )
            .setPositiveButton("转换") { d, _ ->
                d.dismiss()
                if (!ShopStore.get(this).consumeItem(ItemCatalog.ID_SWAP)) {
                    Toast.makeText(this, "转换石不足", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val result = CraftEngine.convertRandom(CollectionRepository.get(this), id, r)
                if (result == null) {
                    Toast.makeText(this, "转换失败：卡片已不在背包", Toast.LENGTH_SHORT).show()
                    reload()
                } else {
                    reload()
                    playConvertAnim(id to r, result) { showResult("转换成功", listOf(result)) }
                }
            }
            .setNegativeButton("再想想", null)
            .show()
    }

    /** 超级转换石：1 张卡 → 指定同品质卡 */
    private fun performConvertPick() {
        if (animating) return
        val sel = selected.entries.firstOrNull() ?: return
        val (id, r, _) = shown[sel.key]
        showTargetPicker("选择转换目标 · ${r.label}", r, excludeId = id) { targetId ->
            val name = CardCatalog.displayName(targetId)
            AlertDialog.Builder(this)
                .setTitle("确认定向转换")
                .setMessage(
                    "将 ${CardCatalog.displayName(id)} · ${r.label} 转换成指定的${r.label}「$name」，" +
                        "并消耗 1 个超级转换石。确定吗？"
                )
                .setPositiveButton("转换") { d, _ ->
                    d.dismiss()
                    if (!ShopStore.get(this).consumeItem(ItemCatalog.ID_MEGA_SWAP)) {
                        Toast.makeText(this, "超级转换石不足", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val result = CraftEngine.convertInto(CollectionRepository.get(this), id, r, targetId)
                    if (result == null) {
                        Toast.makeText(this, "转换失败：卡片已不在背包", Toast.LENGTH_SHORT).show()
                        reload()
                    } else {
                        reload()
                        playConvertAnim(id to r, result) { showResult("转换成功", listOf(result)) }
                    }
                }
                .setNegativeButton("再想想", null)
                .show()
        }
    }

    /** 升级石：1 张卡直接升 1 级稀有度，编号不变 */
    private fun performUpgrade() {
        if (animating) return
        val sel = selected.entries.firstOrNull() ?: return
        val (id, r, _) = shown[sel.key]
        val up = CraftEngine.upgradeTarget(r)
        if (up == null) {
            Toast.makeText(this, "钻石卡已是最高稀有度，无法升级", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("确认升级")
            .setMessage(
                "将 ${CardCatalog.displayName(id)} · ${r.label} 升级为 ${up.label}（编号不变），" +
                    "并消耗 1 个升级石。确定吗？"
            )
            .setPositiveButton("升级") { d, _ ->
                d.dismiss()
                if (!ShopStore.get(this).consumeItem(ItemCatalog.ID_UPGRADE)) {
                    Toast.makeText(this, "升级石不足", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val result = CraftEngine.upgradeRarity(CollectionRepository.get(this), id, r)
                if (result == null) {
                    Toast.makeText(this, "升级失败：卡片已不在背包", Toast.LENGTH_SHORT).show()
                    reload()
                } else {
                    reload()
                    playConvertAnim(id to r, result) { showResult("升级成功", listOf(result)) }
                }
            }
            .setNegativeButton("再想想", null)
            .show()
    }

    /**
     * 目标卡选择器：指定稀有度的 101 张卡网格 + 类别筛选 + 勾选。
     * [excludeId] 指定的编号不可选（定向转换不能转成同一张）。
     */
    private fun showTargetPicker(
        title: String,
        rarity: Rarity,
        excludeId: Int = -1,
        onPick: (Int) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_pick_card, null)
        // 固定高度，让内部 RecyclerView 的 weight 生效并可滚动
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.58f).toInt()
        )

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerPick)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipPickCategory)

        var category: String? = null
        var picked = -1

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        fun syncConfirm() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = picked > 0
        }

        // 类别筛选 Chip
        val cats = CardCatalog.cards.map { it.category }.distinct()
        (listOf(null) + cats).forEachIndexed { i, c ->
            val chip = Chip(this).apply {
                text = c ?: "全部类别"
                isCheckable = true
                id = View.generateViewId()
                isChecked = i == 0
            }
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    category = c
                    picked = -1
                    recycler.adapter?.notifyDataSetChanged()
                    syncConfirm()
                }
            }
            chipGroup.addView(chip)
        }

        fun cardsNow(): List<CardDef> =
            CardCatalog.cards.filter { category == null || it.category == category }

        recycler.layoutManager = GridLayoutManager(this, 4)
        recycler.adapter = object : RecyclerView.Adapter<PickVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                PickVH(ItemPickCardBinding.inflate(layoutInflater, parent, false))

            override fun getItemCount(): Int = cardsNow().size

            override fun onBindViewHolder(holder: PickVH, position: Int) {
                val def = cardsNow()[position]
                val b = holder.b
                b.pickCard.mode = CardView.Mode.FACE
                b.pickCard.rarity = rarity
                b.pickCard.cardNumber = def.id
                b.pickCard.locked = false

                val excluded = def.id == excludeId
                b.root.alpha = if (excluded) 0.3f else 1f
                b.pickCheck.isVisible = def.id == picked
                b.root.setOnClickListener {
                    if (excluded) {
                        Toast.makeText(this@WorkshopActivity, "不能选择同一张卡", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    picked = def.id
                    notifyDataSetChanged()
                    syncConfirm()
                }
            }
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (picked > 0) {
                dialog.dismiss()
                onPick(picked)
            }
        }
        syncConfirm()
    }

    private class PickVH(val b: ItemPickCardBinding) : RecyclerView.ViewHolder(b.root)

    // ---------------- 分解 / 合成演出动画 ----------------

    /** 在演出时间轴 [delayMs] 毫秒后执行动作 */
    private fun at(delayMs: Long, action: () -> Unit) {
        craftHandler.postDelayed(action, delayMs)
    }

    /** 构造一张正面卡视图（演出用） */
    private fun makeStageCard(cardId: Int, rarity: Rarity): CardView = CardView(this).apply {
        mode = CardView.Mode.FACE
        this.rarity = rarity
        cardNumber = cardId
        locked = false
    }

    /** 遮罩淡入并清空旧内容 */
    private fun overlayIn() {
        val overlay = binding.craftOverlay
        overlay.removeAllViews()
        overlay.animate().cancel()
        overlay.alpha = 0f
        overlay.isVisible = true
        overlay.animate().alpha(1f).setDuration(180).start()
    }

    /** 遮罩淡出，结束后复位并回调 */
    private fun overlayOut(delayMs: Long, onDone: () -> Unit) {
        at(delayMs) {
            binding.craftOverlay.animate().alpha(0f).setDuration(240).withEndAction {
                binding.craftOverlay.isVisible = false
                binding.craftOverlay.removeAllViews()
                animating = false
                onDone()
            }.start()
        }
    }

    /**
     * 分解演出：原卡弹入 → 抖动蓄力 → 碎裂飞散（碎片粒子）→ 3 张新卡依次弹入。
     */
    private fun playDecomposeAnim(
        cardId: Int,
        rarity: Rarity,
        drops: List<DropEngine.Drop>,
        onDone: () -> Unit
    ) {
        animating = true
        overlayIn()
        val overlay = binding.craftOverlay

        // 原卡（先隐藏防闪现，布局完成后取中心坐标再开演）
        val source = makeStageCard(cardId, rarity).apply {
            alpha = 0f
        }
        overlay.addView(source, FrameLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // 新卡横排（初始全部缩为 0，碎裂后再依次弹出）
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        drops.forEachIndexed { i, d ->
            row.addView(
                makeStageCard(d.cardId, d.rarity).apply {
                    scaleX = 0f
                    scaleY = 0f
                },
                LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = if (i < drops.size - 1) dp(10) else 0
                }
            )
        }
        overlay.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        source.post {
            if (isFinishing || isDestroyed) return@post
            val cx = source.x + source.width / 2f
            val cy = source.y + source.height / 2f

            // 1) 原卡弹入亮相
            source.scaleX = 0.2f
            source.scaleY = 0.2f
            source.alpha = 0f
            source.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(1.35f))
                .start()

            // 2) 抖动蓄力
            at(560) {
                ObjectAnimator.ofFloat(
                    source, "translationX",
                    0f, dp(-11).toFloat(), dp(11).toFloat(), dp(-8).toFloat(),
                    dp(8).toFloat(), dp(-4).toFloat(), 0f
                ).apply { duration = 280 }.start()
            }

            // 3) 碎裂：碎片四溅 + 原卡缩小旋转消散
            at(860) {
                spawnShards(overlay, rarity.color, cx, cy)
                source.animate()
                    .scaleX(0.15f).scaleY(0.15f)
                    .rotation(28f)
                    .alpha(0f)
                    .setDuration(340)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
            }

            // 4) 3 张新卡依次弹入
            at(1280) {
                for (i in 0 until row.childCount) {
                    val v = row.getChildAt(i)
                    v.scaleX = 0f
                    v.scaleY = 0f
                    v.animate().scaleX(1f).scaleY(1f)
                        .setStartDelay(i * 110L)
                        .setDuration(320)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }
            }

            // 5) 收场
            overlayOut(2150, onDone)
        }
    }

    /**
     * 合成演出：3 张材料卡扇形弹入 → 向中心聚拢缩小 → 目标色闪光爆发 → 新卡放大登场。
     */
    private fun playSynthesizeAnim(
        picks: List<Pair<Int, Rarity>>,
        result: DropEngine.Drop,
        onDone: () -> Unit
    ) {
        animating = true
        overlayIn()
        val overlay = binding.craftOverlay

        val materials = picks.map { (id, r) ->
            makeStageCard(id, r).apply {
                scaleX = 0f
                scaleY = 0f
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        materials.forEach { c ->
            row.addView(c, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            })
        }
        overlay.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // 产物大卡（初始不可见，闪光后登场）
        val big = makeStageCard(result.cardId, result.rarity).apply {
            alpha = 0f
        }
        overlay.addView(big, FrameLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        row.post {
            if (isFinishing || isDestroyed) return@post

            // 1) 材料卡扇形依次弹入（中间张偏高，两翼下沉）
            materials.forEachIndexed { i, c ->
                c.translationX = dp(48) * (i - 1).toFloat()
                c.translationY = dp(24) * abs(i - 1).toFloat()
                c.scaleX = 0f
                c.scaleY = 0f
                c.rotation = if (i == 0) -6f else if (i == 2) 6f else 0f
                c.animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 90L)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator(1.3f))
                    .start()
            }

            // 2) 向中心聚拢、旋转叠合、缩小消散
            at(660) {
                materials.forEachIndexed { i, c ->
                    c.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .rotation(0f)
                        .scaleX(0.1f).scaleY(0.1f)
                        .alpha(0f)
                        .setDuration(360)
                        .setInterpolator(AccelerateInterpolator())
                        .start()
                }
            }

            // 3) 目标稀有度色闪光爆发
            at(1030) {
                spawnBurst(overlay, result.rarity.color)
            }

            // 4) 新卡登场：先猛弹到 1.15 再回弹收稳
            at(1200) {
                big.scaleX = 0f
                big.scaleY = 0f
                big.alpha = 0f
                big.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        big.animate().scaleX(1f).scaleY(1f)
                            .setDuration(180)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }

            // 5) 收场
            overlayOut(2200, onDone)
        }
    }

    /**
     * 转换演出：原卡弹入 → 旋转缩小被吸入 → 目标色闪光 → 新卡弹入登场。
     */
    private fun playConvertAnim(
        from: Pair<Int, Rarity>,
        to: DropEngine.Drop,
        onDone: () -> Unit
    ) {
        animating = true
        overlayIn()
        val overlay = binding.craftOverlay

        val src = makeStageCard(from.first, from.second).apply { alpha = 0f }
        overlay.addView(src, FrameLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        val dst = makeStageCard(to.cardId, to.rarity).apply {
            alpha = 0f
            scaleX = 0f
            scaleY = 0f
        }
        overlay.addView(dst, FrameLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        src.post {
            if (isFinishing || isDestroyed) return@post

            // 1) 原卡弹入
            src.scaleX = 0.2f
            src.scaleY = 0.2f
            src.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(1.35f))
                .start()

            // 2) 旋转缩小淡出（被吸入转换）
            at(560) {
                src.animate()
                    .scaleX(0.1f).scaleY(0.1f)
                    .rotation(300f)
                    .alpha(0f)
                    .setDuration(380)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
            }

            // 3) 目标色闪光
            at(890) { spawnBurst(overlay, to.rarity.color) }

            // 4) 新卡登场：猛弹到 1.15 再回弹收稳
            at(1020) {
                dst.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        dst.animate().scaleX(1f).scaleY(1f)
                            .setDuration(180)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }

            // 5) 收场
            overlayOut(1950, onDone)
        }
    }

    /** 碎片粒子：从 (cx, cy) 向四周随机飞散 + 下坠 + 旋转淡出 */
    private fun spawnShards(overlay: FrameLayout, color: Int, cx: Float, cy: Float) {
        val rng = Random()
        repeat(14) {
            val size = dp(5 + rng.nextInt(6))
            val shard = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(color)
                }
            }
            overlay.addView(shard, FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (cx - size / 2).toInt()
                topMargin = (cy - size / 2).toInt()
            })
            val angle = rng.nextFloat() * 2f * Math.PI.toFloat()
            val dist = dp(100 + rng.nextInt(70))
            shard.animate()
                .translationX(cos(angle) * dist)
                .translationY(sin(angle) * dist + dp(36)) // 带一点下坠
                .rotation(rng.nextInt(540).toFloat())
                .alpha(0f)
                .setDuration(450L + rng.nextInt(250))
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { (shard.parent as? ViewGroup)?.removeView(shard) }
                .start()
        }
    }

    /** 闪光爆发：目标色光核放大 + 描边冲击环扩散 */
    private fun spawnBurst(overlay: FrameLayout, color: Int) {
        val core = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        overlay.addView(core, FrameLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER })
        core.scaleX = 0.2f
        core.scaleY = 0.2f
        core.alpha = 0.95f
        core.animate().scaleX(2.8f).scaleY(2.8f).alpha(0f)
            .setDuration(480)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { overlay.removeView(core) }
            .start()

        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), color)
            }
        }
        overlay.addView(ring, FrameLayout.LayoutParams(dp(80), dp(80)).apply { gravity = Gravity.CENTER })
        ring.scaleX = 0.2f
        ring.scaleY = 0.2f
        ring.alpha = 0.9f
        ring.animate().scaleX(3.2f).scaleY(3.2f).alpha(0f)
            .setDuration(560)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { overlay.removeView(ring) }
            .start()
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

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (id, rarity, owned) = shown[position]
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
