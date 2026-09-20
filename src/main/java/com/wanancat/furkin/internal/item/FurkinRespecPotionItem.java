package com.wanancat.furkin.internal.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 洗点药水 —— 洗点道具（设计稿 §3.2）。
 *
 * <p>消耗一件本道具，清空某只绒亲的全部技能并按 Σ 已投等级全额退还技能点。
 * 本类只承载物品本体；洗点动作经 {@code ResetSkillsPacket}（C→S）上行，
 * 服务端统一走 {@code SkillProgress#resetSkills}，并做二次确认。</p>
 *
 * <p>合成：绒亲契约 + 水瓶（配方 {@code data/furkin/recipes/respec_potion.json}）。</p>
 */
public class FurkinRespecPotionItem extends Item {

    public FurkinRespecPotionItem() {
        super(new Item.Properties());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // 与契约一致，带一点光泽提示「关键道具」。
        return true;
    }
}
