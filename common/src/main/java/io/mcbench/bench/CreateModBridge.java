package io.mcbench.bench;

import io.mcbench.McBench;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 机械动力（Create）模组专项桥接。
 *
 * 通过注册表按 ID 查找 Create 方块，不编译期依赖 Create：
 *  - create:water_wheel
 *  - create:blaze_burner
 *
 * 注意：烈焰人燃烧室是 Create 的方块/方块实体，不是烈焰人生物。
 */
public final class CreateModBridge {
    private static boolean resolved = false;
    private static boolean available = false;
    private static Block waterWheel = null;
    private static Block blazeBurner = null;

    private CreateModBridge() {
    }

    public static synchronized boolean isAvailable() {
        if (!resolved) {
            resolved = true;
            waterWheel = findBlock("water_wheel");
            blazeBurner = findBlock("blaze_burner");
            available = waterWheel != null && blazeBurner != null;
            McBench.LOGGER.info("[MCBench] 机械动力专项检测: available={} waterWheel={} blazeBurner={}",
                    available, waterWheel, blazeBurner);
        }
        return available;
    }

    private static Block findBlock(String path) {
        try {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", path));
            return block == null || block == Blocks.AIR ? null : block;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 放置指定数量水车与烈焰人燃烧室，返回耗时（毫秒）。
     * 目前只放置方块，超级燃烧状态需要后续通过 Create 方块实体 API 设置。
     */
    public static long buildCreateStress(ServerLevel level, int waterWheels, int blazeBurners) {
        if (!isAvailable()) return -1;
        SceneBuilder.removeAllEntities(level);
        long start = System.nanoTime();
        BlockPos c = new BlockPos(0, SceneBuilder.Y_GROUND + 1, 0);
        int placed = 0;
        outer:
        for (int i = 0; i < waterWheels; i++) {
            int dx = (i % 30) * 5;
            int dz = (i / 30) * 5;
            BlockPos pos = c.offset(dx, 0, dz);
            level.setBlock(pos, waterWheel.defaultBlockState(), 3);
            placed++;
        }
        BlockPos b = c.offset(0, 2, 100);
        for (int i = 0; i < blazeBurners; i++) {
            int dx = (i % 30) * 5;
            int dz = (i / 30) * 5;
            BlockPos pos = b.offset(dx, 0, dz);
            level.setBlock(pos, blazeBurner.defaultBlockState(), 3);
            setSuperBurning(level, pos);
            placed++;
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * 通过反射把烈焰人燃烧室方块实体设为 SPECIAL（超级燃烧）状态。
     * 等价于投喂烈焰蛋糕后的效果：activeFuel=SPECIAL、remainingBurnTime 充足并刷新方块状态。
     */
    private static void setSuperBurning(ServerLevel level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return;
            Class<?> clazz = Class.forName("com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity");
            Class<?> fuelType = Class.forName("com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity$FuelType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object special = Enum.valueOf((Class<? extends Enum>) fuelType, "SPECIAL");

            Field activeFuel = clazz.getDeclaredField("activeFuel");
            activeFuel.setAccessible(true);
            activeFuel.set(be, special);

            Field remainingBurnTime = clazz.getDeclaredField("remainingBurnTime");
            remainingBurnTime.setAccessible(true);
            remainingBurnTime.setInt(be, 100_000);

            Method updateBlockState = clazz.getDeclaredMethod("updateBlockState");
            updateBlockState.setAccessible(true);
            updateBlockState.invoke(be);
        } catch (Throwable t) {
            McBench.LOGGER.warn("[MCBench] 设置烈焰人燃烧室超级燃烧失败: {}", t.toString());
        }
    }
}
