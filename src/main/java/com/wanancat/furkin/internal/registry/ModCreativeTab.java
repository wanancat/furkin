package com.wanancat.furkin.internal.registry;

import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 自建 furkin 创造模式标签页（M5 打磨）。
 *
 * <p><b>为什么要自建</b>：1.20.1 的创造搜索树只收「出现在某个 {@code CreativeModeTab}
 * 里的物品」，furkin 四件物品（契约 / 录 / 药水 / 魂石）从 M1 起就没挂任何 tab ⇒ 创造
 * 搜索搜不到。自建品牌 tab 把所有 furkin 物品收进去，一并解决搜索与浏览两个入口。</p>
 *
 * <p><b>API 取证（2026-09-22，javap 反编译 mapped_official 1.20.1）</b>：
 * {@link CreativeModeTab#builder()} → {@code .title(Component)} ·
 * {@code .icon(Supplier<ItemStack>)} · {@code .displayItems(DisplayItemsGenerator)} ·
 * {@code .build()}。其中 {@code DisplayItemsGenerator} 是函数式接口
 * {@code (ItemDisplayParameters, Output) -> void}，{@code Output.accept(ItemLike)}
 * 直接塞物品。</p>
 */
public final class ModCreativeTab {

    /** CreativeModeTab DeferredRegister。 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, FurkinMod.MODID);

    /**
     * furkin 品牌标签页。图标恒定使用契约物品 —— 这不是「暂用」，而是 API 的唯一选择：
     * {@link CreativeModeTab.Builder#icon} 收的是 {@code Supplier<ItemStack>}，
     * 只渲染物品实体，无法接任意 PNG。所以 2026-09-23 定稿的 1024 像素画（=.outputs\logo_clean_1024.png）
     * 用于 CurseForge 头像与 jar 模组图标（mods.toml 的 logoFile），与此处的 tab 图标互不相干，
     * 两者不必也不该统一。旧注释里的「M5 轮换正式图标」属不可兑现的承诺，已于同日订正。
     */
    public static final RegistryObject<CreativeModeTab> FURKIN_TAB = TABS.register("furkin",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.furkin"))
                    .icon(() -> new ItemStack(ModItems.FURKIN_CONTRACT.get()))
                    .displayItems((params, output) -> {
                        // 四件物品全部收进 tab（顺序 = 注册顺序，契约→录→药水→魂石）。
                        output.accept(ModItems.FURKIN_CONTRACT.get());
                        output.accept(ModItems.FURKIN_RECORD.get());
                        output.accept(ModItems.FURKIN_RESPEC_POTION.get());
                        output.accept(ModItems.FURKIN_SOULSTONE.get());
                    })
                    .build());

    private ModCreativeTab() {
    }

    /** 注册到 mod 事件总线。 */
    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
