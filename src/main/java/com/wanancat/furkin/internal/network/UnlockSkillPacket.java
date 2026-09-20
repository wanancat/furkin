package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import com.wanancat.furkin.internal.skill.SkillProgress;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：解锁（加点）技能请求。
 *
 * <p>服务端收到后走 {@link SkillProgress#tryUnlock} 做完整校验
 * （归属 / 已召唤 / 技能存在 / 物种可见 / 前置 / 点数 / 满级），
 * 成功后回一条反馈；不做本地乐观更新，以服务端回包为准（设计稿 §3.2）。</p>
 */
public final class UnlockSkillPacket {

    private final UUID companionId;
    private final ResourceLocation skillId;

    public UnlockSkillPacket(UUID companionId, ResourceLocation skillId) {
        this.companionId = companionId;
        this.skillId = skillId;
    }

    public static void encode(UnlockSkillPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.companionId);
        buf.writeResourceLocation(packet.skillId);
    }

    public static UnlockSkillPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        ResourceLocation skillId = buf.readResourceLocation();
        return new UnlockSkillPacket(id, skillId);
    }

    public static void handle(UnlockSkillPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                applyServer(player, packet);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void applyServer(ServerPlayer player, UnlockSkillPacket packet) {
        SkillProgress.Result r = SkillProgress.tryUnlock(
                player, packet.companionId, packet.skillId, SkillRegistry.tree());

        switch (r) {
            case OK -> {
                player.displayClientMessage(
                        Component.translatable("furkin.msg.skill_unlocked", packet.skillId), true);
                // 回传最新状态，客户端开着界面时原地刷新。
                FurkinRecordActionHandler.refreshScreen(player, packet.companionId);
            }
            case NOT_FOUND -> player.displayClientMessage(
                    Component.translatable("furkin.msg.skill_not_found"), false);
            case NOT_OWNER -> player.displayClientMessage(
                    Component.translatable("furkin.msg.not_owner"), false);
            case NOT_SUMMONED -> player.displayClientMessage(
                    Component.translatable("furkin.msg.skill_not_summoned"), false);
            case SKILL_UNKNOWN -> player.displayClientMessage(
                    Component.translatable("furkin.msg.skill_unknown"), false);
            case SPECIES_MISMATCH -> player.displayClientMessage(
                    Component.translatable("furkin.msg.skill_species_mismatch"), false);
            case PREREQUISITES -> player.displayClientMessage(
                    Component.translatable("furkin.msg.skill_prerequisites"), false);
            case NOT_ENOUGH_POINTS -> player.displayClientMessage(
                    Component.translatable("furkin.msg.skill_no_points"), false);
            case MAXED -> player.displayClientMessage(
                    Component.translatable("furkin.msg.skill_maxed"), false);
        }
    }
}
