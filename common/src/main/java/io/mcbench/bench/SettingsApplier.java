package io.mcbench.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.mcbench.McBench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一设置：跑分前把画面参数统一，跑分后恢复原样。
 *
 * 1) 原版选项（所有加载器/版本一致）：
 *    极佳画质 / 渲染距离12 / 模拟距离8 / 垂直同步关 / 帧数上限关 / 粒子完全 / 失焦不暂停
 * 2) 常见优化模组的配置文件（文件级，尽力而为，存在才改，改前备份）：
 *    - Sodium/Embeddium: config/sodium-options.json、config/embeddium-options.json
 *    - Iris/Oculus:      config/iris.properties、config/oculus.properties（清空光影包）
 *    - Distant Horizons: config/DistantHorizons.toml（尝试关闭渲染）
 *    - FPS 减速器 / Dynamic FPS: config/fpsreducer*.toml、dynamic_fps.json（禁用自动降帧）
 *    - BetterFPS Dist: config/betterfpsdist.json（关闭实体距离缩放）
 * 3) 渲染分辨率由 ResolutionLock 单独处理。
 *
 * 所有变更记录到跑分日志（规则透明），结束后恢复。
 */
public final class SettingsApplier {

    /** 原版选项快照 */
    public static class Snapshot {
        public Object graphicsState;
        public int renderDistance;
        public int simulationDistance;
        public boolean vsync;
        public int framerateLimit;
        public Object particlesState;
        public boolean pauseOnLostFocus;
        public final List<Path> backedUpFiles = new ArrayList<>();
        public final List<String> changed = new ArrayList<>();

        public void record(String what) {
            changed.add(what);
        }
    }

    private SettingsApplier() {
    }

    public static Snapshot apply(boolean strictV2) {
        Snapshot s = new Snapshot();
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;

        s.graphicsState = VersionBridge.captureGraphics(o);
        s.renderDistance = o.renderDistance().get();
        s.simulationDistance = o.simulationDistance().get();
        s.vsync = o.enableVsync().get();
        s.framerateLimit = o.framerateLimit().get();
        s.particlesState = VersionBridge.captureParticles(o);
        s.pauseOnLostFocus = o.pauseOnLostFocus;

        VersionBridge.applyFancyGraphics(o);
        o.renderDistance().set(McBench.RENDER_DISTANCE);
        o.simulationDistance().set(McBench.SIMULATION_DISTANCE);
        o.enableVsync().set(false);
        // 帧率上限：1.21.1 合法范围 10..260，260 为最大值（≈无限制；0 会触发 Illegal option value）
        o.framerateLimit().set(260);
        VersionBridge.applyParticlesAll(o);
        o.pauseOnLostFocus = false;
        o.save();
        McBench.LOGGER.info("[MCBench] 设置统一完成: 帧率上限={} vsync={} 渲染距离={} 粒子={} 画质={} window帧率字段={}",
                o.framerateLimit().get(), o.enableVsync().get(), o.renderDistance().get(),
                VersionBridge.particleStatusName(o), VersionBridge.graphicsStatusName(o),
                VersionBridge.framerateLimit(Minecraft.getInstance()));

        s.record("极佳画质 / 渲染距离12 / 模拟距离8 / 垂直同步关 / 帧数上限关 / 粒子完全 / 失焦不暂停");

        Path configDir = mc.gameDirectory.toPath().resolve("config");

        applySodiumLike(configDir, "sodium-options.json", s);
        applySodiumLike(configDir, "embeddium-options.json", s);
        applyIrisLike(configDir, "iris.properties", s);
        applyIrisLike(configDir, "oculus.properties", s);
        applyDistantHorizons(configDir, s);
        applyFpsReducer(configDir, s);
        applyEntityDistanceCulling(configDir, s);

        if (strictV2) {
            applyV2Strict(configDir, s);
        }

        return s;
    }

