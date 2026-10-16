package io.mcbench.neoforge;

import io.mcbench.McBench;
import io.mcbench.server.ServerBenchmarkManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(McBench.MOD_ID)
public class McBenchNeoForge {
    public McBenchNeoForge(IEventBus modBus) {
        if (FMLEnvironment.dist.isClient()) {
            McBench.init();
        } else {
            // 服务端：预热联测请求注册表；/mcbench 命令由 MinecraftServerMixin 注册
            ServerBenchmarkManager.INSTANCE.pendingRequests();
        }
    }
}
