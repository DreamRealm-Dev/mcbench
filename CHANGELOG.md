# MCBench 更新日志

## 2.0.0（核心框架 + 客户端 2.0 模式）

### 新基准切换
- 跑分模式选择界面左下角新增 **基准切换**（1.0 基准 / 2.0 基准）。
- 1.0 基准完全保留可用；2.0 与 1.0 场景、常数、计分规则不同，结果不可直接对比。
- 2.0 跑分对象选择器已加入：客户端性能 / 本地服务端性能 / 远端服务端性能 / 客户端+远端协同。
  当前版本先开放 **客户端性能**；服务端联测模式入口保留并提示后续迭代开放。

### 2.0 客户端新模式
- `2.0 原版综合`：世界加载 → Chunky 半径100 → 透明 → 实体(250) → 粒子 → TNT，闲置 FPS 记录延长至 20 秒。
- `2.0 快测`：世界加载 → Chunky 半径30 → 透明 → 实体(200) → 粒子 → TNT。
- `2.0 公平绝对性能`：沿用 CPU/GPU 绝对性能测试，标记为 2.0 公平模式。
- `2.0 模组专项（机械动力）`：入口已列出，场景待实现。
- 2.0 客户端基准以手机原版为 1x 参考，计分改为**倍率法不封顶**：
  - 耗时项倍率 = 基准时间 / 实测时间；
  - 帧项倍率 = 0.4×平均帧倍率 + 0.6×低帧倍率；
  - 总分 = 六项几何平均 × 100（1x 基准机 = 100 分）。

### 客户端画质统一（2.0）
- 新增 2.0 严格画质统一：临时禁用 AO、群系混合、实体阴影、伽马、资源包；
  并尽力而为关闭 SodiumExtra 动画/粒子、BetterFPSDist 实体距离缩放、
  ImmersiveOptimization 距离/追踪/实体剔除、CullLeaves、EMF、ETF 等配置（文件级备份，跑分后恢复）。
  Flerovium 按用户反馈视为优化的一部分，2.0 暂不临时禁用。

### 服务端联测（第一阶段）
- 模组改为客户端/服务端通用：Fabric `environment="*"` + server entrypoint，NeoForge `side="BOTH"`。
- 新增 `/mcbench` 服务端命令：
  - `/mcbench list`（OP）
  - `/mcbench accept <clientId+token>`（OP）
  - `/mcbench deny <clientId+token>`（OP）
  - `/mcbench request <clientId> <token> <mode>`（任意在线玩家，客户端可自动发送）
- 新增请求注册表：同一服务端最多 3 个待处理请求，默认 2 分钟超时，token 防顶替。
- 命令注册通过通用 Mixin 在服务器首个 tick 完成，不依赖 Fabric API / NeoForge 事件总线。
- 客户端 2.0 下选择“远端服务端性能”时，若已加入服务器会自动发送 `/mcbench request`。
- 管理员 `/mcbench accept` 后，服务端会启动 `ServerRemoteBenchmarkRunner` 逐 tick 执行远端服务端测项
  （实体碰撞50/300、TNT、寻路压力、方块更新、50玩家模拟、极限运行），结果写入服务器目录
  `benchmark-results/mcbench-server-remote-<时间戳>.json`。
- 远端服务端跑分期间会**安全冻结其他世界**（只保留主世界测试区域继续 tick），结束后自动解冻。
- 远端测试会在主世界铺设一块平坦平台（`SceneBuilder.prepareFlatArea`），让普通主世界也能获得接近超平坦的测试环境。
- 新增 `McBenchDimension`：服务端启动时尝试预创建独立超平坦测试维度（`mcbench:test_flat`），
  平时不加入正常 tick；跑分时通过 `activate()` 临时加入 `levels`/`worldArray` 参与 tick，
  结束后 `deactivate()` 移出。远端服务端跑分已优先切换到该维度，失败时回退主世界平坦测试区。
