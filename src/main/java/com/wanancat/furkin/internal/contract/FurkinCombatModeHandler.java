package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 战斗模式切换处理器 —— 承载「切换绒亲战斗模式（四档）」动作（设计稿 §3.2 配套）。
 *
 * <p><b>规则一套</b>：命令与未来录内按钮都走本类同一方法，不许出现两套规则。
 * 切换后：写能力对象 → 写档案（跨召唤记忆）→ 应用目标 → 同步客户端。</p>
 */
public final class FurkinCombatModeHandler {

    private FurkinCombatModeHandler() {
    }

    /** 切换结果（供命令 / 网络包回反馈文案）。 */
    public enum Result {
        OK,
        NOT_FOUND,
        NOT_OWNER,
        NOT_SUMMONED,
        INVALID_MODE
    }

    /**
     * 切换一只属于本人的绒亲的战斗模式。
     *
     * <p>前置：必须「已召唤」（在场）才能切换 —— 目标挂在实体上，不在场无从切换。
     * 未召唤时切档无意义（召唤时会从档案读回记忆的模式）。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @param mode        目标档位
     * @return 结果枚举
     */
    public static Result setMode(ServerPlayer player, UUID companionId, FurkinCombatMode mode) {
        if (mode == null) {
            return Result.INVALID_MODE;
        }
        ServerLevel serverLevel = player.getLevel();

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return Result.NOT_FOUND;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return Result.NOT_OWNER;
        }
        if (!entry.isSummoned()) {
            return Result.NOT_SUMMONED;
        }

        LivingEntity target = findLivingByCompanionId(serverLevel, companionId);
        if (target == null) {
            return Result.NOT_FOUND;
        }

        // 写能力对象（运行时真相）。
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return Result.NOT_FOUND;
        }
        data.setCombatMode(mode);

        // 写档案（跨召唤记忆）。
        entry.setCombatMode(mode);
        archive.putEntry(entry);

        // 应用目标（TamableAnimal 才有攻击目标体系）。
        if (target instanceof TamableAnimal tamable) {
            mode.applyTo(tamable);
        }

        // 同步客户端（能力数据里含 combat_mode）。
        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target),
                new SyncFurkinDataPacket(target.getId(), data.syncNBT()));

        FurkinMod.LOGGER.info("Furkin combat mode set: id={} mode={} by {}",
                companionId, mode.name(), player.getName().getString());
        return Result.OK;
    }

    /** 在世界里按 companionId 查找在场绒亲实体。 */
    private static LivingEntity findLivingByCompanionId(ServerLevel level, UUID companionId) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof LivingEntity living) {
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data != null && companionId.equals(data.getCompanionId())) {
                    return living;
                }
            }
        }
        return null;
    }
}
