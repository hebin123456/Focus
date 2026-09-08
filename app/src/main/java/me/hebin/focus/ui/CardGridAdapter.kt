package me.hebin.focus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import me.hebin.focus.R
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.Rarity
import me.hebin.focus.ui.view.CardView

/** 图鉴网格：默认全部 101 卡，未收集显示灰剪影；支持按编号列表筛选展示 */
class CardGridAdapter(
    private val repo: CollectionRepository
) : RecyclerView.Adapter<CardGridAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: CardView = v.findViewById(R.id.gridCard)
        val badgeCount: android.widget.TextView = v.findViewById(R.id.badgeCount)
    }

    /** 当前展示的编号（默认 1..101，筛选后变化） */
    private var ids: List<Int> = (1..CardCatalog.TOTAL).toList()

    /** 更新筛选结果 */
    fun submit(newIds: List<Int>) {
        ids = newIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = ids.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val id = ids[position]
        val counts = repo.ownedCounts(id)
        val best = counts.keys.maxByOrNull { it.ordinal }
        val ctx = holder.itemView.context

        holder.card.mode = CardView.Mode.FACE
        holder.card.cardNumber = id
        holder.card.locked = best == null
        holder.card.rarity = best ?: CardCatalog.baseTierOf(id)

        // 持有数量角标：所有稀有度合计，≥2 才显示（1 张是常态，不刷屏）
        val total = counts.values.sum()
        holder.badgeCount.isVisible = total >= 2
        holder.badgeCount.text = "×$total"

        holder.card.setOnClickListener {
            showDetail(ctx, id, counts)
        }
    }

    private fun showDetail(ctx: android.content.Context, id: Int, counts: Map<Rarity, Int>) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_card_detail, null)
        val card = view.findViewById<CardView>(R.id.detailCard)
        card.mode = CardView.Mode.FACE
        card.cardNumber = id
        card.locked = counts.isEmpty()

        // 已收集的稀有度升序；默认展示最高级，左右键循环切换不同版本
        val owned = counts.keys.sortedBy { it.ordinal }
        var idx = owned.lastIndex

        val container = view.findViewById<android.view.ViewGroup>(R.id.detailRarityRows)
        val inflater = LayoutInflater.from(ctx)
        val title: String
        if (counts.isEmpty()) {
            title = "${CardCatalog.displayName(id)} · 未收集"
            card.rarity = CardCatalog.baseTierOf(id)
            val row = inflater.inflate(R.layout.item_rarity_row, container, false)
            row.findViewById<android.widget.TextView>(R.id.rowRarity).apply {
                text = "${CardCatalog.categoryOfId(id)}类 · 尚未获得，专注时间越长越容易掉落哦"
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary))
            }
            container.addView(row)
        } else {
            title = "${CardCatalog.displayName(id)}（${CardCatalog.categoryOfId(id)}）"

            // 切换行：多于一个稀有度时显示，卡片上左右滑动循环切换
            val switchRow = view.findViewById<android.view.View>(R.id.detailRaritySwitch)
            val textCur = view.findViewById<android.widget.TextView>(R.id.textCurRarity)
            fun renderCur() {
                val r = owned[idx]
                card.rarity = r
                textCur.text = "${r.label} × ${counts[r]}"
                textCur.setTextColor(r.color)
            }
            if (owned.size > 1) {
                switchRow.isVisible = true
                var downX = 0f
                val slop = 40 * ctx.resources.displayMetrics.density
                card.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = e.x
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            val dx = e.x - downX
                            if (kotlin.math.abs(dx) > slop) {
                                idx = if (dx < 0) (idx + 1) % owned.size
                                else (idx - 1 + owned.size) % owned.size
                                renderCur()
                            }
                            v.performClick()
                            true
                        }
                        else -> false
                    }
                }
            }
            renderCur()

            for (r in Rarity.entries) {
                val row = inflater.inflate(R.layout.item_rarity_row, container, false)
                row.findViewById<android.widget.TextView>(R.id.rowRarity).apply {
                    text = r.label
                    setTextColor(r.color)
                }
                row.findViewById<android.widget.TextView>(R.id.rowCount).apply {
                    text = "× ${counts[r] ?: 0}"
                }
                container.addView(row)
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("好的") { d, _ -> d.dismiss() }
            .show()
    }
}
