package me.hebin.focus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import me.hebin.focus.R
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.Rarity
import me.hebin.focus.ui.view.CardView

/** 图鉴网格：100 个格子，未收集显示灰剪影 */
class CardGridAdapter(
    private val repo: CollectionRepository
) : RecyclerView.Adapter<CardGridAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: CardView = v.findViewById(R.id.gridCard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = CardCatalog.TOTAL

    override fun onBindViewHolder(holder: VH, position: Int) {
        val id = position + 1
        val counts = repo.ownedCounts(id)
        val best = counts.keys.maxByOrNull { it.ordinal }
        val ctx = holder.itemView.context

        holder.card.mode = CardView.Mode.FACE
        holder.card.cardNumber = id
        holder.card.locked = best == null
        holder.card.rarity = best ?: CardCatalog.baseTierOf(id)
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
        card.rarity = counts.keys.maxByOrNull { it.ordinal } ?: CardCatalog.baseTierOf(id)

        val container = view.findViewById<android.view.ViewGroup>(R.id.detailRarityRows)
        val inflater = LayoutInflater.from(ctx)
        val title: String
        if (counts.isEmpty()) {
            title = "编号 $id · 未收集"
            val row = inflater.inflate(R.layout.item_rarity_row, container, false)
            row.findViewById<android.widget.TextView>(R.id.rowRarity).apply {
                text = "尚未获得该卡片，专注时间越长越容易掉落哦"
                setTextColor(0xFF9AA3C0.toInt())
            }
            container.addView(row)
        } else {
            title = "编号 $id"
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
