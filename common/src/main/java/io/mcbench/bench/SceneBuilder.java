package io.mcbench.bench;

import io.mcbench.McBench;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * 跑分场景构建器。
 *
 * 坐标说明：测试世界为固定种子超平坦世界，地表高度 Y_GROUND = -60（1.18+ 世界生成），
 * 所有场景坐标都基于该地表，保证玩家/实体/建筑都贴合地面。
 *
 *  - 远距离透明方块群（3×3 个实心透明立方体，玻璃/含水玻璃板/水/冰/黏液块/蜂蜜块）
 *  - 5×5 栅栏猪圈（300 只猪，贴地，观察点更近）
 *  - 远处粒子洪流（每 tick 1000 个，范围更大）
 *  - 1000 个 TNT 爆炸场（贴地、位置固定）
 *  - 快测：高空平台 + 300 猪，抽掉平台自然跌落
 */
public final class SceneBuilder {
    /** 超平坦世界地表高度 */
    public static final int Y_GROUND = -60;

    /** 当前测试地表高度（远端服务端在普通主世界测试时切换到平台高度） */
    private static int testY = Y_GROUND;

    public static void setTestY(int y) {
        testY = y;
    }

    public static int currentTestY() {
        return testY;
    }

    // 透明方块场景：3×3 个 32×32×32 实心透明立方体，中心 (0, Y_GROUND+32, 200)
    // 观察点 (0, Y_GROUND+40, 60) 距离中心约 140 格，从远处透视多层透明方块
    public static final BlockPos STRUCTURE_CENTER = new BlockPos(0, Y_GROUND + 32, 200);
    public static final BlockPos STRUCTURE_VIEW = new BlockPos(0, Y_GROUND + 40, 60);
    public static final int STRUCTURE_SIZE = 32;
    /** 3×3 网格，共约 29.5 万透明方块 */
    public static final int STRUCTURE_GRID = 3;
    /** 相邻立方体中心间距（格） */
    public static final int STRUCTURE_SPACING = 48;
    /** 顶部水层厚度（透光） */
    public static final int STRUCTURE_WATER_TOP = 5;

    // 猪圈：中心 (0, Y_GROUND, -100)，5×6 栅栏 2 格高；观察点 (0, Y_GROUND+12, -116)（距中心 16 格）
    public static final BlockPos PEN_CENTER = new BlockPos(0, Y_GROUND, -100);
    public static final BlockPos PEN_VIEW = new BlockPos(0, Y_GROUND + 12, -116);
    public static final int PIG_COUNT = 300;

    // 粒子观察点 (0, Y_GROUND+30, 150)，面向 +z（粒子在其前方 60 格，散布 40×40×40）
    public static final BlockPos PARTICLE_VIEW_POS = new BlockPos(0, Y_GROUND + 30, 150);
    public static final int PARTICLE_COUNT_PER_TICK = 1000;
    public static final double PARTICLE_DISTANCE = 60;
    public static final double PARTICLE_SPREAD = 40;

    // TNT 场：中心 (0, Y_GROUND, 300)；观察点 (0, Y_GROUND+32, 248)
    public static final BlockPos TNT_CENTER = new BlockPos(0, Y_GROUND, 300);
    public static final BlockPos TNT_VIEW = new BlockPos(0, Y_GROUND + 32, 248);
    public static final int TNT_COUNT = 1000;
    public static final int TNT_FUSE = 80;

    // 快测跌落：平台 y = Y_GROUND+90，猪在平台上；观察点 (0, Y_GROUND+20, 60) 面向 +z
    public static final int FALL_PLATFORM_Y = Y_GROUND + 90;
    public static final BlockPos FALL_VIEW = new BlockPos(0, Y_GROUND + 20, 60);
    public static final BlockPos FALL_AREA = new BlockPos(0, FALL_PLATFORM_Y, 100);

