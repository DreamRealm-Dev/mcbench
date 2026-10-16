# MCBench —— 不服跑个分

Minecraft 通用跑分模组：**规则透明、场景多样、跨设备/跨整合包可比**。

- 单人游戏（世界选择）界面右上角新增 **“不服跑个分”** 按钮
- 三种模式：**综合实战测试**（全场景）/ **公平性能测试**（CPU/GPU 纯计算）/ **快测**（优化整合包优选）
- 计分规则与基准常数全部公开、可配置（`config/mcbench.json`），详见 [DESIGN.md](DESIGN.md)
- 支持 1.19+ 各版本与 Fabric / NeoForge / Forge（当前已实机构建：**1.21.1 Fabric + NeoForge**）
- **2.0.0 更新**：新增左下角基准切换（1.0 / 2.0），2.0 使用手机原版 1x 参考、倍率不封顶计分；
  新增 2.0 原版综合 / 公平绝对性能 / 快测 / 模组专项入口；2.0 客户端严格画质统一。
  服务端联测第一阶段已落地：模组支持服务端加载、`/mcbench` 命令、本地集成服务端模式（屏蔽渲染）。
  远端服务端/协同/假客户端模块仍为后续迭代占位。
- 1.0 基准完全保留可用，但与 2.0 结果不可直接对比（场景、常数、计分规则都不同）。
- 1.0.1 维护性修订：未改动 1.0 计分基准、场景、权重与设置统一逻辑；分数可与 1.0.0 对比。
- 1.0.0 更新：重新校准基准（3060Ti 原版 ≈650 分）、透明场景改为远距离 3×3 透明群、
  粒子场景增强至 1000/ tick、猪圈观察点拉近、跑分时自动禁用 FPS 减速器/Dynamic FPS/BetterFPS Dist 实体缩放；
  旧版 `config/mcbench.json` 会自动迁移到新基准

## 快速开始（安装）

1. 将对应 jar 放入 `mods` 文件夹：
   - `dist/mcbench-fabric-1.21.1-2.0.0.jar` → Fabric 1.21.1（2.0 基准）
   - `dist/mcbench-fabric-1.21.11-1.0.1.jar` → Fabric 1.21.11（1.0 基准）
   - `dist/mcbench-neoforge-1.21.1-2.0.0.jar` → NeoForge 1.21.1（2.0 基准）
   - 仍需要 1.0 基准的 1.21.1 用户可继续使用 `dist/mcbench-fabric-1.21.1-1.0.1.jar` / `dist/mcbench-neoforge-1.21.1-1.0.1.jar`
