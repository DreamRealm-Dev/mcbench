package io.mcbench.bench;

import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.GameRules;
import io.mcbench.McBench;

import java.io.File;

/**
 * 版本差异桥（1.21.1 实现）。
 *
 * 跨版本时只需替换本类 + WorldFactory（见 common/src/version/<版本>/java）：
 *  - 图形“极佳”选项：1.21.1 为 Options.graphicsMode()，26.x 为 GraphicsPreset
 *  - 粒子选项：1.21.1 为 net.minecraft.client.ParticleStatus，26.x 移动了包
 *  - 游戏规则：1.21.1 为 GameRules.RULE_*，26.x 为 gamerules.GameRules 的常量
 *  - 退出世界：1.21.1 为 Minecraft.disconnect(Screen)，26.x 有专用方法
 */
public final class VersionBridge {

    private VersionBridge() {
    }

    // ---------- 图形设置 ----------

    public static Object captureGraphics(Options o) {
        return o.graphicsMode().get();
    }

    public static void applyFancyGraphics(Options o) {
        o.graphicsMode().set(GraphicsStatus.FANCY);
    }

    public static void restoreGraphics(Options o, Object state) {
        if (state instanceof GraphicsStatus g) {
            o.graphicsMode().set(g);
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
        return String.valueOf(o.graphicsMode().get());
    }

    // ---------- 跨版本辅助（SceneBuilder / ResultScreen 共用） ----------

    public static Entity createPig(ServerLevel level) {
        return new Pig(EntityType.PIG, level);
    }

    public static Entity createZombie(ServerLevel level) {
        return new net.minecraft.world.entity.monster.Zombie(EntityType.ZOMBIE, level);
    }

    @SuppressWarnings("unchecked")
    public static Class<? extends Entity> pigClass() {
        return (Class<? extends Entity>) (Class<?>) Pig.class;
    }

    public static void openUri(File file) {
        net.minecraft.Util.getPlatform().openUri(file.toURI());
    }

    public static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    public static ServerLevel playerLevel(ServerPlayer player) {
        return player.serverLevel();
    }

    public static int framerateLimit(Minecraft mc) {
        return mc.getWindow().getFramerateLimit();
    }

    // ---------- 服务器规则（超平坦测试世界统一环境） ----------

    public static void applyBenchmarkRules(MinecraftServer server) {
        GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(0, server);
        server.overworld().setDayTime(6000);
        server.overworld().setRainLevel(0);
        server.overworld().setThunderLevel(0);
    }

    // ---------- 退出世界 ----------

    /**
     * 退出世界（不阻塞主线程）：
     * 1) halt(true) 强制停止集成服务器（不保存，立即）；
     * 2) 反射置空 Minecraft.singleplayerServer（disconnect 才做，clearClientLevel 不做）；
     * 3) clearClientLevel(screen)：清空客户端 level/player（内部无 waitForServer 同步等待）。
     *
     * 不能使用 Minecraft.disconnect(Screen)：其内部有
     * `while (!server.isShutdown()) runTick(false)` 同步等待服务器停止，
     * 服务器过载时主线程会被永久卡住（"数据汇总中"卡死）。
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
