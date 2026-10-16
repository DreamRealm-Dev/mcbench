package io.mcbench.bench;

import io.mcbench.McBench;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Chunky 集成（无编译期依赖，纯反射，加载器/版本通用）：
 *
 * 触发方式（两种，任一可用即可）：
 *   A. Chunky API：ChunkyProvider.get() → Chunky.getApi() → startTask(...)
 *   B. Chunky 指令：/chunky radius N + /chunky center 0 0 + /chunky start（服务器命令调度器）
 *
 * 完成检测（不依赖 ChunkyAPI 接口，更底层更可靠）：
 *   ChunkyProvider.get() → Chunky.getGenerationTasks() → Map 为空 = 没有进行中的任务
 *   （Chunky 1.3+ 的公开方法，1.4.23 与 1.5.3 均已验证存在）
 *
 * 若 Chunky 未安装，返回 false，调用方记录"跳过"并重新归一化权重。
 */
public final class ChunkyBridge {
    private static boolean resolved = false;
    private static boolean available = false;
    private static Object chunky;   // org.popcraft.chunky.Chunky
    private static Object api;      // org.popcraft.chunky.api.ChunkyAPI（可能为 null，仅用于 API 触发）

    private ChunkyBridge() {
    }

    /** 探测 Chunky 是否加载（ChunkyProvider.get() 非 null）。 */
    public static synchronized boolean isAvailable() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> providerClass = Class.forName("org.popcraft.chunky.ChunkyProvider");
                chunky = providerClass.getMethod("get").invoke(null);
                if (chunky != null) {
                    available = true;
                    McBench.LOGGER.info("[MCBench] 已连接 Chunky（任务表检测模式）");
                    try {
                        api = chunky.getClass().getMethod("getApi").invoke(chunky);
                    } catch (Throwable ignored) {
                        api = null;
                    }
                }
            } catch (Throwable t) {
                available = false;
                McBench.LOGGER.info("[MCBench] 未检测到 Chunky（区块生成环节将跳过）");
            }
        }
        return available;
    }

    /** 方式 A：Chunky API startTask（方形半径 radiusChunks 区块，中心 0,0）。
     *  注意1：Chunky API 的 world 参数是"维度 key"（如 minecraft:overworld），不是存档名！
     *  注意2：API 的 radiusX/radiusZ 单位是"格"（blocks），不是区块！
     *         半径 N 区块 = N×16 格（Chunky 内部按格计算后向上取整到区块）。 */
    public static boolean startSquareViaApi(net.minecraft.server.level.ServerLevel level, double radiusChunks) {
        if (!isAvailable() || api == null) return false;
        try {
            String worldKey = VersionBridge.dimensionId(level);
            double radiusBlocks = radiusChunks * 16.0;
            Method start = api.getClass().getMethod("startTask",
                    String.class, String.class, double.class, double.class, double.class, double.class, String.class);
            Object ok = start.invoke(api, worldKey, "square", 0.0, 0.0, radiusBlocks, radiusBlocks, "full");
            McBench.LOGGER.info("[MCBench] Chunky API startTask: 半径 {} 区块 ({} 格)", radiusChunks, radiusBlocks);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            McBench.LOGGER.warn("[MCBench] Chunky API startTask 失败: {}", t.toString());
            return false;
        }
    }

    /** 方式 B：Chunky 指令触发（必须在服务器线程执行）。
     *  顺序：center → radius → shape → start（center 可能重置 radius，先设中心再设半径）。 */
    public static boolean startSquareViaCommand(net.minecraft.server.MinecraftServer server, double radius) {
        if (!isAvailable()) return false;
        try {
            net.minecraft.commands.CommandSourceStack source = server.createCommandSourceStack();
            int r = (int) Math.round(radius);
            server.getCommands().performPrefixedCommand(source, "chunky center 0 0");
            server.getCommands().performPrefixedCommand(source, "chunky radius " + r);
            server.getCommands().performPrefixedCommand(source, "chunky shape square");
            server.getCommands().performPrefixedCommand(source, "chunky start");
            McBench.LOGGER.info("[MCBench] 已通过 Chunky 指令触发预生成（半径 {}）", r);
            return true;
        } catch (Throwable t) {
            McBench.LOGGER.warn("[MCBench] Chunky 指令触发失败: {}", t.toString());
            return false;
        }
    }

    /** 是否有进行中的 Chunky 生成任务（任务表非空 = 正在生成）。 */
    public static boolean hasRunningTasks() {
        if (!isAvailable() || chunky == null) return false;
        try {
            Object tasks = chunky.getClass().getMethod("getGenerationTasks").invoke(chunky);
            return tasks instanceof Map<?, ?> map && !map.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前任务已处理块数（无任务返回 -1）。 */
    public static long taskCount() {
        if (!isAvailable() || chunky == null) return -1;
        try {
            Object tasks = chunky.getClass().getMethod("getGenerationTasks").invoke(chunky);
            if (tasks instanceof Map<?, ?> map && !map.isEmpty()) {
                Object task = map.values().iterator().next();
                Object c = task.getClass().getMethod("getCount").invoke(task);
                return c instanceof Number n ? n.longValue() : -1;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 当前任务进度百分比 0~100（无任务返回 -1）。 */
    public static double taskProgressPercent() {
        if (!isAvailable() || chunky == null) return -1;
        try {
            Object tasks = chunky.getClass().getMethod("getGenerationTasks").invoke(chunky);
            if (tasks instanceof Map<?, ?> map && !map.isEmpty()) {
                Object task = map.values().iterator().next();
                long count = ((Number) task.getClass().getMethod("getCount").invoke(task)).longValue();
                Object it = task.getClass().getMethod("getChunkIterator").invoke(task);
                long total = ((Number) it.getClass().getMethod("total").invoke(it)).longValue();
                if (total > 0) return Math.min(100.0, count * 100.0 / total);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 清除 Chunky 的内存进度缓存（RegionCache，按 world key 存储）。
     *
     * 背景：RegionCache 在 Chunky 实例中存活整个 JVM 生命周期；
     * 若测试世界被删除后用同名重建（每次跑分都会），缓存仍认为“这些区块已生成”，
     * 新任务会瞬间“处理”完 40401 块（Total time 0:00:00，假分数）。
     * 每次开始任务前必须先清掉对应维度的缓存。
     */
    public static void clearRegionCache(String worldKey) {
        if (!isAvailable() || chunky == null) return;
        try {
            Object regionCache = chunky.getClass().getMethod("getRegionCache").invoke(chunky);
            if (regionCache != null) {
                regionCache.getClass().getMethod("clear", String.class).invoke(regionCache, worldKey);
                McBench.LOGGER.info("[MCBench] 已清除 Chunky 进度缓存: {}", worldKey);
            }
        } catch (Throwable t) {
            McBench.LOGGER.warn("[MCBench] 清除 Chunky 进度缓存失败: {}", t.toString());
        }
    }

    /** 取消全部生成任务。 */
    public static void cancelAll() {
        if (!isAvailable() || chunky == null) return;
        try {
            Object tasks = chunky.getClass().getMethod("getGenerationTasks").invoke(chunky);
            if (tasks instanceof Map<?, ?> map) {
                Method cancel = map.values().iterator().hasNext()
                        ? map.values().iterator().next().getClass().getMethod("cancel") : null;
                if (cancel != null) {
                    for (Object task : map.values()) {
                        try {
                            cancel.invoke(task);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            McBench.LOGGER.warn("[MCBench] Chunky 取消任务失败: {}", t.toString());
        }
    }
}
