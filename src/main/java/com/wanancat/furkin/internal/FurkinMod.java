package com.wanancat.furkin.internal;

import com.mojang.logging.LogUtils;
import com.wanancat.furkin.internal.config.FurkinClientConfig;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * furkin（绒亲）主类 —— 轻量化伴侣宠物框架。
 *
 * <p>M0 骨架：只承担 {@code @Mod} 入口与两份配置的注册。
 * 具体功能（契约 / 技能 / 装备 / 复活）自 M1 起按包追加。</p>
 *
 * <p><b>端位纪律</b>：本类位于 {@code internal}，属逻辑端共享代码；
 * 不得引用 {@code net.minecraft.client} 下的任何类。</p>
 */
@Mod(FurkinMod.MODID)
public class FurkinMod {

    /** 模组标识符，必须与 {@code META-INF/mods.toml} 中的 modId 一致。 */
    public static final String MODID = "furkin";

    /** 模组日志器，统一使用 slf4j。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    public FurkinMod() {
        // 数值走 SERVER 类型 TOML（世界级，进服自动同步）—— 设计稿 §5 判据二。
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, FurkinServerConfig.SPEC);
        // 客户端项走 CLIENT 类型 TOML —— 设计稿 §5 判据三。
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FurkinClientConfig.SPEC);
    }
}
