package me.hebin.focus.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.hebin.focus.data.DropEngine
import me.hebin.focus.databinding.ActivityMainBinding
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.session.FocusSessionManager
import me.hebin.focus.ui.view.CardView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val durations = listOf(10, 25, 45, 60, 90, 120)
        durations.forEachIndexed { i, m ->
            binding.chipGroupDuration.addView(
                layoutInflater.inflate(
                    me.hebin.focus.R.layout.item_chip_duration, binding.chipGroupDuration, false
                ).apply {
                    id = me.hebin.focus.R.id.chip_duration_base + i
                    text = "${m} 分钟"
                    tag = m
                    isCheckable = true
                }
            )
        }
        binding.chipGroupDuration.check(me.hebin.focus.R.id.chip_duration_base + 1) // 默认 25 分钟

        binding.btnStart.setOnClickListener {
            val checkedId = binding.chipGroupDuration.checkedChipId
            val minutes = if (checkedId == -1) 25 else {
                durations.getOrNull(checkedId - me.hebin.focus.R.id.chip_duration_base) ?: 25
            }
            startActivity(
                Intent(this, FocusActivity::class.java)
                    .putExtra(FocusActivity.EXTRA_MINUTES, minutes)
            )
        }

        binding.cardCollection.setOnClickListener {
            startActivity(Intent(this, CollectionActivity::class.java))
        }
        binding.cardReward.setOnClickListener {
            startActivity(Intent(this, RewardActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        refreshStats()

        // 会话结果兜底：进程被杀后重启，或从后台完成回来
        val pending = CollectionRepository.get(this).takePendingDrop()
        val state = FocusSessionManager.state
        when {
            state is FocusSessionManager.State.Success -> showDrop(state.drop, fromBackground = true)
            state is FocusSessionManager.State.Cracked -> { /* 已在专注页看过 */ }
            pending != null -> showDrop(pending, fromBackground = true)
        }
        if (state is FocusSessionManager.State.Success) FocusSessionManager.reset()
    }

    private fun refreshStats() {
        val repo = CollectionRepository.get(this)
        binding.statMinutes.text = repo.totalFocusMinutes.toString()
        binding.statCollected.text = "${repo.collectedCount()}/100"
        binding.statCracked.text = repo.crackedCount.toString()
    }

    private fun showDrop(drop: DropEngine.Drop, fromBackground: Boolean) {
        val view = LayoutInflater.from(this).inflate(me.hebin.focus.R.layout.dialog_card_detail, null)
        val card = view.findViewById<CardView>(me.hebin.focus.R.id.detailCard)
        card.mode = CardView.Mode.FACE
        card.rarity = drop.rarity
        card.cardNumber = drop.cardId
        card.locked = false

        AlertDialog.Builder(this)
            .setTitle("专注完成，获得卡片！")
            .setView(view)
            .setPositiveButton("收下") { d, _ -> d.dismiss() }
            .show()
    }
}
