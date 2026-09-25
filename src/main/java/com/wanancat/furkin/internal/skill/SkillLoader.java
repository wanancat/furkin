package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能加载器 —— 读 {@code data/furkin/skills/*.json} 填充 {@link SkillTree}（设计稿 §3.2）。
 *
 * <p>用 Forge 的 {@link ResourceManager} 扫描技能目录（双端可见的数据包资源），
 * Gson 解析每个 JSON 为 {@link Skill}，挂到 {@link SkillTree}。</p>
 */
public final class SkillLoader {

    /** 技能定义目录。 */
    private static final String SKILLS_DIR = "skills";

    private SkillLoader() {
    }

    /**
     * 从资源管理器加载全部技能，填充到给定树。
     * 单个文件解析失败只打日志跳过，不拖垮整体加载。
     */
    public static void load(ResourceManager manager, SkillTree tree) {
        Map<ResourceLocation, Resource> resources = manager.listResources(
                SKILLS_DIR, loc -> loc.getPath().endsWith(".json"));

        Map<ResourceLocation, Skill> candidates = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation location = entry.getKey();
            try (InputStream in = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                Skill skill = parseSkill(location, root);
                if (skill != null) {
                    candidates.put(skill.getId(), skill);
                }
            } catch (Exception e) {
                FurkinMod.LOGGER.error("Failed to load skill from {}: {}", location, e.toString());
            }
        }

        rejectBrokenReferences(candidates);
        for (Skill skill : candidates.values()) {
            tree.register(skill);
        }
        FurkinMod.LOGGER.info("Furkin loaded {} skills.", tree.all().size());
    }

    /** 解析单个技能 JSON。 */
    private static Skill parseSkill(ResourceLocation fileLocation, JsonObject root) {
        // 技能 id 优先取 JSON 里的 id 字段，缺省用文件名（去 .json 与 skills/ 前缀）。
        ResourceLocation id;
        if (root.has("id")) {
            id = new ResourceLocation(root.get("id").getAsString());
        } else {
            String path = fileLocation.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.endsWith(".json")) {
                name = name.substring(0, name.length() - 5);
            }
            id = new ResourceLocation(fileLocation.getNamespace(), name);
        }

        String nameKey = root.has("name") ? root.get("name").getAsString()
                : "furkin.skill." + id.getPath();
        String descriptionKey = root.has("description") ? root.get("description").getAsString()
                : nameKey + ".desc";
        int tier = root.has("tier") ? root.get("tier").getAsInt() : 1;
        int maxLevel = root.has("maxLevel") ? root.get("maxLevel").getAsInt() : 1;
        int cost = root.has("cost") ? root.get("cost").getAsInt() : 1;

        if (tier < 1) {
            FurkinMod.LOGGER.warn("Rejecting skill {}: tier must be >= 1 (got {})", fileLocation, tier);
            return null;
        }
        if (maxLevel != -1 && maxLevel < 1) {
            FurkinMod.LOGGER.warn(
                    "Rejecting skill {}: maxLevel must be -1 or >= 1 (got {})", fileLocation, maxLevel);
            return null;
        }
        if (cost <= 0) {
            FurkinMod.LOGGER.warn("Rejecting skill {}: cost must be > 0 (got {})", fileLocation, cost);
            return null;
        }

        List<ResourceLocation> requires = new ArrayList<>();
        if (root.has("requires") && root.get("requires").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("requires")) {
                requires.add(new ResourceLocation(e.getAsString()));
            }
        }

        Map<ResourceLocation, Integer> requiresLevel = new LinkedHashMap<>();
        if (root.has("requiresLevel")) {
            if (!root.get("requiresLevel").isJsonObject()) {
                FurkinMod.LOGGER.warn("Rejecting skill {}: requiresLevel must be an object", fileLocation);
                return null;
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("requiresLevel").entrySet()) {
                int requiredLevel = e.getValue().getAsInt();
                if (requiredLevel <= 0) {
                    FurkinMod.LOGGER.warn(
                            "Rejecting skill {}: requiresLevel[{}] must be > 0 (got {})",
                            fileLocation, e.getKey(), requiredLevel);
                    return null;
                }
                requiresLevel.put(new ResourceLocation(e.getKey()), requiredLevel);
            }
        }

        ResourceLocation levelGate = null;
        if (root.has("levelGate") && !root.get("levelGate").isJsonNull()) {
            levelGate = new ResourceLocation(root.get("levelGate").getAsString());
        }

        List<ResourceLocation> species = new ArrayList<>();
        if (root.has("species") && root.get("species").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("species")) {
                species.add(new ResourceLocation(e.getAsString()));
            }
        }

        List<Skill.SkillEffectSpec> effects = new ArrayList<>();
        if (root.has("effects") && root.get("effects").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("effects");
            for (JsonElement e : arr) {
                JsonObject obj = e.getAsJsonObject();
                ResourceLocation type = new ResourceLocation(obj.get("type").getAsString());
                JsonObject params = obj.has("params") ? obj.getAsJsonObject("params") : new JsonObject();
                effects.add(new Skill.SkillEffectSpec(type, params));
            }
        }

        return new Skill(id, nameKey, descriptionKey, tier, requires, requiresLevel,
                levelGate, maxLevel, cost, effects, species);
    }

    /**
     * 收敛式引用校验：先拒绝直接悬空的技能；引用方若指向已被拒绝的候选，会在下一轮一并被拒绝。
     *
     * <p>{@code maxLevel == -1} 的目标技能不设等级上限，因此
     * {@code requiresLevel <= target.maxLevel} 对该目标不适用。</p>
     */
    private static void rejectBrokenReferences(Map<ResourceLocation, Skill> candidates) {
        boolean changed;
        do {
            changed = false;
            Iterator<Map.Entry<ResourceLocation, Skill>> iterator = candidates.entrySet().iterator();
            while (iterator.hasNext()) {
                Skill skill = iterator.next().getValue();
                String violation = referenceViolation(skill, candidates);
                if (violation != null) {
                    FurkinMod.LOGGER.warn("Rejecting skill {}: {}", skill.getId(), violation);
                    iterator.remove();
                    changed = true;
                }
            }
        } while (changed);
    }

    /** 返回第一条引用完整性错误；无错误时返回 null。 */
    private static String referenceViolation(Skill skill, Map<ResourceLocation, Skill> candidates) {
        for (ResourceLocation required : skill.getRequires()) {
            if (!candidates.containsKey(required)) {
                return "requires references missing skill " + required;
            }
        }
        for (Map.Entry<ResourceLocation, Integer> requirement : skill.getRequiresLevel().entrySet()) {
            ResourceLocation prerequisiteId = requirement.getKey();
            Skill prerequisite = candidates.get(prerequisiteId);
            if (prerequisite == null) {
                return "requiresLevel references missing skill " + prerequisiteId;
            }
            if (!prerequisite.isInfinite() && requirement.getValue() > prerequisite.getMaxLevel()) {
                return "requiresLevel " + requirement.getValue() + " exceeds " + prerequisiteId
                        + " maxLevel " + prerequisite.getMaxLevel();
            }
        }
        ResourceLocation levelGate = skill.getLevelGate();
        if (levelGate != null && !candidates.containsKey(levelGate)) {
            return "levelGate references missing skill " + levelGate;
        }
        return null;
    }
}
