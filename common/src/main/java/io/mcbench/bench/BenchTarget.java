package io.mcbench.bench;

/**
 * 2.0 跑分对象（客户端侧选择）。
 *
 * 远端服务端相关模式需要服务端安装 mcbench 并由管理员同意请求，
 * 当前版本先提供入口与状态标记，联测协议在后续模块完善。
 */
public enum BenchTarget {
    CLIENT("客户端性能",
            "在当前客户端完整渲染并运行，测客户端自身性能"),
    LOCAL_SERVER("本地服务端性能",
            "测本机集成服务端性能，屏蔽渲染部分"),
    REMOTE_SERVER("远端服务端性能",
            "测远端服务端性能，需服务端管理员同意"),
    COORDINATED("客户端+远端协同",
            "客户端不再处理逻辑，更贴近服务器游玩场景，需服务端管理员同意");

    public final String label;
    public final String detail;

    BenchTarget(String label, String detail) {
        this.label = label;
        this.detail = detail;
    }

    @Override
    public String toString() {
        return label;
    }
}