- Mineflayer 假人在玩家模拟阶段会自动传送到独立测试维度，使 40/20 假人的 TPS/MSPT 落在测试维度内。
- 新增 MCProtocolLib 同 JVM 内置假人：Fabric/NeoForge jar 已内嵌 MCProtocolLib，无需 Node.js 即可启动 40/20 假人。

### 本地集成服务端模式（屏蔽渲染）
- 2.0 下选择“本地服务端性能”后，跑分流程改为：
  - 世界加载 → Chunky 区块生成 → 实体碰撞(50) → 实体碰撞(300) → TNT 爆炸
    → 寻路压力(50 僵尸) → 方块更新(20×20×20 红石灯) → 玩家模拟 → 极限运行(sprint 200 ticks)
- 玩家模拟使用 Mineflayer 纯客户端假人：**综合 40 人、快测 20 人**，自动扩展玩家上限并记录 TPS/MSPT；
  未安装 Bot 时回退为实体简化模拟。
- 本地服务端测试期间会临时屏蔽客户端渲染，避免 GPU/渲染干扰服务端读数。
- 新增“客户端+远端协同”简化模式：已加入服务器时，客户端不再创建/处理本地逻辑，仅对当前服务器画面做 20 秒 FPS 采样；
  简易假客户端会在此期间自动按住前进键，模拟真实玩家在服务器中移动。
- 新增机械动力专项实现：通过注册表检测 `create:water_wheel` / `create:blaze_burner`，
  客户端模式下放置 300 水车 + 300 烈焰人燃烧室方块实体，并通过反射将烈焰人燃烧室设为
  `SPECIAL`（超级燃烧）状态，采样客户端 FPS。

### 编译减重
- 版本差异继续通过 `common/src/version/<MC版本>/java` 隔离；不存在的版本模块不参与编译。
- 远端服务端/假客户端相关逻辑以接口与 UI 占位形式预留，待后续迭代接入。

## 1.0.1（当前）

### 维护性修订
- **未改动**任何计分基准、场景、权重、设置统一逻辑与分辨率锁定；1.0.1 与 1.0.0 的分数可直接对比。
- 不引入 Architectury API（AAPI）作为依赖。经实测启动日志确认：
  - C2ME 在启动后报告 `ChunkDataEvent.Save is used by: dev.architectury.event.forge.EventHandlerImplCommon eventChunkDataEvent(...)`；
  - Architectury API 的 NeoForge 实现会在加载时无条件把 `EventHandlerImplCommon` 注册到 `NeoForge.EVENT_BUS`，
    其中包含 `@SubscribeEvent` 的 `ChunkDataEvent.Save` 监听；
  - 因此即使 MCBench 自身不订阅该事件，只要环境加载标准 AAPI，C2ME 就可能禁用部分优化；
    若 1.0.0 跑分环境没有 AAPI、而 1.0.1 强制引入 AAPI，则两者的跑分条件不一致，不能保证分数直接对比。
- MCBench 自身不订阅 `ChunkDataEvent.Save`，日志中没有 MCBench 的该事件监听器。
- 实测确认（DRVP，1.21.1 NeoForge + C2ME）：
  - AAPI 加载时 C2ME 会稳定出现 `ChunkDataEvent.Save` 警告，说明 AAPI 确实可能触发 C2ME 禁用部分优化；
  - 但后续大范围 Chunky 变慢（`~30s → ~140-155s`）的主因是 **Lucis**，不是 AAPI；
  - 移除 Lucis 后，即使不纠结 AAPI，Chunky 也恢复到约 `32s`，MCBench 1.0.1 跑分达到约 `931.3`。
  - 因此 AAPI 是兼容性提示项，Lucis 是本次跑分环境的显著性能干扰项。

### 打包
- 版本号升级为 `1.0.1`，构建并更新：
  - `dist/mcbench-fabric-1.21.1-1.0.1.jar`
  - `dist/mcbench-neoforge-1.21.1-1.0.1.jar`
- `dist/mcbench-fabric-1.21.11-1.0.1.jar`：使用 Gradle 9.5.1 + fabric-loom 1.17.18 从源码重新构建。

## 1.0.0

