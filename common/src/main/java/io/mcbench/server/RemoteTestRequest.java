package io.mcbench.server;

/**
 * 远端服务端跑分请求（骨架）。
 *
 * 客户端发起测远端服务端时，会携带：
 *  - clientId：客户端标识（可为短 ID）
 *  - token：一次性随机令牌，管理员在服务端输入 `/mcbench accept <clientId+token>` 确认
 *  - mode：请求的 2.0 模式
 *  - timestamp：请求时间，默认 2 分钟超时
 *  - requesterUuid / requesterName：发起请求的玩家，用于跑分完成后回传结果路径
 *
 * 同一服务端最多允许同时存在 3 个待处理请求。
 */
public record RemoteTestRequest(
        String clientId,
        String token,
        String mode,
        long timestampMillis,
        String requesterUuid,
        String requesterName
) {
    public RemoteTestRequest(String clientId, String token, String mode, long timestampMillis) {
        this(clientId, token, mode, timestampMillis, "", "");
    }

    public String idAndToken() {
        return clientId + "+" + token;
    }
}
