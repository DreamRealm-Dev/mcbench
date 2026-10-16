package io.mcbench.mixin;

import io.mcbench.server.McBenchDimension;
import io.mcbench.server.ServerBenchmarkCommands;
import io.mcbench.server.ServerRemoteBenchmarkRunner;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 在服务器首个 tick 注册 /mcbench 命令。
 *
 * 不依赖 Fabric API / NeoForge 事件总线，Fabric 与 NeoForge 通用。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Unique
    private boolean mcbench$commandsRegistered = false;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void mcbench$registerCommands(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!mcbench$commandsRegistered) {
            mcbench$commandsRegistered = true;
            ServerBenchmarkCommands.register((MinecraftServer) (Object) this);
            // 启动时预创建独立超平坦测试维度（平时不加入正常 tick）
            McBenchDimension.createAtStartup((MinecraftServer) (Object) this);
        }
        // 远端服务端跑分状态机（每 tick 推进）
        ServerRemoteBenchmarkRunner.INSTANCE.tick((MinecraftServer) (Object) this);
    }
}
