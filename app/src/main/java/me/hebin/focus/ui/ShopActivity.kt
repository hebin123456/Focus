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
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.ItemCatalog
import me.hebin.focus.data.ShopCardEntry
import me.hebin.focus.data.ShopItem
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
                // 磁铁剩余时间 / 到期整页重绘
                val magnetText = magnetRemainText()
                if (magnetText == null) {
                    if (magnetDescView != null) refresh()
                } else {
                    magnetDescView?.text = magnetText
                }
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

    // ---------- 金币磁铁剩余时间（每秒刷新） ----------

    private var magnetDescView: TextView? = null

    private fun magnetRemainText(): String? {
        val left = ShopStore.get(this).magnetActiveUntil() - System.currentTimeMillis()
        if (left <= 0) return null
        val h = left / 3_600_000
        val m = left % 3_600_000 / 60_000
        val s = left % 60_000 / 1000
        return if (h > 0) "翻倍中 · 剩余 ${h}:${m}" else "翻倍中 · 剩余 %02d:%02d".format(m, s)
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
        magnetDescView = null
        val items = ItemCatalog.items
        binding.textShopEmpty.isVisible = items.isEmpty()
        items.forEach { item ->
            val row = ItemShopRowBinding.inflate(layoutInflater, binding.shopList, false)
            row.textItemName.text = item.name
            row.textItemDesc.text = item.desc
            row.textItemPrice.text = "${item.price} 金币"

            // 磁铁激活中：描述行显示倒计时（tick 每秒更新）
            if (item.id == ItemCatalog.ID_MAGNET) {
                magnetRemainText()?.let { row.textItemDesc.text = it }
                magnetDescView = row.textItemDesc
            }
            // 时光回溯：没有可找回的碎裂记录时禁用购买
            if (item.id == ItemCatalog.ID_REWIND) {
                val rewindable = CollectionRepository.get(this).latestRewindableCrack()
                row.btnBuy.isEnabled = rewindable != null
                if (rewindable == null) row.textItemDesc.text = "暂无可找回的碎裂记录（有过碎裂后可用）"
            }

            row.btnBuy.setOnClickListener { onBuyItem(shop, item) }
            binding.shopList.addView(row.root)
        }
    }

    /** 购买入口：即时型走专属流程，普通道具入背包 */
    private fun onBuyItem(shop: ShopStore, item: ShopItem) {
        when (item.id) {
            ItemCatalog.ID_MAGNET -> buyMagnet(shop, item)
            ItemCatalog.ID_REWIND -> buyRewind(shop, item)
            ItemCatalog.ID_REFRESH -> buyRefreshTicket(shop, item)
            else -> {
                if (shop.buy(item)) {
                    Toast.makeText(this, "已购买「${item.name}」，放入背包", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 金币磁铁：购买立即激活 1 小时（可叠加延长） */
    private fun buyMagnet(shop: ShopStore, item: ShopItem) {
        val active = shop.isMagnetActive()
        AlertDialog.Builder(this)
            .setTitle("金币磁铁")
            .setMessage(
                if (active) "当前磁铁已在生效中，再次购买将在剩余时间上追加 1 小时。花费 ${item.price} 金币？"
                else "花费 ${item.price} 金币，激活 1 小时金币获取翻倍？"
            )
            .setPositiveButton("购买") { d, _ ->
                d.dismiss()
                if (!shop.trySpend(item.price)) {
                    Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                shop.extendMagnet(3_600_000)
                Toast.makeText(this, "🧲 金币翻倍已生效 1 小时", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("再想想", null)
            .show()
    }

    /** 时光回溯：找回最近一次未回溯的碎裂，按当次专注时长补发 1 张卡 */
    private fun buyRewind(shop: ShopStore, item: ShopItem) {
        val log = CollectionRepository.get(this).latestRewindableCrack()
        if (log == null) {
            Toast.makeText(this, "暂无可找回的碎裂记录", Toast.LENGTH_SHORT).show()
            return
        }
        val whenText = java.text.SimpleDateFormat(
            "MM-dd HH:mm:ss", Locale.CHINA
        ).format(java.util.Date(log.ts))
        AlertDialog.Builder(this)
            .setTitle("时光回溯")
            .setMessage(
                "将找回 $whenText 的碎裂（${log.reason.removeSuffix("…")}），" +
                    "按当次 ${log.minutes} 分钟专注时长补发 1 张随机掉落。\n花费 ${item.price} 金币？"
            )
            .setPositiveButton("回溯") { d, _ ->
                d.dismiss()
                if (!shop.trySpend(item.price)) {
                    Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val drop = CollectionRepository.get(this).rewindCrack()
                if (drop == null) {
                    Toast.makeText(this, "回溯失败：记录已失效", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "⏳ 找回成功：${drop.rarity.label} · ${CardCatalog.displayName(drop.cardId)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refresh()
            }
            .setNegativeButton("再想想", null)
            .show()
    }

    /** 刷新券：立即重刷卡片补给窗口（可无限次购买使用） */
    private fun buyRefreshTicket(shop: ShopStore, item: ShopItem) {
        AlertDialog.Builder(this)
            .setTitle("刷新卡片补给")
            .setMessage("花费 ${item.price} 金币，立刻换一批补给卡？（当前这批作废，包括未购买的）")
            .setPositiveButton("刷新") { d, _ ->
                d.dismiss()
                if (!shop.trySpend(item.price)) {
                    Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                shop.refreshCardRotation()
                Toast.makeText(this, "已换上新一批补给卡", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("再想想", null)
            .show()
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
