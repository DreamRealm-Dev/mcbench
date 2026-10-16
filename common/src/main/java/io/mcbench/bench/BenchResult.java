package io.mcbench.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跑分结果模型：原始指标 + 子分数 + 总分 + 备注。
 */
public class BenchResult {
    /** 模式标识 */
    public String mode = "";
    public String modeLabel = "";
    /** 基准版本与跑分对象（2.0 起使用） */
    public String benchmarkVersion = "";
    public String benchmarkTarget = "";
    public String minecraftVersion = "";
    public String loader = "";
    public String javaVersion = "";
    public String os = "";
    public long timestamp = System.currentTimeMillis();

    /** 原始指标（如 worldLoadSeconds / transparentAvgFps ...） */
    public final Map<String, Double> metrics = new LinkedHashMap<>();
    /** 子分数（每场景与每指标，用于瓶颈定位） */
    public final Map<String, Double> subScores = new LinkedHashMap<>();
    /** 总分（1.0 为 0~1000；2.0 为倍率分，不封顶） */
    public double totalScore = 0;
    /** 本次跑分总耗时（秒，含全流程） */
    public double elapsedSeconds = 0;
    /** 使用的权重与基准常数快照（规则透明） */
    public final Map<String, Object> rules = new LinkedHashMap<>();
    /** 环境/设置变更/跳过的场景等说明 */
    public final List<String> notes = new ArrayList<>();
    /** 强制 GC 统计（不参与跑分，仅建议） */
    public int forcedGcTotal = 0;
    public final Map<String, Integer> forcedGcByCollector = new LinkedHashMap<>();
    /** 日志文件路径 */
    public String logFile = "";

    public void metric(String key, double value) {
        metrics.put(key, value);
    }

    public void subScore(String key, double value) {
        subScores.put(key, value);
    }

    public void note(String text) {
        notes.add(text);
    }

    public boolean isV2() {
        return benchmarkVersion != null && benchmarkVersion.contains("2.0");
    }
}
