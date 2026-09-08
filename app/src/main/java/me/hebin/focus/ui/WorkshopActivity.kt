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
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.CraftEngine
import me.hebin.focus.data.DropEngine
import me.hebin.focus.data.Rarity
import me.hebin.focus.databinding.ActivityWorkshopBinding
import me.hebin.focus.databinding.ItemCraftCardBinding
import me.hebin.focus.ui.view.CardView
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

    override fun onDestroy() {
        craftHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reload() {
        val repo = CollectionRepository.get(this)
        slots = when (mode) {
            Mode.DECOMPOSE -> repo.ownedSlots().filter { it.second != Rarity.COMMON }
            Mode.SYNTHESIZE -> repo.ownedSlots()
        }
        // 分解模式不涉及普卡，停在普卡筛选会一无所获，自动重置
        if (mode == Mode.DECOMPOSE && filterRarity == Rarity.COMMON) filterRarity = null
        selected.clear()
        applyFilter()
        refreshFilterChips()

        binding.textCraftHint.text = when (mode) {
            Mode.DECOMPOSE -> "选择 1 张卡片分解，随机获得 3 张低一级稀有度的卡片（普卡不可分解；只剩 1 张的卡会被保护，不可用）"
            Mode.SYNTHESIZE -> "选 3 张同稀有度卡片（可不同编号，重复点同张卡可叠加）合成 1 张更高稀有度的卡片；3 张钻石合成随机钻石卡；只剩 1 张的卡会被保护，不可用"
        }
        refreshBottomBar()
    }

    /** 按筛选条件过滤展示槽位 */
    private fun applyFilter() {
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
                    Mode.SYNTHESIZE -> "还没有卡片\n先去专注集卡吧"
                }
            }

            shown.isEmpty() -> {
                binding.textCraftEmpty.isVisible = true
                binding.textCraftEmpty.text = "没有符合筛选的卡片\n换个条件试试"
            }

            else -> binding.textCraftEmpty.isVisible = false
        }
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
        if (animating) return
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
        }
    }

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
