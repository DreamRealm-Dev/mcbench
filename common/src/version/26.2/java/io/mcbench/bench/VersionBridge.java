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

import java.io.File;

/**
 * 版本差异桥（26.2 实现）——基于 26.2 未混淆客户端 jar 的 javap 验证编写。
 *
 * 与 1.21.1 的差异：
 *  - 图形选项：Options.graphicsPreset()（GraphicsPreset 枚举），"极佳" = FABULOUS
 *  - 粒子选项类型移动至 net.minecraft.server.level.ParticleStatus
 *  - 游戏规则：net.minecraft.world.level.gamerules.GameRules 常量 + set(GameRule,T,server)
 *    （SPAWN_MOBS / ADVANCE_TIME / ADVANCE_WEATHER / MAX_ENTITY_CRAMMING）
 *  - 退出世界：Minecraft.disconnectWithProgressScreen()
 *
 * 注意：本文件尚未联网编译验证（本机离线缓存无 26.2 工具链），
 * 需在联网环境执行 ./gradlew :neoforge:build -Pmc.version=26.2 验证。
 */
public final class VersionBridge {

    private VersionBridge() {
    }

    // ---------- 图形设置 ----------

    public static Object captureGraphics(Options o) {
        return o.graphicsPreset().get();
    }

    public static void applyFancyGraphics(Options o) {
        o.graphicsPreset().set(GraphicsPreset.FABULOUS);
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

    // ---------- 服务器规则 ----------

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

    public static void leaveWorld(Minecraft mc, Screen progressScreen) {
        mc.disconnectWithProgressScreen();
    }
}
