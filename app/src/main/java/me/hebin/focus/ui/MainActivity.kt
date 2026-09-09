package me.hebin.focus.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch
import me.hebin.focus.BuildConfig
import me.hebin.focus.R
import me.hebin.focus.ads.AdManager
import me.hebin.focus.ads.AdPlacement
import me.hebin.focus.ads.AdResult
import me.hebin.focus.audio.AmbientPrefs
import me.hebin.focus.data.Achievements
import me.hebin.focus.data.AvatarStore
import me.hebin.focus.data.CardCatalog
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.DailyLoginManager
import me.hebin.focus.data.DropEngine
import me.hebin.focus.data.Quotes
import me.hebin.focus.data.ShopStore
import me.hebin.focus.databinding.ActivityMainBinding
import me.hebin.focus.databinding.ItemCrackLogBinding
import me.hebin.focus.session.FocusSessionManager
import me.hebin.focus.ui.view.CardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 相册选头像（SAF，无需存储权限） */
    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (AvatarStore.saveFromUri(this, uri)) {
                Toast.makeText(this, "头像已更新", Toast.LENGTH_SHORT).show()
                refreshDrawerHeader()
            } else {
                Toast.makeText(this, "读取图片失败，请换一张试试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏沉浸：内容延伸到状态栏/导航栏/刘海底下，关键区域再由 insets 留白
        EdgeToEdge.enable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.pad(binding.mainScroll)
        EdgeToEdge.pad(binding.drawerPane)

        // 每日名言：每次启动随机一条
        setupQuote()

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

        // 深度专注模式：记忆上次选择
        binding.switchDeep.isChecked = AmbientPrefs.deepMode(this)
        binding.switchDeep.setOnCheckedChangeListener { _, checked ->
            AmbientPrefs.setDeepMode(this, checked)
        }

        binding.btnStart.setOnClickListener {
            val checkedId = binding.chipGroupDuration.checkedChipId
            val minutes = if (checkedId == -1) 25 else {
                durations.getOrNull(checkedId - R.id.chip_duration_base) ?: 25
            }
            startActivity(
                Intent(this, FocusActivity::class.java)
                    .putExtra(FocusActivity.EXTRA_MINUTES, minutes)
                    .putExtra(FocusActivity.EXTRA_DEEP, binding.switchDeep.isChecked)
            )
        }

        // ---- 侧边栏 ----
        binding.btnDrawer.setOnClickListener { binding.drawerLayout.openDrawer(Gravity.START) }
        binding.imgAvatarMini.setOnClickListener { showProfileSheet() }
        binding.drawerHeader.setOnClickListener { showProfileSheet() }
        binding.navProfile.setOnClickListener { showProfileSheet() }
        binding.navAchievement.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, AchievementActivity::class.java))
        }
        binding.navCollection.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, CollectionActivity::class.java))
        }
        binding.navWorkshop.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, WorkshopActivity::class.java))
        }
        binding.navShop.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, ShopActivity::class.java))
        }
        binding.navIcon.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            IconPicker.show(this)
        }
        binding.navTheme.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            showThemeDialog()
        }
        binding.statCardCollected.setOnClickListener {
            startActivity(Intent(this, CollectionActivity::class.java))
        }
        binding.statCardCracked.setOnClickListener { showCrackHistory() }
        binding.coinChip.setOnClickListener { startActivity(Intent(this, ShopActivity::class.java)) }
        binding.navReward.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, RewardActivity::class.java))
        }
        binding.navAbout.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            showAbout()
        }
        binding.imgBadge.setOnClickListener { startActivity(Intent(this, AchievementActivity::class.java)) }
        binding.textShowcaseMore.setOnClickListener { startActivity(Intent(this, CollectionActivity::class.java)) }
    }

    override fun onStart() {
        super.onStart()
        refreshStats()
        refreshDrawerHeader()
        refreshShowcase()
        handleSessionResult()
        checkDailyLogin()
    }

    override fun onResume() {
        super.onResume()
        coinTicker.run()
    }

    override fun onPause() {
        super.onPause()
        binding.textCoins.removeCallbacks(coinTicker)
    }

    /** 金币前台累积跳动（每 5 秒刷一次显示） */
    private val coinTicker = object : Runnable {
        override fun run() {
            binding.textCoins.text = ShopStore.get(this@MainActivity).currentCoins().toString()
            binding.textCoins.postDelayed(this, 5_000)
        }
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

        // 上次专注碎裂但用户还没被告知（最小化切走 / 进程被杀后回来）
        repo.takePendingCrack()?.let { pc ->
            val stat = pc.statLine()
            AlertDialog.Builder(this)
                .setTitle("卡片碎裂了")
                .setMessage(
                    buildString {
                        append("上次专注中途离开，卡片碎裂了\n")
                        if (stat.isNotEmpty()) append(stat).append('\n')
                        append("再试一次，坚持到底！")
                    }
                )
                .setPositiveButton("知道了") { d, _ -> d.dismiss() }
                .show()
        }
        checkAchievementUnlocks()
    }

    /** 外观主题：跟随系统 / 浅色 / 深色 */
    private fun showThemeDialog() {
        val current = ThemeStore.mode(this)
        AlertDialog.Builder(this)
            .setTitle("外观主题")
            .setSingleChoiceItems(ThemeStore.labels, current) { d, which ->
                d.dismiss()
                if (which != ThemeStore.mode(this)) ThemeStore.setMode(this, which)
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
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
        refreshDrawerHeader()
        refreshShowcase()
    }

    /** 主页收藏展示条：最近获得的卡片横滑展示 */
    private fun refreshShowcase() {
        val repo = CollectionRepository.get(this)
        val recent = repo.recentCards(12)
        val row = binding.showcaseRow
        row.removeAllViews()

        val hasCards = recent.isNotEmpty()
        binding.showcaseScroll.isVisible = hasCards
        binding.showcaseEmpty.isVisible = !hasCards
        binding.textShowcaseSub.text = if (hasCards) {
            "最近获得 · 共 ${repo.totalCardsOwned()} 张"
        } else "最近获得的卡片会展示在这里"

        recent.forEach { (id, rarity) ->
            val card = me.hebin.focus.ui.view.CardView(this).apply {
                mode = CardView.Mode.FACE
                this.rarity = rarity
                cardNumber = id
                locked = false
                layoutParams = LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(12)
                }
            }
            card.setOnClickListener { showCardDetail("我的卡片", id, rarity) }
            row.addView(card)
        }
    }

    private fun refreshStats() {
        val repo = CollectionRepository.get(this)
        binding.statMinutes.text = repo.totalFocusMinutes.toString()
        binding.statCollected.text = "${repo.collectedCount()}/${CardCatalog.TOTAL}"
        binding.statCracked.text = repo.crackedCount.toString()

        val equipped = Achievements.equippedBadge(repo)
        if (equipped != null) {
            binding.imgBadge.isVisible = true
            binding.imgBadge.imageTintList = ColorStateList.valueOf(equipped.color)
        } else {
            binding.imgBadge.isVisible = false
        }
    }

    /** 侧边栏头部：头像 / 昵称 / 佩戴勋章 / 点数 */
    private fun refreshDrawerHeader() {
        val repo = CollectionRepository.get(this)
        binding.drawerNickname.text = repo.nickname
        AvatarStore.render(this, binding.imgAvatar, repo.nickname)
        AvatarStore.render(this, binding.imgAvatarMini, repo.nickname)

        val pts = Achievements.points(repo)
        val equipped = Achievements.equippedBadge(repo)
        binding.drawerSub.text = "成就点数 $pts · ${equipped?.title ?: "未佩戴勋章"}"
        if (equipped != null) {
            binding.drawerBadge.isVisible = true
            binding.drawerBadge.imageTintList = ColorStateList.valueOf(equipped.color)
        } else {
            binding.drawerBadge.isVisible = false
        }
    }

    // ---------------- 个人资料 BottomSheet ----------------

    private fun showProfileSheet() {
        val repo = CollectionRepository.get(this)
        val sheet = BottomSheetDialog(this)
        val pad = dp(20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 头像（点击 → 从相册选择）
        val avatar = ShapeableImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dp(48).toFloat())
                .build()
        }
        AvatarStore.render(this, avatar, repo.nickname)
        avatar.setOnClickListener { pickAvatar.launch("image/*") }

        root.addView(TextView(this).apply {
            text = "个人资料"
            textSize = 18f
            setTextColor(color(R.color.textPrimary))
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(avatar)

        root.addView(TextView(this).apply {
            text = "点击头像从相册更换"
            textSize = 12f
            setTextColor(color(R.color.textSecondary))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        })

        // 预置头像色板
        root.addView(TextView(this).apply {
            text = "或选择配色生成头像"
            textSize = 13f
            setTextColor(color(R.color.textPrimary))
            setPadding(0, pad, 0, dp(8))
        })

        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        AvatarStore.presets.forEach { color ->
            val swatch = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    setMargins(0, 0, dp(10), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }
            swatch.setOnClickListener {
                if (AvatarStore.savePreset(this@MainActivity, color, repo.nickname)) {
                    Toast.makeText(this@MainActivity, "头像已生成", Toast.LENGTH_SHORT).show()
                    AvatarStore.render(this@MainActivity, avatar, repo.nickname)
                    refreshDrawerHeader()
                }
            }
            colorRow.addView(swatch)
        }
        root.addView(colorRow)

        // 昵称
        root.addView(TextView(this).apply {
            text = "昵称"
            textSize = 13f
            setTextColor(color(R.color.textPrimary))
            setPadding(0, pad, 0, dp(6))
        })
        val input = EditText(this).apply {
            setText(repo.nickname)
            hint = "输入昵称"
            setTextColor(color(R.color.textPrimary))
            setHintTextColor(color(R.color.textSecondary))
            inputType = InputType.TYPE_CLASS_TEXT
            backgroundTintList = ColorStateList.valueOf(color(R.color.primary))
        }
        root.addView(input)

        // 佩戴勋章行
        val pts = Achievements.points(repo)
        val equipped = Achievements.equippedBadge(repo)
        root.addView(TextView(this).apply {
            text = "当前勋章"
            textSize = 13f
            setTextColor(color(R.color.textPrimary))
            setPadding(0, pad, 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = if (equipped != null) "佩戴中：${equipped.title}（点数 $pts）"
            else if (Achievements.currentBadge(repo) != null) "已获得勋章但未佩戴（点数 $pts）"
            else "暂无勋章（点数 $pts），达成成就可以解锁勋章"
            textSize = 14f
            setTextColor(color(R.color.textSecondary))
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                startActivity(Intent(this@MainActivity, AchievementActivity::class.java))
            }
            setPadding(0, 0, 0, dp(4))
        })

        // 保存
        root.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "保存资料"
            cornerRadius = dp(14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = pad }
            setOnClickListener {
                repo.nickname = input.text.toString()
                // 若没自定义头像，预置头像字母跟着换
                refreshDrawerHeader()
                Toast.makeText(this@MainActivity, "资料已保存", Toast.LENGTH_SHORT).show()
                sheet.dismiss()
            }
        })

        sheet.setContentView(root)
        sheet.show()
    }

    // ---------------- 每日名言 ----------------

    /** 当前展示的名言索引（弹窗内「换一句」时用于排除当前条） */
    private var quoteIndex = -1

    /** 主页名言条：随机一条 + 点击弹出作者卡片 */
    private fun setupQuote() {
        val (q, idx) = Quotes.pick(this)
        quoteIndex = idx
        binding.textQuote.text = q.text
        binding.quoteCard.setOnClickListener { showQuoteCard() }
    }

    /** 名言卡片弹窗：完整正文 + 作者身份，可「换一句」（同步更新主页条） */
    private fun showQuoteCard() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_quote, null)
        val textFull = view.findViewById<TextView>(R.id.textQuoteFull)
        val textAuthor = view.findViewById<TextView>(R.id.textQuoteAuthor)
        val textTitle = view.findViewById<TextView>(R.id.textQuoteTitle)
        val body = view.findViewById<View>(R.id.quoteBody)

        fun render(q: Quotes.Quote) {
            textFull.text = q.text
            textAuthor.text = q.author
            textTitle.text = "— ${q.title}"
        }
        render(Quotes.all.getOrElse(quoteIndex) { Quotes.all.first() })

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuoteNext)
            .setOnClickListener {
                val (q, idx) = Quotes.pickAnother(quoteIndex)
                quoteIndex = idx
                Quotes.remember(this, idx) // 下次启动避开这一条
                render(q)
                binding.textQuote.text = q.text
                // 内容轻扫淡入，提示已切换
                body.alpha = 0f
                body.animate().alpha(1f).setDuration(220).start()
            }

        AlertDialog.Builder(this)
            .setTitle("每日一言")
            .setView(view)
            .setPositiveButton("知道了") { d, _ -> d.dismiss() }
            .show()
    }

    // ---------------- 关于 ----------------

    private fun showAbout() {
        val msg = """
            版本 v${BuildConfig.VERSION_NAME}（本地资料版）

            Focus 是一款游戏化专注 App：专注时卡片以剪影慢慢生成，中途离开 App 卡片会碎裂；坚持到底翻卡收入图鉴。

            · 101 张萌宠卡 × 5 种稀有度，集齐图鉴可兑换奖励
            · 卡片工坊：分解随机得 3 张低级卡，3 张合成 1 张高级卡（结果随机）
            · 金币与道具商店：App 前台挂机攒金币，也支持充值（支付通道接入中）
            · 图鉴卡片一键生成分享图，晒到其他 App
            · 每日一言：主页名人名言每次启动随机换
            · 白噪音：雨声 / 海浪 / 炉火 / 白噪程序化合成，专注时可选可调音量
            · 深度专注：全屏沉浸 + 屏幕固定，完成金币翻倍
            · 自定义图标：用收集到的萌宠当桌面图标，样式跟随稀有度
            · 每日登录送卡，连续越久卡越好（联网校验时间）
            · 成就点数解锁青铜/白银/黄金/钻石勋章，可佩戴展示
            · 资料仅保存在本地，无账号无上传

            开源仓库：github.com/hebin123456/Focus
        """.trimIndent()
        val dialog = AlertDialog.Builder(this)
            .setTitle("关于 Focus")
            .setMessage(msg)
            .setPositiveButton("好的") { d, _ -> d.dismiss() }
            .show()

        // 隐藏调试：连点 5 下清空收集，连点 10 下全收集全成就。
        // 由 gradle.properties 的 focusDebugTools 控制，发布时关闭即可随编译剔除。
        if (!BuildConfig.DEBUG_TOOLS) return
        var taps = 0
        var lastTap = 0L
        val tapListener = View.OnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTap > 800) taps = 0
            lastTap = now
            taps++
            if (taps == 5) {
                CollectionRepository.get(this).debugWipe()
                refreshStats(); refreshShowcase(); refreshDrawerHeader()
                binding.textCoins.text = ShopStore.get(this).currentCoins().toString()
                Toast.makeText(this, "调试：已清空全部收集与进度", Toast.LENGTH_SHORT).show()
            } else if (taps == 10) {
                CollectionRepository.get(this).debugUnlockAll()
                refreshStats(); refreshShowcase(); refreshDrawerHeader()
                binding.textCoins.text = ShopStore.get(this).currentCoins().toString()
                Toast.makeText(this, "调试：已解锁全图鉴与全成就，金币 99999，累计专注 6000 分钟", Toast.LENGTH_SHORT).show()
            }
        }
        // 标题和正文都可点
        listOfNotNull(
            dialog.findViewById<View>(android.R.id.title),
            dialog.findViewById<View>(android.R.id.message)
        ).forEach { it.setOnClickListener(tapListener) }
    }

    // ---------------- 碎裂记录 ----------------

    /** 碎裂记录弹窗：按时间倒序、每页 20 条、点「加载更多」翻页 */
    private fun showCrackHistory() {
        val repo = CollectionRepository.get(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_crack_log, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerCrackLog)
        val btnMore = view.findViewById<View>(R.id.btnMoreCracks)
        val textEmpty = view.findViewById<View>(R.id.textCrackEmpty)

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val logs = mutableListOf<CollectionRepository.CrackLog>()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                ItemCrackLogBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
            ) {}

            override fun getItemCount() = logs.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val b = ItemCrackLogBinding.bind(holder.itemView)
                val l = logs[pos]
                b.crackReason.text = l.reason
                b.crackTime.text = if (l.ts > 0) fmt.format(Date(l.ts)) else ""
                b.crackStat.text = l.statLine()
            }
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        fun loadMore() {
            val page = repo.crackLogs(20, logs.size)
            logs.addAll(page)
            adapter.notifyDataSetChanged()
            btnMore.isVisible = page.size == 20 // 还有下一页
        }
        loadMore()
        textEmpty.isVisible = logs.isEmpty()
        btnMore.setOnClickListener { loadMore() }

        AlertDialog.Builder(this)
            .setTitle("碎裂记录（共 ${repo.crackedCount} 次）")
            .setView(view)
            .setPositiveButton("关闭") { d, _ -> d.dismiss() }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 主题色（随浅色/深色切换） */
    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    // ---------------- 掉落弹窗 ----------------

    /** 通用卡片详情弹窗（我的收藏入口） */
    private fun showCardDetail(title: String, cardId: Int, rarity: me.hebin.focus.data.Rarity) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card_detail, null)
        val card = view.findViewById<CardView>(R.id.detailCard)
        card.mode = CardView.Mode.FACE
        card.rarity = rarity
        card.cardNumber = cardId
        card.locked = false

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("确认") { d, _ -> d.dismiss() }
            .show()

        // 收藏里的卡都是已持有的，提供「设为自定义图标」与「分享」入口
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSetAsIcon).apply {
            isVisible = true
            setOnClickListener {
                IconPicker.confirmCard(this@MainActivity, cardId, rarity) { dialog.dismiss() }
            }
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareCard).apply {
            isVisible = true
            setOnClickListener { ShareCard.share(this@MainActivity, cardId, rarity) }
        }
    }

    /** 专注完成后的掉落展示（频控内提供看广告补领一张） */
    private fun showDrop(drop: DropEngine.Drop) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card_detail, null)
        val card = view.findViewById<CardView>(R.id.detailCard)
        card.mode = CardView.Mode.FACE
        card.rarity = drop.rarity
        card.cardNumber = drop.cardId
        card.locked = false

        val builder = AlertDialog.Builder(this)
            .setTitle("专注完成，获得卡片！")
            .setView(view)
            .setPositiveButton("收下") { d, _ -> d.dismiss() }

        // 激励视频补领：只在频控可用时露出入口
        if (AdManager.isAvailable(this, AdPlacement.REWARD_EXTRA_CARD)) {
            builder.setNeutralButton("看广告再领1张") { d, _ ->
                d.dismiss()
                AdManager.maybeShow(this, AdPlacement.REWARD_EXTRA_CARD) { result ->
                    if (result is AdResult.Rewarded) {
                        runOnUiThread {
                            val extra = DropEngine.roll(25) // 补领按标准 25 分钟档掉落
                            CollectionRepository.get(this).addCard(extra.cardId, extra.rarity)
                            checkAchievementUnlocks() // 顺带刷新主页统计/收藏条
                            showDrop(extra) // 展示补领结果（频控已记录，不再弹广告入口）
                        }
                    }
                }
            }
        }

        builder.show()

        // 刚收服的卡，随手晒一张
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareCard).apply {
            isVisible = true
            setOnClickListener { ShareCard.share(this@MainActivity, drop.cardId, drop.rarity) }
        }
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
            setTextColor(color(R.color.textSecondary))
        }
        rows.addView(hint)

        AlertDialog.Builder(this)
            .setTitle("每日登录奖励")
            .setView(view)
            .setPositiveButton("收下") { d, _ -> d.dismiss() }
            .show()
    }
}
