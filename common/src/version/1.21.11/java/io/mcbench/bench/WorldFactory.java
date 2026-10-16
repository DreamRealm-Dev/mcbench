package io.mcbench.bench;

import io.mcbench.McBench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * 创建固定种子超平坦测试世界 test_world_1：
 *  - 超平坦（WorldPresets.FLAT）：无地形起伏，区块生成/渲染负载稳定
 *  - 固定种子：所有设备生成完全相同的世界
 *  - 和平难度 + 创造模式 + 允许指令
 *  - 生物生成/昼夜/天气/实体挤压 由 BenchmarkManager 在服务器启动后关闭
 */
public class WorldFactory {
    public static final long FIXED_SEED = McBench.FIXED_SEED;

    private WorldFactory() {
    }

    /** 删除旧测试世界（若存在）并创建新世界。parent 用于创建失败时返回。 */
    public static void createTestWorld(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource lss = mc.getLevelSource();
        deleteExistingWorld(lss);
        if (lss.levelExists(McBench.WORLD_NAME)) {
            // 删除失败（文件仍被上一轮服务端线程占用）→ 直接重建会抛异常；
            // 这里如实记录，让上层超时保护中止，避免用残留存档跑出假分数
            McBench.LOGGER.warn("[MCBench] 旧测试世界仍存在且删除失败，中止创建（避免残留存档假跑分）");
            return;
        }

        LevelSettings settings = new LevelSettings(
                McBench.WORLD_NAME,
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()),
                WorldDataConfiguration.DEFAULT);

        WorldOptions options = new WorldOptions(FIXED_SEED, false, false);

        mc.createWorldOpenFlows().createFreshLevel(
                McBench.WORLD_NAME,
                settings,
                options,
                provider -> {
                    HolderLookup.RegistryLookup<WorldPreset> lookup =
                            provider.lookup(Registries.WORLD_PRESET).orElseThrow();
                    Holder.Reference<WorldPreset> flat = lookup.getOrThrow(WorldPresets.FLAT);
                    return flat.value().createWorldDimensions();
                },
                parent);
    }

    /** 删除旧世界；文件锁未释放时最多重试 10 次（间隔 1 秒）。 */
    private static void deleteExistingWorld(LevelStorageSource lss) {
        if (!lss.levelExists(McBench.WORLD_NAME)) return;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                LevelStorageSource.LevelStorageAccess access = lss.createAccess(McBench.WORLD_NAME);
                try {
                    access.deleteLevel();
                } finally {
                    access.close();
                }
                McBench.LOGGER.info("[MCBench] 已删除旧测试世界");
                return;
            } catch (Exception e) {
                McBench.LOGGER.warn("[MCBench] 删除旧测试世界失败（第 {} 次重试）: {}", attempt, e.toString());
                if (attempt < 10) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }
        McBench.LOGGER.warn("[MCBench] 删除旧测试世界最终失败");
    }
}
