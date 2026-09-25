package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.equipment.EquipmentSlots;
import com.wanancat.furkin.internal.inventory.PouchDrop;
import com.wanancat.furkin.internal.skill.SkillEffectApplier;
import com.wanancat.furkin.internal.skill.SkillPassiveDispatcher;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * WP-02B：常规解绑与注销墓碑共用的清理管线。
 *
 * <p>本类只负责把一只仍在世的实体恢复为普通动物语义，不删除任何档案或墓碑。
 * 调用方只有在收到 {@link Result#ok()} 后才能删除普通档案，或移除注销墓碑。</p>
 *
 * <p>清理按固定顺序执行：AI → 行囊 → 装备/掉率 → 技能效果 → 运行时数据 → 原版归属。
 * 任一阶段异常都会返回带阶段信息的失败结果；已掉落的物品会被立即清槽，因此再次调用
 * 只处理剩余内容，不会重复生成同一件物品。</p>
 */
public final class FurkinUnbindCleanup {

    private FurkinUnbindCleanup() {
    }

    /** 清理触发来源。 */
    public enum Trigger {
        /** 常规解绑：档案条目仍在。 */
        NORMAL,
        /** 强制解绑后的延迟清理：档案已删除，墓碑仍在。 */
        REVOCATION
    }

    /** 清理阶段，用于失败诊断和墓碑重试定位。 */
    public enum Stage {
        VALIDATION,
        AI,
        POUCH,
        EQUIPMENT,
        DROP_CHANCE,
        SKILL_EFFECTS,
        RUNTIME_DATA,
        VANILLA_OWNERSHIP,
        POSTCONDITION
    }

    /** 清理结果；失败时保留阶段和原始异常，供调用方决定是否保留档案/墓碑。 */
    public record Result(boolean success, @Nullable Stage failedStage, @Nullable Throwable cause) {
        public static Result ok() {
            return new Result(true, null, null);
        }

        public static Result failure(Stage stage, Throwable cause) {
            return new Result(false, stage, cause);
        }
    }

    /**
     * 清理目标实体。
     *
     * @param target              已在服务端入世的目标实体
     * @param expectedCompanionId 调用方已知的绒亲身份；常规解绑传档案 ID，墓碑清理传墓碑 ID
     * @param trigger             触发来源
     */
    public static Result cleanup(LivingEntity target, @Nullable UUID expectedCompanionId,
                                 Trigger trigger) {
        if (target == null) {
            return Result.failure(Stage.VALIDATION,
                    new IllegalArgumentException("cleanup target is null"));
        }

        Stage stage = Stage.VALIDATION;
        UUID companionId = expectedCompanionId;
        FurkinData data = null;
        try {
            if (target.level().isClientSide()) {
                throw new IllegalStateException("unbind cleanup must run on the server");
            }

            data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
            if (data == null) {
                throw new IllegalStateException("target has no Furkin capability");
            }

            UUID actualCompanionId = data.getCompanionId();
            if (expectedCompanionId != null && actualCompanionId != null
                    && !expectedCompanionId.equals(actualCompanionId)) {
                throw new IllegalStateException("target companion id does not match expected id");
            }
            if (actualCompanionId != null) {
                companionId = actualCompanionId;
            }
            if (companionId == null) {
                throw new IllegalStateException("target has no companion id");
            }

            stage = Stage.AI;
            clearCombatAi(target, data);

            stage = Stage.POUCH;
            PouchDrop.dropAll(target, data.getPouch());

            stage = Stage.EQUIPMENT;
            EquipmentSlots.dropAndClear(target);

            stage = Stage.DROP_CHANCE;
            EquipmentSlots.restoreDefaultDropChances(target);

            stage = Stage.SKILL_EFFECTS;
            SkillEffectApplier.clearAll(target, SkillRegistry.tree(), data.getSkillLevels());
            SkillPassiveDispatcher.clearRuntimeEffects(target, data);

            stage = Stage.RUNTIME_DATA;
            data.getSkillLevels().clear();
            List<ItemStack> overflow = data.resizePouchToLevel();
            PouchDrop.dropStacks(target, overflow);
            data.clearForUnbind();

            stage = Stage.VANILLA_OWNERSHIP;
            clearVanillaOwnership(target);

            stage = Stage.POSTCONDITION;
            checkPostconditions(target, data);

            FurkinMod.LOGGER.info(
                    "Furkin unbind cleanup complete: id={}, entity={}, dimension={}, trigger={}",
                    companionId, target.getUUID(), target.level().dimension().location(), trigger);
            return Result.ok();
        } catch (Exception exception) {
            // 墓碑清理若在身份字段已清空后才失败，必须把 companionId 放回去；
            // 否则下一次实体入世无法再次命中墓碑，延迟清理会永久失去重试入口。
            if (trigger == Trigger.REVOCATION && data != null
                    && data.getCompanionId() == null && companionId != null) {
                data.setCompanionId(companionId);
                FurkinMod.LOGGER.warn(
                        "Restored companion id after failed revocation cleanup for retry: id={}, entity={}",
                        companionId, target.getUUID());
            }
            FurkinMod.LOGGER.error(
                    "Furkin unbind cleanup failed: id={}, entity={}, dimension={}, trigger={}, stage={}",
                    companionId, target.getUUID(), target.level().dimension().location(), trigger,
                    stage, exception);
            return Result.failure(stage, exception);
        }
    }

    private static void clearCombatAi(LivingEntity target, FurkinData data) {
        if (!(target instanceof TamableAnimal tamable)) {
            return;
        }
        FurkinCombatAiState aiState = data.getCombatAiState();
        if (aiState == null) {
            aiState = data.getOrCreateCombatAiState();
        }
        FurkinCombatMode.onUnbind(tamable, aiState);
    }

    private static void clearVanillaOwnership(LivingEntity target) {
        if (target instanceof TamableAnimal tamable) {
            tamable.setTame(false);
            tamable.setOwnerUUID(null);
            tamable.setOrderedToSit(false);
            tamable.setInSittingPose(false);
        }
        if (target instanceof Mob mob) {
            mob.setTarget(null);
        }
        target.setCustomName(null);
        target.setCustomNameVisible(false);
    }

    private static void checkPostconditions(LivingEntity target, FurkinData data) {
        if (data.getCompanionId() != null || data.getOwnerUuid() != null) {
            throw new IllegalStateException("companion identity was not cleared");
        }
        if (data.getState() != FurkinState.WILD
                || data.getCombatMode() != FurkinCombatMode.FOLLOW
                || data.getAiStateVersion() != 0
                || data.getCombatAiState() != null) {
            throw new IllegalStateException("Furkin runtime state was not reset");
        }
        if (!data.getSkillLevels().isEmpty() || !data.getCooldowns().isEmpty()
                || data.getFeedCount() != 0 || data.getLastFeedMillis() != 0L) {
            throw new IllegalStateException("Furkin skill/feeding state was not cleared");
        }
        if (!data.getPouch().isEmpty() || data.getPouch().getContainerSize() != 0) {
            throw new IllegalStateException("Furkin pouch was not cleared");
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ARMOR
                    && !target.getItemBySlot(slot).isEmpty()) {
                throw new IllegalStateException("Furkin armor slot was not cleared");
            }
        }
        if (target instanceof Mob mob && mob.getTarget() != null) {
            throw new IllegalStateException("Furkin attack target was not cleared");
        }
        if (target instanceof TamableAnimal tamable
                && (tamable.isTame() || tamable.getOwnerUUID() != null
                || tamable.isOrderedToSit() || tamable.isInSittingPose())) {
            throw new IllegalStateException("TamableAnimal ownership state was not cleared");
        }
        if (target.getCustomName() != null) {
            throw new IllegalStateException("custom name was not cleared");
        }
    }
}
