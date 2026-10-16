package io.mcbench.mixin;

import io.mcbench.bench.BenchmarkManager;
import io.mcbench.bench.FrameProbe;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 渲染钩子：
 * 1) 帧采样/帧时间测量（1.20.5+ 的 Minecraft.tick 是 20TPS 游戏刻，不能测帧率）；
 * 2) 渲染耗时探针（诊断用）。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void mcbench$renderHead(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProbe.onRenderStart();
        BenchmarkManager.INSTANCE.onRenderFrame();
        // 本地服务端测试：屏蔽渲染部分，避免 GPU/客户端渲染干扰服务端性能读数
        if (BenchmarkManager.INSTANCE.isRenderSuppressed()) {
            FrameProbe.onRenderEnd();
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void mcbench$renderTail(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FrameProbe.onRenderEnd();
    }
}
