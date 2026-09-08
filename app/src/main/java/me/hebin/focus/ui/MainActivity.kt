package me.hebin.focus.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.hebin.focus.R
import me.hebin.focus.data.Achievements
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.DailyLoginManager
import me.hebin.focus.data.DropEngine
import me.hebin.focus.databinding.ActivityMainBinding
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
            val chip = layoutInflater.inflate(
                R.layout.item_chip_duration, binding.chipGroupDuration, false
            ) as com.google.android.material.chip.Chip
            chip.id = R.id.chip_duration_base + i
            chip.text = "${m} 分钟"
            chip.tag = m
            chip.isCheckable = true
            binding.chipGroupDuration.addView(chip)
        }
        binding.chipGroupDuration.check(R.id.chip_duration_base + 1) // 默认 25 分钟

        binding.btnStart.setOnClickListener {
            val checkedId = binding.chipGroupDuration.checkedChipId
            val minutes = if (checkedId == -1) 25 else {
                durations.getOrNull(checkedId - R.id.chip_duration_base) ?: 25
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
        binding.cardAchievements.setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }
        binding.imgBadge.setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        refreshStats()
        handleSessionResult()
        checkDailyLogin()
    }

    /** 专注会话结果兜底（后台完成 / 进程被杀后重启） */
    private fun handleSessionResult() {
        val repo = CollectionRepository.get(this)
        val pending = repo.takePendingDrop()
        val state = FocusSessionManager.state
        when {
            state is FocusSessionManager.State.Success -> showDrop(state.drop)
            pending != null -> showDrop(pending)
        }
        if (state is FocusSessionManager.State.Success) FocusSessionManager.reset()
        checkAchievementUnlocks()
    }

    /** 每日登录：联网校验时间 → 发卡弹窗 */
    private fun checkDailyLogin() {
        lifecycleScope.launch {
            when (val r = DailyLoginManager.checkAndClaim(this@MainActivity)) {
                is DailyLoginManager.Result.Claimed -> {
                    showDailyReward(r)
                    checkAchievementUnlocks()
                }
                is DailyLoginManager.Result.Rejected ->
                    Toast.makeText(
                        this@MainActivity, "时间校验异常，今日登录奖励暂缓发放", Toast.LENGTH_LONG
                    ).show()
                else -> Unit // 已领过 / 离线等待，静默
            }
        }
    }

    /** 成就检查 + 新解锁提示 */
    private fun checkAchievementUnlocks() {
        val repo = CollectionRepository.get(this)
        val fresh = Achievements.checkNew(repo)
        fresh.forEach {
            Toast.makeText(this, "成就解锁：${it.title} +${it.points} 点", Toast.LENGTH_SHORT).show()
        }
        refreshStats()
    }

    private fun refreshStats() {
        val repo = CollectionRepository.get(this)
        binding.statMinutes.text = repo.totalFocusMinutes.toString()
        binding.statCollected.text = "${repo.collectedCount()}/100"
        binding.statCracked.text = repo.crackedCount.toString()

        val pts = Achievements.points(repo)
        val badge = Achievements.currentBadge(repo)
        binding.textAchievementsSub.text = "点数 $pts · ${badge?.title ?: "暂无徽章"}"

        val equipped = Achievements.equippedBadge(repo)
        if (equipped != null) {
            binding.imgBadge.isVisible = true
            binding.imgBadge.imageTintList = ColorStateList.valueOf(equipped.color)
        } else {
            binding.imgBadge.isVisible = false
        }
    }

    /** 专注完成后的掉落展示 */
    private fun showDrop(drop: DropEngine.Drop) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card_detail, null)
        val card = view.findViewById<CardView>(R.id.detailCard)
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

    /** 每日登录奖励展示 */
    private fun showDailyReward(r: DailyLoginManager.Result.Claimed) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card_detail, null)
        val card = view.findViewById<CardView>(R.id.detailCard)
        card.mode = CardView.Mode.FACE
        card.rarity = r.rarity
        card.cardNumber = r.cardId
        card.locked = false

        val rows = view.findViewById<ViewGroup>(R.id.detailRarityRows)
        val hint = LayoutInflater.from(this).inflate(R.layout.item_rarity_row, rows, false)
        hint.findViewById<TextView>(R.id.rowRarity).apply {
            text = "已连续登录 ${r.streak} 天，坚持打卡奖励会升级"
            setTextColor(0xFF9AA3C0.toInt())
        }
        rows.addView(hint)

        AlertDialog.Builder(this)
            .setTitle("每日登录奖励")
            .setView(view)
            .setPositiveButton("收下") { d, _ -> d.dismiss() }
            .show()
    }
}