2. **前置（强烈推荐）**：安装 [Chunky](https://modrinth.com/mod/chunky)（1.21.1 用 Chunky 1.4.x NeoForge/Fabric 版）。
   - 区块生成环节通过 Chunky API 触发（API 不可用时自动改用 `/chunky` 指令），
     以 (0,0) 为中心生成**半径 100 区块（40401 块，综合实战）/ 半径 50 区块（10201 块，快测）**并记录用时；
   - 未安装 Chunky 时该环节跳过并重新归一化权重（结果中会有备注）。
3. 启动游戏 → 单人游戏 → 右上角 **“不服跑个分”** → 选择模式。
4. 测试全程自动：建世界、调设置、跑场景、恢复设置、删世界、出结果。
   期间**请勿操作**（Esc 已被拦截，防止误退）。结果同时显示在游戏内并写入：
   `<游戏目录>/benchmark-results/mcbench-<时间戳>.json` 与 `.txt`。
   跑分开始前会自动禁用 FPS 减速器 / Dynamic FPS 的降帧，并关闭 BetterFPS Dist 的实体距离缩放，结束后恢复原配置。

> 手机端（如 ZL2 启动器 + MobileGlues）同样适用：分辨率锁定与设置统一均不依赖桌面特性；
> 分辨率固定锁定为 **1280×720**（不再按设备宽高比缩放），高 DPI/非 16:9 屏幕也使用同一像素负载。

## 三种模式

| 模式 | 内容 | 用时参考 |
|---|---|---|
| 综合实战测试（1.0） | 统一设置 → 固定超平坦世界 → 世界加载 → Chunky 半径100区块 → 远距离透明方块群 10s → 300猪（近距） 10s → 粒子洪流 10s → 1000TNT 10s | 15~30 分钟（Chunky 视 CPU 而定，实测 i5-12400F 约 2 分 40 秒） |
| 公平性能测试（1.0） | CPU 单/多线程（整数、浮点、内存带宽、SHA-256）+ GPU 填充率 | 约 1 分钟 |
| 快测（1.0） | 世界加载（等50区块）→ Chunky 半径50区块 → 300猪高空跌落 10s（只此一个可视化场景） | 2~8 分钟 |
| 2.0 原版综合 | 世界加载 → Chunky 半径100 → 透明 → 实体(250) → 粒子 → TNT；闲置 FPS 记录 20s | 视 Chunky 而定 |
| 2.0 公平绝对性能 | CPU/GPU 绝对性能测试，标记为 2.0 公平模式 | 约 1 分钟 |
| 2.0 快测 | 世界加载 → Chunky 半径30 → 透明 → 实体(200) → 粒子 → TNT | 比 1.0 快测更全面但更短 |
| 2.0 模组专项（机械动力） | 可用：检测到 Create 时放置 300 水车 + 300 烈焰人燃烧室方块实体并设为超级燃烧，采样 FPS | — |

> 快测完成后停在结果页，点右下角 **“退出”** 返回主界面；结果已自动写入
> `<游戏目录>/benchmark-results/mcbench-<时间戳>.json` 与 `.txt`。
> Chunky 计时使用服务端/独立监视线程时间戳，按住窗口标题栏导致渲染冻结也不会计时失真。

## 2.0 跑分对象

2.0 基准下，左下角可选择跑分对象：

| 对象 | 当前状态 |
|---|---|
| 客户端性能 | ✅ 可用 |
| 本地服务端性能 | ✅ 可用（实体碰撞50/300、TNT、寻路压力、方块更新、Mineflayer假人40/20人TPS/MSPT、极限运行，屏蔽渲染） |
| 远端服务端性能 | ✅ 基础可用：已加入服务器时可自动发送 `/mcbench request`；管理员 `/mcbench accept` 后服务端自动执行测项、铺设平坦测试区、冻结其他世界并写结果文件 |
| 客户端+远端协同 | ✅ 简化可用：已加入服务器时客户端只负责渲染采样 20 秒，并自动前进模拟玩家移动 |

服务端命令（OP）：

```
/mcbench list
/mcbench accept <clientId+token>
/mcbench deny <clientId+token>
```

### Mineflayer 假人

- 将 `dist/mcbench-bot.zip` 放到游戏目录或当前工作目录，mod 会自动解压到 `config/mcbench-bot/`。
- 也可手动放置 `config/mcbench-bot/bot.js`。
- 本地服务端性能：综合 40 假人、快测 20 假人，自动扩展玩家上限并记录 TPS/MSPT。
- 未找到 Node.js 或 Bot 脚本时自动回退实体模拟。

## 计分规则（摘要，完整版见 DESIGN.md）

- 越高越好：`子分 = min(200, 100×实测/基准)`；越低越好（耗时）：`子分 = min(200, 100×基准/实测)`
- 帧场景：`0.4×平均帧 + 0.6×1%Low`
- 综合场景权重：世界加载 10% / 区块生成 15% / 透明 20% / 实体 20% / 粒子 15% / TNT 20%
- 总分 = Σ(权重×子分)/Σ(执行权重) × 5 → **0~1000 分，基准机 = 500 分**
- 所有常数与权重可在 `config/mcbench.json` 修改；日志记录实际使用值
- 强制 GC 次数（G1GC/ZGC 分开统计）不参与计分，仅作优化建议

## 构建

fabric 与 neoforge 需要的 Gradle 版本不同，因此**分平台构建**（`common` 是共享源码目录，不单独成工程）：

```bat
:: 需要 JDK 21
set JAVA_HOME=<你的 JDK 21 路径>

:: Fabric 1.21.1（Gradle 8.7，fabric-loom 1.6-SNAPSHOT）
gradlew.bat :fabric:build "-Pmc.version=1.21.1" "-Pmc.platforms=fabric"

:: Fabric 1.21.11（需 Gradle 9.5+，fabric-loom 1.17.18）
gradlew.bat :fabric:build "-Pmc.version=1.21.11" "-Pmc.platforms=fabric" "-Pfabric_loom_version=1.17.18"

:: NeoForge 1.21.1（需 Gradle 8.8+，NeoGradle 2.0.134）
gradlew.bat :neoforge:build "-Pmc.version=1.21.1" "-Pmc.platforms=neoforge"

:: 产物：fabric/build/libs/*.jar 与 neoforge/build/libs/*.jar，均已收录进 dist/
```

> 仓库自带的 wrapper 是 **Gradle 8.7**。1.21.11 的 Fabric 与 NeoForge 构建需要更高版本的 Gradle，
> 按上面注释里的最低版本要求切换（NeoForge 8.8+、Fabric 1.21.11 需 9.5+）。
> 首次构建需要联网拉取依赖，不必加 `--offline`。

### 版本矩阵

- `configs/1.21.1.json` —— 已构建验证（本机离线）
- `configs/1.21.11.json` —— Fabric 已构建验证（Gradle 9.5.1 + loom 1.17.18）；NeoForge 暂不需要，未构建
- `configs/1.20.1.json`（Forge+Fabric）、`configs/26.2.json` —— 结构就绪，
  需要联网构建；版本差异代码位于 `common/src/version/<版本>/java/`（目前 1.21.1 与 1.21.11 目录，
  其余版本的适配清单见 [PORTING.md](PORTING.md)）

### 冒烟测试（开发用）

```bat
gradle.bat :neoforge:runClient --offline "-Pmc.version=1.21.1" "-Pmc.platforms=neoforge" "-Pmcbench.smoketest=45"
```

游戏启动后 45 秒自动退出；日志出现 `[MCBench] 不服跑个分已初始化` 即模组加载成功。

## 目录结构

```
mcbench/
├── common/src/main/java/io/mcbench/    # 全部共享代码（官方映射）
│   ├── bench/        # 跑分核心：状态机/帧采样/GC监控/场景/Chunky桥/分辨率锁定/设置统一
│   ├── scoring/      # 计分引擎（纯 Java）
│   ├── ui/           # 模式选择/进度/结果界面
│   └── mixin/        # 界面按钮 + 渲染帧钩子
├── common/src/version/<MC版本>/java/   # 版本差异代码（如 WorldFactory/VersionBridge）
├── fabric/           # Fabric 入口 + fabric.mod.json + mixin 配置
├── neoforge/         # NeoForge 入口 + mods.toml + mixin 配置
├── configs/*.json    # 版本矩阵（1.20.1 / 1.21.1 / 1.21.11 / 26.2）
├── libs/             # 本地依赖归档（Chunky-NeoForge-1.4.23.jar，dev 测试用）
└── dist/             # 已构建产物
```

## 许可

MIT
