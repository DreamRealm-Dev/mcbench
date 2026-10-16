package io.mcbench.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端专用日志，避免加载客户端耦合的 io.mcbench.McBench。
 */
public final class ServerMcBenchLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("MCBench");

    private ServerMcBenchLog() {
    }
}
