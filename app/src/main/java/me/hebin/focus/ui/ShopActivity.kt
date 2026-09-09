package me.hebin.focus.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.hebin.focus.R
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.ItemCatalog
import me.hebin.focus.data.ShopCardEntry
import me.hebin.focus.data.ShopItem
import me.hebin.focus.data.ShopStore
import me.hebin.focus.ads.AdResult
import me.hebin.focus.ads.AdRewardManager
import me.hebin.focus.databinding.ActivityShopBinding
import me.hebin.focus.databinding.ItemBagTileBinding
import me.hebin.focus.databinding.ItemShopCardBinding
import me.hebin.focus.databinding.ItemShopTileBinding
import me.hebin.focus.payment.PaymentManager
import me.hebin.focus.payment.PaymentResult
import me.hebin.focus.ui.view.CardView
import java.util.Locale

/**
 * 道具商店：金币余额 + 卡片补给（每小时随机刷新，每张限购 1 次，可花 30 金币立即换一批）
 * + 道具网格（3 列竖版小卡）+ 我的背包（点击查看，磁铁在背包手动激活）。
 * 金币在 App 前台时线性累积（每满 1 分钟 +1）；也可充值（模拟支付）或每日看 2 次广告各 +10。
 * 所有购买均需二次确认。
 */
class ShopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopBinding

    /** 每秒刷新倒计时；到点自动重刷补给窗口。
     *  注意：只在「跨整点」和「磁铁刚到期」两种边界整页重绘，
     *  平时只改文本 —— 否则每秒重建网格会把点击中的按钮换掉，导致买不了东西。
     */
    private val ticker = Handler(Looper.getMainLooper())
    private var magnetWasActive = false

    private val tick = object : Runnable {
        override fun run() {
            val shop = ShopStore.get(this@ShopActivity)
            val left = shop.cardRotationNextAt() - System.currentTimeMillis()
            if (left <= 0) {
                refresh() // 跨整点：重刷补给窗口并重绘全页（refresh 内部会重新 post tick）
                return
            }
            binding.textCardRefresh.text = String.format(
                Locale.CHINA, "%02d:%02d 后刷新", left / 60_000, left % 60_000 / 1000
            )
            // 磁铁倒计时：只更新金币卡的提示文本，不重绘
            val magnetText = magnetRemainText()
            if (magnetText != null) {
                binding.textRateHint.text = magnetText
                magnetWasActive = true
            } else if (magnetWasActive) {
                // 磁铁刚到期：整页重绘一次恢复正常文案
                magnetWasActive = false
                refresh()
                return
            }
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        binding = ActivityShopBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.pad(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefreshCards.setOnClickListener {
            confirmRefreshCards(ShopStore.get(this))
        }
        // 金币充值：支付通道接入前先展示档位（PaymentManager 统一收口）
        binding.btnBuyCoins.setOnClickListener { showCoinShop() }
        // 每日看广告领金币（当前模拟激励视频，AdRewardManager 统一收口）
        binding.btnWatchAd.setOnClickListener { watchAd() }
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
        renderCoinCard(shop)
        renderCardShop(shop)
        renderShop(shop)
        renderBag(shop)
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 金币充值（支付接入留口） ----------

    /**
     * 金币充值底部面板：2 列档位卡片（金币数 / 角标 / 价格），点击档位拉起支付，
     * 支付成功余额实时刷新、面板不关闭可继续买。
     * 档位与支付回调统一走 PaymentManager——当前挂 Stub 模拟收银台（点确认必成功），
     * 接真实 SDK（微信 / 支付宝 / IAP）时业务代码零改动。
     */
    private fun showCoinShop() {
        val skus = PaymentManager.coinSkus
        val available = PaymentManager.isAvailable(this)
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_coin_shop, null)

        // 当前余额（支付成功后原地刷新）
        val balanceText = view.findViewById<TextView>(R.id.textSheetBalance)
        fun renderBalance() {
            balanceText.text = "当前余额 ${ShopStore.get(this).currentCoins()} 金币"
        }
        renderBalance()

        view.findViewById<TextView>(R.id.textSheetNote).text =
            if (available) "选择档位完成支付，金币立即到账"
            else "支付通道接入中，档位抢先看：\n金币也可以靠前台挂机慢慢攒哦"
        view.findViewById<TextView>(R.id.textSheetFooter).isVisible = available

        val grid = view.findViewById<GridLayout>(R.id.gridCoinSkus)
        skus.forEach { sku ->
            val card = layoutInflater.inflate(R.layout.item_coin_sku, grid, false)
            card.findViewById<TextView>(R.id.textSkuCoins).text = sku.coins.toString()
            card.findViewById<TextView>(R.id.textSkuPrice).text = sku.priceLabel
            card.findViewById<TextView>(R.id.textSkuTag).apply {
                if (sku.tag != null) {
                    text = sku.tag
                    isVisible = true
                }
            }
            card.setOnClickListener {
                PaymentManager.purchaseCoins(this, sku) { r ->
                    when (r) {
                        is PaymentResult.Success -> {
                            Toast.makeText(
                                this, "充值成功：+${r.sku.coins} 金币", Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                            renderBalance()
                        }
                        is PaymentResult.Failed ->
                            Toast.makeText(this, r.reason, Toast.LENGTH_SHORT).show()
                        PaymentResult.Cancelled -> Unit
                    }
                }
            }
            grid.addView(card)
        }
        sheet.setContentView(view)
        sheet.show()
    }

    // ---------- 每日看广告（广告接入留口） ----------

    /**
     * 看激励广告 +10 金币，每日 2 次（跨天自动重置）。
     * 次数限制与发奖统一在 AdRewardManager，接入真实 SDK 时业务代码零改动。
     */
    private fun watchAd() {
        AdRewardManager.showRewardedAd(this) { r ->
            when (r) {
                is AdResult.Rewarded -> {
                    Toast.makeText(
                        this,
                        "🎬 广告奖励 +${AdRewardManager.REWARD_COINS} 金币",
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
                is AdResult.Failed ->
                    Toast.makeText(this, r.reason, Toast.LENGTH_SHORT).show()
                AdResult.Cancelled ->
                    Toast.makeText(this, "看完广告才能领奖励哦", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 金币卡（磁铁激活时提示行显示倒计时） ----------

    private fun renderCoinCard(shop: ShopStore) {
        binding.textCoinCount.text = shop.currentCoins().toString()
        binding.progressNextCoin.progress = (shop.nextCoinProgress() * 100).toInt()
        binding.textRateHint.text = magnetRemainText() ?: "App 在前台时每满 1 分钟 +1 金币"
        renderAdButton()
    }

    /** 广告按钮状态：剩余次数 / 今日已看完 */
    private fun renderAdButton() {
        val left = AdRewardManager.leftToday(this)
        binding.btnWatchAd.isEnabled = left > 0
        binding.btnWatchAd.text =
            if (left > 0) "🎬 广告 +${AdRewardManager.REWARD_COINS}（剩 $left）"
            else "今日已看完"
    }

    private fun magnetRemainText(): String? {
        val left = ShopStore.get(this).magnetActiveUntil() - System.currentTimeMillis()
        if (left <= 0) return null
        val h = left / 3_600_000
        val m = left % 3_600_000 / 60_000
        val s = left % 60_000 / 1000
        return if (h > 0) "🧲 翻倍中 · 剩余 ${h}:${m}"
        else "🧲 翻倍中 · 剩余 %02d:%02d".format(m, s)
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
                when (shop.buyCard(e.cardId, e.rarity)) {
                    ShopStore.BuyCardResult.OK ->
                        Toast.makeText(this, "已获得 $name · ${e.rarity.label}", Toast.LENGTH_SHORT).show()
                    ShopStore.BuyCardResult.NO_COINS ->
                        Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                    ShopStore.BuyCardResult.ALREADY_BOUGHT ->
                        Toast.makeText(this, "这张已经买过了", Toast.LENGTH_SHORT).show()
                    ShopStore.BuyCardResult.WINDOW_REFRESHED ->
                        Toast.makeText(this, "补给刚刷新，这批卡片已下架", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .setNegativeButton("再想想", null)
            .show()
    }

    /** 补给区 ⟳：花 30 金币立即换一批补给卡 */
    private fun confirmRefreshCards(shop: ShopStore) {
        AlertDialog.Builder(this)
            .setTitle("刷新卡片补给")
            .setMessage(
                "花费 ${ItemCatalog.CARD_REFRESH_COST} 金币，立刻换一批补给卡？\n" +
                    "当前这批作废，包括未购买的"
            )
            .setPositiveButton("刷新") { d, _ ->
                d.dismiss()
                if (!shop.trySpend(ItemCatalog.CARD_REFRESH_COST)) {
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

    // ---------- 道具网格 ----------

    private fun renderShop(shop: ShopStore) {
        binding.shopList.removeAllViews()
        val items = ItemCatalog.items
        binding.textShopEmpty.isVisible = items.isEmpty()
        items.forEach { item ->
            val tile = ItemShopTileBinding.inflate(layoutInflater, binding.shopList, false)
            tile.textItemName.text = item.name
            tile.textItemDesc.text = item.desc
            tile.textItemPrice.text = "${item.price} 金币"

            // 时光回溯：没有可找回的碎裂记录时禁用购买
            if (item.id == ItemCatalog.ID_REWIND) {
                val rewindable = CollectionRepository.get(this).latestRewindableCrack()
                tile.btnBuy.isEnabled = rewindable != null
                if (rewindable == null) tile.textItemDesc.text = "暂无可找回的碎裂记录（有过碎裂后可用）"
            }

            tile.btnBuy.setOnClickListener { onBuyItem(shop, item) }
            addGridTile(binding.shopList, tile.root)
        }
    }

    /** 购买入口：时光回溯有专属流程（要选碎裂记录），其余统一二次确认 */
    private fun onBuyItem(shop: ShopStore, item: ShopItem) {
        when (item.id) {
            ItemCatalog.ID_REWIND -> buyRewind(shop, item)
            else -> confirmBuyItem(shop, item)
        }
    }

    /** 二次确认：金币来之不易，避免误触 */
    private fun confirmBuyItem(shop: ShopStore, item: ShopItem) {
        AlertDialog.Builder(this)
            .setTitle("购买「${item.name}」")
            .setMessage("${item.desc}\n\n花费 ${item.price} 金币购买？")
            .setPositiveButton("购买") { d, _ ->
                d.dismiss()
                if (shop.buy(item)) {
                    Toast.makeText(this, "已购买「${item.name}」，放入背包", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "金币不足，再挂一会儿吧", Toast.LENGTH_SHORT).show()
                }
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

    // ---------- 背包网格 ----------

    private fun renderBag(shop: ShopStore) {
        binding.bagList.removeAllViews()
        val owned = shop.inventory()
            .mapNotNull { (id, n) -> ItemCatalog.byId(id)?.let { it to n } }
            .sortedBy { it.first.id }
        binding.textBagEmpty.isVisible = owned.isEmpty()
        owned.forEach { (item, n) ->
            val tile = ItemBagTileBinding.inflate(layoutInflater, binding.bagList, false)
            tile.textBagName.text = item.name
            tile.textBagCount.text = "×$n"
            tile.textBagDesc.text = item.desc
            tile.root.setOnClickListener { onBagItemTap(item, n) }
            addGridTile(binding.bagList, tile.root)
        }
    }

    /** 背包道具详情：磁铁手动激活，其余展示说明与使用位置 */
    private fun onBagItemTap(item: ShopItem, count: Int) {
        if (item.id == ItemCatalog.ID_MAGNET) {
            val shop = ShopStore.get(this)
            val active = shop.isMagnetActive()
            AlertDialog.Builder(this)
                .setTitle("${item.name} ×$count")
                .setMessage(
                    "${item.desc}\n\n" + if (active)
                        "磁铁生效中，现在使用将在剩余时间上追加 1 小时。使用 1 个？"
                    else "使用 1 个，激活 1 小时金币获取翻倍？"
                )
                .setPositiveButton("使用") { d, _ ->
                    d.dismiss()
                    if (!shop.consumeItem(item.id)) {
                        Toast.makeText(this, "背包里没有「${item.name}」了", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    shop.extendMagnet(3_600_000)
                    Toast.makeText(this, "🧲 金币翻倍已生效 1 小时", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("${item.name} ×$count")
                .setMessage("${item.desc}\n\n${whereToUse(item.id)}")
                .setPositiveButton("好的", null)
                .show()
        }
    }

    private fun whereToUse(id: String): String = when (id) {
        ItemCatalog.ID_FORGE, ItemCatalog.ID_SPLIT, ItemCatalog.ID_SWAP,
        ItemCatalog.ID_MEGA_SWAP, ItemCatalog.ID_UPGRADE -> "在卡片工坊中使用"
        else -> "满足条件时自动消耗，无需手动操作"
    }

    /** 往 3 列网格加一个等宽单元格（列权重 1） */
    private fun addGridTile(parent: GridLayout, child: View) {
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        )
        lp.width = 0
        val m = dp(4)
        lp.setMargins(m, m, m, m)
        parent.addView(child, lp)
    }
}
