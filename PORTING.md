# 跨版本适配指南（PORTING）

> 目标：同一套代码构建 1.20.1 / 1.21.1 / 1.21.11 / 26.2。
> 当前**已构建验证 1.21.1（Fabric + NeoForge）**；其余版本的适配清单如下。

## 架构

- 全部共享代码位于 `common/src/main/java`（编译使用 **Mojang 官方映射**，跨版本类名稳定）
- **版本差异代码**位于 `common/src/version/<MC版本>/java`，构建时按 `configs/<版本>.json`
  自动选择（见 `settings.gradle` 的 `src/version/${mc}/java`）
- 差异面已收敛到两个文件：
  - `io.mcbench.bench.WorldFactory` —— 世界创建（API 差异最大）
  - `io.mcbench.bench.VersionBridge` —— 图形/粒子选项、游戏规则、退出世界
- 其余代码（状态机、场景、计分、UI、mixin）经 1.21.1 验证为跨版本稳定：
  mixin 目标 `Minecraft.tick()`、`SelectWorldScreen.init()` 在 1.20.1~26.2 均存在。

## 各版本差异清单（基于本机 javap 实读验证）

### 1.20.1（Forge + Fabric，configs/1.20.1.json）

| 位置 | 1.21.1 | 1.20.1 |
|---|---|---|
| createFreshLevel | 存在 | **不存在**；1.20.1 为 `createLevelFromSettings(Holder<WorldStem<?>>)`，或走 CreateWorldScreen 流程（需改 WorldFactory） |
| WorldPresets 包 | levelgen.presets | `net.minecraft.world.level.levelgen.WorldPresets`（无 presets 子包） |
| LevelSettings | (name, GameType, hardcore, Difficulty, allowCommands, GameRules, WorldDataConfiguration) | 同左（WorldDataConfiguration 存在） |
| ChunkStatus 包 | `net.minecraft.world.level.chunk.status.ChunkStatus` | `net.minecraft.world.level.chunk.ChunkStatus` |
| GameRenderer.render | (DeltaTracker, boolean) | (float partialTick, long nanos, boolean) |
| RenderTarget.resize | (w, h, boolean) | (w, h, boolean) 同 |
| Window framebufferWidth/Height | 私有字段存在 | 私有字段存在（反射方案通用） |
| GameRules | RULE_MAX_ENTITY_CRAMMING | RULE_DOENTITYCRAMMING |
| Minecraft.disconnect(Screen) | 存在 | 存在（`disconnect(Screen)`） |
| Java | 21 | 17（config 已配置） |

需要：`src/version/1.20.1/java` 下的 `WorldFactory`、`VersionBridge`；`configs/1.20.1.json`
平台含 forge 时需在 `settings.gradle` 增加 forge 模块（loom 的 forge 平台或 NeoGradle legacyforge）。

### 1.21.11（Fabric + NeoForge，configs/1.21.11.json）

| 位置 | 1.21.1 | 1.21.11 |
|---|---|---|
| createFreshLevel | Function<RegistryAccess, WorldDimensions> | 大概率同 1.21.1（1.21.x 内未变，26.x 才改为 HolderLookup.Provider） |
| 其余 | — | 1.21.1→1.21.11 属于小版本演进，API 基本一致；预计只需核对 WorldFactory 与 Options 字段 |

预期工作量最小：先复制 1.21.1 的 overlay，联网编译后按报错微调。

### 26.2（Fabric + NeoForge，configs/26.2.json）—— 已写好 overlay（未编译验证）

26.x 起 MC 官方直接发布**未混淆（官方映射）**客户端，`configs/26.2.json` 已设 `"unobfuscated": true`。

已基于 26.2 客户端 jar（本机 loom 缓存）javap 实读验证的差异：

| 位置 | 1.21.1 | 26.2 |
|---|---|---|
| createFreshLevel | Function<RegistryAccess, WorldDimensions> | Function<**HolderLookup.Provider**, WorldDimensions> |
| LevelSettings | (name, GameType, hardcore, Difficulty, allowCommands, GameRules, DataConfig) | (name, GameType, **DifficultySettings**, allowCommands, DataConfig) |
| 图形选项 | Options.graphicsMode() → GraphicsStatus.FANCY | Options.**graphicsPreset()** → GraphicsPreset.FABULOUS |
| 粒子选项 | net.minecraft.client.ParticleStatus | net.minecraft.**server.level**.ParticleStatus |
| GameRules | GameRules.RULE_* (Key API) | **net.minecraft.world.level.gamerules.GameRules** 常量 + `set(GameRule, T, server)`；SPAWN_MOBS / ADVANCE_TIME / ADVANCE_WEATHER / MAX_ENTITY_CRAMMING |
| 退出世界 | Minecraft.disconnect(Screen) | Minecraft.**disconnectWithProgressScreen()** |
| Window.framebufferWidth/Height | 私有字段 | 私有字段（反射方案通用） |
| GuiGraphics / Button / Screen / tick() | — | 一致 |
| Java | 21 | 25（config 已配置） |

已提供：`src/version/26.2/java/io/mcbench/bench/{WorldFactory,VersionBridge}.java`。
**注意**：26.2 构建需要联网（本机离线缓存无 26.2 的 NeoForge userdev / Gradle 9.5 loom），
请在有网机器执行：

```bat
gradle :fabric:build -Pmc.version=26.2 -Pmc.platforms=fabric     (需要支持 26.2 的 loom，如 1.17+)
gradle :neoforge:build -Pmc.version=26.2 -Pmc.platforms=neoforge (需要对应 NeoGradle)
```

## 新增版本的标准流程

1. `configs/<版本>.json`（参照现有文件：java_version、加载器版本、parchment 可为 null、unobfuscated 标志）
2. 复制 `common/src/version/1.21.1/java` → `common/src/version/<版本>/java`
3. 联网编译，按编译器报错修正 `WorldFactory` / `VersionBridge`（绝大多数差异都在这两个文件）
4. 平台模块按需调整（fabric.mod.json 的 minecraft 版本范围、neoforge.mods.toml 的 versionRange）
5. 运行 `-Pmcbench.autostart=FAIR` 冒烟验证
