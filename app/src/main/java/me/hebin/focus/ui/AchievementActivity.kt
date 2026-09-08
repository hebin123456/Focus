package me.hebin.focus.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import me.hebin.focus.R
import me.hebin.focus.data.Achievements
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.databinding.ActivityAchievementBinding

/**
 * 成就页：点数概览 + 徽章佩戴 + 成就进度列表。
 * 行视图全部程序化构建（数量少、无复用需求，避免再写一套 Adapter）。
 */
class AchievementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAchievementBinding

    private val cTextPrimary = 0xFFF2F5FF.toInt()
    private val cTextSecondary = 0xFF9AA3C0.toInt()
    private val cMuted = 0xFF39415A.toInt()
    private val cAccent = 0xFFFFC94D.toInt()
    private val cTrack = 0xFF232B40.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAchievementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 打开成就页先补一次检查（用户可能刚从专注/领奖返回）
        Achievements.checkNew(CollectionRepository.get(this))
        render()
    }

    private fun render() {
        val repo = CollectionRepository.get(this)
        val pts = Achievements.points(repo)
        val current = Achievements.currentBadge(repo)
        val next = Achievements.nextBadge(repo)

        binding.textPoints.text = pts.toString()
        binding.textBadgeName.text = current?.title ?: "暂无徽章"
        binding.textBadgeName.setTextColor(current?.color ?: cTextSecondary)
        binding.textBadgeNext.text = when {
            next != null -> "距「${next.title}」还差 ${next.minPoints - pts} 点"
            else -> "已达成最高徽章！"
        }

        renderBadges(repo, pts)
        renderAchievements(repo)
    }

    // ---------------- 徽章行 ----------------

    private fun renderBadges(repo: CollectionRepository, pts: Int) {
        val row = binding.badgeRow
        row.removeAllViews()
        val equipped = repo.equippedBadgeId

        for (b in Achievements.badges) {
            val earned = pts >= b.minPoints

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(12), dp(4))
            }

            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_badge)
                val s = dp(46)
                layoutParams = LinearLayout.LayoutParams(s, s)
                if (earned) {
                    imageTintList = ColorStateList.valueOf(b.color)
                    alpha = if (equipped == b.id) 1f else 0.55f
                } else {
                    imageTintList = ColorStateList.valueOf(cMuted)
                    alpha = 0.6f
                }
            }

            val label = TextView(this).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                when {
                    !earned -> { text = "${b.minPoints}点解锁"; setTextColor(cTextSecondary) }
                    equipped == b.id -> { text = "佩戴中"; setTextColor(b.color); paint.isFakeBoldText = true }
                    else -> { text = b.title; setTextColor(cTextSecondary) }
                }
            }

            cell.addView(icon)
            cell.addView(label)
            cell.setOnClickListener {
                if (!earned) {
                    Toast.makeText(this, "达到 ${b.minPoints} 点解锁「${b.title}」", Toast.LENGTH_SHORT).show()
                } else if (equipped == b.id) {
                    repo.equippedBadgeId = null
                    Toast.makeText(this, "已取下「${b.title}」", Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    repo.equippedBadgeId = b.id
                    Toast.makeText(this, "已佩戴「${b.title}」，展示在主页", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            row.addView(cell)
        }
    }

    // ---------------- 成就列表 ----------------

    private fun renderAchievements(repo: CollectionRepository) {
        val list = binding.listAchievements
        list.removeAllViews()
        val unlocked = repo.unlockedAchievementIds()

        Achievements.defs.groupBy { it.category }.forEach { (cat, defs) ->
            list.addView(sectionHeader(cat))
            defs.forEach { def ->
                val (cur, target) = def.progress(repo)
                val done = def.id in unlocked
                list.addView(achievementRow(def, cur.coerceAtMost(target), target, done))
            }
        }
    }

    private fun sectionHeader(title: String): View =
        TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(cTextSecondary)
            paint.isFakeBoldText = true
            setPadding(dp(16), dp(16), dp(16), dp(6))
        }

    private fun achievementRow(def: Achievements.Def, cur: Int, target: Int, done: Boolean): View {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))

            // 第一行：标题 + 点数 + 状态
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(ctx).apply {
                    text = def.title
                    textSize = 15f
                    setTextColor(if (done) cAccent else cTextPrimary)
                    paint.isFakeBoldText = true
                })

                addView(TextView(ctx).apply {
                    text = "  +${def.points}点"
                    textSize = 12f
                    setTextColor(cTextSecondary)
                })

                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
                })

                addView(TextView(ctx).apply {
                    text = if (done) "已解锁" else "$cur/$target"
                    textSize = 12f
                    setTextColor(if (done) cAccent else cTextSecondary)
                })
            })

            // 第二行：描述
            addView(TextView(ctx).apply {
                text = def.desc
                textSize = 12f
                setTextColor(cTextSecondary)
                setPadding(0, dp(3), 0, 0)
            })

            // 第三行：进度条（已解锁则隐藏）
            addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                ).apply { topMargin = dp(8) }
                max = target
                progress = cur
                progressTintList = ColorStateList.valueOf(cAccent)
                progressBackgroundTintList = ColorStateList.valueOf(cTrack)
                isVisible = !done
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
