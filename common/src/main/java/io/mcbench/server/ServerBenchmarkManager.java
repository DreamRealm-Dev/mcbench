package io.mcbench.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端联测请求注册表（骨架）。
 *
 * 规则：
 *  - 同一服务端最多同时存在 3 个待处理请求
 *  - 请求默认 2 分钟超时
 *  - 管理员通过 /mcbench list / accept / deny 处理
 *  - token 为一次性随机值，避免请求被顶替
 */
public final class ServerBenchmarkManager {
    public static final ServerBenchmarkManager INSTANCE = new ServerBenchmarkManager();

    public static final int MAX_PENDING = 3;
    public static final long TIMEOUT_MILLIS = 2 * 60 * 1000L;

    private final List<RemoteTestRequest> pending = new ArrayList<>();

    private ServerBenchmarkManager() {
    }

    /** 创建请求（服务端生成 token）；超过上限或为空参数时返回 null。 */
    public synchronized String createRequest(String clientId, String mode) {
        return createRequest(clientId, "", mode, "", "");
    }

    /** 创建请求；token 可由客户端提供，为空时服务端自动生成。超过上限或为空参数时返回 null。 */
    public synchronized String createRequest(String clientId, String token, String mode) {
        return createRequest(clientId, token, mode, "", "");
    }

    /** 创建请求并记录发起玩家；超过上限或为空参数时返回 null。 */
    public synchronized String createRequest(String clientId, String token, String mode,
                                             String requesterUuid, String requesterName) {
        purgeExpired();
        if (clientId == null || clientId.isBlank()) return null;
        if (pending.size() >= MAX_PENDING) return null;
        String finalToken = (token == null || token.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                : token.trim();
        RemoteTestRequest request = new RemoteTestRequest(
                clientId.trim(), finalToken, mode == null ? "" : mode, System.currentTimeMillis(),
                requesterUuid == null ? "" : requesterUuid,
                requesterName == null ? "" : requesterName);
        pending.add(request);
        return request.idAndToken();
    }

    /** 管理员同意请求；成功返回 true。 */
    public synchronized boolean acceptRequest(String idAndToken) {
        return acceptAndGet(idAndToken) != null;
    }

    /** 管理员同意请求并返回被同意的请求；未找到返回 null。 */
    public synchronized RemoteTestRequest acceptAndGet(String idAndToken) {
        purgeExpired();
        RemoteTestRequest found = find(idAndToken);
        if (found == null) return null;
        pending.remove(found);
        return found;
    }

    /** 管理员拒绝请求；成功返回 true。 */
    public synchronized boolean denyRequest(String idAndToken) {
        purgeExpired();
        RemoteTestRequest found = find(idAndToken);
        if (found == null) return false;
        pending.remove(found);
        return true;
    }

    /** 当前待处理请求列表（调试/命令用）。 */
    public synchronized List<RemoteTestRequest> pendingRequests() {
        purgeExpired();
        return new ArrayList<>(pending);
    }

    /** 列出待处理请求。 */
    public synchronized String listPendingRequests() {
        List<RemoteTestRequest> list = pendingRequests();
        if (list.isEmpty()) return "当前没有待处理的 MCBench 远端跑分请求";
        StringBuilder sb = new StringBuilder("MCBench 待处理请求 (").append(list.size()).append("/")
                .append(MAX_PENDING).append("):");
        for (RemoteTestRequest r : list) {
            sb.append("\n  ").append(r.idAndToken())
                    .append("  mode=").append(r.mode().isEmpty() ? "?" : r.mode())
                    .append("  age=").append((System.currentTimeMillis() - r.timestampMillis()) / 1000).append("s");
        }
        return sb.toString();
    }

    private RemoteTestRequest find(String idAndToken) {
        if (idAndToken == null) return null;
        for (RemoteTestRequest r : pending) {
            if (r.idAndToken().equalsIgnoreCase(idAndToken.trim())) return r;
        }
        return null;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.removeIf(r -> now - r.timestampMillis() > TIMEOUT_MILLIS);
    }
}
