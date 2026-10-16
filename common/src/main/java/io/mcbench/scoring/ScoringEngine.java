package io.mcbench.scoring;

import io.mcbench.bench.BenchConfig;

import java.util.Map;

/**
 * 计分引擎（纯 Java，规则透明）：
 *
 * 1) 归一化：
 *    - 越高越好（帧率等）：sub = min(cap, 100 × 实测/基准)
 *    - 越低越好（耗时等）：sub = min(cap, 100 × 基准/实测)
 *    - 基准机（实测=基准）每项恰得 100 分；2×基准封顶 200 分。
 *
 * 2) 帧场景分：avgWeight×avg子分 + lowWeight×1%Low子分（默认 0.4/0.6，
 *    卡顿体验权重更高，避免“平均帧虚高但掉帧严重”的机器得高分）。
 *
 * 3) 总分 = Σ(场景权重×场景分) × scoreScale（默认 5 → 0~1000，基准机=500）。
 *    若某场景被跳过（如未装 Chunky），权重会按已执行场景重新归一化。
 *
 * 4) 保留全部子分数用于瓶颈定位。
 */
public final class ScoringEngine {

    private ScoringEngine() {
    }

    public static double higherBetter(double value, double ref, double cap) {
        if (value <= 0 || ref <= 0 || cap <= 0) return 0;
        return Math.min(cap, 100.0 * value / ref);
    }

    public static double lowerBetter(double value, double ref, double cap) {
        if (value <= 0 || ref <= 0 || cap <= 0) return 0;
        return Math.min(cap, 100.0 * ref / value);
    }

    // ---------------- 2.0 倍率法（不封顶） ----------------

    /** 2.0 越低越好（耗时）：倍率 = 基准 / 实测，无上限。 */
    public static double ratioLower(double value, double ref) {
        if (value <= 0 || ref <= 0) return 0;
        return ref / value;
    }

    /** 2.0 越高越好（帧率/吞吐）：倍率 = 实测 / 基准，无上限。 */
    public static double ratioHigher(double value, double ref) {
        if (value <= 0 || ref <= 0) return 0;
        return value / ref;
    }

    /** 2.0 帧场景倍率：0.4×平均帧倍率 + 0.6×低帧倍率（权重和自动归一化）。 */
    public static double ratioFrame(double avgFps, double lowFps, double refAvg, double refLow,
                                    double avgWeight, double lowWeight) {
        double wSum = avgWeight + lowWeight;
        if (wSum <= 0) {
            avgWeight = 0.4;
            lowWeight = 0.6;
        } else {
            avgWeight /= wSum;
            lowWeight /= wSum;
        }
        return avgWeight * ratioHigher(avgFps, refAvg) + lowWeight * ratioHigher(lowFps, refLow);
    }

    /** 几何平均：用于 2.0 总倍率（所有项目倍率相乘开 N 次方）。 */
    public static double geometricMean(Iterable<Double> values) {
        double product = 1.0;
        int count = 0;
        for (double v : values) {
            if (v > 0) {
                product *= v;
                count++;
            }
        }
        if (count == 0) return 0;
        return Math.pow(product, 1.0 / count);
    }

    /** 帧场景：avg 与 1%Low 加权（默认 0.4/0.6）。权重和会自动归一化，避免配置写错导致越界。 */
    public static double frameScene(double avgFps, double lowFps, double refAvg, double refLow,
                                    double avgWeight, double lowWeight, double cap) {
        double wSum = avgWeight + lowWeight;
        if (wSum <= 0) {
            avgWeight = 0.4;
            lowWeight = 0.6;
        } else {
            avgWeight /= wSum;
            lowWeight /= wSum;
        }
        return avgWeight * higherBetter(avgFps, refAvg, cap)
                + lowWeight * higherBetter(lowFps, refLow, cap);
    }

    /**
     * 加权总分：subScores 中的 key 需与 sceneWeights 的 key 对应。
     * 只统计有权重且已执行的场景，并重新归一化权重。
     */
    public static double weightedTotal(Map<String, Double> subScores, Map<String, Double> sceneWeights, double scale) {
        double weightSum = 0;
        double weighted = 0;
        for (Map.Entry<String, Double> e : sceneWeights.entrySet()) {
            Double sub = subScores.get(e.getKey());
            if (sub == null || e.getValue() == null || e.getValue() <= 0) continue;
            weightSum += e.getValue();
            weighted += e.getValue() * sub;
        }
        if (weightSum <= 0) return 0;
        return weighted / weightSum * scale;
    }

