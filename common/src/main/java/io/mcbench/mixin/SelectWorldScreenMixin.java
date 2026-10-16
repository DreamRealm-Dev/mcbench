package io.mcbench.mixin;

import io.mcbench.McBench;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在单人游戏（世界选择）界面右上角添加“不服跑个分”按钮。
 */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void mcbench$addBenchmarkButton(CallbackInfo ci) {
        SelectWorldScreen self = (SelectWorldScreen) (Object) this;
        ((ScreenAccessor) self).mcbench$addRenderableWidget(Button.builder(
                Component.literal("不服跑个分"),
                b -> McBench.openBenchmarkMenu())
                .bounds(self.width - 112, 5, 107, 20)
                .build());
    }
}
