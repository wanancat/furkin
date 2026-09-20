package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.skill.SkillEffectApplier;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 绒亲录管理动作处理器 —— 承载「收回 / 解绑 / 改名」三个录内管理动作
 * （设计稿 §3.4.1「查看 + 全部管理」）。
 *
 * <p><b>规则只有一套</b>（设计稿 §3.4.1）：录内按钮与命令都走本类同一方法，
 * 不许出现两套规则。召唤 / 传送已由 {@link FurkinCompanionManager} 承载，
 * 本类只管「管理」侧的三动作。</p>
 *
 * <p><b>转移已取消</b>（乌狸 2026-09-20 拍板）：彻底不做，范围外。</p>
 */
public final class FurkinRecordActionHandler {

    private FurkinRecordActionHandler() {
    }

    /** 动作结果（供网络包 / 命令回反馈文案 key）。 */
    public enum Result {
        OK,
        NOT_FOUND,
        NOT_OWNER,
        NOT_SUMMONED,
        INVALID_NAME
    }

    /**
     * 收回一只已召唤的绒亲（等价于手持契约潜行右键）。
     *
     * <p>委托 {@link FurkinCompanionManager#dismiss}。此处仅是录内 / 命令的
     * 统一入口封装，保证入口收敛到一套逻辑。</p>
     *
     * @return 是否成功收回
     */
    public static boolean dismiss(ServerPlayer player, UUID companionId) {
        // 先定位在场实体（dismiss 需要实体引用，而非仅 UUID）。
        LivingEntity target = findLivingByCompanionId(player.serverLevel(), companionId);
        if (target == null) {
            return false;
        }
        return FurkinCompanionManager.dismiss(player, target);
    }

    /**
     * 解绑（摘掉绒亲数据层）一只属于本人的绒亲。
     *
     * <p>语义（乌狸 2026-09-20 定）：</p>
     * <ul>
     *   <li><b>已召唤</b> → 实体留在世界当普通动物：清绒亲层（身份 / 状态 / 技能）、
     *       清 {@code TamableAnimal} 的 TAME 与主人、清 {@code CustomName}；
     *       档案删除。</li>
     *   <li><b>未召唤</b> → 实体本就不在场，直接删档（消失）。</li>
     * </ul>
     *
     * <p>⚠️ 副作用（设计稿 §3.1 有意接受）：契约时置上的 {@code TAME} 在解绑后
     * 会一并清掉（回到野生），这是「摘掉绒亲层、回落原版状态」的定义，不写恢复逻辑。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @return 结果枚举
     */
    public static Result unbind(ServerPlayer player, UUID companionId) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return Result.NOT_FOUND;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return Result.NOT_FOUND;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return Result.NOT_OWNER;
        }

        // 已召唤 → 找到在场实体，摘掉绒亲层后留普通动物。
        if (entry.isSummoned()) {
            LivingEntity target = findLivingByCompanionId(serverLevel, companionId);
            if (target != null) {
                clearFurkinLayer(target);
                // 同步客户端：状态回 WILD 后头顶图标消失。
                FurkinNetwork.channel().send(
                        PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target),
                        new SyncFurkinDataPacket(target.getId(),
                                target.getCapability(FurkinCapability.FURKIN_DATA)
                                        .orElseGet(FurkinData::new).serializeNBT()));
            }
        }

        // 删档（已召唤 / 未召唤都删）。
        archive.removeEntry(companionId);

        FurkinMod.LOGGER.info("Furkin unbound: id={} by {}",
                companionId, player.getName().getString());
        return Result.OK;
    }

    /**
     * 改名（契约后改名）。
     *
     * <p>空名 → 回退物种显示名（与契约命名同口径）。同步档案 {@code name} 字段，
     * 若实体在场则同步 {@code CustomName}（头顶显示 + 命名牌一致性）。</p>
     *
     * @return 结果枚举
     */
    public static Result rename(ServerPlayer player, UUID companionId, String name) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return Result.NOT_FOUND;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return Result.NOT_FOUND;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return Result.NOT_OWNER;
        }

        // 解析名字：空 → 物种显示名；非空 → 字面量。
        Component resolved = resolveName(name, entry);
        if (resolved == null) {
            return Result.INVALID_NAME;
        }

        // 写档案：物种默认名不写冗余（与契约命名口径一致）。
        if (resolved.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents) {
            entry.setName(null);
        } else {
            entry.setName(resolved);
        }
        archive.putEntry(entry);

        // 若实体在场，同步 CustomName。
        if (entry.isSummoned()) {
            LivingEntity target = findLivingByCompanionId(serverLevel, companionId);
            if (target != null) {
                if (resolved.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents) {
                    target.setCustomName(null);
                    target.setCustomNameVisible(false);
                } else {
                    target.setCustomName(resolved);
                    target.setCustomNameVisible(true);
                }
            }
        }

        FurkinMod.LOGGER.info("Furkin renamed: id={} by {}",
                companionId, player.getName().getString());
        return Result.OK;
    }

    /** 解析名字：空串 → 物种显示名（translatable）；非空 → 字面量。 */
    private static Component resolveName(String name, FurkinArchiveEntry entry) {
        if (name == null || name.trim().isEmpty()) {
            if (entry.getSpecies() == null) {
                return null;
            }
            return Component.translatable(
                    com.wanancat.furkin.api.companion.FurkinSpeciesRegistry
                            .byEntityType(entry.getSpecies())
                            .map(com.wanancat.furkin.api.companion.FurkinSpecies::getNameKey)
                            .orElseGet(() -> entry.getSpecies().getDescriptionId()));
        }
        return Component.literal(name.trim());
    }

    /** 摘掉绒亲数据层：身份 / 状态 / 技能 / 等级 / 战斗模式清空，TamableAnimal 清 TAME 与主人，清 CustomName。 */
    private static void clearFurkinLayer(LivingEntity target) {
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data != null) {
            // 先摘技能效果（attribute modifier 等运行时表现），再清数据，避免残留属性加成。
            SkillEffectApplier.removeAll(target, SkillRegistry.tree(), data.getSkillLevels());
            data.setCompanionId(null);
            data.setOwnerUuid(null);
            data.setLevel(1);
            data.setXp(0);
            data.setSkillPoints(0);
            data.getSkillLevels().clear();
            data.setCombatMode(FurkinCombatMode.FOLLOW);
            data.setState(FurkinState.WILD);
        }

        // 清原版驯服归属（回落野生）。注意：不动 targetSelector / goalSelector ——
        // 解绑 = 回落「普通动物」，原版 AI 自行接管（狗原版会自己挂攻击目标）。
        if (target instanceof TamableAnimal tamable) {
            tamable.setTame(false);
            tamable.setOwnerUUID(null);
            tamable.setOrderedToSit(false);
        }

        // 清名字（乌狸定：解绑不保留名字）。
        target.setCustomName(null);
        target.setCustomNameVisible(false);
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
