package io.mcbench.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.mcbench.McBench;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 跑分日志：写入 <游戏目录>/benchmark-results/mcbench-<时间戳>.json 与 .txt。
 * JSON 为机器可读完整数据；TXT 为中文人读报告。
 */
public final class BenchLogger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BenchLogger() {
    }

    public static Path write(BenchResult result) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("benchmark-results");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            McBench.LOGGER.warn("[MCBench] 无法创建日志目录: {}", e.toString());
            return null;
        }
        String stamp = LocalDateTime.now().format(TS);
        Path json = dir.resolve("mcbench-" + stamp + ".json");
        Path txt = dir.resolve("mcbench-" + stamp + ".txt");
        try {
            result.logFile = json.toString();
            Files.writeString(json, GSON.toJson(result), StandardCharsets.UTF_8);
            Files.writeString(txt, toText(result), StandardCharsets.UTF_8);
            return json;
        } catch (IOException e) {
            McBench.LOGGER.warn("[MCBench] 写日志失败: {}", e.toString());
            return null;
        }
    }

    public static String toText(BenchResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("=============================================\n");
        sb.append(" MCBench 不服跑个分 - 跑分报告\n");
        sb.append("=============================================\n");
        sb.append("模式        : ").append(r.modeLabel).append('\n');
        sb.append("基准/对象   : ").append(r.benchmarkVersion.isEmpty() ? "1.0 基准" : r.benchmarkVersion)
                .append(" / ").append(r.benchmarkTarget.isEmpty() ? "客户端" : r.benchmarkTarget).append('\n');
        sb.append("Minecraft   : ").append(r.minecraftVersion).append(" / ").append(r.loader).append('\n');
        sb.append("Java        : ").append(r.javaVersion).append('\n');
        sb.append("系统        : ").append(r.os).append('\n');
        sb.append("时间        : ").append(r.timestamp).append('\n');
        sb.append("耗时        : ").append(String.format("%.1f", r.elapsedSeconds)).append(" 秒\n");
        sb.append("-------------------------------------------------\n");
        if (r.isV2()) {
            sb.append(String.format("总分        : %.1f（2.0 倍率分，不封顶）\n", r.totalScore));
        } else {
            sb.append(String.format("总分        : %.1f / 1000（基准机=500）\n", r.totalScore));
        }
        sb.append("-------------------------------------------------\n");
        sb.append("【子分数】（定位瓶颈用）\n");
        r.subScores.forEach((k, v) -> sb.append(String.format("  %-28s %8.1f\n", k, v)));
        sb.append("-------------------------------------------------\n");
        sb.append("【原始指标】\n");
        r.metrics.forEach((k, v) -> sb.append(String.format("  %-28s %12.3f\n", k, v)));
        sb.append("-------------------------------------------------\n");
        sb.append("【强制 GC】（不参与跑分，优化建议指标）\n");
        if (r.forcedGcByCollector.isEmpty()) {
            sb.append("  无（跑分期间没有检测到 System.gc() 调用）\n");
        } else {
            r.forcedGcByCollector.forEach((k, v) -> sb.append(String.format("  %-28s %d 次\n", k, v)));
        }
        sb.append("-------------------------------------------------\n");
        sb.append("【备注】\n");
        if (r.notes.isEmpty()) sb.append("  无\n");
        else r.notes.forEach(n -> sb.append("  - ").append(n).append('\n'));
        sb.append("-------------------------------------------------\n");
        sb.append("【计分规则】（规则透明：常数与权重可在 config/mcbench.json 修改）\n");
        r.rules.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
        sb.append("=============================================\n");
        return sb.toString();
    }
}
