package io.mcbench.ui;

import io.mcbench.bench.BenchmarkManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 跑分进度界面：显示当前阶段、已用时间、实时帧率。
 * 整个跑分期间用户不应操作。
 */
public class ProgressScreen extends Screen {
    private long shownAt = 0;

    public ProgressScreen() {
        super(Component.literal("跑分进行中"));
    }

    @Override
    public void tick() {
        if (shownAt == 0) shownAt = System.currentTimeMillis();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        long elapsed = shownAt == 0 ? 0 : (System.currentTimeMillis() - shownAt) / 1000;
        graphics.drawCenteredString(this.font, "不服跑个分 - 测试进行中", this.width / 2, this.height / 2 - 60, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, BenchmarkManager.INSTANCE.phaseLabel(), this.width / 2, this.height / 2 - 30, 0xFFFFCC66);
        if (BenchmarkManager.INSTANCE.isRunning()) {
            graphics.drawCenteredString(this.font, "已用 " + elapsed + " 秒", this.width / 2, this.height / 2 - 10, 0xFFAAAAAA);
            if (BenchmarkManager.INSTANCE.lastFrameMs() > 0) {
                double fps = 1000.0 / Math.max(1, BenchmarkManager.INSTANCE.lastFrameMs());
                graphics.drawCenteredString(this.font, String.format("实时帧率: %.0f FPS", fps), this.width / 2, this.height / 2 + 10, 0xFFAAAAAA);
            }
            double chunky = BenchmarkManager.INSTANCE.chunkyProgressPercent();
            if (chunky >= 0) {
                graphics.drawCenteredString(this.font, String.format("Chunky 区块预生成: %.1f%%", chunky),
                        this.width / 2, this.height / 2 + 30, 0xFF88FF88);
            }
        }
        graphics.drawCenteredString(this.font, "请勿操作游戏（会自动恢复所有设置）", this.width / 2, this.height / 2 + 55, 0xFF666666);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 跑分期间禁止 Esc 关闭（防止误操作中断测试）。
     */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent keyEvent) {
        if (keyEvent.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            return true; // 吞掉 Esc
        }
        return super.keyPressed(keyEvent);
    }

    /**
     * 1.20.5+ 原版 renderBackground 会对整屏执行高斯模糊（GameRenderer.processBlurEffect），
     * 跑分期间禁用：视觉发虚，且模糊本身会消耗 GPU、污染帧率采样数据。
     * 改为半透明色块（仍能看到世界画面）。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x80101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
