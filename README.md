# Focus · 专注集卡

一款游戏化专注力 App：专注时卡片以剪影形态慢慢生成，中途离开 App 卡片会碎裂；坚持到底则翻卡揭示稀有度并收入图鉴。

## 核心玩法

| 机制 | 说明 |
|---|---|
| 专注计时 | 10/25/45/60/90/120 分钟可选，屏幕常亮 |
| 剪影生成 | 专注期间卡片以黑色剪影 + 流光效果自下而上渐显 |
| 离开惩罚 | 切出 App（非锁屏）→ 卡片碎裂为 6 块碎片飞散 |
| 掉落规则 | 专注时间越长，高稀有度概率越高；不同编号概率不同 |
| 卡牌体系 | 100 张编号卡 × 5 种稀有度（普/铜/银/金/钻），金/钻卡有辉光 |
| 图鉴系统 | 4 列网格展示收集进度，点开可看各稀有度持有数量 |
| 奖励兑换 | 预留 `RewardApi` 接口，集齐 100 张可兑换奖品（当前为本地 Stub 实现） |

## 卡牌稀有度与掉落概率

- 编号 001-060 普卡底 / 061-085 铜底 / 086-095 银底 / 096-099 金底 / 100 钻底
- 两阶段掉落：先按编号权重抽卡（时间越长高稀有编号权重越高），再从底子稀有度逐级升级（可连跳）
- 单步升级概率 `p = 0.05 + 0.45 × (分钟数/120)`，即 120 分钟专注单步升级率 50%

## 项目结构

```
app/src/main/java/me/hebin/focus/
├── data/
│   ├── Rarity.kt              # 稀有度枚举（配色）
│   ├── CardCatalog.kt         # 100 张卡定义 + 编号概率
│   ├── DropEngine.kt          # 掉落概率引擎
│   ├── CollectionRepository.kt# 图鉴/统计持久化（SharedPreferences+JSON）
│   └── RewardApi.kt           # 奖励兑换接口 + Stub 实现
├── session/
│   └── FocusSessionManager.kt # 专注会话状态机（进程级单例）
└── ui/
    ├── MainActivity.kt        # 主页：时长选择 + 统计
    ├── FocusActivity.kt       # 专注页：计时/剪影/碎裂/翻卡
    ├── CollectionActivity.kt  # 图鉴页
    ├── CardGridAdapter.kt     # 图鉴网格适配器
    ├── RewardActivity.kt      # 兑换页
    └── view/CardView.kt       # 卡片自绘控件（正面/剪影两种模式）
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
- **卡面占位**：`CardView` 用 Canvas 自绘编号数字，美术资源到位后在 `drawFace` 换成图片即可，外部接口不变

## 后续路线

- [ ] 美术卡面替换（100 张插画）
- [ ] 后端接入：账号系统、`RewardApi` 真实实现、防作弊校验
- [ ] 白噪音 / 深度专注模式
- [ ] 卡片分解与合成（重复卡再利用）
- [ ] 集换社交（与好友交换重复卡）
