package io.mcbench.fabric;

import io.mcbench.server.ServerBenchmarkManager;
import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * Fabric 服务端入口：仅初始化服务端联测请求注册表。
 * /mcbench 命令由 MinecraftServerMixin 在服务端首个 tick 注册，无需 Fabric API。
 */
public class McBenchFabricServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        // 触发类加载并预热请求注册表（命令注册由 Mixin 完成）
        ServerBenchmarkManager.INSTANCE.pendingRequests();
    }
}
