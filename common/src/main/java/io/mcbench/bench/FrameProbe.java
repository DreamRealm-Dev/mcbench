package io.mcbench.bench;

/**
 * 全局帧率探针（由 MinecraftTickMixin / GameRendererMixin 每帧更新，供诊断日志使用）。
 * 注意：不能放在 mixin 包（io.mcbench.mixin）内——mixin 包中的非 mixin 类
 * 不允许被 mixin 类直接引用。
 */
public final class FrameProbe {
    private static long lastTickNanos = 0;
    private static volatile double tickFps = 0;

    private static long renderStartNanos = 0;
    private static long lastRenderNanos = 0;
    private static volatile double renderFps = 0;
    private static volatile double renderMs = 0;

    private FrameProbe() {
    }

    /** 每帧调用（Minecraft.tick mixin）。 */
    public static void onFrame() {
        long now = System.nanoTime();
        if (lastTickNanos != 0) {
            tickFps = 1_000_000_000.0 / (now - lastTickNanos);
        }
        lastTickNanos = now;
    }

    /** GameRenderer.render 开始。 */
    public static void onRenderStart() {
        renderStartNanos = System.nanoTime();
    }

    /** GameRenderer.render 结束。 */
    public static void onRenderEnd() {
        long now = System.nanoTime();
        renderMs = (now - renderStartNanos) / 1_000_000.0;
        if (lastRenderNanos != 0) {
            renderFps = 1_000_000_000.0 / (now - lastRenderNanos);
        }
        lastRenderNanos = now;
    }

    public static double tickFps() {
        return tickFps;
    }

    public static double renderFps() {
        return renderFps;
    }

    public static double renderMs() {
        return renderMs;
    }

    /** 诊断：当前 window 帧率限制字段。 */
    public static int windowFramerateLimit() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return VersionBridge.framerateLimit(mc);
        } catch (Throwable t) {
            return -1;
        }
    }
}
