package com.wanancat.furkin.internal;

import com.mojang.logging.LogUtils;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.config.FurkinClientConfig;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.registry.BuiltinSpecies;
import com.wanancat.furkin.internal.registry.ModCreativeTab;
import com.wanancat.furkin.internal.registry.ModItems;
import com.wanancat.furkin.internal.registry.ModMenus;
import com.wanancat.furkin.internal.registry.ModMobEffects;
import com.wanancat.furkin.internal.skill.SkillEffects;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * furkin（绒亲）主类 —— 轻量化伴侣宠物框架。
 *
 * <p>M1：在 M0 骨架之上，注册物品、能力、内置物种。</p>
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
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 物品注册。
        ModItems.register(modBus);

        // 创造模式标签页（M5 自建 furkin 品牌 tab，解决创造搜索搜不到）。
        ModCreativeTab.register(modBus);

        // MobEffect 注册（流血等）。
        ModMobEffects.register(modBus);

        // 菜单注册（绒亲面板 —— 行囊容器）。
        ModMenus.register(modBus);

        // 能力注册。
        modBus.addListener(this::onRegisterCapabilities);

        // 通用设置（内置物种注册等）。
        modBus.addListener(this::onCommonSetup);

        // 网络通道（能力数据服务端 → 客户端同步，供头顶图标等客户端表现读取）。
        FurkinNetwork.register();

        // 数值走 SERVER 类型 TOML（世界级，进服自动同步）—— 设计稿 §5 判据二。
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, FurkinServerConfig.SPEC);
        // 客户端项走 CLIENT 类型 TOML —— 设计稿 §5 判据三。
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FurkinClientConfig.SPEC);
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        FurkinCapability.register(event);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BuiltinSpecies.register();
            // 注册内置四类效果类型（attribute / ability / passive / interaction）。
            SkillEffects.registerBuiltin();
        });
        LOGGER.info("Furkin builtin species registered.");
    }
}
