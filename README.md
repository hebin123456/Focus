# Focus

游戏化专注力 App：专注时卡片以剪影形态慢慢生成，中途离开 App 卡片会碎裂；坚持到底则翻卡揭示稀有度并收入图鉴。

## 核心玩法

| 机制 | 说明 |
|---|---|
| 专注计时 | 10/25/45/60/90/120 分钟可选，屏幕常亮 |
| 剪影生成 | 专注期间卡片以黑色剪影 + 流光效果自下而上渐显 |
| 离开惩罚 | 切出 App（非锁屏）→ 卡片碎裂为 6 块碎片飞散 |
| 掉落规则 | 专注时间越长，高稀有度概率越高；不同编号概率不同 |
| 卡牌体系 | 101 张萌宠卡（哺乳 21 / 鸟类 20 / 海洋 20 / 爬行两栖 20 / 昆虫软体 20）× 5 种稀有度（普/铜/银/金/钻），金/钻卡有辉光；卡面为 Q 版贴纸风插画 + 名称 + 编号 |
| 图鉴系统 | 4 列网格展示收集进度，未收集以暗色剪影展示动物轮廓；点开可看各稀有度持有数量 |
| 卡片工坊 | **分解**：1 张卡（普卡除外）→ 3 张随机低一级稀有度卡；**合成**：3 张同稀有度（可不同编号）→ 1 张随机高一级卡，3 张钻石合成随机钻石卡。产物编号随机（沿用掉落权重），无法定向凑卡 |
| 金币系统 | App 在前台时线性累积，每满 1 分钟 +1；主页顶栏实时跳动，前台期间每 30 秒入账防进程被杀丢进度 |
| 道具商店 | 金币购买道具放入背包；道具清单暂空（`ItemCatalog` 一行补一条即可上线） |
| 自定义图标 | 从已收集的图鉴卡里选一张当桌面图标，样式跟随该卡稀有度（普卡朴素 → 钻石青色辉光圈）；activity-alias 预置 101×5+1 共 506 个别名运行时切换，Android 8.0+ |
| 每日登录 | 每天首次打开送随机卡；联网校验时间防改本地时间；连续登录越久卡越好（第 1 天≈10 分钟档，第 23 天起封顶 120 分钟档） |
| 成就系统 | 20 个成就 × 点数（总计 525 点）：连续登录天数、集齐普/铜/银/金/钻各 101 张、专注时长/次数、首次金卡/钻石卡 |
| 徽章系统 | 点数达 30/80/150/250 解锁青铜/白银/黄金/钻石徽章，可佩戴展示在主页标题旁 |
| 侧边栏 | 头像 + 昵称 + 佩戴勋章的抽屉导航；本地头像（相册选图 / 6 色预置生成）；个人资料、成就与勋章、图鉴、兑换、关于 |
| 收藏展示 | 主页"开始专注"下方横滑展示最近获得的卡片，点击查看详情；空状态引导 |
| 广告预留 | `AdApi` 抽象接口 + `AdManager` 频控（冷却/每日上限，自然日重置）；默认 Stub 模拟实现；专注完成弹窗已跑通"看广告再领 1 张"闭环，接真实 SDK 见 `docs/广告接入指南.md` |
| 奖励兑换 | 预留 `RewardApi` 接口，集齐 101 张可兑换奖品（当前为本地 Stub 实现） |

### 每日登录防作弊设计

- 发奖时间以**网络时间为准**（淘宝时间接口 → worldtimeapi → HTTPS Date 头，三级容错，全部 HTTPS）
- 离线时用「上次可信网络时间 + elapsedRealtime 单调时钟」推算；设备重启后无法推算则**暂缓发奖**，等联网
- 网络时间比上次记录早 60 秒以上（回拨）→ 拒绝发奖
- 已知边界：专注计时尚用本地时钟，改时间可跳过等待（列入后续路线）

## 卡牌稀有度与掉落概率

- 编号 001-060 普卡底 / 061-085 铜底 / 086-095 银底 / 096-100 金底 / 101 钻底（压轴大黄蜂）
- 两阶段掉落：先按编号权重抽卡（时间越长高稀有编号权重越高），再从底子稀有度逐级升级（可连跳）
- 单步升级概率 `p = 0.05 + 0.45 × (分钟数/120)`，即 120 分钟专注单步升级率 50%

## 项目结构

