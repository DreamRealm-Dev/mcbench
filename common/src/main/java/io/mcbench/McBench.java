package io.mcbench;

import io.mcbench.bench.BenchConfig;
import io.mcbench.bench.BenchMode;
import io.mcbench.bench.BenchmarkManager;
import io.mcbench.ui.ModeSelectScreen;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCBench 核心入口：初始化、打开跑分菜单。
 * 由各加载器的入口类调用（fabric: ClientModInitializer / neoforge: @Mod）。
 */
public final class McBench {
    public static final String MOD_ID = "mcbench";
    public static final String MOD_NAME = "MCBench - 不服跑个分";
    public static final String WORLD_NAME = "test_world_1";
    /** 固定种子：保证所有设备生成完全相同的世界 */
    public static final long FIXED_SEED = 20260815L;
    /** 渲染距离 / 模拟距离：统一参数 */
    public static final int RENDER_DISTANCE = 12;
    public static final int SIMULATION_DISTANCE = 8;

    public static final Logger LOGGER = LoggerFactory.getLogger("MCBench");

    private static boolean initialized = false;

    private McBench() {
    }

    /** 加载器入口调用（仅客户端）。 */
    public static void init() {
        if (initialized) return;
        initialized = true;
        BenchConfig.load();
        LOGGER.info("[MCBench] 不服跑个分已初始化");
        // 冒烟测试钩子：-Dmcbench.smoketest=<秒> 时自动退出游戏（开发/CI 用）
        String smoke = System.getProperty("mcbench.smoketest");
        if (smoke != null) {
            try {
                int seconds = Integer.parseInt(smoke);
                Thread t = new Thread(() -> {
                    try {
                        for (int i = 0; i < seconds / 5; i++) {
                            Thread.sleep(5000);
                            LOGGER.info("[MCBench] 探针: tick帧率={} FPS 渲染帧率={} FPS 渲染耗时={}ms window帧率字段={}",
                                    String.format("%.1f", io.mcbench.bench.FrameProbe.tickFps()),
                                    String.format("%.1f", io.mcbench.bench.FrameProbe.renderFps()),
                                    String.format("%.1f", io.mcbench.bench.FrameProbe.renderMs()),
                                    io.mcbench.bench.FrameProbe.windowFramerateLimit());
                        }
                        Minecraft.getInstance().stop();
                    } catch (Exception ignored) {
                    }
                }, "mcbench-smoketest");
                t.setDaemon(true);
                t.start();
                LOGGER.info("[MCBench] 冒烟测试模式：{} 秒后自动退出", seconds);
            } catch (NumberFormatException ignored) {
            }
        }
        // 自动跑分钩子：-Dmcbench.autostart=<COMBAT|FAIR|QUICK> 时自动开始对应模式（开发/CI 用）；
        // -Dmcbench.autostart.repeat=N 时连续跑 N 轮，每轮结束后模拟点击结果页“退出”再开下一轮
        String auto = System.getProperty("mcbench.autostart");
        if (auto != null) {
            try {
                BenchMode m = BenchMode.valueOf(auto);
                int repeat = Math.max(1, Integer.parseInt(System.getProperty("mcbench.autostart.repeat", "1")));
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(3000); // 等游戏进入标题界面
                        Minecraft mc = Minecraft.getInstance();
                        for (int round = 0; round < repeat; round++) {
                            final int r = round;
                            mc.execute(() -> {
                                if (r > 0) {
                                    // 模拟用户点击结果页“退出”返回主界面
                                    io.mcbench.bench.BenchmarkManager.INSTANCE.exitToTitle();
                                }
                                // 模拟用户路径：先打开单人游戏界面（触发 SelectWorldScreenMixin 按钮注入），
                                // 再开始跑分 —— 完整复现真实使用流程
                                mc.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(null));
                                io.mcbench.bench.BenchmarkManager.INSTANCE.start(m);
                            });
                            // 等本轮真正开始（mc.execute 是异步排队的，需等状态机进入运行态）
                            long startDeadline = System.currentTimeMillis() + 60_000;
                            while (!io.mcbench.bench.BenchmarkManager.INSTANCE.isRunning()
                                    && System.currentTimeMillis() < startDeadline) {
                                Thread.sleep(100);
                            }
                            // 等本轮结束（FINISHED/ABORTED）再开始下一轮
                            long deadline = System.currentTimeMillis() + 40 * 60_000L;
                            while (io.mcbench.bench.BenchmarkManager.INSTANCE.isRunning()
                                    && System.currentTimeMillis() < deadline) {
                                Thread.sleep(500);
                            }
                            LOGGER.info("[MCBench] 自动跑分第 {} 轮结束（共 {} 轮）", r + 1, repeat);
                        }
                    } catch (Exception ignored) {
                    }
                }, "mcbench-autostart");
                t.setDaemon(true);
                t.start();
                LOGGER.info("[MCBench] 自动跑分模式：{} 秒后开始 {}（共 {} 轮）", 3, m.label, repeat);
            } catch (IllegalArgumentException ignored) {
                LOGGER.warn("[MCBench] 未知的自动跑分模式: {}", auto);
            }
        }
    }

    /** 打开跑分模式选择界面（单人游戏界面按钮回调）。 */
    public static void openBenchmarkMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.setScreen(new ModeSelectScreen());
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