    public static void restore(Snapshot s) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        try {
            VersionBridge.restoreGraphics(o, s.graphicsState);
            o.renderDistance().set(s.renderDistance);
            o.simulationDistance().set(s.simulationDistance);
            o.enableVsync().set(s.vsync);
            o.framerateLimit().set(s.framerateLimit);
            VersionBridge.restoreParticles(o, s.particlesState);
            o.pauseOnLostFocus = s.pauseOnLostFocus;
            o.save();
        } catch (Exception e) {
            McBench.LOGGER.warn("[MCBench] 恢复原版选项失败: {}", e.toString());
        }
        for (Path backup : s.backedUpFiles) {
            try {
                Path target = backup.resolveSibling(backup.getFileName().toString()
                        .replace(".mcbench-backup", ""));
                Files.move(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                McBench.LOGGER.warn("[MCBench] 恢复配置文件失败 {}: {}", backup, e.toString());
            }
        }
    }

    /** Sodium/Embeddium：合并写回 quality/advanced 关键项。 */
    private static void applySodiumLike(Path configDir, String fileName, Snapshot s) {
        Path file = configDir.resolve(fileName);
        if (!Files.exists(file)) {
            s.record(fileName + " 不存在，跳过（未安装或未生成配置）");
            return;
        }
        try {
            backup(file, s);
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject quality = ensureObject(root, "quality");
            quality.addProperty("graphicsQuality", "FANCY");
            quality.addProperty("cloudsQuality", "FANCY");
            quality.addProperty("weatherQuality", "FANCY");
            quality.addProperty("leavesQuality", "FANCY");
            quality.addProperty("enableVignette", false);
            JsonObject advanced = ensureObject(root, "advanced");
            advanced.addProperty("alwaysDeferChunkUpdates", true);
            advanced.addProperty("animateOnlyVisibleTextures", false);
            advanced.addProperty("useAdvancedBlockCulling", true);
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
            s.record(fileName + " 已统一（画质FANCY/延迟区块更新/关闭仅渲染可见纹理动画）");
        } catch (Exception e) {
            s.record(fileName + " 修改失败（保留原样）: " + e);
        }
    }

