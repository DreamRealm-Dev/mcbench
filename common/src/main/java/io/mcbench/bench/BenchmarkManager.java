package io.mcbench.bench;

import io.mcbench.McBench;
import io.mcbench.scoring.ScoringEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跑分状态机：由客户端每帧 tick（Minecraft.tick mixin）驱动。
 *
 * 综合实战（COMBAT）流程：
 *   统一设置/锁分辨率 → 创建 test_world_1 → 世界加载计时 → 等区块 → 空闲5秒帧率(仅记录)
 *   → Chunky 半径100(计时) → 透明方块建筑10秒 → 250猪10秒 → 粒子10秒 → 1000TNT10秒
 *   → 退出并删除世界 → 恢复设置 → 计分 → 写日志 → 结果界面
 *
 * 公平性能（FAIR）：CPU 纯计算（后台线程）+ GPU 填充率（渲染线程），不进游戏。
 * 快测（QUICK）：世界加载 → Chunky 半径50 → 250猪高空跌落10秒。
 */
public class BenchmarkManager {
    public static final BenchmarkManager INSTANCE = new BenchmarkManager();

    /** 综合实战 Chunky 方形半径（区块）：100 = 201×201 区块范围。 */
    public static final int CHUNK_RADIUS = 100;
    /** 快测 Chunky 方形半径（区块）：缩小一半，明显缩短快测时长。 */
    public static final int QUICK_CHUNK_RADIUS = 50;
    /** 2.0 快测 Chunky 半径（区块）：按 2.0 快测标准进一步缩小。 */
    public static final int V2_QUICK_CHUNK_RADIUS = 30;
    /** 快测进入下一阶段前只等待 50 个区块加载完成（避免等满渲染距离的 625 块）。 */
    public static final int QUICK_LOAD_CHUNKS = 50;

    private enum State {
        IDLE,
        PREPARE, CREATE_WORLD, WAIT_WORLD_LOAD, WAIT_CHUNKS, SAMPLE_IDLE,
        CHUNKY_START, CHUNKY_WAIT,
        BUILD_TRANSPARENT, SAMPLE_TRANSPARENT,
        CLEAN_TRANSPARENT, BUILD_PIGS, SAMPLE_PIGS,
        CLEAN_PIGS, PARTICLES, SAMPLE_PARTICLES,
        TNT, SAMPLE_TNT,
        BUILD_FALL, SAMPLE_FALL,
        FAIR_CPU, FAIR_GPU,
        SERVER_ENTITY_LIGHT_PREP, SERVER_ENTITY_LIGHT_SAMPLE,
        SERVER_ENTITY_HEAVY_PREP, SERVER_ENTITY_HEAVY_SAMPLE,
        SERVER_TNT_PREP, SERVER_TNT_SAMPLE,
        SERVER_PATHFINDING_PREP, SERVER_PATHFINDING_SAMPLE,
        SERVER_BLOCK_UPDATE_PREP, SERVER_BLOCK_UPDATE_SAMPLE,
        SERVER_PLAYERS_PREP, SERVER_PLAYERS_SAMPLE,
        SERVER_MAX_TICK,
        COORDINATED_SAMPLE,
        CREATE_PREP, CREATE_SAMPLE,
        CLEANUP, FINISHED, ABORTED
    }

    private State state = State.IDLE;
    private BenchMode mode;
    private BenchVersion benchVersion = BenchVersion.V1;
    private BenchTarget benchTarget = BenchTarget.CLIENT;
    private final BenchResult result = new BenchResult();
    private final FrameSampler sampler = new FrameSampler();
    private final GcMonitor gcMonitor = new GcMonitor();
    private SettingsApplier.Snapshot settingsSnapshot;
    private Screen parentScreen;

    private long benchmarkStartNanos = 0;
    private long phaseStartNanos;   // 当前阶段起始（用于超时/计时）
    private long sampleStartNanos;  // 采样起始
    private long loadStartNanos;    // 世界加载起始
    private int waitTicks;
    private boolean worldLeft = false;
    private double lastFrameMs = 0;
    private String phaseLabel = "";
    private long lastFrameNanos = 0;

    // Chunky 计时：起点在服务端线程记录，终点由独立监视线程在任务完成瞬间记录。
    // 两者都不经过渲染线程 → 按住窗口标题栏导致渲染线程冻结时计时依然准确。
    private volatile long chunkStartServerNanos = 0;
    private volatile long chunkEndServerNanos = 0;
    private volatile boolean chunkWatchArmed = false;
    private volatile double chunkyProgress = -1;   // 供进度界面显示（-1 = 无任务）
    private long lastChunkyCount = -1;             // 卡死检测：任务计数
    private long lastChunkyAdvanceNanos = 0;       // 卡死检测：计数最后变化时间
    private Thread chunkyWatchThread = null;       // 独立监视线程（不占服务端任务批次）

    // 清理阶段：被 halt 的服务端引用（halt 是异步的，需等服务端线程真正停止后再删世界）
    private volatile IntegratedServer leavingServer = null;

    // 公平测试
    private boolean cpuBenchStarted = false;
    private boolean cpuBenchFinished = false;
    private final List<CpuBench.CpuResult> cpuResults = new ArrayList<>();
    private final AtomicInteger cpuProgress = new AtomicInteger();
    private final AtomicInteger cpuTotal = new AtomicInteger();
    private long gpuBenchStartNanos;
    private int gpuBenchFrames;
    private int gpuBenchDrawsPerFrame = 120;

    // 本地集成服务端测试（屏蔽渲染）
    private boolean renderSuppressed = false;
    /** 协同模式简易假客户端：自动按住前进键模拟玩家移动 */
    private boolean fakeClientKeysActive = false;
    /** 服务端假人测试：记录原始玩家上限，跑完恢复 */
    private int originalMaxPlayers = -1;
    private long serverSampleStartTick = -1;
    private long serverSampleStartNanos = 0;
    private int serverSampleTargetTicks = 100;
    private String serverSamplePrefix = "";

    private BenchmarkManager() {
    }

    // ================= 对外接口 =================

    public void start(BenchMode m) {
        start(m, m.version, BenchTarget.CLIENT);
    }

