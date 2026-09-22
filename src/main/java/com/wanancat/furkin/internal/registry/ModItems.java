package com.wanancat.furkin.internal.registry;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.item.FurkinContractItem;
import com.wanancat.furkin.internal.item.FurkinRecordItem;
import com.wanancat.furkin.internal.item.FurkinRespecPotionItem;
import com.wanancat.furkin.internal.item.FurkinSoulstoneItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册表（设计稿 §6 registry\ 包）。
 *
 * <p>M1 阶段仅注册「绒亲契约」。绒亲录（{@code FurkinRecordItem}）、
 * 魂石（{@code FurkinSoulstoneItem}）随对应里程碑追加。</p>
 */
public final class ModItems {

    /** 物品 DeferredRegister。 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, FurkinMod.MODID);

    /** 绒亲契约（契约道具）。 */
    public static final RegistryObject<Item> FURKIN_CONTRACT =
            ITEMS.register("contract", FurkinContractItem::new);

    /** 绒亲录（打开档案的入口，召唤正式入口）。 */
    public static final RegistryObject<Item> FURKIN_RECORD =
            ITEMS.register("record", FurkinRecordItem::new);

    /** 洗点药水（洗点道具，合成：契约 + 水瓶）。 */
    public static final RegistryObject<Item> FURKIN_RESPEC_POTION =
            ITEMS.register("respec_potion", FurkinRespecPotionItem::new);

    /** 魂石（复活钥匙，M4）。死亡掉落，防火不掉岩浆。 */
    public static final RegistryObject<Item> FURKIN_SOULSTONE =
            ITEMS.register("soulstone", FurkinSoulstoneItem::new);

    private ModItems() {
    }

    /** 注册到 mod 事件总线。 */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
