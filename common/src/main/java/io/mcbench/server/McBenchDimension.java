package io.mcbench.server;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * MCBench 独立超平坦测试维度。
 *
 * 方案（按用户建议）：
 *  1. 服务端启动时预先创建该维度，平时不加入正常 tick 列表（不加载）。
 *  2. 跑分开始时热加载/激活，跑完热重置。
 *  3. 保留“冻结其他世界”逻辑。
 *
 * 当前实现：启动时构造 ServerLevel 并保存在静态字段中，但不放入 server 的 levels/worldArray，
 * 因此平时不会被服务器 tick。
 */
public final class McBenchDimension {
    public static final String DIMENSION_NAMESPACE = "mcbench";
    public static final String DIMENSION_PATH = "test_flat";

    public static final ResourceKey<Level> LEVEL_KEY = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(DIMENSION_NAMESPACE, DIMENSION_PATH));

    public static final ResourceKey<LevelStem> STEM_KEY = ResourceKey.create(
            Registries.LEVEL_STEM,
            ResourceLocation.fromNamespaceAndPath(DIMENSION_NAMESPACE, DIMENSION_PATH));

    private static ServerLevel testLevel;

    private McBenchDimension() {
    }

    /** 服务端启动时预先创建超平坦维度并保存引用，平时不加载。 */
    public static void createAtStartup(MinecraftServer server) {
        if (testLevel != null) return;
        try {
            ServerLevel overworld = server.overworld();
            ServerLevelData overworldData = server.getWorldData().overworldData();
            DerivedLevelData derived = new DerivedLevelData(server.getWorldData(), overworldData);

            Registry<WorldPreset> presets = server.registryAccess().registryOrThrow(Registries.WORLD_PRESET);
            Holder<WorldPreset> flatHolder = presets.getHolderOrThrow(WorldPresets.FLAT);
            WorldDimensions dims = flatHolder.value().createWorldDimensions();
            Map<ResourceKey<LevelStem>, LevelStem> stems = dims.dimensions();
            LevelStem flatStem = stems.get(LevelStem.OVERWORLD);
            if (flatStem == null) {
                throw new IllegalStateException("无法获取超平坦 LevelStem");
            }

            Field storageField = MinecraftServer.class.getDeclaredField("storageSource");
            storageField.setAccessible(true);
            LevelStorageSource.LevelStorageAccess storage = (LevelStorageSource.LevelStorageAccess) storageField.get(server);

            boolean isDebug = server.getWorldData().isDebugWorld();
            long biomeZoomSeed = BiomeManager.obfuscateSeed(server.getWorldData().worldGenOptions().seed());

            testLevel = new ServerLevel(
                    server,
                    server,
                    storage,
                    derived,
                    LEVEL_KEY,
                    flatStem,
                    LoggerChunkProgressListener.createCompleted(),
                    isDebug,
                    biomeZoomSeed,
                    List.of(),
                    false,
                    overworld.getRandomSequences());

            ServerMcBenchLog.LOGGER.info("[MCBench] 已预创建独立超平坦测试维度 {}（未加入正常 tick）", LEVEL_KEY.location());
        } catch (Exception e) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 预创建测试维度失败: {}", e.toString());
            testLevel = null;
        }
    }

    public static ServerLevel getTestLevel() {
        return testLevel;
    }

    /** 跑分开始时激活维度：加入 server levels 并强制刷新 worldArray，使其参与 tick。 */
    public static void activate(MinecraftServer server) {
        if (testLevel == null) return;
        try {
            Field levelsField = MinecraftServer.class.getDeclaredField("levels");
            levelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ResourceKey<Level>, ServerLevel> levels = (Map<ResourceKey<Level>, ServerLevel>) levelsField.get(server);
            if (!levels.containsKey(LEVEL_KEY)) {
                levels.put(LEVEL_KEY, testLevel);
                refreshWorldArray(server);
                ServerMcBenchLog.LOGGER.info("[MCBench] 独立测试维度已激活: {}", LEVEL_KEY.location());
            }
        } catch (Exception e) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 激活独立测试维度失败: {}", e.toString());
        }
    }

    /** 跑分结束后热重置（清实体、重置平台/区块）。 */
    public static void reset(MinecraftServer server) {
        if (testLevel == null) return;
        io.mcbench.bench.SceneBuilder.removeAllEntities(testLevel);
        io.mcbench.bench.SceneBuilder.setTestY(64);
        io.mcbench.bench.SceneBuilder.prepareFlatArea(testLevel);
    }

    /** 跑分结束后卸载维度：从 server levels 移除并刷新 worldArray，使其不再 tick。 */
    public static void deactivate(MinecraftServer server) {
        if (testLevel == null) return;
        try {
            Field levelsField = MinecraftServer.class.getDeclaredField("levels");
            levelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ResourceKey<Level>, ServerLevel> levels = (Map<ResourceKey<Level>, ServerLevel>) levelsField.get(server);
            if (levels.remove(LEVEL_KEY) != null) {
                refreshWorldArray(server);
                ServerMcBenchLog.LOGGER.info("[MCBench] 独立测试维度已卸载: {}", LEVEL_KEY.location());
            }
        } catch (Exception e) {
            ServerMcBenchLog.LOGGER.warn("[MCBench] 卸载独立测试维度失败: {}", e.toString());
        }
    }

    private static void refreshWorldArray(MinecraftServer server) throws Exception {
        Field marker = MinecraftServer.class.getDeclaredField("worldArrayMarker");
        marker.setAccessible(true);
        marker.setInt(server, marker.getInt(server) + 1);
        Method m = MinecraftServer.class.getDeclaredMethod("getWorldArray");
        m.setAccessible(true);
        m.invoke(server);
    }
}