    /**
     * 远端服务端请求：客户端自动向服务器发送 /mcbench request。
     * 返回 true 表示已发送；false 表示当前不在服务器中。
     */
    public boolean requestRemoteBenchmark(BenchMode m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return false;
        }
        String clientId = mc.player.getGameProfile().getId().toString().replace("-", "").substring(0, 12);
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        mc.player.connection.sendCommand("mcbench request " + clientId + " " + token + " " + m.name());
        McBench.LOGGER.info("[MCBench] 已发送远端跑分请求 clientId={} token={} mode={}", clientId, token, m.name());
        return true;
    }

    public void start(BenchMode m, BenchVersion version, BenchTarget target) {
        if (isRunning()) return;
        this.mode = m;
        this.benchVersion = version;
        this.benchTarget = target;
        Minecraft mc = Minecraft.getInstance();
        this.parentScreen = mc.screen;
        resetResult(m);
        benchmarkStartNanos = System.nanoTime();
        state = State.PREPARE;
        phaseLabel = "准备中…";
        McBench.LOGGER.info("[MCBench] 开始跑分：{} [{} / {}]", m.label, version.label, target.label);
        mc.setScreen(new io.mcbench.ui.ProgressScreen());
    }

    private void resetResult(BenchMode m) {
        result.mode = m.name();
        result.modeLabel = m.label;
        result.benchmarkVersion = benchVersion.label;
        result.benchmarkTarget = benchTarget.label;
        result.minecraftVersion = Minecraft.getInstance().getLaunchedVersion();
        result.loader = loaderName();
        result.javaVersion = System.getProperty("java.version", "?");
        result.os = System.getProperty("os.name", "?") + " " + System.getProperty("os.arch", "?");
        result.metrics.clear();
        result.subScores.clear();
        result.notes.clear();
        result.rules.clear();
        result.forcedGcByCollector.clear();
        result.forcedGcTotal = 0;
        result.totalScore = 0;
        result.elapsedSeconds = 0;
        cpuResults.clear();
        cpuProgress.set(0);
        cpuTotal.set(0);
        cpuBenchStarted = false;
        cpuBenchFinished = false;
        // 每轮跑分必须重置清理状态，否则第二轮会因残留标记跳过强制退世界/删世界
        worldLeft = false;
        waitTicks = 0;
        leavingServer = null;
        chunkWatchArmed = false;
        renderSuppressed = false;
        serverSampleStartTick = -1;
        serverSampleStartNanos = 0;
        restoreFakeClientKeys();
        FakeClientBridge.INSTANCE.stopAll();
        originalMaxPlayers = -1;
    }

    private static String loaderName() {
        // 只加载不初始化，避免目标类静态初始化副作用
        for (String name : new String[]{
                "net.fabricmc.loader.api.FabricLoader",
                "net.neoforged.fml.loader.FMLLoader"}) {
            try {
                Class.forName(name, false, BenchmarkManager.class.getClassLoader());
                return name.contains("fabric") ? "Fabric" : "NeoForge";
            } catch (Throwable ignored) {
            }
        }
        return "?";
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.FINISHED && state != State.ABORTED;
    }

    public boolean isV2() {
        return benchVersion == BenchVersion.V2;
    }

    public boolean isRenderSuppressed() {
        return renderSuppressed;
    }

    private void setRenderSuppressed(boolean renderSuppressed) {
        this.renderSuppressed = renderSuppressed;
    }

    private void restoreFakeClientKeys() {
        if (fakeClientKeysActive) {
            Minecraft.getInstance().options.keyUp.setDown(false);
            fakeClientKeysActive = false;
        }
    }

    private boolean isLocalServerTarget() {
        return benchTarget == BenchTarget.LOCAL_SERVER;
    }

    private boolean isQuickChunkWait() {
        return mode == BenchMode.QUICK || mode == BenchMode.V2_QUICK;
    }

    /**
     * 每渲染帧调用（GameRenderer.render mixin）。
     * 1.20.5+ 的 Minecraft.tick 是游戏刻（20TPS），不能用于帧率测量；
     * 帧时间/帧采样必须以真实渲染帧为准。
     */
    public void onRenderFrame() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            lastFrameMs = (now - lastFrameNanos) / 1_000_000.0;
        }
        lastFrameNanos = now;
        if (sampler.isActive()) {
            sampler.frame();
        }
    }

    public String phaseLabel() {
        return phaseLabel;
    }

    /** 当前模式的 Chunky 半径：1.0 快测 50、2.0 快测 30、综合 100。 */
    private int chunkyRadius() {
        if (isV2()) {
            return mode == BenchMode.V2_QUICK ? V2_QUICK_CHUNK_RADIUS : CHUNK_RADIUS;
        }
        return mode == BenchMode.QUICK ? QUICK_CHUNK_RADIUS : CHUNK_RADIUS;
    }

    private String chunkyPhaseLabel() {
        return "Chunky 区块预生成（半径" + chunkyRadius() + "）…";
    }

    /** 当前模式的实体数量：2.0 综合 250、2.0 快测 200、1.0 保持 300。 */
    private int pigCount() {
        if (isV2()) {
            return mode == BenchMode.V2_QUICK ? BenchConfig.get().v2.client.quickEntityCount
                    : BenchConfig.get().v2.client.entityCount;
        }
        return SceneBuilder.PIG_COUNT;
    }

    /** Chunky 任务进度百分比（-1 = 无任务），供进度界面显示。 */
    public double chunkyProgressPercent() {
        return chunkyProgress;
    }

    public double lastFrameMs() {
        return lastFrameMs;
    }

    public BenchResult result() {
        return result;
    }

    public String cpuBenchText() {
        return String.format("CPU 基准 %d/%d（%d 线程）", cpuProgress.get(), cpuTotal.get(), CpuBench.coreCount());
    }

    /** 每帧调用（Minecraft.tick mixin —— 注意：1.20.5+ 这是游戏刻 20TPS，不是渲染帧！）。 */
    public void tick() {
        if (state == State.IDLE || state == State.FINISHED || state == State.ABORTED) return;
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        // 采样期间保持分辨率锁定（窗口被调整时重新锁定）
        if (ResolutionLock.isLocked()) {
            ResolutionLock.ensureLocked();
        }
        try {
            switch (state) {
                case PREPARE -> tickPrepare(mc);
                case CREATE_WORLD -> tickCreateWorld(mc, now);
                case WAIT_WORLD_LOAD -> tickWaitWorldLoad(mc, now);
                case WAIT_CHUNKS -> tickWaitChunks(mc, now);
                case SAMPLE_IDLE -> tickSample(now, isV2() ? 20_000_000_000L : 5_000_000_000L, "idle", null);
                case CHUNKY_START -> tickChunkyStart(mc);
                case CHUNKY_WAIT -> tickChunkyWait(now);
                case BUILD_TRANSPARENT -> tickBuildTransparent(mc);
                case SAMPLE_TRANSPARENT -> tickSample(now, 10_000_000_000L, "transparent", null);
                case CLEAN_TRANSPARENT -> tickCleanTransparent(mc);
                case BUILD_PIGS -> tickBuildPigs(mc);
                case SAMPLE_PIGS -> tickSample(now, 10_000_000_000L, "entity", null);
                case CLEAN_PIGS -> tickCleanPigs(mc);
                case PARTICLES -> tickParticles(mc);
                case SAMPLE_PARTICLES -> tickSample(now, 10_000_000_000L, "particle", mc);
                case TNT -> tickTnt(mc);
                case SAMPLE_TNT -> {
                    if (lastFrameMs > 15_000) {
                        // 卡死保护：单帧超过 15 秒 → 强制退出世界（不崩溃）
                        result.note("TNT 场景客户端卡死（单帧>15s），强制退出世界");
                        result.metric("tntAvgFps", 0);
                        result.metric("tntLowFps", 0);
                        if (sampler.isActive()) sampler.end();
                        sampleStartNanos = 0;
                        forceLeaveWorld(mc);
                        worldLeft = true;
                        state = State.CLEANUP;
                        phaseStartNanos = now;
                        phaseLabel = "数据汇总中……";
                    } else {
                        tickSample(now, 10_000_000_000L, "tnt", mc);
                    }
                }
                case BUILD_FALL -> tickBuildFall(mc);
                case SAMPLE_FALL -> tickSampleFall(now);
                case SERVER_ENTITY_LIGHT_PREP -> tickServerEntityPrep(mc, 50, "entityLight");
                case SERVER_ENTITY_LIGHT_SAMPLE -> tickServerSample(mc, "entityLight");
                case SERVER_ENTITY_HEAVY_PREP -> tickServerEntityPrep(mc, 300, "entityHeavy");
                case SERVER_ENTITY_HEAVY_SAMPLE -> tickServerSample(mc, "entityHeavy");
                case SERVER_TNT_PREP -> tickServerTntPrep(mc, "tnt");
                case SERVER_TNT_SAMPLE -> tickServerSample(mc, "tnt");
                case SERVER_PATHFINDING_PREP -> tickServerPathfindingPrep(mc);
                case SERVER_PATHFINDING_SAMPLE -> tickServerSample(mc, "pathfinding");
                case SERVER_BLOCK_UPDATE_PREP -> tickServerBlockUpdatePrep(mc);
                case SERVER_BLOCK_UPDATE_SAMPLE -> tickServerSample(mc, "blockUpdate");
                case SERVER_PLAYERS_PREP -> tickServerPlayersPrep(mc);
                case SERVER_PLAYERS_SAMPLE -> tickServerSample(mc, "players");
                case SERVER_MAX_TICK -> tickServerMaxTick(mc);
                case COORDINATED_SAMPLE -> tickSample(now, 20_000_000_000L, "coordinated", null);
                case CREATE_PREP -> tickCreatePrep(mc);
                case CREATE_SAMPLE -> tickSample(now, 10_000_000_000L, "create", null);
                case FAIR_CPU -> tickFairCpu();
                case FAIR_GPU -> tickFairGpu(now);
                case CLEANUP -> tickCleanup(mc);
                default -> {
                }
            }
        } catch (Exception e) {
            McBench.LOGGER.error("[MCBench] 跑分状态机异常，中止: ", e);
            result.note("状态机异常: " + e);
            abortAndCleanup(mc);
        }
    }

    // ================= 准备与建世界 =================

    private void tickPrepare(Minecraft mc) {
        gcMonitor.start();
        if (mode == BenchMode.FAIR || mode == BenchMode.V2_FAIR) {
            state = State.FAIR_CPU;
            phaseLabel = "CPU 计算基准（后台运行）…";
            return;
        }
        if (benchTarget == BenchTarget.COORDINATED) {
            if (mc.player == null || mc.getConnection() == null) {
                result.note("协同模式需要先加入服务器");
                state = State.CLEANUP;
                phaseStartNanos = System.nanoTime();
                phaseLabel = "数据汇总中……";
                return;
            }
            // 客户端不再创建/处理逻辑，仅渲染当前服务器画面并采样 FPS
            // 简易假客户端：自动按住前进键，模拟真实玩家在服务器中移动
            mc.options.keyUp.setDown(true);
            fakeClientKeysActive = true;
            beginWarmup(System.nanoTime());
            state = State.COORDINATED_SAMPLE;
            phaseLabel = "客户端-服务器协同：自动移动 + 仅渲染采样（20 秒）…";
            return;
        }
        phaseLabel = "统一设置参数…";
        settingsSnapshot = SettingsApplier.apply(isV2());
        result.notes.addAll(settingsSnapshot.changed);
        // 本地服务端测试：从建世界开始降低渲染负载（锁 10fps + 640×360），避免 Sable 等模组因完全取消渲染出问题
        if (isLocalServerTarget()) {
            ResolutionLock.applyLowPower();
            mc.options.framerateLimit().set(10);
            result.note("本地服务端测试：锁 10fps + 640×360 低负载渲染");
        }
        // 注意：分辨率锁定不在准备阶段进行——只在帧率采样阶段锁定，
        // 保证准备/建世界/汇总/结果等界面阶段始终以原生分辨率显示（清晰）。
        phaseStartNanos = System.nanoTime();
        state = State.CREATE_WORLD;
        phaseLabel = "创建测试世界 " + McBench.WORLD_NAME;
        WorldFactory.createTestWorld(parentScreen);
    }

    private void tickCreateWorld(Minecraft mc, long now) {
        if (mc.getSingleplayerServer() == null) {
            // 世界创建失败/超时保护（60 秒）
            if (now - phaseStartNanos > 60_000_000_000L) {
                result.note("世界创建超时，中止");
                abortAndCleanup(mc);
            }
            return;
        }
        loadStartNanos = now;
        phaseStartNanos = now;
        state = State.WAIT_WORLD_LOAD;
        phaseLabel = "世界加载中…";
    }

    private void tickWaitWorldLoad(Minecraft mc, long now) {
        if (mc.level == null || mc.player == null) {
            if (now - phaseStartNanos > 180_000_000_000L) {
                result.note("世界加载超时（>180s），中止");
                abortAndCleanup(mc);
            }
            return;
        }
        result.metric("worldLoadSeconds", (now - loadStartNanos) / 1_000_000_000.0);
        McBench.LOGGER.info("[MCBench] 世界加载完成: {}s", String.format("%.2f", result.metrics.get("worldLoadSeconds")));
        applyServerRules(mc);
        // 世界创建流程会顶掉进度界面，重新显示
        if (!(mc.screen instanceof io.mcbench.ui.ProgressScreen)) {
            mc.setScreen(new io.mcbench.ui.ProgressScreen());
        }
        phaseStartNanos = now;
        waitTicks = 0;
        state = State.WAIT_CHUNKS;
        phaseLabel = "等待区块加载完成…";
    }

    /** 关闭生物生成/昼夜/天气/实体挤压，固定时间。 */
    private void applyServerRules(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;
        onServer(server.overworld(), () -> VersionBridge.applyBenchmarkRules(server));
    }

    private void tickWaitChunks(Minecraft mc, long now) {
        ServerLevel level = serverLevel(mc);
        // 快测只等 50 个区块加载即可继续，减少手机端准备阶段耗时；
        // 综合实战仍等满渲染距离对应的区块数，确保各场景完整可见。
        int expected = isQuickChunkWait()
                ? QUICK_LOAD_CHUNKS
                : (McBench.RENDER_DISTANCE * 2 + 1) * (McBench.RENDER_DISTANCE * 2 + 1);
        int loaded = level == null ? 0 : level.getChunkSource().getLoadedChunksCount();
        waitTicks++;
        if (loaded < expected && waitTicks <= 20 * 60 && now - phaseStartNanos <= 120_000_000_000L) {
            return; // 继续等待
        }
        if (mode == BenchMode.V2_MOD_CREATE) {
            state = State.CREATE_PREP;
            phaseLabel = "机械动力场景建造…";
        } else if (isLocalServerTarget() || isQuickChunkWait()) {
            state = State.CHUNKY_START;
            phaseLabel = chunkyPhaseLabel();
        } else {
            state = State.SAMPLE_IDLE;
            beginWarmup(now);
            phaseLabel = isV2()
                    ? "空闲场景（20 秒，仅记录）…"
                    : "空闲场景（5 秒，仅记录）…";
        }
    }

    // ================= 采样辅助 =================

    /** 进入预热：warmupTicks 后再开始采样。 */
    private void beginWarmup(long now) {
        // 进入帧率采样阶段 → 锁定渲染分辨率（像素负载统一；画面等比缩放属正常现象）
        ResolutionLock.apply();
        phaseStartNanos = now;
        waitTicks = 0;
        sampleStartNanos = 0;
        if (sampler.isActive()) sampler.end();
    }

    private void tickSample(long now, long durationNanos, String prefix, Minecraft mcForSpawn) {
        if (sampleStartNanos == 0) {
            // 预热阶段（80 tick ≈ 4 秒）：期间持续产生粒子（如需要）
            if (mcForSpawn != null) {
                spawnParticlesAsync(mcForSpawn);
            }
            if (++waitTicks > 80) {
                sampleStartNanos = now;
                sampler.begin();
            }
            return;
        }
        // 采样阶段：继续产生粒子
        if (mcForSpawn != null) {
            spawnParticlesAsync(mcForSpawn);
        }
        if (now - sampleStartNanos < durationNanos) return;

        FrameSampler.Sample s = sampler.result();
        sampler.end();
        sampleStartNanos = 0;
        result.metric(prefix + "AvgFps", round2(s.avgFps()));
        result.metric(prefix + "LowFps", round2(s.lowFps()));
        result.metric(prefix + "WorstFps", round2(s.worstFps()));
        result.metric(prefix + "Frames", s.frames());
        McBench.LOGGER.info("[MCBench] {} 场景 avg={} low={}", prefix,
                String.format("%.1f", s.avgFps()), String.format("%.1f", s.lowFps()));
        onSampleDone(prefix);
    }

    /** 一个场景采样完成后的状态流转。 */
    private void onSampleDone(String prefix) {
        switch (prefix) {
            case "idle" -> {
                state = State.CHUNKY_START;
                phaseLabel = chunkyPhaseLabel();
            }
            case "transparent" -> {
                state = State.CLEAN_TRANSPARENT;
                phaseLabel = "清理建筑…";
            }
            case "entity" -> {
                state = State.CLEAN_PIGS;
                phaseLabel = "清理猪圈…";
            }
            case "particle" -> {
                state = State.TNT;
                phaseLabel = "TNT 爆炸场准备…";
            }
            case "tnt" -> {
                // 最后一个采样场景结束 → 立即恢复原生分辨率（汇总界面保持清晰）
                ResolutionLock.restore();
                state = State.CLEANUP;
                phaseStartNanos = System.nanoTime();
                phaseLabel = "数据汇总中……";
            }
            case "fall" -> {
                ResolutionLock.restore();
                state = State.CLEANUP;
                phaseStartNanos = System.nanoTime();
                phaseLabel = "数据汇总中……";
            }
            case "coordinated" -> {
                ResolutionLock.restore();
                state = State.CLEANUP;
                phaseStartNanos = System.nanoTime();
                phaseLabel = "数据汇总中……";
            }
            case "create" -> {
                ResolutionLock.restore();
                state = State.CLEANUP;
                phaseStartNanos = System.nanoTime();
                phaseLabel = "数据汇总中……";
            }
            default -> {
            }
        }
    }

    // ================= Chunky =================

    private void tickChunkyStart(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) {
            result.note("服务器不可用，跳过 Chunky");
            skipChunky();
            return;
        }
        if (!ChunkyBridge.isAvailable()) {
            result.note("未安装 Chunky，区块生成环节跳过（权重重新归一化）");
            skipChunky();
            return;
        }
        chunkStartServerNanos = 0;
        chunkEndServerNanos = 0;
        lastChunkyCount = -1;
        lastChunkyAdvanceNanos = 0;
        chunkyProgress = -1;
        // 清除 Chunky 内存进度缓存：同名世界删除重建后，旧缓存会让新任务“秒完成”（假分数）
        onServer(level, () -> ChunkyBridge.clearRegionCache(VersionBridge.dimensionId(level)));
        int radius = chunkyRadius();
        result.metric("chunkGenRadiusChunks", radius);
        // 方式 A：Chunky API（快；world 参数为维度 key）
        if (ChunkyBridge.startSquareViaApi(level, radius)) {
            McBench.LOGGER.info("[MCBench] Chunky 任务已启动（API 方式，半径 {}）", radius);
            armChunkyTimer(level);
            phaseStartNanos = System.nanoTime();
            state = State.CHUNKY_WAIT;
            phaseLabel = chunkyPhaseLabel();
            return;
        }
        // 方式 B：Chunky 指令（服务器线程）
        final boolean[] started = new boolean[1];
        int finalRadius = radius;
        onServer(level, () -> {
            started[0] = ChunkyBridge.startSquareViaCommand(level.getServer(), finalRadius);
            if (started[0]) chunkStartServerNanos = System.nanoTime();
        });
        if (started[0]) {
            result.note("Chunky 使用指令方式触发（/chunky radius " + radius + " + start）");
            armChunkyTimer(level);
            phaseStartNanos = System.nanoTime();
            state = State.CHUNKY_WAIT;
            phaseLabel = chunkyPhaseLabel();
            return;
        }
        result.note("Chunky 触发失败（API 与指令均失败），区块生成环节跳过");
        skipChunky();
    }

    /**
     * 启动 Chunky 计时监视：起点已在服务端线程记录，这里起一个独立守护线程
     * 每 100ms 检查任务表，任务完成瞬间在监视线程记录终点。
     *
     * 注意：不能像最初版本那样用 server.execute 自续检查——服务端任务批次
     * (runAllTasks) 会同一 tick 内反复执行自续任务，形成死循环占满服务端线程
     * （曾导致 Chunky 卡在 72 块、日志每秒刷 9 千条）。独立线程与两者都解耦。
     */
    private void armChunkyTimer(ServerLevel level) {
        net.minecraft.server.MinecraftServer server = level.getServer();
        // 起点：确保在服务端线程上记录（API 路径的 startTask 在客户端线程调用）
        onServer(level, () -> {
            if (chunkStartServerNanos == 0) chunkStartServerNanos = System.nanoTime();
        });
        chunkWatchArmed = true;
        if (chunkyWatchThread != null) chunkyWatchThread.interrupt();
        chunkyWatchThread = new Thread(this::chunkyWatchLoop, "mcbench-chunky-watch");
        chunkyWatchThread.setDaemon(true);
        chunkyWatchThread.start();
    }

    /** 独立监视循环：100ms 轮询 Chunky 任务表。 */
    private void chunkyWatchLoop() {
        int ticks = 0;
        while (chunkWatchArmed) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            if (!chunkWatchArmed) return;
            long count = ChunkyBridge.taskCount();
            chunkyProgress = ChunkyBridge.taskProgressPercent();
            if (!ChunkyBridge.hasRunningTasks()) {
                chunkEndServerNanos = System.nanoTime();
                chunkWatchArmed = false;
                return;
            }
            if (++ticks % 50 == 0) { // 每 5 秒一条诊断
                McBench.LOGGER.info("[MCBench] Chunky 监视: count={} 进度={}% 已耗时={}s",
                        count, String.format("%.1f", chunkyProgress),
                        (System.nanoTime() - chunkStartServerNanos) / 1_000_000_000.0);
            }
        }
    }

    private void skipChunky() {
        result.metric("chunkGenSeconds", -1);
        if (!result.metrics.containsKey("chunkGenRadiusChunks")) {
            result.metric("chunkGenRadiusChunks", -1);
        }
        state = afterChunkyState();
        phaseStartNanos = System.nanoTime();
        phaseLabel = afterChunkyLabel();
    }

    private State afterChunkyState() {
        if (isLocalServerTarget()) return State.SERVER_ENTITY_LIGHT_PREP;
        if (isV2()) return State.BUILD_TRANSPARENT;
        return mode == BenchMode.QUICK ? State.BUILD_FALL : State.BUILD_TRANSPARENT;
    }

    private String afterChunkyLabel() {
        if (isLocalServerTarget()) return "本地服务端测试：实体碰撞（50）…";
        if (isV2()) return "建造透明方块建筑…";
        return mode == BenchMode.QUICK ? "生物跌落准备（300猪）…" : "建造透明方块建筑…";
    }

    private void tickChunkyWait(long now) {
        if (ChunkyBridge.hasRunningTasks()) {
            // 卡死检测：任务存在但计数长时间不增长（如 Chunky 内部异常）→ 取消并跳过，避免无限等待
            long c = ChunkyBridge.taskCount();
            if (c != lastChunkyCount) {
                lastChunkyCount = c;
                lastChunkyAdvanceNanos = now;
            }
            if (c == lastChunkyCount && now - lastChunkyAdvanceNanos > 60_000_000_000L) {
                ChunkyBridge.cancelAll();
                chunkWatchArmed = false;
                McBench.LOGGER.warn("[MCBench] Chunky 任务 60 秒无进展（疑似卡死），已取消并跳过该环节");
                result.note("Chunky 任务 60 秒无进展（疑似卡死），该环节跳过并重新归一化权重");
                result.metric("chunkGenSeconds", -1);
                state = afterChunkyState();
                phaseStartNanos = now;
                phaseLabel = afterChunkyLabel();
                return;
            }
            if (now - phaseStartNanos > 20 * 60_000_000_000L) {
                ChunkyBridge.cancelAll();
                chunkWatchArmed = false;
                result.note("Chunky 超时（>20min），已取消");
            } else {
                return;
            }
        } else {
            chunkWatchArmed = false;
        }
        // 优先使用服务端时间戳（客户端冻结不影响）；无服务端时间戳时退回客户端兜底
        double seconds;
        if (chunkStartServerNanos > 0 && chunkEndServerNanos > 0) {
            seconds = (chunkEndServerNanos - chunkStartServerNanos) / 1_000_000_000.0;
        } else {
            seconds = (now - phaseStartNanos) / 1_000_000_000.0;
        }
        // 安全网：40401 个区块在真实机器上不可能 1 秒内完成；
        // 若出现说明测试世界未真正重建（残留存档/Chunky 缓存），作废该环节并重新归一化权重
        if (seconds < 1.0) {
            result.note("Chunky 用时异常（" + String.format("%.1f", seconds)
                    + "s < 1s），疑似测试世界未真正重建，该环节作废并重新归一化权重");
            seconds = -1;
        }
        result.metric("chunkGenSeconds", seconds);
        McBench.LOGGER.info("[MCBench] Chunky 区块生成完成: {}s", String.format("%.1f", seconds));
        state = afterChunkyState();
        phaseStartNanos = System.nanoTime();
        phaseLabel = afterChunkyLabel();
    }

    // ================= 场景 =================

    private void tickBuildTransparent(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        long[] buildMs = new long[1];
        onServer(level, () -> buildMs[0] = SceneBuilder.buildTransparentStructure(level));
        result.metric("structureBuildMs", buildMs[0]);
        // 玩家从远处观察透明方块群（3×3 个实心透明立方体，远距离多层透叠）
        BlockPos v = SceneBuilder.STRUCTURE_VIEW;
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 0, 0);
        beginWarmup(System.nanoTime());
        state = State.SAMPLE_TRANSPARENT;
        phaseLabel = "透明方块渲染（远距离透明群，预热+10秒采样）…";
    }

    private void tickCleanTransparent(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level != null) onServer(level, () -> SceneBuilder.removeTransparentStructure(level));
        state = State.BUILD_PIGS;
        phaseStartNanos = System.nanoTime();
        phaseLabel = "建造猪圈（" + pigCount() + "只猪）…";
    }

    private void tickBuildPigs(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        int count = pigCount();
        onServer(level, () -> SceneBuilder.buildPigPen(level, count));
        BlockPos v = SceneBuilder.PEN_VIEW;
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 0, 0);
        beginWarmup(System.nanoTime());
        state = State.SAMPLE_PIGS;
        phaseLabel = "实体渲染（" + pigCount() + "猪近距，预热+10秒采样）…";
    }

    private void tickCleanPigs(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level != null) onServer(level, () -> SceneBuilder.removePigPen(level));
        state = State.PARTICLES;
        phaseStartNanos = System.nanoTime();
        phaseLabel = "粒子准备…";
    }

    private void tickParticles(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        BlockPos v = SceneBuilder.PARTICLE_VIEW_POS;
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 0, 0);
        beginWarmup(System.nanoTime());
        state = State.SAMPLE_PARTICLES;
        phaseLabel = "粒子渲染（1000/ tick，预热+10秒采样）…";
    }

    private void tickTnt(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        BlockPos v = SceneBuilder.TNT_VIEW;
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 0, 0);
        onServer(level, () -> SceneBuilder.spawnTntField(level));
        beginWarmup(System.nanoTime());
        state = State.SAMPLE_TNT;
        phaseLabel = "1000 TNT 爆炸（预热+10秒采样）…";
    }

    /** 快测：高空平台 + 300 猪（先站平台，采样开始后抽掉平台自然跌落）。 */
    private void tickBuildFall(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        onServer(level, () -> {
            SceneBuilder.buildFallPlatform(level);
            SceneBuilder.spawnPigsOnPlatform(level);
        });
        BlockPos v = SceneBuilder.FALL_VIEW;
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 0, 0);
        beginWarmup(System.nanoTime());
        state = State.SAMPLE_FALL;
        phaseLabel = "生物跌落（300猪站平台，抽平台后自然跌落）…";
    }

    // ================= 本地集成服务端测试（屏蔽渲染） =================

    private void tickServerEntityPrep(Minecraft mc, int count, String prefix) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        onServer(level, () -> {
            SceneBuilder.removeAllEntities(level);
            SceneBuilder.buildPigPen(level, count);
        });
        BlockPos v = SceneBuilder.PEN_VIEW;
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 0, 0);
        State next = prefix.equals("entityLight") ? State.SERVER_ENTITY_LIGHT_SAMPLE : State.SERVER_ENTITY_HEAVY_SAMPLE;
        beginServerSample(prefix, next);
    }

    private void tickServerTntPrep(Minecraft mc, String prefix) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        onServer(level, () -> {
            SceneBuilder.removeAllEntities(level);
            SceneBuilder.spawnTntField(level);
        });
        BlockPos v = SceneBuilder.TNT_VIEW;
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 0, 0);
        beginServerSample(prefix, State.SERVER_TNT_SAMPLE);
    }

    private void tickServerPathfindingPrep(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        onServer(level, () -> SceneBuilder.buildPathfindingStress(level, 50));
        BlockPos v = new BlockPos(-25, SceneBuilder.currentTestY() + 20, -25);
        teleportPlayer(mc, v.getX() + 0.5, v.getY() + 0.5, v.getZ() + 0.5, 45, 0);
        beginServerSample("pathfinding", State.SERVER_PATHFINDING_SAMPLE);
    }

    private void tickServerBlockUpdatePrep(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        long[] ms = new long[1];
        onServer(level, () -> ms[0] = SceneBuilder.buildBlockUpdateStress(level));
        result.metric("blockUpdateSeconds", round2(ms[0] / 1000.0));
        McBench.LOGGER.info("[MCBench] 本地服务端方块更新: {}ms", ms[0]);
        serverSampleStartTick = -1;
        state = State.SERVER_MAX_TICK;
        phaseStartNanos = System.nanoTime();
        phaseLabel = "本地服务端测试：极限运行（sprint 200 ticks）…";
    }

    private int fakePlayerCount() {
        return mode == BenchMode.V2_QUICK ? 20 : 40;
    }

    private void startFakePlayersForServer(ServerLevel level) {
        net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null) return;
        if (!server.isDedicatedServer() && !server.isPublished()) {
            try {
                server.publishServer(net.minecraft.world.level.GameType.CREATIVE, false, 0);
            } catch (Exception e) {
                McBench.LOGGER.warn("[MCBench] 开放局域网失败，假人可能无法连接: {}", e.toString());
            }
        }
        int count = fakePlayerCount();
        // 自动扩展玩家上限
        try {
            java.lang.reflect.Field f = net.minecraft.server.players.PlayerList.class.getDeclaredField("maxPlayers");
            f.setAccessible(true);
            originalMaxPlayers = f.getInt(server.getPlayerList());
            f.setInt(server.getPlayerList(), Math.max(count + 5, originalMaxPlayers));
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] 扩展玩家上限失败: {}", e.toString());
        }
        String host = "127.0.0.1";
        int port = server.getPort();
        boolean ok = FakeClientBridge.INSTANCE.startFakeClients(host, port, count, "MCBenchFake", 30, "walk");
        result.note(ok
                ? "已启动 Mineflayer 假人 " + count + " 名模拟玩家（TPS/MSPT 采样中）"
                : "Mineflayer 假人启动失败，回退实体模拟");
        if (!ok) {
            SceneBuilder.buildPlayerSimulation(level, 50);
        }
    }

    private void stopFakePlayersForServer(ServerLevel level) {
        FakeClientBridge.INSTANCE.stopAll();
        if (originalMaxPlayers > 0) {
            try {
                net.minecraft.server.MinecraftServer server = level.getServer();
                if (server != null) {
                    java.lang.reflect.Field f = net.minecraft.server.players.PlayerList.class.getDeclaredField("maxPlayers");
                    f.setAccessible(true);
                    f.setInt(server.getPlayerList(), originalMaxPlayers);
                }
            } catch (Exception e) {
                McBench.LOGGER.warn("[MCBench] 恢复玩家上限失败: {}", e.toString());
            }
            originalMaxPlayers = -1;
        }
    }

    private void tickServerPlayersPrep(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        onServer(level, () -> {
            SceneBuilder.removeAllEntities(level);
            startFakePlayersForServer(level);
        });
        beginServerSample("players", State.SERVER_PLAYERS_SAMPLE);
    }

    private void beginServerSample(String prefix, State nextState) {
        // 本地服务端使用低负载渲染（640×360 + 10fps），不再完全取消渲染，避免 Sable 等模组异常
        serverSamplePrefix = prefix;
        serverSampleStartTick = -1;
        serverSampleStartNanos = 0;
        serverSampleTargetTicks = 100;
        phaseStartNanos = System.nanoTime();
        phaseLabel = "本地服务端测试：" + prefix + "（100 ticks）…";
        state = nextState;
    }

    private void tickServerSample(Minecraft mc, String prefix) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        long now = System.nanoTime();
        if (now - phaseStartNanos > 60_000_000_000L) {
            result.note(prefix + " 采样超时（>60s），跳过");
            serverSampleStartTick = -1;
            if ("players".equals(prefix)) stopFakePlayersForServer(level);
            nextServerStage(prefix);
            return;
        }
        if (serverSampleStartTick < 0) {
            long[] startTick = new long[1];
            long[] startNanos = new long[1];
            onServer(level, () -> {
                startTick[0] = level.getServer().getTickCount();
                startNanos[0] = System.nanoTime();
            });
            serverSampleStartTick = startTick[0];
            serverSampleStartNanos = startNanos[0];
            return;
        }
        long[] endTick = new long[1];
        long[] endNanos = new long[1];
        onServer(level, () -> {
            endTick[0] = level.getServer().getTickCount();
            endNanos[0] = System.nanoTime();
        });
        long elapsedTicks = endTick[0] - serverSampleStartTick;
        if (elapsedTicks < serverSampleTargetTicks) return;
        double seconds = (endNanos[0] - serverSampleStartNanos) / 1e9;
        if (elapsedTicks <= 0 || seconds <= 0) {
            result.note(prefix + " 采样无效，跳过");
        } else {
            double tps = elapsedTicks / seconds;
            double mspt = seconds * 1000.0 / elapsedTicks;
            result.metric(prefix + "Tps", round2(tps));
            result.metric(prefix + "Mspt", round2(mspt));
            McBench.LOGGER.info("[MCBench] 本地服务端 {}: {} ticks / {}s -> TPS={} MSPT={}",
                    prefix, elapsedTicks, String.format("%.2f", seconds),
                    String.format("%.2f", tps), String.format("%.2f", mspt));
        }
        serverSampleStartTick = -1;
        if ("players".equals(prefix)) stopFakePlayersForServer(level);
        nextServerStage(prefix);
    }

    private void nextServerStage(String prefix) {
        switch (prefix) {
            case "entityLight" -> {
                state = State.SERVER_ENTITY_HEAVY_PREP;
                phaseLabel = "本地服务端测试：实体碰撞（300）…";
            }
            case "entityHeavy" -> {
                state = State.SERVER_TNT_PREP;
                phaseLabel = "本地服务端测试：TNT 爆炸…";
            }
            case "tnt" -> {
                state = State.SERVER_PATHFINDING_PREP;
                phaseLabel = "本地服务端测试：寻路压力…";
            }
            case "pathfinding" -> {
                state = State.SERVER_BLOCK_UPDATE_PREP;
                phaseLabel = "本地服务端测试：方块更新…";
            }
            case "blockUpdate" -> {
                // 本地服务端模式不包含玩家数量测试（40/20 假人仅远端使用）
                state = State.SERVER_MAX_TICK;
                phaseLabel = "本地服务端测试：极限运行（sprint 200 ticks）…";
            }
            default -> {
                state = State.CLEANUP;
                phaseStartNanos = System.nanoTime();
                phaseLabel = "数据汇总中……";
            }
        }
    }

    private void tickServerMaxTick(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        long now = System.nanoTime();
        if (now - phaseStartNanos > 60_000_000_000L) {
            result.note("极限运行采样超时（>60s），跳过");
            serverSampleStartTick = -1;
            setRenderSuppressed(false);
            state = State.CLEANUP;
            phaseStartNanos = now;
            phaseLabel = "数据汇总中……";
            return;
        }
        if (serverSampleStartTick < 0) {
            onServer(level, () -> {
                SceneBuilder.removeAllEntities(level);
                serverSampleStartTick = level.getServer().getTickCount();
                serverSampleStartNanos = System.nanoTime();
                level.getServer().tickRateManager().requestGameToSprint(200);
            });
            phaseLabel = "本地服务端测试：极限运行（sprint 200 ticks）…";
            return;
        }
        long[] endTick = new long[1];
        long[] endNanos = new long[1];
        boolean[] sprinting = new boolean[1];
        onServer(level, () -> {
            endTick[0] = level.getServer().getTickCount();
            endNanos[0] = System.nanoTime();
            sprinting[0] = level.getServer().tickRateManager().isSprinting();
        });
        long elapsedTicks = endTick[0] - serverSampleStartTick;
        if (elapsedTicks < 200 && sprinting[0]) return;
        double seconds = (endNanos[0] - serverSampleStartNanos) / 1e9;
        if (elapsedTicks > 0 && seconds > 0) {
            result.metric("maxTickSeconds", round2(seconds));
            result.metric("maxTickTps", round2(elapsedTicks / seconds));
            McBench.LOGGER.info("[MCBench] 本地服务端极限运行: {} ticks / {}s", elapsedTicks, String.format("%.2f", seconds));
        } else {
            result.note("极限运行（sprint 200 ticks）采样无效，跳过");
        }
        serverSampleStartTick = -1;
        setRenderSuppressed(false);
        state = State.CLEANUP;
        phaseStartNanos = System.nanoTime();
        phaseLabel = "数据汇总中……";
    }

    // ================= 机械动力专项 =================

    private void tickCreatePrep(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        if (!CreateModBridge.isAvailable()) {
            result.note("未检测到机械动力（Create）模组，2.0 模组专项跳过");
            state = State.CLEANUP;
            phaseStartNanos = System.nanoTime();
            phaseLabel = "数据汇总中……";
            return;
        }
        long[] ms = new long[1];
        onServer(level, () -> ms[0] = CreateModBridge.buildCreateStress(level, 300, 300));
        result.metric("createStructureBuildMs", ms[0]);
        BlockPos view = new BlockPos(0, SceneBuilder.Y_GROUND + 40, -40);
        teleportPlayer(mc, view.getX() + 0.5, view.getY() + 0.5, view.getZ() + 0.5, 0, 0);
        beginWarmup(System.nanoTime());
        state = State.CREATE_SAMPLE;
        phaseLabel = "机械动力专项（300水车+300烈焰人燃烧室，预热+10秒采样）…";
    }

    // ================= 公平测试 =================

    private void tickFairCpu() {
        if (!cpuBenchStarted) {
            cpuBenchStarted = true;
            phaseLabel = "CPU 计算基准（后台运行，界面可保持流畅）…";
            CpuBench.runAsync(cpuResults, cpuProgress, cpuTotal, () -> cpuBenchFinished = true);
            return;
        }
        if (!cpuBenchFinished) return;
        state = State.FAIR_GPU;
        ResolutionLock.apply(); // GPU 填充率按锁定分辨率测量（像素负载统一）
        gpuBenchStartNanos = System.nanoTime();
        gpuBenchFrames = 0;
        phaseLabel = "GPU 填充率基准（120 帧）…";
    }

    private void tickFairGpu(long now) {
        GpuBench.drawFills(gpuBenchDrawsPerFrame);
        gpuBenchFrames++;
        if (gpuBenchFrames < 120) return;
        double elapsedSec = (now - gpuBenchStartNanos) / 1e9;
        double fillsPerSec = elapsedSec > 0 ? (gpuBenchFrames * gpuBenchDrawsPerFrame) / elapsedSec : 0;
        result.metric("gpuFillRate", round2(fillsPerSec));
        result.metric("gpuFrames", gpuBenchFrames);
        result.metric("gpuElapsedSeconds", round2(elapsedSec));
        McBench.LOGGER.info("[MCBench] GPU 填充率 {} fills/s", String.format("%.0f", fillsPerSec));
        ResolutionLock.restore(); // GPU 测试结束 → 恢复原生分辨率
        state = State.CLEANUP;
        phaseStartNanos = System.nanoTime();
        phaseLabel = "数据汇总中……";
    }

    // ================= 收尾 =================

    /**
     * 粒子流：异步提交到服务器线程生成（不阻塞客户端采样帧）。
     * sendParticles 是服务器世界操作，跨线程调用不安全。
     */
    private void spawnParticlesAsync(Minecraft mc) {
        ServerLevel level = serverLevel(mc);
        if (level == null) return;
        net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null) return;
        server.execute(() -> {
            ServerPlayer sp = serverPlayer(mc);
            if (sp != null) {
                SceneBuilder.spawnParticleBarrage(level, sp);
            }
        });
    }

    /** 快测跌落采样：预热 60 tick 后抽掉平台 → 猪自然碰撞跌落 → 采样 10 秒。 */
    private void tickSampleFall(long now) {
        if (sampleStartNanos == 0) {
            if (++waitTicks > 60) {
                Minecraft mc = Minecraft.getInstance();
                ServerLevel level = serverLevel(mc);
                if (level != null) {
                    onServer(level, () -> SceneBuilder.removeFallPlatform(level)); // 抽平台 → 猪自然跌落
                }
                sampleStartNanos = now;
                sampler.begin();
            }
            return;
        }
        if (now - sampleStartNanos < 10_000_000_000L) return;
        FrameSampler.Sample s = sampler.result();
        sampler.end();
        sampleStartNanos = 0;
        result.metric("fallAvgFps", round2(s.avgFps()));
        result.metric("fallLowFps", round2(s.lowFps()));
        result.metric("fallWorstFps", round2(s.worstFps()));
        result.metric("fallFrames", s.frames());
        McBench.LOGGER.info("[MCBench] fall 场景 avg={} low={}",
                String.format("%.1f", s.avgFps()), String.format("%.1f", s.lowFps()));
        onSampleDone("fall");
    }

    private void tickCleanup(Minecraft mc) {
        if (benchTarget == BenchTarget.COORDINATED) {
            // 协同模式不创建/退出测试世界，直接汇总结果
            finish();
            return;
        }
        if (mc.level != null || mc.getSingleplayerServer() != null) {
            if (!worldLeft) {
                McBench.LOGGER.info("[MCBench] CLEANUP: 开始退出世界 (level={} server={})",
                        mc.level != null, mc.getSingleplayerServer() != null);
                forceLeaveWorld(mc);
                worldLeft = true;
                waitTicks = 0;
            } else if (++waitTicks > 100) {
                // 退出世界异常（>5 秒仍未离开）→ 再强制退出一次后收尾，避免卡死在"数据汇总中"
                // 注意：此时不能删世界——服务端线程可能仍在保存，文件被锁；
                // 留待下次跑分时重建（createTestWorld 会重试删除）
                McBench.LOGGER.warn("[MCBench] 退出世界异常（5s 后仍未离开），强制收尾 (level={} server={})",
                        mc.level != null, mc.getSingleplayerServer() != null);
                result.note("退出世界异常，测试世界未能删除（下次跑分将自动重建）");
                forceLeaveWorld(mc);
                finish();
                return;
            }
            return;
        }
        if (mode == BenchMode.FAIR || mode == BenchMode.V2_FAIR) {
            finish();
            return;
        }
        // halt 是异步的：服务端线程可能仍在保存大世界（40401 区块需数秒）。
        // 必须等服务端线程真正停止、文件句柄释放后再删除世界，
        // 否则删除失败 → 残留存档 → 下次跑分 Chunky 秒完成（假高分）。
        if (leavingServer != null && leavingServer.isRunning()) {
            if (++waitTicks > 400) { // 20 秒上限
                McBench.LOGGER.warn("[MCBench] 服务端线程 20s 未停止，跳过等待直接收尾 (server={})",
                        leavingServer.isRunning());
                result.note("服务端线程未在 20s 内停止，测试世界可能残留（下次跑分将自动重建）");
                leavingServer = null;
            } else {
                return;
            }
        }
        leavingServer = null;
        // 等文件句柄完全释放后再删世界
        if (++waitTicks < 40) return;
        McBench.LOGGER.info("[MCBench] CLEANUP: 删除测试世界并收尾");
        deleteTestWorld(mc);
        finish();
    }

    /** 强制退出世界（不崩溃），显示进度界面等待收尾。 */
    private void forceLeaveWorld(Minecraft mc) {
        try {
            McBench.LOGGER.info("[MCBench] CLEANUP: disconnect 开始");
            leavingServer = mc.getSingleplayerServer();
            VersionBridge.leaveWorld(mc, new io.mcbench.ui.ProgressScreen());
            McBench.LOGGER.info("[MCBench] CLEANUP: disconnect 完成 (level={} server={})",
                    mc.level != null, mc.getSingleplayerServer() != null);
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] 退出世界异常: {}", e.toString());
        }
    }

    /**
     * 结果页“退出”按钮：无论清理阶段是否收尾干净，都保证离开测试世界并返回主界面。
     * （此前若清理超时，结果页其实还挂在测试世界上，点“退出”会回到世界而非主界面。）
     */
    public void exitToTitle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null || mc.getSingleplayerServer() != null) {
            McBench.LOGGER.info("[MCBench] 结果页退出：强制离开测试世界");
            IntegratedServer server = mc.getSingleplayerServer();
            VersionBridge.leaveWorld(mc, null);
            // 等服务端线程真正停止（保存大世界可能需数秒），让文件解锁
            if (server != null) {
                long deadline = System.nanoTime() + 15_000_000_000L;
                while (server.isRunning() && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
        mc.setScreen(null); // 返回主界面（标题界面）
    }

    private void deleteTestWorld(Minecraft mc) {
        try {
            var lss = mc.getLevelSource();
            if (lss.levelExists(McBench.WORLD_NAME)) {
                var access = lss.createAccess(McBench.WORLD_NAME);
                try {
                    access.deleteLevel();
                    McBench.LOGGER.info("[MCBench] 测试世界已删除");
                } finally {
                    access.close();
                }
            }
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] 删除测试世界失败: {}", e.toString());
        }
    }

    private void abortAndCleanup(Minecraft mc) {
        if (sampler.isActive()) sampler.end();
        setRenderSuppressed(false);
        restoreFakeClientKeys();
        FakeClientBridge.INSTANCE.stopAll();
        worldLeft = false;
        state = State.CLEANUP;
        phaseStartNanos = System.nanoTime();
        phaseLabel = "数据汇总中……";
    }

    /** 计算分数、写日志、打开结果界面。 */
    private void finish() {
        setRenderSuppressed(false);
        restoreFakeClientKeys();
        gcMonitor.stop();
        ResolutionLock.restore(); // 兜底恢复（幂等；采样阶段结束时已恢复）
        if (settingsSnapshot != null) SettingsApplier.restore(settingsSnapshot);

        var gc = gcMonitor.snapshot();
        result.forcedGcTotal = gc.total();
        result.forcedGcByCollector.putAll(gc.forcedByCollector());

        result.elapsedSeconds = round2((System.nanoTime() - benchmarkStartNanos) / 1e9);

        BenchConfig cfg = BenchConfig.get();
        ScoringEngine.fillRules(result, cfg, mode.name());
        computeScores(cfg);

        BenchLogger.write(result);
        McBench.LOGGER.info("[MCBench] 跑分完成 总分={} 日志={}",
                String.format("%.1f", result.totalScore), result.logFile);

        state = State.FINISHED;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new io.mcbench.ui.ResultScreen(result));
    }

    private void computeScores(BenchConfig cfg) {
        if (isV2() && mode == BenchMode.V2_MOD_CREATE) {
            result.note("2.0 模组专项（机械动力）场景尚未在本核心框架中实现，请等待后续模块");
            result.totalScore = 0;
            return;
        }
        if (benchTarget == BenchTarget.COORDINATED) {
            result.totalScore = 0;
            result.note("协同模式：客户端仅渲染采样，记录 coordinatedAvgFps/coordinatedLowFps 等指标，暂不计总分");
            return;
        }
        if (isLocalServerTarget() && isV2()) {
            computeScoresV2Server(cfg);
            return;
        }
        if (isV2() && mode != BenchMode.V2_FAIR) {
            computeScoresV2(cfg);
            return;
        }
        double cap = cfg.cap;
        Map<String, Double> sceneWeights = new LinkedHashMap<>();
        if (mode == BenchMode.COMBAT) {
            BenchConfig.Combat c = cfg.reference.combat;
            if (metric("worldLoadSeconds") > 0) {
                result.subScore("世界加载", round1(ScoringEngine.lowerBetter(metric("worldLoadSeconds"), c.worldLoadSeconds, cap)));
                sceneWeights.put("世界加载", cfg.weights.combat.worldLoad);
            } else {
                result.note("世界加载数据无效，该环节跳过并重新归一化");
            }
            double chunk = metric("chunkGenSeconds");
            if (chunk > 0) {
                result.subScore("Chunky区块生成", round1(ScoringEngine.lowerBetter(chunk, c.chunkGenSeconds, cap)));
                sceneWeights.put("Chunky区块生成", cfg.weights.combat.chunkGen);
            }
            addFrameScene("透明方块渲染", "transparent", c.transparentAvgFps, c.transparentLowFps,
                    cfg, cap, sceneWeights, cfg.weights.combat.transparent);
            addFrameScene("实体渲染(250猪)", "entity", c.entityAvgFps, c.entityLowFps,
                    cfg, cap, sceneWeights, cfg.weights.combat.entity);
            addFrameScene("粒子渲染", "particle", c.particleAvgFps, c.particleLowFps,
                    cfg, cap, sceneWeights, cfg.weights.combat.particle);
            addFrameScene("TNT爆炸", "tnt", c.tntAvgFps, c.tntLowFps,
                    cfg, cap, sceneWeights, cfg.weights.combat.tnt);
            result.note("空闲场景帧率（idleAvgFps/idleLowFps）仅记录，不参与计分");
        } else if (mode == BenchMode.QUICK) {
            BenchConfig.Quick q = cfg.reference.quick;
            if (metric("worldLoadSeconds") > 0) {
                result.subScore("世界加载", round1(ScoringEngine.lowerBetter(metric("worldLoadSeconds"), q.worldLoadSeconds, cap)));
                sceneWeights.put("世界加载", cfg.weights.quick.worldLoad);
            } else {
                result.note("世界加载数据无效，该环节跳过并重新归一化");
            }
            double chunk = metric("chunkGenSeconds");
            if (chunk > 0) {
                result.subScore("Chunky区块生成", round1(ScoringEngine.lowerBetter(chunk, q.chunkGenSeconds, cap)));
                sceneWeights.put("Chunky区块生成", cfg.weights.quick.chunkGen);
            }
            addFrameScene("生物跌落", "fall", q.fallAvgFps, q.fallLowFps,
                    cfg, cap, sceneWeights, cfg.weights.quick.fall);
        } else if (mode == BenchMode.FAIR || mode == BenchMode.V2_FAIR) {
            BenchConfig.Fair f = cfg.reference.fair;
            for (CpuBench.CpuResult r : cpuResults) {
                String key = "CPU-" + r.name();
                if (r.singleOpsPerSec() <= 0 || r.multiOpsPerSec() <= 0) {
                    result.note(key + " 数据无效，该子项跳过并重新归一化");
                    continue;
                }
                boolean bandwidth = r.name().contains("内存带宽");
                // 内存带宽受 DRAM 上限约束，多线程不翻倍 —— 单/多线程都用带宽基准
                double refMulti = bandwidth ? f.memoryBandwidth : f.cpuMulti;
                result.subScore(key + "-单线程", round1(ScoringEngine.higherBetter(r.singleOpsPerSec(), f.cpuSingle, cap)));
                result.subScore(key + "-多线程", round1(ScoringEngine.higherBetter(r.multiOpsPerSec(), refMulti, cap)));
                result.metric(key + "单线程", round2(r.singleOpsPerSec()));
                result.metric(key + "多线程", round2(r.multiOpsPerSec()));
                sceneWeights.put(key + "-单线程", cfg.weights.fair.cpuSingle);
                sceneWeights.put(key + "-多线程", cfg.weights.fair.cpuMulti);
            }
            if (metric("gpuFillRate") > 0) {
                result.subScore("GPU填充率", round1(ScoringEngine.higherBetter(metric("gpuFillRate"), f.gpuFillRate, cap)));
                sceneWeights.put("GPU填充率", cfg.weights.fair.gpu);
            } else {
                result.note("GPU 填充率数据无效，该环节跳过并重新归一化");
            }
        }
        result.totalScore = round1(ScoringEngine.weightedTotal(result.subScores, sceneWeights, cfg.scoreScale));
        result.note("计分规则与基准常数详见日志 rules 段与 config/mcbench.json");
    }

    /**
     * 2.0 本地服务端计分（简化版）：
     *  - 世界加载 / Chunky / 极限运行按耗时倍率
     *  - 实体碰撞 / TNT 按 MSPT 倍率（越低越好）
     *  - 总分 = 几何平均 × totalScale
     */
    private void computeScoresV2Server(BenchConfig cfg) {
        BenchConfig.Server s = cfg.v2.server;
        List<Double> ratios = new ArrayList<>();
        addV2Time("世界加载", "worldLoadSeconds", s.worldLoadSeconds, ratios);
        addV2Time("Chunky区块生成", "chunkGenSeconds", s.chunkGenSeconds, ratios);
        addV2LowerMetric("实体碰撞(50)", "entityLightMspt", s.entityCollisionLightMspt, ratios);
        addV2LowerMetric("实体碰撞(300)", "entityHeavyMspt", s.entityCollisionHeavyMspt, ratios);
        addV2LowerMetric("TNT爆炸", "tntMspt", s.tntMspt, ratios);
        addV2LowerMetric("寻路压力", "pathfindingMspt", s.pathfindingMspt, ratios);
        addV2LowerMetric("方块更新", "blockUpdateSeconds", s.blockUpdateSeconds, ratios);
        addV2LowerMetric("50玩家模拟(简化)", "playersMspt", s.fiftyPlayerMspt, ratios);
        addV2LowerMetric("极限运行", "maxTickSeconds", s.maxTickSeconds, ratios);
        result.totalScore = round1(ScoringEngine.geometricMean(ratios) * cfg.v2.totalScale);
        result.note("2.0 本地服务端计分：世界加载/Chunky/极限运行为耗时倍率，碰撞/TNT 为 MSPT 倍率");
        result.note("服务端基准 v2.server 为占位值，待联测模块实测后定稿");
    }

    private void addV2LowerMetric(String label, String key, double ref, List<Double> ratios) {
        double v = metric(key);
        if (v > 0) {
            double ratio = ScoringEngine.ratioLower(v, ref);
            result.subScore(label, round2(ratio * 100.0));
            ratios.add(ratio);
        } else {
            result.note(label + " 数据无效，未计入本地服务端总倍率");
        }
    }

    /**
     * 2.0 客户端计分：所有项目不封顶。
     *  - 耗时项目倍率 = 基准时间 / 实测时间
     *  - 帧项目倍率 = 0.4×平均帧倍率 + 0.6×低帧倍率
     *  - 总分 = 六项几何平均 × totalScale（默认 100，1x 基准机 = 100 分）
     */
    private void computeScoresV2(BenchConfig cfg) {
        BenchConfig.Client c = cfg.v2.client;
        List<Double> ratios = new ArrayList<>();
        addV2Time("世界加载", "worldLoadSeconds", c.worldLoadSeconds, ratios);
        addV2Time("Chunky区块生成", "chunkGenSeconds", c.chunkGenSeconds, ratios);
        addV2Frame("透明方块渲染", "transparent", c.transparentAvgFps, c.transparentLowFps, ratios);
        addV2Frame("实体渲染", "entity", c.entityAvgFps, c.entityLowFps, ratios);
        addV2Frame("粒子渲染", "particle", c.particleAvgFps, c.particleLowFps, ratios);
        addV2Frame("TNT爆炸", "tnt", c.tntAvgFps, c.tntLowFps, ratios);
        result.totalScore = round1(ScoringEngine.geometricMean(ratios) * cfg.v2.totalScale);
        result.note("2.0 计分：各项倍率不封顶，总分 = 几何平均倍率 × " + cfg.v2.totalScale
                + "（1x 基准机 = " + cfg.v2.totalScale + " 分）");
        result.note("2.0 客户端基准：手机原版（worldLoad=0.862s / chunkGen=242.858s / 透明=57.65/14.36 / "
                + "实体=100.08/32.84 / 粒子=244.42/116.16 / TNT=213.81/30.59）");
        result.note("空闲场景帧率（idleAvgFps/idleLowFps/idleWorstFps）仅记录，不参与计分");
    }

    private void addV2Time(String label, String key, double ref, List<Double> ratios) {
        double v = metric(key);
        if (v > 0) {
            double ratio = ScoringEngine.ratioLower(v, ref);
            result.subScore(label, round2(ratio * 100.0));
            ratios.add(ratio);
        } else {
            result.note(label + " 数据无效，未计入 2.0 总倍率");
        }
    }

    private void addV2Frame(String label, String prefix, double refAvg, double refLow, List<Double> ratios) {
        double avg = metric(prefix + "AvgFps");
        double low = metric(prefix + "LowFps");
        if (avg > 0 && low > 0) {
            double ratio = ScoringEngine.ratioFrame(avg, low, refAvg, refLow, 0.4, 0.6);
            result.subScore(label, round2(ratio * 100.0));
            ratios.add(ratio);
        } else {
            result.note(label + " 无有效帧率数据，未计入 2.0 总倍率");
        }
    }

    /** 帧场景计分：只有 avg/low 都有效才入总分，避免一次失败把整个总分拖成 0。 */
    private void addFrameScene(String label, String prefix, double refAvg, double refLow,
                               BenchConfig cfg, double cap, Map<String, Double> sceneWeights, double weight) {
        double avg = metric(prefix + "AvgFps");
        double low = metric(prefix + "LowFps");
        if (avg > 0 && low > 0) {
            result.subScore(label, round1(ScoringEngine.frameScene(avg, low,
                    refAvg, refLow, cfg.avgWeight, cfg.lowWeight, cap)));
            sceneWeights.put(label, weight);
        } else {
            result.note(label + " 无有效帧率数据，该环节跳过并重新归一化");
        }
    }

    private double metric(String key) {
        Double v = result.metrics.get(key);
        return v == null ? 0 : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ================= 工具 =================

    private ServerLevel serverLevel(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return null;
        return server.overworld();
    }

    private ServerPlayer serverPlayer(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return null;
        return server.getPlayerList().getPlayer(mc.player.getUUID());
    }

    /**
     * 在服务器线程执行操作（同步等待，超时 10 秒）。
     * 客户端 tick 线程不能直接操作服务器世界（实体集合/方块），否则会与
     * 服务器线程的实体 tick 并发冲突（ConcurrentModificationException）。
     */
    private void onServer(ServerLevel level, Runnable action) {
        if (level == null) return;
        net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null || server.isSameThread()) {
            action.run();
            return;
        }
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    action.run();
                } catch (Exception e) {
                    McBench.LOGGER.warn("[MCBench] 服务器操作异常: {}", e.toString());
                } finally {
                    done.complete(null);
                }
            });
            done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] 服务器操作超时/失败: {}", e.toString());
        }
    }

    /** 传送服务器玩家并同步客户端视角；同时开启飞行（防止传送后从高空掉落）。 */
    private void teleportPlayer(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        ServerPlayer sp = serverPlayer(mc);
        if (sp != null) {
            ServerLevel level = VersionBridge.playerLevel(sp);
            onServer(level, () -> {
                sp.teleportTo(x, y, z);
                sp.setYRot(yaw);
                sp.setXRot(pitch);
                sp.setYHeadRot(yaw);
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
            });
        }
        if (mc.player != null) {
            mc.player.teleportTo(x, y, z);
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.setYHeadRot(yaw);
            mc.player.getAbilities().flying = true;
        }
    }
}
