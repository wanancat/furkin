package com.wanancat.furkin.internal.item;

import com.wanancat.furkin.internal.registry.ModCreativeTab;
import net.minecraft.world.item.Item;

/**
 * 绒亲契约 —— 契约道具（设计稿 §3.1）。
 *
 * <p>手持本物品右键已注册物种，即触发契约动作（消耗一张）。
 * 具体契约逻辑在 {@code contract\FurkinContractHandler} 中，
 * 由 {@code CommonEvents} 拦截 {@code PlayerInteractEvent.EntityInteract} 触发。</p>
 *
 * <p>本类只承载物品本体（模型 / 堆叠属性），不包含交互逻辑，
 * 保持「物品是数据、动作是事件」的边界。</p>
 *
 * <p>⚠️ <b>不带附魔光效</b> —— 2026-09-22 她定「撤掉」：M1 起这里有个 {@code isFoil}
 * 覆写恒返 {@code true}（想让关键道具泛点光），实际观感是「看着像已附魔」，
 * 已删、回到原版口径（原版 {@code Item#isFoil} = {@code stack.isEnchanted()}，
 * 只有真有附魔才发光）。<b>别再顺手加回来</b>。</p>
 */
public class FurkinContractItem extends Item {

    public FurkinContractItem() {
        // 温和成本（金锭 / 青金石 / 纸一级），配方归 SERVER TOML —— 设计稿 §3.1。
        super(new Item.Properties().tab(ModCreativeTab.FURKIN_TAB));
    }
}
