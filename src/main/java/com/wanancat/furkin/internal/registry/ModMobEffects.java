package com.wanancat.furkin.internal.registry;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.effect.BleedingEffect;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 模组 MobEffect 注册表。
 *
 * <p>目前仅注册「流血」（{@code furkin:bleeding}），供流血撕咬等被动技能施加。
 * 原版 1.20.1 无流血效果，故自实现。</p>
 */
public final class ModMobEffects {

    /** MobEffect DeferredRegister。 */
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, FurkinMod.MODID);

    /** 流血：持续掉血，可叠加时长。 */
    public static final RegistryObject<MobEffect> BLEEDING =
            MOB_EFFECTS.register("bleeding", BleedingEffect::new);

    private ModMobEffects() {
    }

    /** 注册到 mod 事件总线。 */
    public static void register(IEventBus modBus) {
        MOB_EFFECTS.register(modBus);
    }
}
