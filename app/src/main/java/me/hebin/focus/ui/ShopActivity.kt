package me.hebin.focus.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import me.hebin.focus.data.ItemCatalog
import me.hebin.focus.data.ShopStore
import me.hebin.focus.databinding.ActivityShopBinding
import me.hebin.focus.databinding.ItemBagRowBinding
import me.hebin.focus.databinding.ItemShopRowBinding

/**
 * 道具商店：金币余额 + 商店列表 + 我的背包。
 * 金币在 App 前台时线性累积（每满 1 分钟 +1）；道具在卡片工坊使用。
 */
class ShopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopBinding

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
    }

    private fun refresh() {
        val shop = ShopStore.get(this)
        binding.textCoinCount.text = shop.currentCoins().toString()
        binding.progressNextCoin.progress = (shop.nextCoinProgress() * 100).toInt()
        renderShop(shop)
        renderBag(shop)
    }

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
