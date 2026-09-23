package com.wanancat.furkin.internal.registry;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * 自建 furkin 创造模式标签页（M5 打磨）。
 *
 * <p><b>1.19.2 注册方式</b>：旧式 {@link CreativeModeTab} 没有 Builder，也没有
 * {@code Registries.CREATIVE_MODE_TAB}。Forge 43.2.0 提供的
 * {@link CreativeModeTab#CreativeModeTab(String)} 会通过内部
 * {@code addGroupSafe(-1, this)} 自动把本实例追加到 {@link CreativeModeTab#TABS}。</p>
 *
 * <p>物品构造器通过 {@code Item.Properties.tab(FURKIN_TAB)} 引用本字段，因此会在
 * 物品注册时触发本类初始化；标签页实例只创建一次，无需 DeferredRegister 或事件。</p>
 *
 * <p><b>搜索页</b>：1.19.2 的 {@code Item#allowedIn} 会让设有
 * {@code Item.Properties.tab} 的物品同时进入对应标签页与搜索页，所以不需要手工
 * 覆写 {@code fillItemList}。</p>
 */
public final class ModCreativeTab {

    /** furkin 品牌标签页；构造器自动注册到 {@link CreativeModeTab#TABS}。 */
    public static final CreativeModeTab FURKIN_TAB = new CreativeModeTab("furkin") {
        @Override
        public ItemStack makeIcon() {
            return new ItemStack(ModItems.FURKIN_CONTRACT.get());
        }
    };

    private ModCreativeTab() {
    }
}
