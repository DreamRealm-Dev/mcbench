package io.mcbench.bench;

import com.mojang.blaze3d.platform.Window;
import io.mcbench.McBench;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;

/**
 * 渲染分辨率锁定（参考 Fabrishot 高清截图的做法）：
 *
 * 1) 直接修改 Window 的 framebufferWidth/framebufferHeight 字段（渲染分辨率），
 *    保持设备宽高比、总像素数 ≈ 1280×720 = 921600 —— 像素负载统一；
 * 2) 调用 Minecraft.resizeDisplay()（官方正规流程）：它会用新 framebuffer 尺寸
 *    重算 GUI 缩放（window.calculateScale）、重建主渲染目标、重排当前界面，
 *    与 Fabrishot 触发一次完整"伪窗口缩放"的思路一致；
 * 3) 不改变 GLFW 窗口本身 → 分辨率锁定的环境（MobileGlues 手机渲染器）也能用。
 *
 * 锁定/恢复时打印前后尺寸与自检结果。
 */
public final class ResolutionLock {
    public static final int TARGET_PIXELS = 1280 * 720;

    private static int origFramebufferWidth = -1;
    private static int origFramebufferHeight = -1;
    private static boolean locked = false;
    /** 锁定目标（apply 时按当时窗口宽高比固定，避免窗口拖动时目标漂移） */
    private static int targetW = -1;
    private static int targetH = -1;

    private ResolutionLock() {
    }

    /**
     * 计算目标分辨率：固定 1280×720（16:9），不再按设备宽高比缩放。
     * 手机/带鱼屏等非 16:9 屏幕也统一使用同一像素负载，保证跨设备分数可比。
     */
    public static int[] targetSize() {
        return new int[]{1280, 720};
    }

    public static void apply() {
        int[] size = targetSize();
        applySize(size[0], size[1], "渲染分辨率锁定");
    }

    /** 本地服务端测试低负载模式：缩小分辨率到 640×360，减少渲染开销但不取消渲染。 */
    public static void applyLowPower() {
        applySize(640, 360, "渲染分辨率低负载锁定");
    }

    private static void applySize(int w, int h, String label) {
        if (locked) return;
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        try {
            int fbW = framebufferWidth(window);
            int fbH = framebufferHeight(window);
            targetW = w;
            targetH = h;
            if (fbW == targetW && fbH == targetH) {
                locked = true; // 已一致也标记，restore 时恢复原值
                return;
            }
            origFramebufferWidth = fbW;
            origFramebufferHeight = fbH;
            setFramebufferSize(window, targetW, targetH);
            // 官方正规流程：重算 GUI 缩放 + 重建主渲染目标 + 重排界面
            mc.resizeDisplay();
            locked = true;
            McBench.LOGGER.info("[MCBench] {}: {}x{} -> {}x{} ({} 像素, GUI缩放={})",
                    label, fbW, fbH, targetW, targetH, targetW * targetH, window.getGuiScale());
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] {}失败（继续跑分）: {}", label, e.toString());
            locked = true; // 防止重复尝试
            origFramebufferWidth = origFramebufferHeight = -1;
            targetW = targetH = -1;
        }
    }

    public static void restore() {
        if (!locked) return;
        locked = false;
        targetW = targetH = -1;
        if (origFramebufferWidth <= 0 || origFramebufferHeight <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        try {
            setFramebufferSize(window, origFramebufferWidth, origFramebufferHeight);
            mc.resizeDisplay();
            boolean ok = framebufferWidth(window) == origFramebufferWidth
                    && framebufferHeight(window) == origFramebufferHeight;
            McBench.LOGGER.info("[MCBench] 渲染分辨率已恢复: {}x{} (GUI缩放={}, 自检{})",
                    origFramebufferWidth, origFramebufferHeight, window.getGuiScale(), ok ? "通过" : "异常");
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] 渲染分辨率恢复失败: {}", e.toString());
        }
        origFramebufferWidth = origFramebufferHeight = -1;
    }

    public static boolean isLocked() {
        return locked;
    }

    /**
     * 保持锁定：采样期间每帧调用。
     * 若窗口被用户/系统调整导致 framebuffer 尺寸变化（GLFW 回调会覆盖我们的字段），
     * 立即重新锁定为固定目标（apply 时确定的尺寸），保证整个采样阶段分辨率恒定。
     */
    public static void ensureLocked() {
        if (!locked || targetW <= 0 || targetH <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        try {
            if (framebufferWidth(window) != targetW || framebufferHeight(window) != targetH) {
                setFramebufferSize(window, targetW, targetH);
                mc.resizeDisplay();
                McBench.LOGGER.info("[MCBench] 分辨率锁定保持: 重新锁定为 {}x{}", targetW, targetH);
            }
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] 分辨率锁定保持失败: {}", e.toString());
        }
    }

    // ---------- 字段读写 ----------

    private static int framebufferWidth(Window window) throws Exception {
        return (Integer) field(window, "framebufferWidth").get(window);
    }

    private static int framebufferHeight(Window window) throws Exception {
        return (Integer) field(window, "framebufferHeight").get(window);
    }

    private static void setFramebufferSize(Window window, int w, int h) throws Exception {
        field(window, "framebufferWidth").setInt(window, w);
        field(window, "framebufferHeight").setInt(window, h);
    }

    private static Field field(Window window, String name) throws Exception {
        Field f = Window.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
