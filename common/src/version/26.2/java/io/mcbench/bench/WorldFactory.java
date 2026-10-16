package io.mcbench.bench;

import io.mcbench.McBench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * 创建固定种子超平坦测试世界（26.2 实现）——基于 26.2 未混淆客户端 jar 的 javap 验证编写。
 *
 * 与 1.21.1 的差异：
 *  - createFreshLevel 的维度工厂参数为 Function<HolderLookup.Provider, WorldDimensions>
 *  - LevelSettings 构造参数变为 (name, gameType, DifficultySettings, allowCommands, dataConfig)
 *  - 其余（超平坦预设、固定种子、删除旧世界）一致。
 */
public class WorldFactory {
    public static final long FIXED_SEED = McBench.FIXED_SEED;

    private WorldFactory() {
    }

    public static void createTestWorld(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource lss = mc.getLevelSource();
        deleteExistingWorld(lss);
        if (lss.levelExists(McBench.WORLD_NAME)) {
            McBench.LOGGER.warn("[MCBench] 旧测试世界仍存在且删除失败，中止创建（避免残留存档假跑分）");
            return;
        }

        LevelSettings.DifficultySettings difficulty =
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false);
        LevelSettings settings = new LevelSettings(
                McBench.WORLD_NAME,
                GameType.CREATIVE,
                difficulty,
                true,
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
