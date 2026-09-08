package me.hebin.focus.ui

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import me.hebin.focus.R
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.Rarity
import me.hebin.focus.ui.view.CardView

/**
 * 自定义图标选择面板：只能从已收集的图鉴卡里选。
 * 图标样式跟随所选卡的稀有度（普卡朴素，钻石炫彩）——由 506 个 activity-alias 承载。
 */
object IconPicker {

    fun show(activity: Activity) {
        if (!IconSwitcher.isCustomSupported()) {
            Toast.makeText(activity, "自定义图标需要 Android 8.0 以上", Toast.LENGTH_SHORT).show()
            return
        }

        val repo = CollectionRepository.get(activity)
        // 展开所有已收集的（编号, 稀有度）组合，按稀有度升序、编号升序；
        // 这样集齐后依然能选普卡/铜卡等低稀有度版本，不止最高级
        val owned = repo.ownedSlots()

        val sheet = BottomSheetDialog(activity)
        val pad = dp(activity, 20)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(activity).apply {
            text = "自定义图标"
            textSize = 18f
            setTextColor(ContextCompat.getColor(activity, R.color.textPrimary))
            paint.isFakeBoldText = true
        })
        root.addView(TextView(activity).apply {
            text = "选择一张已收集的卡片作为桌面图标，样式跟随这张卡的稀有度（普卡朴素，钻石炫彩）"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.textSecondary))
            setPadding(0, dp(activity, 6), 0, 0)
            setLineSpacing(dp(activity, 2).toFloat(), 1.0f)
        })

        if (IconSwitcher.isCustom(activity)) {
            root.addView(MaterialButton(
                ContextThemeWrapper(activity, com.google.android.material.R.style.Widget_Material3_Button_TextButton)
            ).apply {
                text = "恢复默认图标"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 6) }
                setOnClickListener {
                    if (IconSwitcher.applyDefault(activity)) {
                        Toast.makeText(activity, "已恢复默认图标，回到桌面看看吧", Toast.LENGTH_SHORT).show()
                        sheet.dismiss()
                    }
                }
            })
        }

        if (owned.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "还没有收集到卡片\n先去专注集卡，就能用萌宠当图标啦"
                textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.textSecondary))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(activity, 40), 0, dp(activity, 40))
                setLineSpacing(dp(activity, 4).toFloat(), 1.0f)
            })
        } else {
            // ---------- 筛选：稀有度 / 类别（集齐后条目多，靠筛选快速找卡） ----------
            var curRarity: Rarity? = null
            var curCategory: String? = null

            val adapter = PickerAdapter { id, rarity ->
                if (IconSwitcher.applyCard(activity, id, rarity)) {
                    Toast.makeText(
                        activity,
                        "图标已更换！回到桌面看看效果（部分桌面需要几秒刷新）",
                        Toast.LENGTH_LONG
                    ).show()
                    sheet.dismiss()
                } else {
                    Toast.makeText(activity, "图标切换失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }

            val emptyFilter = TextView(activity).apply {
                text = "没有符合条件的卡片，换个筛选试试"
                textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.textSecondary))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(activity, 40), 0, dp(activity, 40))
                visibility = View.GONE
            }

            fun applyFilter() {
                val list = owned.filter { (id, r, _) ->
                    (curRarity == null || r == curRarity) &&
                        (curCategory == null || CardCatalog.categoryOfId(id) == curCategory)
                }
                adapter.update(list)
                emptyFilter.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }

            root.addView(
                chipRow(
                    activity,
                    listOf(null) + Rarity.entries.toList(),
                    { v -> (v as? Rarity)?.label ?: "全部稀有度" },
                    { v -> curRarity = v as? Rarity; applyFilter() }
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(activity, 12) }
                }
            )

            val categories = owned.map { CardCatalog.categoryOfId(it.first) }.distinct()
            root.addView(
                chipRow(
                    activity,
                    listOf(null) + categories,
                    { v -> if (v == null) "全部类别" else "${v}类" },
                    { v -> curCategory = v as? String; applyFilter() }
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(activity, 6) }
                }
            )

            root.addView(emptyFilter)

            val height = (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()
            root.addView(RecyclerView(activity).apply {
                layoutManager = GridLayoutManager(activity, 4)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height
                ).apply { topMargin = dp(activity, 10) }
                this.adapter = adapter
            })
            applyFilter()
        }

        sheet.setContentView(root)
        sheet.show()
    }

    /** 水平滚动的筛选 Chip 行；values[0] 必须是 null（表示「全部」） */
    private fun chipRow(
        activity: Activity,
        values: List<Any?>,
        labelOf: (Any?) -> String,
        onPick: (Any?) -> Unit
    ): HorizontalScrollView {
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val group = ChipGroup(activity).apply {
            setSingleSelection(true)
            setSelectionRequired(true)
        }
        values.forEachIndexed { i, v ->
            val chip = Chip(
                ContextThemeWrapper(
                    activity,
                    com.google.android.material.R.style.Widget_Material3_Chip_Filter
                )
            ).apply {
                text = labelOf(v)
                isCheckable = true
                id = View.generateViewId()
                if (i == 0) isChecked = true
            }
            chip.setOnCheckedChangeListener { _, checked -> if (checked) onPick(v) }
            group.addView(chip)
        }
        scroll.addView(group)
        return scroll
    }

    private class PickerAdapter(
        private val onPick: (Int, Rarity) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.VH>() {

        private var cards: List<Triple<Int, Rarity, Int>> = emptyList()

        /** 筛选变化时刷新数据集 */
        fun update(list: List<Triple<Int, Rarity, Int>>) {
            cards = list
            notifyDataSetChanged()
        }

        class VH(val card: CardView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = CardView(parent.context)
            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return VH(card)
        }

        override fun getItemCount(): Int = cards.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (id, rarity, _) = cards[position]
            holder.card.mode = CardView.Mode.FACE
            holder.card.rarity = rarity
            holder.card.cardNumber = id
            holder.card.locked = false
            holder.itemView.setOnClickListener { onPick(id, rarity) }
        }
    }

    private fun dp(ctx: Activity, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