    private static final BlockState[] TRANSPARENT_BLOCKS = {
            Blocks.GLASS.defaultBlockState(),
            Blocks.WHITE_STAINED_GLASS.defaultBlockState(),
            Blocks.MAGENTA_STAINED_GLASS.defaultBlockState(),
            Blocks.ORANGE_STAINED_GLASS.defaultBlockState(),
            Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),
            Blocks.ICE.defaultBlockState(),
            Blocks.PACKED_ICE.defaultBlockState(),
            Blocks.SLIME_BLOCK.defaultBlockState(),
            Blocks.HONEY_BLOCK.defaultBlockState(),
    };

    private static final long FIXED_SEED_STRUCTURE = 1234567L;

    private SceneBuilder() {
    }

    /** 在测试区域铺设一块平坦平台（用于普通主世界远端测试，模拟超平坦环境）。 */
    public static void prepareFlatArea(ServerLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = testY;
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                pos.set(x, y, z);
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                pos.set(x, y + 1, z);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /**
     * 建造远距离透明方块群：3×3 个 32×32×32 实心透明立方体（约 29.5 万方块）。
     * 立方体内部也填透明方块，从远处看会形成大量透明层叠/Overdraw，
     * 比旧版“站在建筑内部”更考验透明混合排序。
     * 顶部 STRUCTURE_WATER_TOP 层为水（阳光透水照入）。
     * 材质：普通/彩色玻璃、玻璃板、含水玻璃板、水、冰、黏液块、蜂蜜块。
     */
    public static long buildTransparentStructure(ServerLevel level) {
        long start = System.nanoTime();
        BlockPos c = STRUCTURE_CENTER;
        int s = STRUCTURE_SIZE;
        int half = s / 2;
        int gridHalf = STRUCTURE_GRID / 2;
        Random rnd = new Random(FIXED_SEED_STRUCTURE);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int gx = -gridHalf; gx <= gridHalf; gx++) {
            for (int gz = -gridHalf; gz <= gridHalf; gz++) {
                int baseX = c.getX() + gx * STRUCTURE_SPACING - half;
                int baseY = c.getY() - half;
                int baseZ = c.getZ() + gz * STRUCTURE_SPACING - half;
                for (int x = 0; x < s; x++) {
                    for (int y = 0; y < s; y++) {
                        for (int z = 0; z < s; z++) {
                            pos.set(baseX + x, baseY + y, baseZ + z);
                            // 顶部水层（透光）：最上方 STRUCTURE_WATER_TOP 层全为水
                            if (y >= s - STRUCTURE_WATER_TOP) {
                                level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                                continue;
                            }

                            boolean surface = x == 0 || y == 0 || z == 0 || x == s - 1 || y == s - 1 || z == s - 1;
                            BlockState state;
                            if (surface) {
                                state = TRANSPARENT_BLOCKS[rnd.nextInt(TRANSPARENT_BLOCKS.length)];
                            } else {
                                int idx = (x * 7 + y * 3 + z * 5) % 10;
                                state = switch (idx) {
                                    case 0 -> Blocks.WATER.defaultBlockState();                    // 水（内部，不会流动）
                                    case 1 -> Blocks.GLASS_PANE.defaultBlockState()
                                            .setValue(BlockStateProperties.WATERLOGGED, true);      // 含水玻璃板
                                    case 2 -> Blocks.GLASS_PANE.defaultBlockState();                // 玻璃板
                                    case 3, 4 -> Blocks.GLASS.defaultBlockState();
                                    case 5 -> Blocks.BLUE_STAINED_GLASS.defaultBlockState();
                                    case 6 -> Blocks.ICE.defaultBlockState();
                                    case 7 -> Blocks.SLIME_BLOCK.defaultBlockState();
                                    case 8 -> Blocks.HONEY_BLOCK.defaultBlockState();
                                    default -> Blocks.PACKED_ICE.defaultBlockState();
                                };
                            }
                            level.setBlock(pos, state, 3);
                        }
                    }
                }
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** 拆除透明方块群（全部置为空气）。 */
    public static void removeTransparentStructure(ServerLevel level) {
        BlockPos c = STRUCTURE_CENTER;
        int s = STRUCTURE_SIZE;
        int half = s / 2;
        int gridHalf = STRUCTURE_GRID / 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int gx = -gridHalf; gx <= gridHalf; gx++) {
            for (int gz = -gridHalf; gz <= gridHalf; gz++) {
                int baseX = c.getX() + gx * STRUCTURE_SPACING - half;
                int baseY = c.getY() - half;
                int baseZ = c.getZ() + gz * STRUCTURE_SPACING - half;
                for (int x = 0; x < s; x++) {
                    for (int y = 0; y < s; y++) {
                        for (int z = 0; z < s; z++) {
                            pos.set(baseX + x, baseY + y, baseZ + z);
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }

    /** 建造 5×6 栅栏圈并放入 300 只猪（贴地）。 */
    public static void buildPigPen(ServerLevel level) {
        buildPigPen(level, PIG_COUNT);
    }

    /** 建造 5×6 栅栏圈并放入指定数量猪（贴地）。 */
    public static void buildPigPen(ServerLevel level, int count) {
        BlockPos c = new BlockPos(PEN_CENTER.getX(), testY, PEN_CENTER.getZ());
        // 栅栏墙：2 格高，围出 5×6 内部空间
        for (int x = -3; x <= 3; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) != 3 && Math.abs(z) != 4) continue;
                level.setBlock(c.offset(x, 0, z), Blocks.OAK_FENCE.defaultBlockState(), 3);
                level.setBlock(c.offset(x, 1, z), Blocks.OAK_FENCE.defaultBlockState(), 3);
            }
        }
        // count 只猪：5×6×10 堆叠在圈内
        Random rnd = new Random(42L);
        for (int i = 0; i < count; i++) {
            Entity pig = VersionBridge.createPig(level);
            int dx = i % 5 - 2;
            int layer = (i / 30) % 10;
            int dz = (i / 5) % 6 - 2;
            pig.setPos(c.getX() + dx + 0.5, c.getY() + 1 + layer * 0.6, c.getZ() + dz + 0.5);
            pig.setYRot(rnd.nextFloat() * 360f);
            level.addFreshEntity(pig);
        }
    }

    /** 清除猪圈（移除猪与栅栏）。 */
    public static void removePigPen(ServerLevel level) {
        removeEntitiesOfType(level, VersionBridge.pigClass());
        BlockPos c = new BlockPos(PEN_CENTER.getX(), testY, PEN_CENTER.getZ());
        for (int x = -3; x <= 3; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) != 3 && Math.abs(z) != 4) continue;
                level.setBlock(c.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(c.offset(x, 1, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /** 快测：建造高空平台（石英块 25×10），猪站在平台上。 */
    public static void buildFallPlatform(ServerLevel level) {
        BlockPos c = FALL_AREA;
        for (int dx = 0; dx < 25; dx++) {
            for (int dz = 0; dz < 10; dz++) {
                level.setBlock(c.offset(dx, 0, dz), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
            }
        }
    }

    /** 快测：抽掉平台 → 猪自然碰撞后跌落。 */
    public static void removeFallPlatform(ServerLevel level) {
        BlockPos c = FALL_AREA;
        for (int dx = 0; dx < 25; dx++) {
            for (int dz = 0; dz < 10; dz++) {
                level.setBlock(c.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /** 快测：在平台上生成 300 只猪（相互碰撞、部分被挤落）。 */
    public static void spawnPigsOnPlatform(ServerLevel level) {
        Random rnd = new Random(777L);
        for (int i = 0; i < PIG_COUNT; i++) {
            Entity pig = VersionBridge.createPig(level);
            int dx = i % 25;
            int dz = (i / 25) % 10;
            pig.setPos(FALL_AREA.getX() + dx + 0.5, FALL_AREA.getY() + 1 + (i % 4) * 0.5, FALL_AREA.getZ() + dz + 0.5);
            pig.setYRot(rnd.nextFloat() * 360f);
            level.addFreshEntity(pig);
        }
    }

    /** 寻路压力：生成 count 只僵尸并让它们向远处目标寻路。 */
    public static void buildPathfindingStress(ServerLevel level, int count) {
        removeAllEntities(level);
        Random rnd = new Random(888L);
        BlockPos center = new BlockPos(0, testY, 0);
        for (int i = 0; i < count; i++) {
            Entity z = VersionBridge.createZombie(level);
            int dx = (i % 10) - 5;
            int dz = (i / 10) % 10 - 5;
            z.setPos(center.getX() + dx + 0.5, testY + 1, center.getZ() + dz + 0.5);
            z.setYRot(rnd.nextFloat() * 360f);
            level.addFreshEntity(z);
            if (z instanceof net.minecraft.world.entity.Mob mob) {
                mob.getNavigation().moveTo(center.getX() + 40.0, testY + 1, center.getZ() + 40.0, 1.0);
            }
        }
    }

    /** 50 玩家模拟（简化版）：生成 count 只带名字的实体，模拟大量在线实体造成的 TPS/MSPT 压力。 */
    public static void buildPlayerSimulation(ServerLevel level, int count) {
        removeAllEntities(level);
        Random rnd = new Random(999L);
        for (int i = 0; i < count; i++) {
            Entity e = VersionBridge.createPig(level);
            int dx = (i % 10) - 5;
            int dz = (i / 10) % 10 - 5;
            e.setPos(0.5 + dx, testY + 1, 0.5 + dz);
            e.setYRot(rnd.nextFloat() * 360f);
            e.setCustomName(net.minecraft.network.chat.Component.literal("FakePlayer" + i));
            level.addFreshEntity(e);
        }
    }

    /** 方块更新压力：放置 20×20×20 红石灯并触发邻居更新，返回耗时（毫秒）。 */
    public static long buildBlockUpdateStress(ServerLevel level) {
        removeAllEntities(level);
        long start = System.nanoTime();
        BlockPos c = new BlockPos(0, testY + 1, 0);
        BlockState state = Blocks.REDSTONE_LAMP.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 20; z++) {
                    pos.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                    level.setBlock(pos, state, 3);
                }
            }
        }
        for (int i = 0; i < 400; i++) {
            pos.set(c.getX() + (i % 20), c.getY() + ((i / 20) % 20), c.getZ() + 20);
            level.updateNeighborsAt(pos, state.getBlock());
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** 在玩家视野方向 60 格处生成粒子洪流（每 tick 1000 个，40×40×40 散布，多种粒子）。 */
    public static void spawnParticleBarrage(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 base = player.position().add(look.scale(PARTICLE_DISTANCE));
        Random rnd = new Random(System.nanoTime());
        for (int i = 0; i < PARTICLE_COUNT_PER_TICK; i++) {
            double px = base.x + (rnd.nextDouble() - 0.5) * PARTICLE_SPREAD;
            double py = base.y + (rnd.nextDouble() - 0.5) * PARTICLE_SPREAD;
            double pz = base.z + (rnd.nextDouble() - 0.5) * PARTICLE_SPREAD;
            ParticleOptions particle = switch (i & 3) {
                case 0 -> ParticleTypes.FLAME;
                case 1 -> ParticleTypes.END_ROD;
                case 2 -> ParticleTypes.CLOUD;
                default -> ParticleTypes.ELECTRIC_SPARK;
            };
            level.sendParticles(particle, px, py, pz, 1, 0.2, 0.2, 0.2, 0.03);
        }
    }

    /** 生成 1000 个 TNT（25×20×2 网格，贴地、位置固定，统一引信）。 */
    public static void spawnTntField(ServerLevel level) {
        BlockPos c = new BlockPos(TNT_CENTER.getX(), testY, TNT_CENTER.getZ());
        int spawned = 0;
        outer:
        for (int ix = 0; ix < 25; ix++) {
            for (int iz = 0; iz < 20; iz++) {
                for (int iy = 0; iy < 2; iy++) {
                    if (spawned >= TNT_COUNT) break outer;
                    PrimedTnt tnt = new PrimedTnt(EntityType.TNT, level);
                    // 贴地生成（固定位置，不会在空中下落）
                    tnt.setPos(c.getX() + ix * 4.0 + 0.5, c.getY() + 0.5 + iy, c.getZ() + iz * 4.0 + 0.5);
                    tnt.setFuse(TNT_FUSE);
                    level.addFreshEntity(tnt);
                    spawned++;
                }
            }
        }
    }

    /** 清除指定类型的实体。 */
    public static void removeEntitiesOfType(ServerLevel level, Class<? extends Entity> clazz) {
        for (Entity entity : level.getEntitiesOfClass(clazz, HUGE_AABB)) {
            entity.discard();
        }
    }

    /** 清除全部非玩家实体。 */
    public static void removeAllEntities(ServerLevel level) {
        for (Entity entity : level.getEntitiesOfClass(Entity.class, HUGE_AABB)) {
            if (!(entity instanceof net.minecraft.world.entity.player.Player)) {
                entity.discard();
            }
        }
    }

    /** 覆盖测试区域范围的包围盒（避免 Sable 对超大 AABB 报错）。 */
    private static final net.minecraft.world.phys.AABB HUGE_AABB =
            new net.minecraft.world.phys.AABB(-2_000, -100, -2_000, 2_000, 1_000, 2_000);
}
