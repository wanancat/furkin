package com.wanancat.furkin.internal.registry;

import com.wanancat.furkin.api.FurkinApi;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * 内置物种注册 —— 猫狗（设计稿 §3.1、§4）。
 *
 * <p><b>关键约束（M1 锚点）</b>：猫狗走与第三方完全相同的公开 API 路径注册，
 * 全库不得出现 {@code instanceof Cat / Wolf}。本类只用 {@link EntityType#CAT}、
 * {@link EntityType#WOLF} 的注册表常量，不用具体类判断。</p>
 */
public final class BuiltinSpecies {

    private BuiltinSpecies() {
    }

    /** 在模组初始化时调用，注册猫、狗两个内置物种。 */
    public static void register() {
        FurkinApi.registerSpecies(
                new ResourceLocation(FurkinMod.MODID, "cat"),
                EntityType.CAT,
                "furkin.species.cat");

        FurkinApi.registerSpecies(
                new ResourceLocation(FurkinMod.MODID, "dog"),
                EntityType.WOLF,
                "furkin.species.dog");
    }
}
