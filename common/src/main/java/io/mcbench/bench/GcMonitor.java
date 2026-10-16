package io.mcbench.bench;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 强制 GC 监控：统计跑分期间由 System.gc() 触发的显式垃圾回收次数。
 *
 * 判定方法：监听 GC Notification，读取 GcInfo 的 "gcCause" 属性（"System.gc()" 即显式 GC），
 * 按回收器名称（"gcName"）分开统计（G1GC: "G1 Young Generation"/"G1 Old Generation"；
 * ZGC: "ZGC Minor"/"ZGC Major"）。不依赖 com.sun.management 内部 API，跨 JDK 兼容。
 * 该指标不参与跑分，仅作为“优化建议”参考。
 */
public class GcMonitor {
    private final Map<String, Integer> forcedCounts = new TreeMap<>();
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final NotificationListener listener = this::onNotification;
    private int totalForced = 0;
    private boolean running = false;

    public void start() {
        if (running) return;
        running = true;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitters.add(emitter);
                try {
                    emitter.addNotificationListener(listener, null, bean);
                } catch (Exception e) {
                    // 忽略单个 bean 的监听失败
                }
            }
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(listener);
            } catch (Exception ignored) {
            }
        }
        emitters.clear();
    }

    private void onNotification(Notification notification, Object handback) {
        try {
            if (!(notification.getUserData() instanceof CompositeData cd)) return;
            Object causeObj = cd.get("gcCause");
            if (!(causeObj instanceof String cause)) return;
            if (cause.contains("System.gc()")) {
                Object nameObj = cd.get("gcName");
                String name = nameObj instanceof String n ? n : "unknown";
                forcedCounts.merge(name, 1, Integer::sum);
                totalForced++;
            }
        } catch (Exception ignored) {
            // 部分 JDK 的 GC 通知结构不同，忽略即可
        }
    }

    public Map<String, Integer> forcedCounts() {
        return new TreeMap<>(forcedCounts);
    }

    public int totalForced() {
        return totalForced;
    }

    /** 快照：{collectorName: count} 与总数 */
    public record GcSnapshot(Map<String, Integer> forcedByCollector, int total) {
    }

    public GcSnapshot snapshot() {
        return new GcSnapshot(forcedCounts(), totalForced);
    }
}
