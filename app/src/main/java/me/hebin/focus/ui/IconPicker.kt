package me.hebin.focus.ui

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
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
        val owned = (1..CardCatalog.TOTAL)
            .mapNotNull { id -> repo.bestRarity(id)?.let { id to it } }

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
            val height = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
            root.addView(RecyclerView(activity).apply {
                layoutManager = GridLayoutManager(activity, 4)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height
                ).apply { topMargin = dp(activity, 14) }
                adapter = PickerAdapter(owned) { id, rarity ->
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
            })
        }

        sheet.setContentView(root)
        sheet.show()
    }

    private class PickerAdapter(
        private val cards: List<Pair<Int, Rarity>>,
        private val onPick: (Int, Rarity) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.VH>() {

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
            val (id, rarity) = cards[position]
            holder.card.mode = CardView.Mode.FACE
            holder.card.rarity = rarity
            holder.card.cardNumber = id
            holder.card.locked = false
            holder.itemView.setOnClickListener { onPick(id, rarity) }
        }
    }

    private fun dp(ctx: Activity, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
