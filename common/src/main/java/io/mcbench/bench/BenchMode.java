package io.mcbench.bench;

/** 跑分模式。 */
public enum BenchMode {
    // ---------------- 1.0 基准（保留可用，不与 2.0 对比） ----------------
    COMBAT(BenchVersion.V1, "综合实战测试",
            "统一设置参数 → 创建固定超平坦世界 → 世界加载/Chunky区块生成/透明方块/250猪/粒子/TNT 六场景实测",
            "考验设备性能与优化（模组/整合包）真实游戏表现，最全面"),
    FAIR(BenchVersion.V1, "公平性能测试",
            "在当前 Java 环境中直接执行 CPU（整数/浮点/内存/哈希，单线程+多线程）与 GPU 填充率计算基准",
            "绕开游戏机制，快速对比设备硬性性能（类似 CPU-Z）"),
    QUICK(BenchVersion.V1, "快测",
            "世界加载用时 → Chunky 半径50区块生成 → 250只猪高空跌落实体测试",
            "优化整合包优选：5分钟内给出核心结论"),

    // ---------------- 2.0 基准（新基准，不与 1.0 对比） ----------------
    V2_COMBAT(BenchVersion.V2, "2.0 原版综合",
            "世界加载/Chunky区块生成/透明方块/实体渲染/粒子/TNT + 20秒闲置FPS记录",
            "全新 2.0 标准：以手机原版为 1x 参考，倍率不封顶"),
    V2_FAIR(BenchVersion.V2, "2.0 公平绝对性能",
            "不同 JAR 环境/MC 版本均可跑的 CPU/GPU 绝对性能测试",
            "主测 CPU，有 GPU 也可测；1.21.11 旧 GPU 测试已标记为需重做"),
    V2_QUICK(BenchVersion.V2, "2.0 快测",
            "世界加载/Chunky(30)/透明/实体(200)/粒子/TNT 六项简化快测",
            "部分环境直接综合会很卡时使用"),
    V2_MOD_CREATE(BenchVersion.V2, "2.0 模组专项（机械动力）",
            "机械动力 300 小水车 + 300 烈焰人燃烧室方块实体（不是烈焰人生物）超级燃烧，涵盖客户端 FPS 与服务端 TPS/MSPT",
            "每次只测一个模组，本版本优先机械动力");

    public final BenchVersion version;
    public final String label;
    public final String detail;
    public final String hint;

    BenchMode(BenchVersion version, String label, String detail, String hint) {
        this.version = version;
        this.label = label;
        this.detail = detail;
        this.hint = hint;
    }
}
