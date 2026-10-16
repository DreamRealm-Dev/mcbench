package io.mcbench.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.mcbench.McBench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * 公平性能测试 - GPU 填充率基准（渲染线程执行）：
 * 每帧绘制 N 个全屏彩色四边形（POSITION_COLOR，走完整渲染管线），
 * 统计每秒可绘制的全屏填充次数，近似衡量 GPU 光栅化吞吐。
 * 使用原版着色器管线，MobileGlues（GLES）同样适用。
 */
public final class GpuBench {

    private GpuBench() {
    }

    /** 绘制 count 个全屏填充四边形（渲染线程调用）。 */
    public static void drawFills(int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;
        int w = Math.max(1, mc.getWindow().getWidth());
        int h = Math.max(1, mc.getWindow().getHeight());

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = new Matrix4f().setOrtho(0, w, h, 0, 1000, 3000);
        RenderSystem.setProjectionMatrix(matrix, VertexSorting.ORTHOGRAPHIC_Z);

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
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }
}
