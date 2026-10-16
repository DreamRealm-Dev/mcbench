package io.mcbench.ui;

import io.mcbench.bench.BenchMode;
import io.mcbench.bench.BenchTarget;
import io.mcbench.bench.BenchVersion;
import io.mcbench.bench.BenchmarkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 跑分模式选择界面：
 *  - 左下角可切换基准（1.0 基准 / 2.0 基准）
 *  - 2.0 下可选择跑分对象（客户端 / 本地服务端 / 远端服务端 / 客户端+远端协同）
 *  - 1.0 基准保留可用，但与 2.0 结果不可直接对比
 *
 * 布局：每个模式按钮下方单独显示介绍，避免介绍被按钮遮挡。
 */
public class ModeSelectScreen extends Screen {
    private static final int BUTTON_H = 20;
    /** 每个模式占用的行高：小窗口自动压缩 */
    private static final int ROW_H = 38;

    private final BenchVersion selectedVersion;
    private final BenchTarget selectedTarget;
    private String message = "";

    public ModeSelectScreen() {
        this(BenchVersion.V1, BenchTarget.CLIENT);
    }

    private ModeSelectScreen(BenchVersion version, BenchTarget target) {
        super(Component.literal("MCBench - 不服跑个分"));
        this.selectedVersion = version;
        this.selectedTarget = target;
    }

    private List<BenchMode> visibleModes() {
        return List.of(BenchMode.values()).stream()
                .filter(m -> m.version == selectedVersion)
                .toList();
    }

    @Override
    protected void init() {
        int buttonW = Math.min(300, this.width - 40);
        int cx = this.width / 2 - buttonW / 2;
        int rowH = this.height < 300 ? 34 : ROW_H;
        List<BenchMode> modes = visibleModes();
        int top = Math.max(28, Math.min(64, this.height - modes.size() * rowH - 90));

        // 基准切换：左下角（切换时重建界面）
        this.addRenderableWidget(Button.builder(
                Component.literal("基准: " + selectedVersion.label + "（点击切换）"),
                b -> {
                    BenchVersion next = selectedVersion == BenchVersion.V1 ? BenchVersion.V2 : BenchVersion.V1;
                    Minecraft.getInstance().setScreen(new ModeSelectScreen(next, selectedTarget));
                }).bounds(10, this.height - 58, 190, 20).build());

        // 2.0 跑分对象选择：基准切换按钮下方
        if (selectedVersion == BenchVersion.V2) {
            this.addRenderableWidget(Button.builder(
                    Component.literal("对象: " + selectedTarget.label + "（点击切换）"),
                    b -> {
                        BenchTarget next = switch (selectedTarget) {
                            case CLIENT -> BenchTarget.LOCAL_SERVER;
                            case LOCAL_SERVER -> BenchTarget.REMOTE_SERVER;
                            case REMOTE_SERVER -> BenchTarget.COORDINATED;
                            case COORDINATED -> BenchTarget.CLIENT;
                        };
                        Minecraft.getInstance().setScreen(new ModeSelectScreen(selectedVersion, next));
                    }).bounds(10, this.height - 34, 190, 20).build());
        }

        int i = 0;
        for (BenchMode m : modes) {
            int my = top + i * rowH;
            this.addRenderableWidget(Button.builder(Component.literal("▶ " + m.label), b -> startOrNotify(m))
                    .bounds(cx, my, buttonW, BUTTON_H).build());
            i++;
        }

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(cx, top + modes.size() * rowH + 4, buttonW, BUTTON_H).build());
    }

    private void startOrNotify(BenchMode m) {
        if (m == BenchMode.V2_MOD_CREATE) {
            if (selectedTarget != BenchTarget.CLIENT) {
                message = "2.0 模组专项（机械动力）当前仅支持客户端性能";
                return;
            }
            message = "";
            BenchmarkManager.INSTANCE.start(m, selectedVersion, selectedTarget);
            return;
        }
        if (selectedVersion == BenchVersion.V2 && selectedTarget == BenchTarget.REMOTE_SERVER) {
            if (m == BenchMode.V2_FAIR || m == BenchMode.V2_MOD_CREATE) {
                message = "远端服务端模式当前支持 2.0 原版综合 / 2.0 快测";
                return;
            }
            if (BenchmarkManager.INSTANCE.requestRemoteBenchmark(m)) {
                message = "已向服务器提交请求，请让管理员执行 /mcbench list 查看并 /mcbench accept <ID+token> 同意";
            } else {
                message = "请先加入目标服务器后再发起远端服务端跑分";
            }
            return;
        }
        if (selectedVersion == BenchVersion.V2 && selectedTarget == BenchTarget.COORDINATED) {
            if (m == BenchMode.V2_FAIR || m == BenchMode.V2_MOD_CREATE) {
                message = "协同模式当前支持 2.0 原版综合 / 2.0 快测";
                return;
            }
            if (Minecraft.getInstance().player == null || Minecraft.getInstance().getConnection() == null) {
                message = "协同模式需要先加入服务器，客户端只负责渲染采样";
            } else {
                message = "";
                BenchmarkManager.INSTANCE.start(m, selectedVersion, selectedTarget);
            }
            return;
        }
        if (selectedVersion == BenchVersion.V2 && selectedTarget == BenchTarget.LOCAL_SERVER
                && (m == BenchMode.V2_FAIR || m == BenchMode.V2_MOD_CREATE)) {
            message = "本地服务端模式当前支持 2.0 原版综合 / 2.0 快测";
            return;
        }
        message = "";
        BenchmarkManager.INSTANCE.start(m, selectedVersion, selectedTarget);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int titleY = Math.max(12, this.height / 2 - 100);
        graphics.drawCenteredString(this.font, "不服跑个分", this.width / 2, titleY, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                selectedVersion == BenchVersion.V1 ? "规则透明 · 跨设备可比 · 1.0 三种模式" : "2.0 新基准 · 倍率不封顶 · 不与 1.0 对比",
                this.width / 2, titleY + 20, 0xFFAAAAAA);

        List<BenchMode> modes = visibleModes();
        int rowH = this.height < 300 ? 34 : ROW_H;
        int top = Math.max(28, Math.min(64, this.height - modes.size() * rowH - 90));
        for (int i = 0; i < modes.size(); i++) {
            BenchMode m = modes.get(i);
            int descY = top + i * rowH + BUTTON_H + 3;
            // 介绍放在按钮下方，使用更亮的颜色，避免被按钮遮挡
            graphics.drawCenteredString(this.font, m.detail, this.width / 2, descY, 0xFFDDDDDD);
            if (!m.hint.isEmpty() && this.height >= 280) {
                graphics.drawCenteredString(this.font, m.hint, this.width / 2, descY + 10, 0xFF999999);
            }
        }

        if (!message.isEmpty()) {
            graphics.drawCenteredString(this.font, message, this.width / 2, this.height - 80, 0xFFFF6666);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 1.20.5+ 原版 renderBackground 会对整屏执行高斯模糊（GameRenderer.processBlurEffect），
     * 本模组界面不需要该效果（视觉发虚且消耗 GPU），改为半透明色块。
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
