package me.hebin.focus.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import me.hebin.focus.R
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.ItemCatalog
import me.hebin.focus.data.ShopCardEntry
import me.hebin.focus.data.ShopStore
import me.hebin.focus.databinding.ActivityShopBinding
import me.hebin.focus.databinding.ItemBagRowBinding
import me.hebin.focus.databinding.ItemShopCardBinding
import me.hebin.focus.databinding.ItemShopRowBinding
import me.hebin.focus.ui.view.CardView
import java.util.Locale

/**
 * 道具商店：金币余额 + 卡片补给（每小时随机刷新，每张限购 1 次）+ 道具列表 + 我的背包。
 * 金币在 App 前台时线性累积（每满 1 分钟 +1）；道具在卡片工坊使用。
 */
class ShopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopBinding

    /** 每秒刷新倒计时；到点自动重刷补给窗口 */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val shop = ShopStore.get(this@ShopActivity)
            val left = shop.cardRotationNextAt() - System.currentTimeMillis()
            if (left <= 0) {
                refresh() // 跨整点，重刷补给窗口并重绘全页
            } else {
                binding.textCardRefresh.text = String.format(
                    Locale.CHINA, "%02d:%02d 后刷新", left / 60_000, left % 60_000 / 1000
                )
                ticker.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.textRateHint.text = "App 在前台时每满 1 分钟 +1 金币"
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val shop = ShopStore.get(this)
        binding.textCoinCount.text = shop.currentCoins().toString()
        binding.progressNextCoin.progress = (shop.nextCoinProgress() * 100).toInt()
        renderCardShop(shop)
        renderShop(shop)
        renderBag(shop)
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    // ---------- 卡片补给 ----------

    private fun renderCardShop(shop: ShopStore) {
        binding.cardShopRow.removeAllViews()
        shop.cardRotation().forEach { e ->
            val row = ItemShopCardBinding.inflate(layoutInflater, binding.cardShopRow, false)
            row.shopCard.mode = CardView.Mode.FACE
            row.shopCard.rarity = e.rarity
            row.shopCard.cardNumber = e.cardId
            row.shopCard.locked = false
            row.shopCard.alpha = if (e.bought) 0.35f else 1f
            row.badgeSold.isVisible = e.bought
            if (e.bought) {
                row.textCardPrice.text = "已购"
                row.textCardPrice.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, R.color.textSecondary)
                )
            } else {
                row.textCardPrice.text = "${e.price} 金币"
                row.textCardPrice.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, R.color.coinText)
                )
            }

            row.root.setOnClickListener {
                if (e.bought) {
                    Toast.makeText(this, "这张已经买过了，等下一批吧", Toast.LENGTH_SHORT).show()
                } else {
                    confirmBuyCard(shop, e)
                }
            }
            binding.cardShopRow.addView(row.root)
        }
    }

    private fun confirmBuyCard(shop: ShopStore, e: ShopCardEntry) {
        val name = CardCatalog.displayName(e.cardId)
        AlertDialog.Builder(this)
            .setTitle("购买卡片")
            .setMessage(
                "花费 ${e.price} 金币购买「$name · ${e.rarity.label}」？\n" +
                    "每张限购 1 次，下一批 ${binding.textCardRefresh.text}"
            )
            .setPositiveButton("购买") { d, _ ->
                d.dismiss()
                if (shop.buyCard(e.cardId, e.rarity)) {
                    Toast.makeText(this, "已获得 $name · ${e.rarity.label}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .setNegativeButton("再想想", null)
            .show()
    }

    // ---------- 道具 ----------

    private fun renderShop(shop: ShopStore) {
        binding.shopList.removeAllViews()
        val items = ItemCatalog.items
        binding.textShopEmpty.isVisible = items.isEmpty()
        items.forEach { item ->
            val row = ItemShopRowBinding.inflate(layoutInflater, binding.shopList, false)
            row.textItemName.text = item.name
            row.textItemDesc.text = item.desc
            row.textItemPrice.text = "${item.price} 金币"
            row.btnBuy.setOnClickListener {
                if (shop.buy(item)) {
                    Toast.makeText(this, "已购买「${item.name}」，放入背包", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                }
            }
            binding.shopList.addView(row.root)
        }
    }

    private fun renderBag(shop: ShopStore) {
        binding.bagList.removeAllViews()
        val owned = shop.inventory()
            .mapNotNull { (id, n) -> ItemCatalog.byId(id)?.let { it to n } }
            .sortedBy { it.first.id }
        binding.textBagEmpty.isVisible = owned.isEmpty()
        owned.forEach { (item, n) ->
            val row = ItemBagRowBinding.inflate(layoutInflater, binding.bagList, false)
            row.textBagName.text = item.name
            row.textBagCount.text = "×$n"
            row.textBagDesc.text = item.desc
            binding.bagList.addView(row.root)
        }
    }
}
