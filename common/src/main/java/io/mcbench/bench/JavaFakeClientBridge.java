package io.mcbench.bench;

import io.mcbench.server.ServerMcBenchLog;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同 JVM 内置假人：基于 MCProtocolLib 的纯客户端协议 Bot。
 *
 * 不需要 Node.js / Rust / 外部进程，随 mod 一起分发。
 * 每个假人是一个独立 TcpClientSession，服务端看到的是普通玩家。
 */
public final class JavaFakeClientBridge {
    public static final JavaFakeClientBridge INSTANCE = new JavaFakeClientBridge();

    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executor;

    private JavaFakeClientBridge() {
    }

    public boolean isAvailable() {
        return true;
    }

    /** 启动 count 个同 JVM 假人，持续 durationSeconds 秒后自动断开。 */
    public synchronized boolean startFakeClients(String host, int port, int count, String prefix,
                                                 int durationSeconds, String action) {
        stopAll();
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newScheduledThreadPool(Math.max(2, Math.min(count, 8)));
        }
        try {
            for (int i = 0; i < count; i++) {
                String username = prefix + i;
                MinecraftProtocol protocol = new MinecraftProtocol(username);
                TcpClientSession client = new TcpClientSession(host, port, protocol, null);
                client.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
                client.addListener(new SessionAdapter() {
                    @Override
                    public void packetReceived(Session session, org.geysermc.mcprotocollib.network.packet.Packet packet) {
                        if (packet instanceof ClientboundLoginPacket) {
                            ServerMcBenchLog.LOGGER.info("[MCBench-JavaBot] {} 已进入服务器", username);
                            scheduleWalk(session, username);
                        }
                    }

                    @Override
                    public void disconnected(DisconnectedEvent event) {
                        ServerMcBenchLog.LOGGER.info("[MCBench-JavaBot] {} 断开: {}", username, event.getReason());
                    }
                });
                sessions.add(client);
                client.connect();
            }
            ScheduledFuture<?> stopTask = executor.schedule(this::stopAll, durationSeconds, TimeUnit.SECONDS);
            tasks.add(stopTask);
            ServerMcBenchLog.LOGGER.info("[MCBench] 已启动 {} 个同 JVM MCProtocolLib 假人 -> {}:{}", count, host, port);
            return true;
        } catch (Exception e) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 启动 MCProtocolLib 假人失败: {}", e.toString());
            stopAll();
            return false;
        }
    }

    private void scheduleWalk(Session session, String username) {
        AtomicInteger tick = new AtomicInteger();
        ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
            try {
                int t = tick.getAndIncrement();
                double x = 0.5 + t * 0.1;
                double y = 65;
                double z = 0.5;
                session.send(new ServerboundMovePlayerPosPacket(true, x, y, z));
            } catch (Exception e) {
                ServerMcBenchLog.LOGGER.warn("[MCBench-JavaBot] {} 移动失败: {}", username, e.toString());
            }
        }, 1, 50, TimeUnit.MILLISECONDS);
        tasks.add(task);
    }

    /** 停止所有假人并释放线程池。 */
    public synchronized void stopAll() {
        for (ScheduledFuture<?> task : tasks) {
            task.cancel(false);
        }
        tasks.clear();
        for (Session session : sessions) {
            try {
                session.disconnect("MCBench test finished");
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
