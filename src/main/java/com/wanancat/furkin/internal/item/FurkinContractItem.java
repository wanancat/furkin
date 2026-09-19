package com.wanancat.furkin.internal.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 绒亲契约 —— 契约道具（设计稿 §3.1）。
 *
 * <p>手持本物品右键已注册物种，即触发契约动作（消耗一张）。
 * 具体契约逻辑在 {@code contract\FurkinContractHandler} 中，
 * 由 {@code CommonEvents} 拦截 {@code PlayerInteractEvent.EntityInteract} 触发。</p>
 *
 * <p>本类只承载物品本体（模型 / 堆叠属性），不包含交互逻辑，
 * 保持「物品是数据、动作是事件」的边界。</p>
 */
public class FurkinContractItem extends Item {

    public FurkinContractItem() {
        // 温和成本（金锭 / 青金石 / 纸一级），配方归 SERVER TOML —— 设计稿 §3.1。
        super(new Item.Properties());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // 契约道具带一点光泽提示「这是关键道具」，非必要，M5 打磨可调。
        return true;
    }
}
