package com.wanancat.furkin.internal.item;

import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.revive.ReviveStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

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

    /**
     * 复活仪式入口（M4.2）：手持魂石右键中心羊毛，结构判定通过即复活。
     *
     * <p>流程：服务端判定 → 目标须为羊毛 → {@link ReviveStructure#matches} 验结构 →
     * 读魂石绑定的 {@code companionId} → {@link FurkinCompanionManager#revive}（三重校验 +
     * 重建实体）→ 成功后消耗魂石。</p>
     *
     * <p><b>复活本体零代价零冷却</b>，唯一代价 = 消耗这枚魂石（设计稿 §3.4「2026-09-22 修订」）。
     * 失败一律 {@link InteractionResult#PASS}（不吞事件，结构不对时原版行为照旧）。</p>
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();

        // 只在服务端判定 + 执行；客户端返回「由服务端定夺」。
        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(false);
        }

        // 目标方块必须是羊毛（BlockTags.WOOL），且玩家有效。
        BlockPos center = context.getClickedPos();
        if (context.getPlayer() instanceof ServerPlayer player) {
            ItemStack stack = context.getItemInHand();
            UUID companionId = getBoundCompanion(stack);
            if (companionId == null) {
                return InteractionResult.PASS; // 未绑定的魂石（/give 出来的）不参与复活。
            }
            if (ReviveStructure.matches(level, center)) {
                boolean revived = FurkinCompanionManager.revive(player, companionId, center);
                if (revived) {
                    // 消耗魂石（纯钥匙，用掉即消失）。
                    if (!player.isCreative()) {
                        stack.shrink(1);
                    }
                    return InteractionResult.CONSUME;
                }
            }
        }
        return InteractionResult.PASS;
    }
}
