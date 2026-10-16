package io.mcbench.mixin;

import io.mcbench.bench.BenchmarkManager;
import io.mcbench.bench.FrameProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每帧钩子：驱动跑分状态机与帧采样（Minecraft.tick 每渲染帧调用一次），
 * 并更新全局帧率探针（FrameProbe）。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void mcbench$onTick(CallbackInfo ci) {
        FrameProbe.onFrame();
        BenchmarkManager.INSTANCE.tick();
    }
}
