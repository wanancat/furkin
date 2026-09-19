package com.wanancat.furkin.internal.growth;

import com.wanancat.furkin.api.event.FurkinLevelUpEvent;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 成长核心逻辑 —— 经验曲线 + 升级结算 + 技能点发放（设计稿 §3.2「成长曲线」）。
 *
 * <p><b>刻度硬编码、速率可配</b>（设计稿 §4.2 判据一）：经验曲线公式属「刻度」，
 * 直接照搬 Java 版玩家公式，不做参数化、不开放配置；战斗 / 进食的<b>系数</b>才进配置。</p>
 *
 * <p>经验曲线（照搬玩家公式）：</p>
 * <pre>
 * 0–14 级   每级所需 = 7 + 2 × level
 * 15–29 级  每级所需 = 37 + 5 × (level − 15)
 * 30 级起   每级所需 = 112 + 9 × (level − 30)
 * </pre>
 *
 * <p><b>等级无上限</b>：经验溢出即继续升级（循环结算，一次加经验可跨多级）。</p>
 *
 * <p><b>每级 1 技能点</b>：属「刻度」、硬编码不开放（设计稿 §3.2 `23:25` 更正）。</p>
 */
public final class FurkinGrowth {

    private FurkinGrowth() {
    }

    /** 从某等级升到下一级所需经验（照搬玩家公式，三段式）。 */
    public static int xpNeededForNextLevel(int currentLevel) {
        if (currentLevel >= 30) {
            return 112 + 9 * (currentLevel - 30);
        }
        if (currentLevel >= 15) {
            return 37 + 5 * (currentLevel - 15);
        }
        return 7 + 2 * currentLevel;
    }

    /**
     * 给一只在场绒亲加经验，结算升级与技能点。
     *
     * <p>只接受 {@code xp > 0}；经验来源（战斗 / 进食）由调用方在上游结算，
     * 本方法只做「加经验 → 升级 → 发点 → 广播」这一段确定性逻辑。</p>
     *
     * <p>升级事件 {@link FurkinLevelUpEvent} 在<b>每级</b>结算后触发（跨多级则触发多次）。
     * 事件为「只可监听」，第三方不能取消升级。</p>
     *
     * @param companion 在场绒亲实体
     * @param amount    增加的经验量（正数）
     * @return 本次加经验后是否发生了至少一次升级
     */
    public static boolean addXp(LivingEntity companion, int amount) {
        if (amount <= 0) {
            return false;
        }

        FurkinData data = companion.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return false;
        }

        int xp = data.getXp() + amount;
        int level = data.getLevel();
        boolean leveledUp = false;

        // 主人解析一次（升级提示用；可能 null —— 主人离线但宠物仍在场时）。
        ServerPlayer owner = resolveOwner(companion);

        // 溢出循环：经验够升一级就升一级，直到不够为止。
        while (xp >= xpNeededForNextLevel(level)) {
            xp -= xpNeededForNextLevel(level);
            level++;
            data.setSkillPoints(data.getSkillPoints() + 1); // 每级 1 点。
            leveledUp = true;

            // 触发升级事件（每级一次）。
            MinecraftForge.EVENT_BUS.post(new FurkinLevelUpEvent(
                    companion, owner, level - 1, level));

            // 升级 Action Bar 提示（「X 升到了 Lv.N」）—— 只在主人在线时发。
            if (owner != null) {
                Component name = companion.hasCustomName()
                        ? companion.getCustomName()
                        : Component.translatable(companion.getType().getDescriptionId());
                owner.displayClientMessage(
                        Component.translatable("furkin.msg.level_up", name, level),
                        true);
            }
        }

        data.setXp(xp);
        data.setLevel(level);

        // 同步写回档案（绒亲录列表读档案，须即时一致；否则列表等级滞后到收回才更新）。
        syncToArchive(companion, data);

        if (leveledUp) {
            FurkinMod.LOGGER.info("Furkin leveled up: id={} -> Lv.{} (xp={})",
                    data.getCompanionId(), level, xp);
            syncToClients(companion, data);
        }

        return leveledUp;
    }

    /** 解析主人（可能 null —— 主人离线但宠物仍在场时）。 */
    private static ServerPlayer resolveOwner(LivingEntity companion) {
        if (companion.level() instanceof ServerLevel serverLevel) {
            var ownerUuid = companion.getCapability(FurkinCapability.FURKIN_DATA)
                    .map(FurkinData::getOwnerUuid).orElse(null);
            if (ownerUuid != null) {
                return serverLevel.getServer().getPlayerList().getPlayer(ownerUuid);
            }
        }
        return null;
    }

    /** 广播能力数据（等级 / 经验 / 技能点变化）到所有追踪该实体的客户端。 */
    private static void syncToClients(LivingEntity companion, FurkinData data) {
        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> companion),
                new SyncFurkinDataPacket(companion.getId(), data.serializeNBT()));
    }

    /** 同步写回档案（等级 / 经验 / 技能点），使绒亲录列表读档案即得最新值。 */
    private static void syncToArchive(LivingEntity companion, FurkinData data) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        UUID companionId = data.getCompanionId();
        if (companionId == null) {
            return;
        }
        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return;
        }
        entry.setLevel(data.getLevel());
        entry.setXp(data.getXp());
        entry.setSkillPoints(data.getSkillPoints());
        archive.putEntry(entry);
    }
}