```
app/src/main/java/me/hebin/focus/
├── FocusApp.kt                 # Application：前台时长跟踪（金币累积）
├── data/
│   ├── Rarity.kt               # 稀有度枚举（配色）
│   ├── CardCatalog.kt          # 101 张萌宠卡定义（名称/分类/编号概率）
│   ├── DropEngine.kt           # 掉落概率引擎（专注掉卡 + 每日登录）
│   ├── CraftEngine.kt          # 工坊引擎：分解/合成（随机产物）
│   ├── ShopStore.kt            # 金币 + 背包 + 道具清单（ItemCatalog）
│   ├── CollectionRepository.kt # 图鉴/统计/每日登录/成就持久化
│   ├── DailyLoginManager.kt    # 每日登录：联网时间校验 + streak + 发卡
│   ├── Achievements.kt         # 成就定义 / 点数 / 徽章 / 解锁检查
│   ├── AvatarStore.kt          # 本地头像：相册选图(SAF) / 预置色板生成 / 渲染
│   └── RewardApi.kt            # 奖励兑换接口 + Stub 实现
├── session/
│   └── FocusSessionManager.kt  # 专注会话状态机（进程级单例）
├── ads/
│   ├── AdApi.kt                # 广告位定义 / 结果回调 / SDK 抽象接口
│   ├── AdManager.kt            # 广告分发 + 频控（冷却/日限，自然日重置）
│   └── StubAdApi.kt            # 本地模拟实现（无 SDK 依赖）
└── ui/
    ├── MainActivity.kt         # 主页：抽屉导航 + 统计 + 金币跳动 + 每日领卡
    ├── FocusActivity.kt        # 专注页：计时/剪影/碎裂/翻卡
    ├── CollectionActivity.kt   # 图鉴页
    ├── CardGridAdapter.kt      # 图鉴网格适配器
    ├── WorkshopActivity.kt     # 卡片工坊：分解/合成双 Tab
    ├── ShopActivity.kt         # 道具商店：金币 + 商店 + 背包
    ├── IconPicker.kt           # 自定义图标选择面板（BottomSheet）
    ├── IconSwitcher.kt         # activity-alias 图标切换
    ├── AchievementActivity.kt  # 成就页：点数/徽章佩戴/成就进度
    ├── RewardActivity.kt       # 兑换页
    └── view/
        ├── CardView.kt         # 卡片自绘控件（萌宠图 + 名称 / 未收集剪影 / 生成中剪影）
        └── CardArt.kt          # 卡面图加载（assets → LruCache，异步解码）

tools/gen_icon_aliases.py       # 生成 506 个图标别名资源 + 清单（可重复运行）
```

## 构建

```bash
# 需要 JDK 17、Android SDK 34
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 已配置（`.github/workflows/android.yml`）：push 到 master 自动构建，APK 在 Actions → Artifacts 下载。

## 技术要点

- **离开 App 判定**：`onStop` 且非配置变更、非锁屏（3 秒内收到 `ACTION_SCREEN_OFF`）→ 判定离开
- **锁屏场景**：锁屏期间计时继续（协程不依赖前台），倒计时自然结束后结果存入 pending，回来取
- **进程被杀兜底**：掉落结果先写 `pendingDrop` 再广播状态，主页 `onStart` 时恢复展示
- **卡面美术**：101 张 Q 版萌宠插画（cut_square 紧裁版）转 512px WebP（q85，共约 2.3MB）放入 `assets/cards/`；`CardView` Canvas 自绘稀有度底色/边框/辉光 + 图片 + 名称，`CardArt` 做 LruCache（1/8 堆内存）+ 单线程异步解码，未收集态用 ColorMatrix 染成暗剪影（保留轮廓悬念）
- **自适应图标**：focus_B 涟漪方案；前景为不透明渐变底 + 缩放至 66dp 安全区（108dp 画布）的涟漪，旧版（< Android 8）直接用 1024 成品缩放
- **自定义图标（activity-alias）**：清单预置 `.icon.standard`（默认涟漪）+ `.icon.aNNN_R`（101 卡 × 5 稀有度）共 506 个别名，均挂 MAIN/LAUNCHER 指向 MainActivity，卡片别名默认禁用；`IconSwitcher` 先启用新别名再禁用旧别名（避免桌面入口真空），`DONT_KILL_APP` 不杀进程；图标资源由 `tools/gen_icon_aliases.py` 幂等生成（稀有度渐变底 + 描边圈 + 卡面 layer-list，约 1.3MB）
- **金币累积**：`FocusApp` 用 ActivityLifecycleCallbacks 统计前台毫秒（elapsedRealtime 单调时钟），前台期间每 30 秒入账 `ShopStore`，最后一个 Activity onStop 时结算；熄屏会触发 onStop 自动暂停，符合"前台开着才攒"

## 后续路线

- [x] 美术卡面替换（101 张萌宠插画 + focus_B 涟漪图标，v0.4.0）
- [x] 卡片分解与合成（工坊，产物随机，v0.5.0）
- [x] 金币 + 道具商店 + 背包（道具清单待定，框架就绪，v0.5.0）
- [x] 自定义图标（图鉴卡 + 稀有度样式，v0.5.0）
- [ ] 道具内容设计（清单在 `ItemCatalog`，加一行即上架）
- [ ] 专注计时防作弊（elapsedRealtime 替代 currentTimeMillis + 网络校验）
- [ ] 后端接入：账号系统、`RewardApi` 真实实现、防作弊校验
- [ ] 白噪音 / 深度专注模式
- [ ] 集换社交（与好友交换重复卡、徽章展示互动）
