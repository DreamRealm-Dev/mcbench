package io.mcbench.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 帧时间采样器：挂接在客户端每帧 tick 上，统计平均帧率与 1% Low 帧。
 *
 * 1% Low 定义：取所有帧时间（毫秒）排序后最慢的 1% 帧，其平均帧时间的倒数。
 * 即：1000 / (最慢 1% 帧的平均毫秒数)。该指标比平均帧更能反映卡顿体验。
 */
public class FrameSampler {
    private final List<Double> frameTimesMs = new ArrayList<>(1 << 16);
    private long lastNanos;
    private boolean active = false;

    /** 开始采样（清空历史）。 */
    public void begin() {
        frameTimesMs.clear();
        lastNanos = System.nanoTime();
        active = true;
    }

    /** 每帧调用一次。 */
    public void frame() {
        if (!active) return;
        long now = System.nanoTime();
        frameTimesMs.add((now - lastNanos) / 1_000_000.0);
        lastNanos = now;
    }

    public void end() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    /** 结束并计算统计结果。 */
    public Sample result() {
        int n = frameTimesMs.size();
        if (n == 0) return new Sample(0, 0, 0, 0);
        double[] arr = new double[n];
        double totalMs = 0;
        for (int i = 0; i < n; i++) {
            arr[i] = frameTimesMs.get(i);
            totalMs += arr[i];
        }
        Arrays.sort(arr);
        double avgFps = totalMs <= 0 ? 0 : 1000.0 * n / totalMs;
        int lowCount = Math.max(1, n / 100);
        double lowSum = 0;
        for (int i = n - lowCount; i < n; i++) lowSum += arr[i];
        double lowFps = lowSum <= 0 ? 0 : 1000.0 * lowCount / lowSum;
        double worstFps = arr[n - 1] <= 0 ? 0 : 1000.0 / arr[n - 1];
        return new Sample(avgFps, lowFps, worstFps, n);
    }

    public record Sample(double avgFps, double lowFps, double worstFps, int frames) {
    }
}
