package io.mcbench.ui;

import io.mcbench.bench.BenchResult;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 跑分结果界面：总分 + 子分数（定位瓶颈）+ 备注 + GC 建议 + 日志路径。
 * 点“退出”返回主界面（标题界面）。
 */
public class ResultScreen extends Screen {
    private final BenchResult result;
    private final List<String> lines = new ArrayList<>();
    private int scrollOffset = 0;

    public ResultScreen(BenchResult result) {
        super(Component.literal("跑分结果"));
        this.result = result;
        buildLines();
    }

    private void buildLines() {
        lines.clear();
        lines.add("");
        if (result.isV2()) {
            lines.add("  总分: " + String.format("%.1f", result.totalScore) + "   (2.0 倍率分，不封顶)");
        } else {
            lines.add("  总分: " + String.format("%.1f", result.totalScore) + " / 1000" + "    (基准机=500)");
        }
        lines.add("  耗时: " + String.format("%.1f", result.elapsedSeconds) + " 秒");
        lines.add("");
        lines.add("  【子分数】(定位瓶颈)");
        result.subScores.forEach((k, v) -> lines.add("    " + k + ": " + String.format("%.1f", v)));
        lines.add("");
        lines.add("  【原始指标】");
        result.metrics.forEach((k, v) -> lines.add("    " + k + ": " + String.format("%.3f", v)));
        lines.add("");
        lines.add("  【强制GC】(不参与计分, 优化建议)");
        if (result.forcedGcByCollector.isEmpty()) {
            lines.add("    无");
        } else {
            result.forcedGcByCollector.forEach((k, v) -> lines.add("    " + k + ": " + v + " 次"));
        }
        lines.add("");
        lines.add("  【备注】");
        if (result.notes.isEmpty()) {
            lines.add("    无");
        } else {
            result.notes.forEach(n -> lines.add("    - " + n));
        }
        lines.add("");
        lines.add("  日志: " + (result.logFile.isEmpty() ? "(写入失败)" : result.logFile));
        lines.add("");
        lines.add("  ↑↓ 滚动 · 计分规则见 config/mcbench.json 与日志 rules 段");
    }

    @Override
    protected void init() {
        int w = 140;
        this.addRenderableWidget(Button.builder(Component.literal("打开日志目录"), b -> openLogDir())
                .bounds(this.width / 2 - w - 6, this.height - 30, w, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("退出"), b ->
                        io.mcbench.bench.BenchmarkManager.INSTANCE.exitToTitle())
                .bounds(this.width / 2 + 6, this.height - 30, w, 20).build());
    }

    private void openLogDir() {
        try {
            File dir = new File(result.logFile).getParentFile();
            if (dir != null && dir.exists()) {
                io.mcbench.bench.VersionBridge.openUri(dir);
            }
        } catch (Exception e) {
            // 移动端（MobileGlues/Android）可能不支持打开目录，忽略
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset = (int) Math.max(0, scrollOffset - verticalAmount * 12);
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, "不服跑个分 - 结果", this.width / 2, 8, 0xFFFFFFFF);

        int lineHeight = this.font.lineHeight + 2;
        int top = 24;
        int visible = (this.height - 70) / lineHeight;
        int maxScroll = Math.max(0, lines.size() - visible);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        int y = top;
        for (int i = scrollOffset; i < Math.min(lines.size(), scrollOffset + visible); i++) {
            String line = lines.get(i);
            int color = 0xFFAAAAAA;
            if (i == 1) color = 0xFFFFDD55;
            if (line.contains("【")) color = 0xFFFFFFFF;
            if (line.startsWith("    总分") || line.contains("总分")) color = 0xFFFFAA00;
            graphics.drawString(this.font, line, 16, y, color, false);
            y += lineHeight;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 1.20.5+ 原版 renderBackground 会对整屏执行高斯模糊（GameRenderer.processBlurEffect），
     * 本界面不需要该效果（视觉发虚），改为半透明色块。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
