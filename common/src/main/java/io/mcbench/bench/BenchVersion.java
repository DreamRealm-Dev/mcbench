package io.mcbench.bench;

/**
 * 基准版本。
 *
 * 1.0 与 2.0 使用不同的场景、基准常数和计分规则，结果不可直接对比。
 */
public enum BenchVersion {
    V1("1.0 基准"),
    V2("2.0 基准");

    public final String label;

    BenchVersion(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
