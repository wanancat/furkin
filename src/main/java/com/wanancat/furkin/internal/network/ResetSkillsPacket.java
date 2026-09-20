package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import com.wanancat.furkin.internal.item.FurkinRespecPotionItem;
import com.wanancat.furkin.internal.skill.SkillProgress;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：洗点请求（消耗洗点药水，清空技能 + 全额退点）。
 *
 * <p>二次确认在客户端完成（点「洗点」→ 确认框 → 确认才发本包）。
 * 服务端收到后校验：本人 + 已召唤 + 持有洗点药水 → 消耗一件 →
 * 走 {@link SkillProgress#resetSkills} 清空 + 退点。</p>
 */
public final class ResetSkillsPacket {

    private final UUID companionId;

    public ResetSkillsPacket(UUID companionId) {
        this.companionId = companionId;
    }

    public static void encode(ResetSkillsPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.companionId);
    }

    public static ResetSkillsPacket decode(FriendlyByteBuf buf) {
        return new ResetSkillsPacket(buf.readUUID());
    }

    public static void handle(ResetSkillsPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                applyServer(player, packet);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void applyServer(ServerPlayer player, ResetSkillsPacket packet) {
        // 消耗洗点药水：主手有就用主手，否则找物品栏里的。
        ItemStack potion = findRespecPotion(player);
        if (potion == null) {
            player.displayClientMessage(
                    Component.translatable("furkin.msg.reset_no_potion"), true);
            return;
        }

        // 先看宠物是否有效（resetSkills 内部会再校验归属/在场，但这里先确认存在，避免白耗道具）。
        // 简化：直接消耗道具 + resetSkills；若 resetSkills 未生效（宠物不存在/非本人），
        // 道具已消耗 —— 需先校验。故这里先做一层归属/在场校验再消耗。
        if (!SkillProgress.isOwnedAndSummoned(player, packet.companionId)) {
            player.displayClientMessage(
                    Component.translatable("furkin.msg.reset_failed"), false);
            return;
        }

        potion.shrink(1);

        int refund = SkillProgress.resetSkills(player, packet.companionId, SkillRegistry.tree());

        player.displayClientMessage(
                Component.translatable("furkin.msg.reset_ok", refund), true);

        // 回传最新状态（技能点已退、等级已清），客户端开着界面时原地刷新。
        FurkinRecordActionHandler.refreshScreen(player, packet.companionId);
    }

    /** 在玩家物品栏里找洗点药水（优先主手，其次副手，再扫整个背包）。 */
    private static ItemStack findRespecPotion(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof FurkinRespecPotionItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof FurkinRespecPotionItem) {
            return off;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof FurkinRespecPotionItem) {
                return stack;
            }
        }
        return null;
    }
}
