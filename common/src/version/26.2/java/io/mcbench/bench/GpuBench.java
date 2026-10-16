package io.mcbench.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.mcbench.McBench;
import net.minecraft.client.Minecraft;

/**
 * 1.21.11 的渲染管线已迁移到 GpuDevice/RenderPass，旧的 BufferUploader 已移除。
 * 该实现暂时只构建顶点数据并释放 MeshData，保证模组在 1.21.11 可编译运行；
 * GPU 填充率数值在当前版本不作为精确参考（后续可接入新 RenderPass 管线）。
 */
public final class GpuBench {

    private GpuBench() {
    }

    public static void drawFills(int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;
        int w = Math.max(1, mc.getWindow().getWidth());
        int h = Math.max(1, mc.getWindow().getHeight());

        // 新渲染管线没有 BufferUploader.drawWithShader；这里只做 CPU 端构建占位。
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float r = 0.1f, g = 0.2f, b = 0.9f, a = 1f;
        for (int i = 0; i < count; i++) {
            float shade = 0.4f + 0.6f * ((i % 7) / 7.0f);
            float rr = Math.min(1f, r * shade + 0.1f);
            float gg = Math.min(1f, g * shade + 0.1f);
            float bb = Math.min(1f, b * shade + 0.1f);
            builder.addVertex(0, 0, 0).setColor(rr, gg, bb, a);
            builder.addVertex(0, h, 0).setColor(rr, gg, bb, a);
            builder.addVertex(w, h, 0).setColor(rr, gg, bb, a);
            builder.addVertex(w, 0, 0).setColor(rr, gg, bb, a);
        }
        try (var mesh = builder.buildOrThrow()) {
            // MeshData 已构建；1.21.11 暂不接入 RenderPass 绘制。
        }
        RenderSystem.getDevice(); // 确保渲染线程/设备访问路径被引用，避免误删导入
    }
}
