package io.mcbench.bench;

import io.mcbench.McBench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 公平性能测试 - CPU 纯计算基准（绕开游戏，在当前 Java 环境直接执行）。
 *
 * 测试项：
 *  - 整数运算：埃拉托斯特尼筛法（count of primes）
 *  - 浮点运算：sin/cos/mul 流水
 *  - 内存带宽：数组拷贝 + 随机读
 *  - SHA-256 哈希吞吐
 * 全部以“每秒操作数”计，单线程 + 多线程（可用核数）两轮。
 */
public final class CpuBench {

    /** 单项结果 */
    public record CpuResult(String name, double singleOpsPerSec, double multiOpsPerSec) {
    }

    private CpuBench() {
    }

    /** 在后台线程执行全部 CPU 测试；progress 每项完成时更新；返回结果列表。 */
    public static void runAsync(List<CpuResult> sink, AtomicInteger progress, AtomicInteger total,
                                Runnable onDone) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        total.set(8);
        Thread t = new Thread(() -> {
            try {
                sink.add(new CpuResult("整数素数筛", runSingle(CpuBench::primeSieveOps, 5), runMulti(CpuBench::primeSieveOps, 5, cores)));
                progress.incrementAndGet();
                sink.add(new CpuResult("浮点sin/cos", runSingle(CpuBench::floatOps, 5), runMulti(CpuBench::floatOps, 5, cores)));
                progress.incrementAndGet();
                sink.add(new CpuResult("内存带宽MB/s", runSingle(CpuBench::memoryBandwidth, 5), runMulti(CpuBench::memoryBandwidth, 5, cores)));
                progress.incrementAndGet();
                sink.add(new CpuResult("SHA-256 KB/s", runSingle(CpuBench::sha256Speed, 5), runMulti(CpuBench::sha256Speed, 5, cores)));
                progress.incrementAndGet();
            } catch (Exception e) {
                McBench.LOGGER.warn("[MCBench] CPU 基准异常: {}", e.toString());
            }
            onDone.run();
        }, "mcbench-cpu-bench");
        t.setDaemon(true);
        t.start();
    }

    // ---------- 单项基准（返回 每秒操作数 或 吞吐） ----------

    static double primeSieveOps() {
        int n = 2_000_000;
        boolean[] sieve = new boolean[n + 1];
        int count = 0;
        for (int i = 2; i <= n; i++) {
            if (!sieve[i]) {
                count++;
                if ((long) i * i <= n) {
                    for (int j = i * i; j <= n; j += i) sieve[j] = true;
                }
            }
        }
        return count;
    }

    static double floatOps() {
        double acc = 0;
        for (int i = 0; i < 200_000; i++) {
            double v = i * 0.001;
            acc += Math.sin(v) * Math.cos(v) + Math.sqrt(Math.abs(v)) - v * 0.5;
        }
        return 200_000;
    }

    static double memoryBandwidth() {
        int size = 8 * 1024 * 1024; // 8M doubles = 64MB
        double[] src = new double[size];
        double[] dst = new double[size];
        java.util.Arrays.fill(src, 1.0);
        long start = System.nanoTime();
        System.arraycopy(src, 0, dst, 0, size);
        long elapsed = System.nanoTime() - start;
        double mb = (size * 8.0) / 1024.0 / 1024.0;
        return mb / (elapsed / 1e9);
    }

    static double sha256Speed() {
        byte[] data = new byte[64 * 1024];
        java.util.Arrays.fill(data, (byte) 7);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) md.digest(data);
            long elapsed = System.nanoTime() - start;
            return (data.length * 200.0 / 1024.0) / (elapsed / 1e9);
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 计时执行 ----------

    private static double runSingle(Callable<Double> work, int seconds) throws Exception {
        return runWorkers(work, 1, seconds);
    }

    private static double runMulti(Callable<Double> work, int seconds, int cores) throws Exception {
        return runWorkers(work, cores, seconds);
    }

    /** 在 workers 个线程上并发执行 work 固定时长，返回各线程单次结果之和/秒。 */
    private static double runWorkers(Callable<Double> work, int workers, int seconds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            AtomicLong iterations = new AtomicLong();
            AtomicInteger running = new AtomicInteger(workers);
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                futures.add(pool.submit(() -> {
                    try {
                        while (running.get() > 0) {
                            double ops = work.call();
                            iterations.addAndGet((long) ops);
                        }
                    } catch (Exception ignored) {
                    }
                }));
            }
            Thread.sleep(seconds * 1000L);
            running.set(0);
            for (Future<?> f : futures) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            double elapsed = (System.nanoTime() - start) / 1e9;
            return iterations.get() / Math.max(elapsed, 0.001);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 供 UI 显示核心数 */
    public static int coreCount() {
        return Runtime.getRuntime().availableProcessors();
    }
}
