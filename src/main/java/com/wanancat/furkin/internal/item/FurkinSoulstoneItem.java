package com.wanancat.furkin.internal.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 魂石 —— 复活已亡绒亲的「纯钥匙」（设计稿 §3.4 复活）。
 *
 * <p><b>定位</b>：不是附魔、不是代价载体，就是一把钥匙。绒亲死亡时掉落一枚
 * 绑定该宠物身份的魂石；手持魂石在正确结构上「复活仪式」后消耗，把已亡绒亲
 * 从档案按同一身份 UUID 重建（M4.2 落地，复活本体零代价 —— 魂石即代价）。</p>
 *
 * <p><b>NBT</b>：只存 {@code companion_id}（绑定宠物，<b>不存死亡世代号</b>）。
 * 复活的「实体唯一」保证不靠世代号，而是靠触发时对档案条目做
 * {@code isAlive()==false && isSummoned()==false} 的状态校验 —— 复活成功即置
 * {@code alive=true, summoned=true}，手里多余的同宠物旧魂石会在下次右键时撞上
 * {@code isAlive()==true} 直接失效，自然作废（见 M4.2 的 {@code ReviveRitual}）。</p>
 *
 * <p><b>防火</b>：{@code Item.Properties.fireResistant()}，与原版下界合金同一条链 ——
 * {@code ItemEntity#fireImmune()} 判 {@code item.isFireResistant()}，熔岩烧不掉
 * （2026-09-22 javap 取证）。魂石「掉地上、可交易可追踪」，防火是它不掉岩浆的前提。</p>
 */
public class FurkinSoulstoneItem extends Item {

    /** 魂石 NBT 键：绑定的宠物身份 UUID。 */
    public static final String KEY_COMPANION_ID = "companion_id";

    public FurkinSoulstoneItem() {
        // fireResistant：熔岩烧不掉；stacksTo(1)：钥匙逐枚管理，不堆叠。
        super(new Item.Properties().fireResistant().stacksTo(1));
    }

    /**
     * 给一枚空魂石绑定宠物身份。
     *
     * @param stack       魂石物品栈
     * @param companionId 要绑定的绒亲 UUID
     */
    public static void bindCompanion(ItemStack stack, UUID companionId) {
        if (companionId == null) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(KEY_COMPANION_ID, companionId);
    }

    /**
     * 读取魂石绑定的宠物身份（未绑定返回 {@code null}）。
     */
    public static UUID getBoundCompanion(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(KEY_COMPANION_ID)) {
            return null;
        }
        return tag.getUUID(KEY_COMPANION_ID);
    }
}
