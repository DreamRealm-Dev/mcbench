package io.mcbench.bench;

import io.mcbench.server.ServerMcBenchLog;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 纯客户端假人桥接：优先使用同 JVM MCProtocolLib，失败时回退到外部 Mineflayer Bot（Node.js）。
 *
 * 脚本位置：
 *  1. <游戏目录>/config/mcbench-bot/bot.js
 *  2. 当前工作目录/bot/bot.js（开发环境）
 */
public final class FakeClientBridge {
    public static final FakeClientBridge INSTANCE = new FakeClientBridge();

    private final List<Process> processes = new CopyOnWriteArrayList<>();
    private boolean nodeChecked = false;
    private boolean nodeAvailable = false;

    private FakeClientBridge() {
    }

    public boolean isAvailable() {
        return findScript() != null && isNodeAvailable();
    }

    private synchronized boolean isNodeAvailable() {
        if (nodeChecked) return nodeAvailable;
        nodeChecked = true;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            nodeAvailable = p.waitFor() == 0 && new String(out, java.nio.charset.StandardCharsets.UTF_8).contains("v");
        } catch (Exception e) {
            nodeAvailable = false;
        }
        return nodeAvailable;
    }

    private Path findScript() {
        try {
            Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
            Path config = gameDir.resolve("config/mcbench-bot/bot.js");
            if (Files.exists(config)) return config;
            Path cwd = Path.of("").toAbsolutePath().resolve("bot/bot.js");
            if (Files.exists(cwd)) return cwd;
            // 自动解压随包分发的 mcbench-bot.zip（放在游戏目录或当前工作目录）
            extractBotZipIfPresent(gameDir);
            if (Files.exists(config)) return config;
        } catch (Throwable t) {
            // 服务端/无客户端环境可能取不到 gameDirectory
        }
        return null;
    }

    private void extractBotZipIfPresent(Path gameDir) {
        try {
            Path target = gameDir.resolve("config/mcbench-bot");
            if (Files.exists(target.resolve("bot.js"))) return;
            Path[] candidates = {
                    gameDir.resolve("mcbench-bot.zip"),
                    Path.of("mcbench-bot.zip").toAbsolutePath(),
                    Path.of("dist/mcbench-bot.zip").toAbsolutePath()
            };
            for (Path zip : candidates) {
                if (zip == null || !Files.exists(zip)) continue;
                Files.createDirectories(target);
                try (InputStream fis = Files.newInputStream(zip);
                     ZipInputStream zis = new ZipInputStream(fis)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        Path out = target.resolve(entry.getName()).normalize();
                        if (!out.startsWith(target)) continue;
                        if (entry.isDirectory()) {
                            Files.createDirectories(out);
                        } else {
                            Files.createDirectories(out.getParent());
                            Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                ServerMcBenchLog.LOGGER.info("[MCBench] 已从 {} 自动解压 Mineflayer Bot 到 {}", zip, target);
                return;
            }
        } catch (Exception e) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 自动解压 mcbench-bot.zip 失败: {}", e.toString());
        }
    }

    /** 启动 count 个假人，持续 durationSeconds 秒后自动退出。优先使用同 JVM MCProtocolLib，失败再回退 Node。 */
    public synchronized boolean startFakeClients(String host, int port, int count, String prefix,
                                                 int durationSeconds, String action) {
        stopAll();
        // 同 JVM 内置假人：MCProtocolLib（无需 Node.js）
        if (JavaFakeClientBridge.INSTANCE.startFakeClients(host, port, count, prefix, durationSeconds, action)) {
            return true;
        }
        Path script = findScript();
        if (script == null) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 未找到 bot/bot.js，请将 Mineflayer Bot 放到 config/mcbench-bot/bot.js");
            return false;
        }
        if (!isNodeAvailable()) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 未检测到 Node.js，无法启动 Mineflayer 假人");
            return false;
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("node");
            cmd.add(script.toString());
            cmd.add("--host");
            cmd.add(host);
            cmd.add("--port");
            cmd.add(String.valueOf(port));
            cmd.add("--count");
            cmd.add(String.valueOf(count));
            cmd.add("--prefix");
            cmd.add(prefix);
            cmd.add("--duration");
            cmd.add(String.valueOf(durationSeconds));
            cmd.add("--action");
            cmd.add(action);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            processes.add(p);
            ServerMcBenchLog.LOGGER.info("[MCBench] 已启动 Mineflayer 假人进程: {} 个 -> {}:{} action={}", count, host, port, action);
            return true;
        } catch (Exception e) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 启动 Mineflayer 假人失败: {}", e.toString());
            return false;
        }
    }

    /** 停止所有假人进程。 */
    public synchronized void stopAll() {
        JavaFakeClientBridge.INSTANCE.stopAll();
        for (Process p : processes) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
        processes.clear();
    }
}
