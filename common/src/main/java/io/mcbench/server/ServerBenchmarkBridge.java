package io.mcbench.server;

/**
 * 服务端联测桥接接口（骨架）。
 *
 * 后续迭代将由 Fabric / NeoForge 服务端入口实现：
 *  - `/mcbench list`：列出等待同意的远端跑分请求
 *  - `/mcbench accept <UUID+token>`：管理员同意请求
 *  - `/mcbench deny <UUID+token>`：拒绝请求
 *  - 默认 2 分钟超时，同时最多 3 个待处理请求
 *  - 测试在服务端自建的独立超平坦维度进行，并安全冻结其他世界
 *
 * 本文件仅定义接口，当前版本尚未注册命令。
 */
public interface ServerBenchmarkBridge {
    /** 服务端是否已安装并启用 mcbench 联测模块。 */
    boolean isAvailable();

    /** 列出当前待处理的远端跑分请求（管理命令用）。 */
    String listPendingRequests();

    /** 同意指定请求；token 用于防止请求被顶替。 */
    boolean acceptRequest(String clientIdAndToken);

    /** 拒绝指定请求。 */
    boolean denyRequest(String clientIdAndToken);
}
