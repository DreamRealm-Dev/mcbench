package io.mcbench.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.mcbench.McBench;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 跑分配置：基准常数与权重（规则透明的核心）。
 *
 * 所有分数归一化都基于这里的“基准机”常数：
 *  - 越高越好型（帧率）：子分 = min(cap, 100 × 实测/基准)
 *  - 越低越好型（耗时）：子分 = min(cap, 100 × 基准/实测)
 *  - 基准机（各指标恰好等于基准值）得 100 分/项，总分 = Σ(权重×子分)×scoreScale，
 *    因此基准机总分恒为 100×scoreScale（默认 500 分）。
 *
 * 配置文件位于 <游戏目录>/config/mcbench.json，可自行修改并复现结果；
 * 每次跑分日志都会记录实际使用的常数，保证规则透明、结果可比。
 */
public final class BenchConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int version = 2;

    public Reference reference = new Reference();
    public Weights weights = new Weights();

    /** 2.0 基准：客户端以手机原版为 1x 参考，倍率不封顶；服务端基准为占位值。 */
    public V2 v2 = new V2();

    /** 总分缩放：子分 0~200，加权后 ×scoreScale（默认 5 → 总分 0~1000，基准机=500） */
    public double scoreScale = 5.0;
    /** 单项子分封顶：2×基准=200 分（防高配设备单项无限刷分，保证跨设备可比） */
    public double cap = 200.0;
    /** 1% Low 帧权重（卡顿体验）与平均帧权重，两者之和必须为 1 */
    public double lowWeight = 0.6;
    public double avgWeight = 0.4;

    public static class Reference {
        public Combat combat = new Combat();
        public Quick quick = new Quick();
        public Fair fair = new Fair();
    }

    /** 综合实战测试：各场景基准值（基准机 = 在此配置的机器上各指标恰好为基准值） */
    public static class Combat {
        public double worldLoadSeconds = 15.0;
        public double chunkGenSeconds = 60.0;
        // 1.0.0 场景增强后按“3060Ti 原版 ≈ 650 分”重新标定：
        // 透明场景改为远距离 3×3 透明群、粒子增强至 1000/ tick、猪圈观察点拉近。
        public double transparentAvgFps = 230.0;
        public double transparentLowFps = 65.0;
        public double entityAvgFps = 240.0;
        public double entityLowFps = 85.0;
        public double particleAvgFps = 380.0;
        public double particleLowFps = 120.0;
        public double tntAvgFps = 880.0;
        public double tntLowFps = 270.0;
    }

    /** 快测：各环节基准值 */
    public static class Quick {
        public double worldLoadSeconds = 15.0;
        public double chunkGenSeconds = 60.0;
        public double fallAvgFps = 60.0;
        public double fallLowFps = 30.0;
    }

    /** 公平性能测试：纯计算基准值（ops/秒 等） */
    public static class Fair {
        // 这些基准值按 1.21.1 实测手机/桌面数据上调，避免子分数大量撞 200 上限（“数值溢出”）。
        // 如仍想自定标尺，可直接修改 config/mcbench.json。
        public double cpuSingle = 20_000_000.0;
        public double cpuMulti = 50_000_000.0;
        public double memoryBandwidth = 150_000.0;   // MB/s
        public double sha256Speed = 50_000_000.0;    // KB/s
        public double gpuFillRate = 20_000.0;        // 全屏填充次数/秒
    }

    /** 2.0 基准数据：客户端/服务端分离，服务端数值待联测模块实测定稿。 */
    public static class V2 {
        public Client client = new Client();
        public Server server = new Server();
        /** 总分缩放：2.0 使用倍率几何平均 × totalScale，默认 100（1x 基准机 = 100 分） */
        public double totalScale = 100.0;
    }

    /**
     * 2.0 客户端基准（手机原版 1.00x 参考）。
     * 注意：这些数值来自用户提供的手机原版实测，作为 2.0 的“1x 基准”。
     */
    public static class Client {
        public double worldLoadSeconds = 0.862;
        public double chunkGenSeconds = 242.858;
        public double transparentAvgFps = 57.65;
        public double transparentLowFps = 14.36;
        public double entityAvgFps = 100.08;
        public double entityLowFps = 32.84;
        public double particleAvgFps = 244.42;
        public double particleLowFps = 116.16;
        public double tntAvgFps = 213.81;
        public double tntLowFps = 30.59;
        /** 闲置 FPS 记录时长（2.0 起 20 秒） */
        public double idleSeconds = 20.0;
        /** 2.0 综合实体数量（与手机基准对齐） */
        public int entityCount = 250;
        /** 2.0 快测实体数量 */
        public int quickEntityCount = 200;
    }

    /** 2.0 服务端基准（占位，待服务端联测模块实测后填入合理参考值）。 */
    public static class Server {
        public double worldLoadSeconds = 15.0;
        public double chunkGenSeconds = 120.0;
        public double entityCollisionLightTps = 20.0;
        public double entityCollisionLightMspt = 50.0;
        public double entityCollisionHeavyTps = 20.0;
        public double entityCollisionHeavyMspt = 50.0;
        public double tntTps = 20.0;
        public double tntMspt = 50.0;
        public double pathfindingTps = 20.0;
        public double pathfindingMspt = 50.0;
        public double blockUpdateTps = 20.0;
        public double blockUpdateMspt = 50.0;
        /** 方块更新压力测试的放置耗时参考（秒） */
        public double blockUpdateSeconds = 5.0;
        public double maxTickSeconds = 120.0;
        public double fiftyPlayerTps = 20.0;
        public double fiftyPlayerMspt = 50.0;
    }

    /** 场景权重（总和应为 1；跑分时会按实际执行场景重新归一化） */
    public static class Weights {
        public CombatWeights combat = new CombatWeights();
        public QuickWeights quick = new QuickWeights();
        public FairWeights fair = new FairWeights();
    }

    public static class CombatWeights {
        public double worldLoad = 0.10;
        public double chunkGen = 0.15;
        public double transparent = 0.20;
        public double entity = 0.20;
        public double particle = 0.15;
        public double tnt = 0.20;
    }

    public static class QuickWeights {
        public double worldLoad = 0.30;
        public double chunkGen = 0.40;
        public double fall = 0.30;
    }

    /** 公平性能测试权重：CPU 单/多线程各 4 项、GPU 填充率。 */
    public static class FairWeights {
        public double cpuSingle = 0.125;
        public double cpuMulti = 0.125;
        public double gpu = 0.5;
    }

    /** 兼容旧配置文件：补充缺失的嵌套对象、修正非法基础值。 */
    public void normalize() {
        if (reference == null) reference = new Reference();
        if (reference.combat == null) reference.combat = new Combat();
        if (reference.quick == null) reference.quick = new Quick();
        if (reference.fair == null) reference.fair = new Fair();
        if (weights == null) weights = new Weights();
        if (weights.combat == null) weights.combat = new CombatWeights();
        if (weights.quick == null) weights.quick = new QuickWeights();
        if (weights.fair == null) weights.fair = new FairWeights();
        if (v2 == null) v2 = new V2();
        if (v2.client == null) v2.client = new Client();
        if (v2.server == null) v2.server = new Server();
        if (v2.totalScale <= 0) v2.totalScale = 100.0;
        if (scoreScale <= 0) scoreScale = 5.0;
        if (cap <= 0) cap = 200.0;
        if (avgWeight + lowWeight <= 0) {
            avgWeight = 0.4;
            lowWeight = 0.6;
        }
    }

    /**
     * 1.0.0 迁移：旧版（0.x）配置中的综合实战帧率基准是 60/30，会导致 3060Ti 原版大量撞 200 分。
     * 只有仍等于旧默认值的项才会更新为新标定值；用户手动调过的值保留。
     */
    public void migrateFromV1() {
        if (reference.combat == null) reference.combat = new Combat();
        Combat c = reference.combat;
        if (c.transparentAvgFps <= 0 || c.transparentAvgFps == 60.0) c.transparentAvgFps = 230.0;
        if (c.transparentLowFps <= 0 || c.transparentLowFps == 30.0) c.transparentLowFps = 65.0;
        if (c.entityAvgFps <= 0 || c.entityAvgFps == 60.0) c.entityAvgFps = 240.0;
        if (c.entityLowFps <= 0 || c.entityLowFps == 30.0) c.entityLowFps = 85.0;
        if (c.particleAvgFps <= 0 || c.particleAvgFps == 60.0) c.particleAvgFps = 380.0;
        if (c.particleLowFps <= 0 || c.particleLowFps == 30.0) c.particleLowFps = 120.0;
        if (c.tntAvgFps <= 0 || c.tntAvgFps == 60.0) c.tntAvgFps = 880.0;
        if (c.tntLowFps <= 0 || c.tntLowFps == 30.0) c.tntLowFps = 270.0;
        version = 2;
    }

    private static BenchConfig instance = new BenchConfig();

    public static BenchConfig get() {
        return instance;
    }

    /** 加载 config/mcbench.json；不存在则写入默认值（规则透明：用户可改） */
    public static void load() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        Path file = dir.resolve("mcbench.json");
        try {
            if (Files.exists(file)) {
                instance = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BenchConfig.class);
                if (instance == null) instance = new BenchConfig();
                instance.normalize();
                if (instance.version < 2) {
                    instance.migrateFromV1();
                    Files.writeString(file, GSON.toJson(instance), StandardCharsets.UTF_8);
                    McBench.LOGGER.info("[MCBench] 配置已从 0.x 自动迁移到 1.0.0 基准（{}/{}）",
                            file.getParent(), file.getFileName());
                }
            } else {
                Files.createDirectories(dir);
                instance.normalize();
                Files.writeString(file, GSON.toJson(instance), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            McBench.LOGGER.warn("[MCBench] 读取配置失败，使用默认值: {}", e.toString());
            instance = new BenchConfig();
        }
    }
}
