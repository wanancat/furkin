package com.wanancat.furkin.internal.item;

import com.wanancat.furkin.internal.registry.ModCreativeTab;
import net.minecraft.world.item.Item;

/**
 * 洗点药水 —— 洗点道具（设计稿 §3.2）。
 *
 * <p>消耗一件本道具，清空某只绒亲的全部技能并按 Σ 已投等级全额退还技能点。
 * 本类只承载物品本体；洗点动作经 {@code ResetSkillsPacket}（C→S）上行，
 * 服务端统一走 {@code SkillProgress#resetSkills}，并做二次确认。</p>
 *
 * <p>合成：绒亲契约 + 水瓶（配方 {@code data/furkin/recipes/respec_potion.json}）。</p>
 *
 * <p>⚠️ <b>不带附魔光效</b> —— 2026-09-22 她定「撤掉」：M2-2.5 起这里有个 {@code isFoil}
 * 覆写恒返 {@code true}（「与契约一致」），与契约同批一起删，回到原版口径。</p>
 */
public class FurkinRespecPotionItem extends Item {

    public FurkinRespecPotionItem() {
        super(new Item.Properties().tab(ModCreativeTab.FURKIN_TAB));
    }
}
