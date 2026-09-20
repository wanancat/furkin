package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.registry.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端初始化 —— 把菜单类型绑到屏幕实现。
 *
 * <p><b>为什么只能用原版 {@code MenuScreens.register}</b>：Forge 1.20.1 的
 * {@code net.minecraftforge.client.event} 包里有 51 个类，<b>没有</b>
 * {@code RegisterMenuScreensEvent} —— 该事件是 1.20.2+ 才加入的。
 * 故这里走 {@link MenuScreens#register}，并且必须放在 {@link FMLClientSetupEvent} 里
 * （屏幕注册表在客户端启动阶段才可用）。</p>
 *
 * <p><b>端位纪律</b>：本类只在 {@code Dist.CLIENT} 加载。</p>
 */
@Mod.EventBusSubscriber(modid = FurkinMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FurkinClientSetup {

    private FurkinClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // enqueueWork：屏幕注册表不是线程安全的，官方要求在客户端主线程里注册。
        event.enqueueWork(() -> MenuScreens.register(
                ModMenus.FURKIN_POUCH.get(), FurkinPanelScreen::new));
    }
}
