package io.mcbench.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * /mcbench 服务端联测命令。
 *
 * 用法（需要 OP 权限）：
 *  - /mcbench list
 *  - /mcbench accept &lt;clientId+token&gt;
 *  - /mcbench deny &lt;clientId+token&gt;
 */
public final class ServerBenchmarkCommands {

    private ServerBenchmarkCommands() {
    }

    /** 注册命令（应在服务器线程执行，通常由 MinecraftServerMixin 在首个 tick 调用）。 */
    public static void register(MinecraftServer server) {
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        dispatcher.register(Commands.literal("mcbench")
                .then(Commands.literal("list")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("accept")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> accept(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("deny")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> deny(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("request")
                        .then(Commands.argument("clientId", StringArgumentType.word())
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .then(Commands.argument("mode", StringArgumentType.greedyString())
                                                .executes(ctx -> request(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "clientId"),
                                                        StringArgumentType.getString(ctx, "token"),
                                                        StringArgumentType.getString(ctx, "mode"))))))));
        ServerMcBenchLog.LOGGER.info("[MCBench] /mcbench 服务端联测命令已注册");
    }

    private static int request(CommandSourceStack source, String clientId, String token, String mode) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        String requesterUuid = player == null ? "" : player.getStringUUID();
        String requesterName = player == null ? "" : player.getGameProfile().getName();
        String idAndToken = ServerBenchmarkManager.INSTANCE.createRequest(
                clientId, token, mode, requesterUuid, requesterName);
        if (idAndToken == null) {
            source.sendFailure(Component.literal("请求创建失败：参数为空，或待处理请求已达上限（3）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("MCBench 远端跑分请求已提交: " + idAndToken
                + "（请管理员执行 /mcbench accept " + idAndToken + "）"), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(ServerBenchmarkManager.INSTANCE.listPendingRequests()), false);
        return 1;
    }

    private static int accept(CommandSourceStack source, String idAndToken) {
        RemoteTestRequest request = ServerBenchmarkManager.INSTANCE.acceptAndGet(idAndToken);
        if (request != null) {
            boolean started = ServerRemoteBenchmarkRunner.INSTANCE.start(source.getServer(), request);
            if (started) {
                source.sendSuccess(() -> Component.literal("已同意并开始 MCBench 远端服务端跑分: " + idAndToken), false);
            } else {
                source.sendSuccess(() -> Component.literal("已同意请求，但已有跑分正在运行，稍后自动开始: " + idAndToken), false);
            }
            return 1;
        }
        source.sendFailure(Component.literal("未找到该请求，或请求已超时/已处理: " + idAndToken));
        return 0;
    }

    private static int deny(CommandSourceStack source, String idAndToken) {
        if (ServerBenchmarkManager.INSTANCE.denyRequest(idAndToken)) {
            source.sendSuccess(() -> Component.literal("已拒绝 MCBench 远端跑分请求: " + idAndToken), false);
            return 1;
        }
        source.sendFailure(Component.literal("未找到该请求，或请求已超时/已处理: " + idAndToken));
        return 0;
    }
}
