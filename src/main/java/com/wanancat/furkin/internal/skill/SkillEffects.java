package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.api.skill.FurkinSkillEffectType;
import com.wanancat.furkin.api.skill.FurkinSkillEffectTypeRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.skill.effect.AbilityEffect;
import com.wanancat.furkin.internal.skill.effect.AttributeEffect;
import com.wanancat.furkin.internal.skill.effect.InteractionEffect;
import com.wanancat.furkin.internal.skill.effect.PassiveEffect;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 效果类型 → 实现 的映射（设计稿 §3.2「注册一个效果类型表」）。
 *
 * <p>内置四类效果类型在此注册；第三方可通过
 * {@link com.wanancat.furkin.api.FurkinApi} 注册自己的效果类型 + 实现。</p>
 *
 * <p>类型标识注册在 {@code api.skill} 的开放注册表（{@link FurkinSkillEffectTypeRegistry}），
 * 实现映射在 {@code internal.skill}（含具体 Java 逻辑，属内部件）。</p>
 */
public final class SkillEffects {

    private static final Map<ResourceLocation, SkillEffect> BY_TYPE = new ConcurrentHashMap<>();

    private SkillEffects() {
    }

    /** 注册内置四类效果类型 + 实现（模组初始化时调用）。 */
    public static void registerBuiltin() {
        register("attribute", new AttributeEffect());
        register("ability", new AbilityEffect());
        register("passive", new PassiveEffect());
        register("interaction", new InteractionEffect());
    }

    /** 注册一个效果类型 + 实现（开放给第三方）。 */
    public static void register(String typeId, SkillEffect effect) {
        ResourceLocation id = new ResourceLocation(FurkinMod.MODID, typeId);
        FurkinSkillEffectTypeRegistry.register(new FurkinSkillEffectType(id));
        BY_TYPE.put(id, effect);
    }

    /** 按类型标识查实现。 */
    public static SkillEffect byType(ResourceLocation type) {
        return BY_TYPE.get(type);
    }

    /** 是否已知该效果类型。 */
    public static boolean isKnown(ResourceLocation type) {
        return BY_TYPE.containsKey(type);
    }
}
