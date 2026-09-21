package com.wanancat.furkin.internal.equipment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 装备准入判据 —— 本模组「什么物品能进哪个装备槽」的<b>唯一出口</b>。
 *
 * <p><b>为什么只用 {@link LivingEntity#getEquipmentSlotForItem} 一个函数</b>
 * （2026-09-22 javap 取证）：</p>
 * <ul>
 *   <li>它是 {@code static} 的 —— <b>不需要实体</b>。这一点是决定性的：客户端面板上
 *       根本没有实体引用（槽位内容靠容器同步灌进来），若判据需要实体，客户端就只能
 *       「乐观放行」，于是玩家把头盔拖到胸槽时客户端先摆上去、服务端再拒掉，
 *       物品弹回来。双端同源才能避免这种错位。</li>
 *   <li>它内部已封好两级规则：先取 {@code stack.getEquipmentSlot()}（Forge 的
 *       {@code IForgeItemStack} 默认实现，第三方模组可覆写），为 {@code null} 再退回
 *       {@code Equipable}（原版鞘翅走这条），都不匹配才落到 {@code MAINHAND} ——
 *       于是「不可穿戴的物品」自然得到 {@code MAINHAND}，与四个盔甲槽天然不等。</li>
 * </ul>
 *
 * <p><b>为什么不额外叠加 Forge 的 {@code stack.canEquip(slot, entity)}</b>：那个 default
 * 需要实体，客户端用不了（同第一条），而服务端加上它就等于双端两套判据。原版静态函数
 * 已覆盖「盔甲 + 鞘翅 + 模组自定义槽位」的全部情形，够用。</p>
 *
 * <p><b>未采信的写法</b>：设计稿初稿写的 {@code ItemStack#canEquip(slot, entity)} 在
 * 1.20.1 <b>不存在</b>（该实例方法没有内联到 {@code ItemStack}；Forge 侧只有
 * {@code IForgeItemStack#canEquip} 扩展）—— 以本条取证为准。</p>
 */
public final class EquipValidator {

    private EquipValidator() {
    }

    /**
     * 该物品能否放进这个槽位。
     *
     * <p>空栈恒放行 —— 「清空槽位」在任何槽位上都必须允许，否则玩家把物品拖出去的操作
     * 会被自己挡掉。</p>
     */
    public static boolean fits(EquipmentSlot slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        return LivingEntity.getEquipmentSlotForItem(stack) == slot;
    }
}