    /** 写规则快照到结果（日志中记录实际使用的常数）。 */
    public static void fillRules(io.mcbench.bench.BenchResult result, BenchConfig cfg, String mode) {
        result.rules.put("scoreScale", cfg.scoreScale);
        result.rules.put("cap", cfg.cap);
        result.rules.put("avgWeight", cfg.avgWeight);
        result.rules.put("lowWeight", cfg.lowWeight);
        if ("COMBAT".equals(mode)) {
            BenchConfig.Combat c = cfg.reference.combat;
            result.rules.put("ref.worldLoadSeconds", c.worldLoadSeconds);
            result.rules.put("ref.chunkGenSeconds", c.chunkGenSeconds);
            result.rules.put("ref.transparentAvgFps", c.transparentAvgFps);
            result.rules.put("ref.transparentLowFps", c.transparentLowFps);
            result.rules.put("ref.entityAvgFps", c.entityAvgFps);
            result.rules.put("ref.entityLowFps", c.entityLowFps);
            result.rules.put("ref.particleAvgFps", c.particleAvgFps);
            result.rules.put("ref.particleLowFps", c.particleLowFps);
            result.rules.put("ref.tntAvgFps", c.tntAvgFps);
            result.rules.put("ref.tntLowFps", c.tntLowFps);
            result.rules.put("weight.worldLoad", cfg.weights.combat.worldLoad);
            result.rules.put("weight.chunkGen", cfg.weights.combat.chunkGen);
            result.rules.put("weight.transparent", cfg.weights.combat.transparent);
            result.rules.put("weight.entity", cfg.weights.combat.entity);
            result.rules.put("weight.particle", cfg.weights.combat.particle);
            result.rules.put("weight.tnt", cfg.weights.combat.tnt);
        } else if ("QUICK".equals(mode)) {
            BenchConfig.Quick q = cfg.reference.quick;
            result.rules.put("ref.worldLoadSeconds", q.worldLoadSeconds);
            result.rules.put("ref.chunkGenSeconds", q.chunkGenSeconds);
            result.rules.put("ref.fallAvgFps", q.fallAvgFps);
            result.rules.put("ref.fallLowFps", q.fallLowFps);
            result.rules.put("weight.worldLoad", cfg.weights.quick.worldLoad);
            result.rules.put("weight.chunkGen", cfg.weights.quick.chunkGen);
            result.rules.put("weight.fall", cfg.weights.quick.fall);
        } else if ("FAIR".equals(mode)) {
            BenchConfig.Fair f = cfg.reference.fair;
            result.rules.put("ref.cpuSingle", f.cpuSingle);
            result.rules.put("ref.cpuMulti", f.cpuMulti);
            result.rules.put("ref.memoryBandwidth", f.memoryBandwidth);
            result.rules.put("ref.sha256Speed", f.sha256Speed);
            result.rules.put("ref.gpuFillRate", f.gpuFillRate);
            result.rules.put("weight.cpuSingle", cfg.weights.fair.cpuSingle);
            result.rules.put("weight.cpuMulti", cfg.weights.fair.cpuMulti);
            result.rules.put("weight.gpu", cfg.weights.fair.gpu);
        } else if ("V2_COMBAT".equals(mode) || "V2_QUICK".equals(mode)) {
            BenchConfig.Client c = cfg.v2.client;
            result.rules.put("v2.totalScale", cfg.v2.totalScale);
            result.rules.put("v2.ref.worldLoadSeconds", c.worldLoadSeconds);
            result.rules.put("v2.ref.chunkGenSeconds", c.chunkGenSeconds);
            result.rules.put("v2.ref.transparentAvgFps", c.transparentAvgFps);
            result.rules.put("v2.ref.transparentLowFps", c.transparentLowFps);
            result.rules.put("v2.ref.entityAvgFps", c.entityAvgFps);
            result.rules.put("v2.ref.entityLowFps", c.entityLowFps);
            result.rules.put("v2.ref.particleAvgFps", c.particleAvgFps);
            result.rules.put("v2.ref.particleLowFps", c.particleLowFps);
            result.rules.put("v2.ref.tntAvgFps", c.tntAvgFps);
            result.rules.put("v2.ref.tntLowFps", c.tntLowFps);
            result.rules.put("v2.idleSeconds", c.idleSeconds);
            result.rules.put("v2.entityCount", c.entityCount);
            result.rules.put("v2.quickEntityCount", c.quickEntityCount);
        } else if ("V2_FAIR".equals(mode)) {
            BenchConfig.Fair f = cfg.reference.fair;
            result.rules.put("v2.fair.ref.cpuSingle", f.cpuSingle);
            result.rules.put("v2.fair.ref.cpuMulti", f.cpuMulti);
            result.rules.put("v2.fair.ref.memoryBandwidth", f.memoryBandwidth);
            result.rules.put("v2.fair.ref.sha256Speed", f.sha256Speed);
            result.rules.put("v2.fair.ref.gpuFillRate", f.gpuFillRate);
        }
    }
}
