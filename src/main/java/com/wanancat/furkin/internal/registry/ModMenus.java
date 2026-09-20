package com.wanancat.furkin.internal.registry;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.menu.FurkinPouchMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 菜单注册表（设计稿 §6 registry\ 包；批 2 第 3 步新增）。
 *
 * <p>绒亲面板（技能 / 行囊 / 装备）是一个 {@code AbstractContainerScreen} ——
 * 槽位语义只落在行囊区，故菜单类型按「行囊」命名（{@code furkin:pouch}）。</p>
 *
 * <p><b>为什么必须带额外数据</b>：官方 {@code MenuType.MenuSupplier} 只有
 * {@code (windowId, Inventory)} 两个参数，客户端拿不到行囊格数。行囊格数是
 * 「config × travel_pouch 等级」的派生值，客户端不同步技能等级，故必须随开屏包下发。
 * Forge 的 {@link IForgeMenuType#create} 正好多一个 {@code FriendlyByteBuf}，
 * 这是官方给「菜单需要自定义初始化数据」留的正路。</p>
 */
public final class ModMenus {

    /** 菜单类型 DeferredRegister。 */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, FurkinMod.MODID);

    /** 绒亲面板（行囊槽位 + 玩家背包槽位）。 */
    public static final RegistryObject<MenuType<FurkinPouchMenu>> FURKIN_POUCH =
            MENUS.register("pouch", () -> IForgeMenuType.create(FurkinPouchMenu::fromNetwork));

    private ModMenus() {
    }

    /** 注册到 mod 事件总线。 */
    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