### 算法与场景重标定
- 基准常数按“3060Ti 原版 ≈ 650 分”重新标定，避免大量场景撞 200 分上限、分数区分度不足。
- 旧版 `config/mcbench.json` 会在启动时自动迁移到新基准（仅覆盖仍为旧默认值的项）。
- 透明方块场景：旧版为站在 40×40×40 建筑内部，改为**远距离 3×3 个 32×32×32 实心透明立方体**（约 29.5 万方块），
  从约 140 格外观察，更考验透明排序与 Overdraw。
- 猪圈观察点从 32 格移至 **16 格**，避免部分模组的实体距离剔除导致远处生物不渲染。
- 粒子场景增强：每 tick **300 → 1000 个**，散布范围 20×20×20 → **40×40×40**，距离 40 → 60 格。

### 设置公平性
- 跑分开始前自动禁用 **FPS 减速器 / Dynamic FPS** 的自动降帧（`fpsreducer*.toml`、`dynamic_fps.json`），
  结束后恢复原配置，防止采样期间帧率被限流导致计数不准。
- 自动关闭 **BetterFPS Dist** 的实体距离缩放（`affectEntities=false`），避免实体被距离剔除导致数量不一致。

### 打包
- 版本号升级为 `1.0.0`，构建并更新 `dist/mcbench-fabric-1.21.1-1.0.0.jar`、`dist/mcbench-fabric-1.21.11-1.0.0.jar`、`dist/mcbench-neoforge-1.21.1-1.0.0.jar`。

## 0.3.0

### 分辨率锁定修复
- 渲染分辨率不再按设备宽高比缩放，统一固定为 **1280×720（16:9）**。
- 修复手机/带鱼屏等非 16:9 屏幕下仍按比例计算（如 2848×1312 → 1424×656）导致像素负载不一致的问题。
- Fabric / NeoForge 共用同一 `ResolutionLock` 逻辑。

## 0.2.0

### 计分优化
- `ScoringEngine.frameScene` 自动归一化平均帧/1%Low 权重，避免配置错误导致子分越界。
- 计分时只把“有有效数据”的环节计入总分；无效/失败场景会跳过并重新归一化，不再把总分拖成 0。
- FAIR 权重（`weights.fair.cpuSingle/cpuMulti/gpu`）改为可在 `config/mcbench.json` 配置，并在日志 rules 段记录。
- 对 `cap <= 0` 等非法配置做兜底，避免 `NaN`/越界分数。

### 调试与健壮性
- 修复连续多轮跑分时 `rules` 残留上一模式常数的问题（每轮 `resetResult` 清空 rules）。
- 新增 `BenchResult.elapsedSeconds`：报告总耗时（游戏内结果页 + TXT 日志）。
- 新增每场景采样帧数记录（`transparentFrames/entityFrames/.../fallFrames`）。
- 新增 `chunkGenRadiusChunks` 指标，记录本次 Chunky 实际生成半径。
- `BenchConfig.load` 自动补全旧配置缺失的嵌套对象，兼容 0.1.0 已生成的 `config/mcbench.json`。

### 打包
- 版本号升级为 `0.2.0`。
- 构建并更新 Fabric / NeoForge 1.21.1 产物到 `dist/`。

## 0.2.0（1.21.11 补充）
- 新增 1.21.11 Fabric 构建：`dist/mcbench-fabric-1.21.11-0.2.0.jar`。
- 适配 1.21.11 API：
  - `ResourceKey.location()` → `identifier()`
  - `ServerPlayer.serverLevel()` → `level()`
  - `Window.getFramerateLimit()` → `Minecraft.getFramerateLimitTracker()`
  - `Screen.keyPressed(int,int,int)` → `keyPressed(KeyEvent)`
  - 图形/粒子/游戏规则/猪实体/工具类包路径迁移
  - 世界创建改用 `HolderLookup.Provider`
- 将 `GpuBench`、`ProgressScreen` 移入版本 overlay；1.21.11 GPU 填充测试暂为占位实现（新渲染管线待接入）。