    /** Iris/Oculus：清空光影包（等价于关闭光影）。 */
    private static void applyIrisLike(Path configDir, String fileName, Snapshot s) {
        Path file = configDir.resolve(fileName);
        if (!Files.exists(file)) {
            s.record(fileName + " 不存在，跳过");
            return;
        }
        try {
            backup(file, s);
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("shaderPack=")) {
                    lines.set(i, "shaderPack=");
                    touched = true;
                }
            }
            if (!touched) lines.add("shaderPack=");
            Files.write(file, lines, StandardCharsets.UTF_8);
            s.record(fileName + " 已清空光影包（光影已关闭）");
        } catch (Exception e) {
            s.record(fileName + " 修改失败（保留原样）: " + e);
        }
    }

    /** Distant Horizons：尝试关闭渲染（尽力而为）。 */
    private static void applyDistantHorizons(Path configDir, Snapshot s) {
        Path file = configDir.resolve("DistantHorizons.toml");
        if (!Files.exists(file)) {
            s.record("DistantHorizons.toml 不存在，跳过（未安装 DH 或尚未生成配置）");
            return;
        }
        try {
            backup(file, s);
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.matches("enabled\\s*=\\s*true.*") || trimmed.matches("enabled\\s*=\\s*false.*")) {
                    lines.set(i, line.replaceFirst("(?i)(enabled\\s*=\\s*)(true|false)", "$1false"));
                    touched = true;
                }
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            s.record(touched
                    ? "DistantHorizons.toml 已关闭 enabled（遥远的地平线渲染已关闭）"
                    : "DistantHorizons.toml 未找到 enabled 键，请手动关闭 DH 渲染");
        } catch (Exception e) {
            s.record("DistantHorizons.toml 修改失败: " + e);
        }
    }

    /** FPS 减速器 / Dynamic FPS：跑分期间强制关闭自动降帧，防止采样帧率被限流。 */
    private static void applyFpsReducer(Path configDir, Snapshot s) {
        String[] candidates = {
                "fpsreducer.toml", "fps-reducer.toml", "FpsReducer.toml", "fpsReducer.toml",
                "fpsreducer.json", "fps-reducer.json",
                "dynamic_fps.json", "dynamicfps.json"
        };
        boolean foundAny = false;
        for (String fileName : candidates) {
            Path file = configDir.resolve(fileName);
            if (!Files.exists(file)) continue;
            foundAny = true;
            try {
                backup(file, s);
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String original = content;
                // 同时兼容 TOML（key = true）与 JSON（"key": true）
                content = content.replaceAll("(?i)(enabled\\s*=\\s*)(true|1)", "$1false");
                content = content.replaceAll("(?i)(reduceFpsWhenIdle\\s*=\\s*)(true|1)", "$1false");
                content = content.replaceAll("(?i)(reduceFpsWhenUnfocused\\s*=\\s*)(true|1)", "$1false");
                content = content.replaceAll("(?i)(reduceFpsWhenHovering\\s*=\\s*)(true|1)", "$1false");
                content = content.replaceAll("(?i)(\"enabled\"\\s*:\\s*)(true)", "$1false");
                content = content.replaceAll("(?i)(\"reduceFpsWhenIdle\"\\s*:\\s*)(true)", "$1false");
                content = content.replaceAll("(?i)(\"reduceFpsWhenUnfocused\"\\s*:\\s*)(true)", "$1false");
                content = content.replaceAll("(?i)(\"reduceFpsWhenHovering\"\\s*:\\s*)(true)", "$1false");
                if (content.equals(original)) {
                    s.record(fileName + " 已备份但未发现可关闭的 FPS 减速项（请手动关闭 FPS 减速）");
                } else {
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                    s.record(fileName + " 已禁用 FPS 减速（enabled/reduceFpsWhen* → false）");
                }
            } catch (Exception e) {
                s.record(fileName + " 修改失败（保留原样）: " + e);
            }
        }
        if (!foundAny) {
            s.record("未找到 FPS 减速器配置（fpsreducer/dynamic_fps），跳过");
        }
    }

    /** BetterFPS Dist：关闭实体距离缩放，避免整合包因实体剔除/距离缩放少渲染远处生物。 */
    private static void applyEntityDistanceCulling(Path configDir, Snapshot s) {
        Path file = configDir.resolve("betterfpsdist.json");
        if (!Files.exists(file)) {
            s.record("betterfpsdist.json 不存在，跳过（未安装 BetterFPS Dist 或尚未生成配置）");
            return;
        }
        try {
            backup(file, s);
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                // 兼容 "affectEntities": true / true, / true  等 JSON 写法（该配置带注释，不能直接用 Gson 解析）
                if (trimmed.matches("\"affectEntities\"\\s*:\\s*true.*")) {
                    lines.set(i, line.replaceFirst("(\"affectEntities\"\\s*:\\s*)true", "$1false"));
                    touched = true;
                }
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            s.record(touched
                    ? "betterfpsdist.json 已关闭 affectEntities（实体距离缩放已关闭）"
                    : "betterfpsdist.json 未发现 affectEntities=true，跳过");
        } catch (Exception e) {
            s.record("betterfpsdist.json 修改失败（保留原样）: " + e);
        }
    }

    /**
     * 2.0 客户端额外处理：彻底统一画质。
     *
     * 运行 2.0 跑分时临时禁用以下可能让跑分虚高的优化/外观差异：
     *  - options.txt：AO、群系混合、实体阴影、伽马、资源包
     *  - 各类优化模组：Sodium Extra、BetterFPSDist、ImmersiveOptimization、Flerovium、CullLeaves、EMF/ETF
     * 结束后由 restore() 统一还原备份。
     */
    private static void applyV2Strict(Path configDir, Snapshot s) {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        applyOptionsTxt(gameDir, s);

        // 常见优化/外观模组配置文件（尽力而为：存在才改，改前备份）
        applyLineToggle(configDir.resolve("sodium-extra-options.json"), s,
                new String[]{
                        "(\"animation_is_on\"\\s*:\\s*)(true)",
                        "(\"particle_is_on\"\\s*:\\s*)(true)"
                }, "SodiumExtra 已关闭动画/粒子选项（避免粒子类场景被裁减）");
        applyLineToggle(configDir.resolve("betterfpsdist.json"), s,
                new String[]{"(\"affectEntities\"\\s*:\\s*)(true)"}, "BetterFPSDist 已关闭 affectEntities");
        applyLineToggle(configDir.resolve("immersive_optimization.toml"), s,
                new String[]{
                        "(enableDistanceCulling\\s*=\\s*)(true)",
                        "(enableTrackingCulling\\s*=\\s*)(true)",
                        "(enableEntityCulling\\s*=\\s*)(true)"
                }, "ImmersiveOptimization 已关闭距离/追踪/实体剔除");
        applyLineToggle(configDir.resolve("immersiveoptimization.toml"), s,
                new String[]{
                        "(enableDistanceCulling\\s*=\\s*)(true)",
                        "(enableTrackingCulling\\s*=\\s*)(true)",
                        "(enableEntityCulling\\s*=\\s*)(true)"
                }, "ImmersiveOptimization(小写) 已关闭距离/追踪/实体剔除");
        // Flerovium 按用户反馈视为优化的一部分，2.0 暂不临时禁用（下个版本再评估/更正）
        applyLineToggle(configDir.resolve("cullleaves.toml"), s,
                new String[]{"(enabled\\s*=\\s*)(true)"}, "CullLeaves 已关闭");
        applyLineToggle(configDir.resolve("entity-model-features.toml"), s,
                new String[]{"(enabled\\s*=\\s*)(true)"}, "EMF 已临时关闭");
        applyLineToggle(configDir.resolve("entity_texture_features.toml"), s,
                new String[]{"(enabled\\s*=\\s*)(true)"}, "ETF 已临时关闭");
    }

    /** 修改 options.txt：AO=0、群系混合=0、实体阴影=false、伽马=0、只留原版资源包。 */
    private static void applyOptionsTxt(Path gameDir, Snapshot s) {
        Path file = gameDir.resolve("options.txt");
        if (!Files.exists(file)) {
            s.record("options.txt 不存在，跳过 2.0 画质统一");
            return;
        }
        try {
            backup(file, s);
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("ao:")) {
                    lines.set(i, "ao:0");
                    touched = true;
                } else if (lower.startsWith("biomeblendradius:")) {
                    lines.set(i, "biomeBlendRadius:0");
                    touched = true;
                } else if (lower.startsWith("entityshadows:")) {
                    lines.set(i, "entityShadows:false");
                    touched = true;
                } else if (lower.startsWith("gamma:")) {
                    lines.set(i, "gamma:0.0");
                    touched = true;
                } else if (lower.startsWith("resourcepacks:")) {
                    lines.set(i, "resourcePacks:[\"vanilla\"]");
                    touched = true;
                }
            }
            if (touched) {
                Files.write(file, lines, StandardCharsets.UTF_8);
                s.record("options.txt 已统一 2.0 画质（AO/群系混合/实体阴影/伽马/资源包）");
            } else {
                s.record("options.txt 未找到需要修改的 2.0 画质键，跳过");
            }
        } catch (Exception e) {
            s.record("options.txt 修改失败（保留原样）: " + e);
        }
    }

    /**
     * 通用配置文件替换：把匹配到的 "xxx:true" 替换为 false / 0。
     * 仅处理文本行，兼容 JSON/TOML 常见写法，不解析完整格式。
     */
    private static void applyLineToggle(Path file, Snapshot s, String[] patterns, String successMsg) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            backup(file, s);
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (String pattern : patterns) {
                    if (line.matches(".*" + pattern + ".*")) {
                        lines.set(i, line.replaceFirst(pattern, "$1false"));
                        touched = true;
                        break;
                    }
                }
            }
            if (touched) {
                Files.write(file, lines, StandardCharsets.UTF_8);
                s.record(successMsg);
            }
        } catch (Exception e) {
            s.record(file.getFileName() + " 修改失败（保留原样）: " + e);
        }
    }

    private static void backup(Path file, Snapshot s) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".mcbench-backup");
        Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        s.backedUpFiles.add(backup);
    }

    private static JsonObject ensureObject(JsonObject root, String key) {
        JsonElement el = root.get(key);
        if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        JsonObject obj = new JsonObject();
        root.add(key, obj);
        return obj;
    }
}
