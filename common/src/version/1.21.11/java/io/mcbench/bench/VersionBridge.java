package io.mcbench.bench;

import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.gamerules.GameRules;
import io.mcbench.McBench;

import java.io.File;

/**
 * 版本差异桥（1.21.11 实现）。
 *
 * 与 1.21.1 的差异：
 *  - 图形选项：Options.graphicsPreset() / GraphicsPreset
 *  - 粒子选项：net.minecraft.server.level.ParticleStatus
 *  - 游戏规则：net.minecraft.world.level.gamerules.GameRules 常量 + set(...)
 *  - 猪实体：net.minecraft.world.entity.animal.pig.Pig
 *  - 工具类：net.minecraft.util.Util
 */
public final class VersionBridge {

    private VersionBridge() {
    }

    // ---------- 图形设置 ----------

    public static Object captureGraphics(Options o) {
        return o.graphicsPreset().get();
    }

    public static void applyFancyGraphics(Options o) {
        o.graphicsPreset().set(GraphicsPreset.FANCY);
    }

    public static void restoreGraphics(Options o, Object state) {
        if (state instanceof GraphicsPreset g) {
            o.graphicsPreset().set(g);
        }
    }

    // ---------- 粒子设置 ----------

    public static Object captureParticles(Options o) {
        return o.particles().get();
    }

    public static void applyParticlesAll(Options o) {
        o.particles().set(ParticleStatus.ALL);
    }

    public static void restoreParticles(Options o, Object state) {
        if (state instanceof ParticleStatus p) {
            o.particles().set(p);
        }
    }

    /** 供日志显示粒子设置 */
    public static String particleStatusName(Options o) {
        return String.valueOf(o.particles().get());
    }

    /** 供日志显示画质设置 */
    public static String graphicsStatusName(Options o) {
        return String.valueOf(o.graphicsPreset().get());
    }

    // ---------- 跨版本辅助（SceneBuilder / ResultScreen 共用） ----------

    public static Entity createPig(ServerLevel level) {
        return EntityType.PIG.create(level, EntitySpawnReason.COMMAND);
    }

    public static Entity createZombie(ServerLevel level) {
        return EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
    }

    @SuppressWarnings("unchecked")
    public static Class<? extends Entity> pigClass() {
        return (Class<? extends Entity>) (Class<?>) Pig.class;
    }

    public static void openUri(File file) {
        net.minecraft.util.Util.getPlatform().openUri(file.toURI());
    }

    public static String dimensionId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    public static ServerLevel playerLevel(ServerPlayer player) {
        return player.level();
    }

    public static int framerateLimit(Minecraft mc) {
        return mc.getFramerateLimitTracker().getFramerateLimit();
    }

    // ---------- 服务器规则（超平坦测试世界统一环境） ----------

    public static void applyBenchmarkRules(MinecraftServer server) {
        GameRules rules = server.overworld().getGameRules();
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.MAX_ENTITY_CRAMMING, 0, server);
        server.overworld().setDayTime(6000);
        server.overworld().setRainLevel(0);
        server.overworld().setThunderLevel(0);
    }

    // ---------- 退出世界 ----------

    /**
     * 退出世界（不阻塞主线程）：
     * 1) halt(true) 强制停止集成服务器（不保存，立即）；
     * 2) 反射置空 Minecraft.singleplayerServer；
     * 3) clearClientLevel(screen)：清空客户端 level/player。
     */
    public static void leaveWorld(Minecraft mc, Screen progressScreen) {
        net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            try {
                server.halt(true);
            } catch (Exception e) {
                McBench.LOGGER.warn("[MCBench] 停止服务器异常: {}", e.toString());
            }
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField("singleplayerServer");
                f.setAccessible(true);
                f.set(mc, null);
            } catch (Exception e) {
                McBench.LOGGER.warn("[MCBench] 置空 singleplayerServer 失败: {}", e.toString());
            }
        }
        mc.clearClientLevel(progressScreen);
    }
}
