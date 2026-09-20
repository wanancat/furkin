package com.wanancat.furkin.api.skill;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能效果类型注册表 —— 效果类型的开放集合（设计稿 §4）。
 *
 * <p>内置四类在 {@link com.wanancat.furkin.internal.skill.SkillEffects} 注册；
 * 第三方通过 {@link com.wanancat.furkin.api.FurkinApi} 暴露的静态方法注册自己的效果类型。</p>
 */
public final class FurkinSkillEffectTypeRegistry {

    private static final Map<ResourceLocation, FurkinSkillEffectType> BY_ID = new ConcurrentHashMap<>();

    private FurkinSkillEffectTypeRegistry() {
    }

    /** 注册一个效果类型（幂等：重复注册覆盖）。 */
    public static FurkinSkillEffectType register(FurkinSkillEffectType type) {
        BY_ID.put(type.getId(), type);
        return type;
    }

    /** 按标识查询。 */
    public static Optional<FurkinSkillEffectType> byId(ResourceLocation id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** 判断某标识是否已注册为效果类型。 */
    public static boolean isRegistered(ResourceLocation id) {
        return BY_ID.containsKey(id);
    }
}
