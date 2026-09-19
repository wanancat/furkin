package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 契约动作处理器 —— 功能① 的核心逻辑（设计稿 §3.1）。
 *
 * <p>流程：右键已注册物种 → 边界校验 → 写入 {@link FurkinData} → 建档（绒亲录）。
 * <b>契约即建档</b>：瞬间写入档案，与是否合成绒亲录无关。</p>
 *
 * <p>边界规则（不消耗契约）：已契约 / 已倒下 / 不在注册表内 / 非生物实体。</p>
 */
public final class FurkinContractHandler {

    private FurkinContractHandler() {
    }

    /**
     * 尝试契约。成功返回 true（契约已消耗），失败返回 false（契约未消耗）。
     *
     * @param player  发起契约的玩家（主人）
     * @param target  被契约的实体
     * @param hand    契约物品所在堆叠
     * @return 是否成功契约
     */
    public static boolean tryContract(ServerPlayer player, LivingEntity target, ItemStack hand) {
        // 边界①：不在注册表内 → 不响应、不消耗。
        if (!FurkinSpeciesRegistry.isRegisteredEntity(target)) {
            return false;
        }

        // 取目标能力。
        FurkinData data = target.getCapability(
                com.wanancat.furkin.internal.capability.FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return false;
        }

        // 边界②：已契约 / 已倒下 → 不响应、不消耗。
        if (data.isCompanion()) {
            return false;
        }

        // 生成宠物身份 UUID（建档主键）。
        UUID companionId = UUID.randomUUID();
        UUID ownerUuid = player.getUUID();

        // 写能力对象（运行时真相）。
        data.setCompanionId(companionId);
        data.setOwnerUuid(ownerUuid);
        data.setLevel(1);
        data.setXp(0);
        data.setState(FurkinState.COMPANION);

        // 对 TamableAnimal 的额外动作：置 TAME=true（不撤销，有意接受的白送）。
        if (target instanceof TamableAnimal tamable) {
            tamable.setTame(true);
            tamable.setOwnerUUID(ownerUuid);
        }

        // 建档（契约即建档）。
        if (target.level() instanceof ServerLevel serverLevel) {
            FurkinArchiveEntry entry = new FurkinArchiveEntry(companionId);
            entry.setOwnerUuid(ownerUuid);
            entry.setAlive(true);
            entry.setSummoned(true); // 契约当场实体在场，标记为已召唤。
            entry.setLevel(1);
            FurkinArchiveData.get(serverLevel).putEntry(entry);
        }

        // 消耗一张契约。
        hand.shrink(1);

        FurkinMod.LOGGER.info("Furkin contracted: {} (id={}) by {}",
                target.getName().getString(), companionId, player.getName().getString());
        return true;
    }
}
