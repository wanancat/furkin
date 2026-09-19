package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

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
     * 尝试契约（第一步：边界校验 + 请求命名）。
     *
     * <p>成功通过所有边界校验后，<b>不立刻落契约</b>，而是发
     * {@link com.wanancat.furkin.internal.network.RequestContractNamePacket} 请求客户端
     * 弹命名框。玩家确认后经
     * {@link com.wanancat.furkin.internal.network.ConfirmContractPacket} 回调
     * {@link #executeContract} 真正落契约。</p>
     *
     * <p>返回 true 表示「已发出命名请求」（契约尚未落地），false 表示「边界不通过，无动作」。</p>
     *
     * @param player  发起契约的玩家（主人）
     * @param target  被契约的实体
     * @param hand    契约物品所在堆叠
     * @return 是否已发出命名请求
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

        // 边界③：活跃上限（契约当场即在场，与召唤共用同一上限，见 FurkinCompanionManager）。
        if (target.level() instanceof ServerLevel sl
                && countActive(sl, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
            FurkinMod.LOGGER.info("Furkin contract blocked: active limit reached for {}",
                    player.getName().getString());
            // 反馈：action bar 提示，避免玩家以为「没按到」。
            player.displayClientMessage(
                    Component.translatable("furkin.msg.active_limit",
                            FurkinServerConfig.ACTIVE_LIMIT.get()),
                    true);
            return false;
        }

        // 边界全部通过 → 请求命名（不消耗契约，契约在命名确认后落）。
        FurkinNetwork.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new com.wanancat.furkin.internal.network.RequestContractNamePacket(target.getId()));
        return true;
    }

    /**
     * 真正执行契约（第二步：命名确认后回调）。
     *
     * <p>由 {@code ConfirmContractPacket} 在服务端调用。此时边界已在前置校验通过，
     * 但为防御性，仍复检一遍关键边界（能力存在 / 未契约 / 活跃上限）。</p>
     *
     * @param player 主人
     * @param target 被契约实体
     * @param hand   契约物品堆叠
     * @param name   玩家输入的名字（空串 = 留空，回退物种名）
     */
    public static void executeContract(ServerPlayer player, LivingEntity target, ItemStack hand, String name) {
        FurkinData data = target.getCapability(
                com.wanancat.furkin.internal.capability.FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || data.isCompanion()) {
            return;
        }

        // 防御性复检活跃上限（正常情况下前置已拦，这里兜底）。
        if (target.level() instanceof ServerLevel sl
                && countActive(sl, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
            player.displayClientMessage(
                    Component.translatable("furkin.msg.active_limit",
                            FurkinServerConfig.ACTIVE_LIMIT.get()),
                    true);
            return;
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
        data.setCombatMode(FurkinCombatMode.FOLLOW); // 默认跟随（不参战），玩家切档后记忆。

        // 对 TamableAnimal 的额外动作：置 TAME=true（不撤销，有意接受的白送）。
        if (target instanceof TamableAnimal tamable) {
            tamable.setTame(true);
            tamable.setOwnerUUID(ownerUuid);
            // 清一次坐定，保证契约后立即跟随（原版「右键坐下」交互保留，玩家后续仍可手动让猫坐下）。
            tamable.setOrderedToSit(false);
            // 应用战斗模式（默认 FOLLOW = 清掉攻击目标，不参战）。
            FurkinCombatMode.FOLLOW.applyTo(tamable);
        }

        // 名字：留空回退物种名（本地化 key 渲染前的默认名）。这里存的是「名字」而非 key。
        Component petName = resolveName(name, target);

        // 建档（契约即建档）。
        if (target.level() instanceof ServerLevel serverLevel) {
            FurkinArchiveEntry entry = new FurkinArchiveEntry(companionId);
            entry.setOwnerUuid(ownerUuid);
            entry.setSpecies(target.getType());
            entry.setAlive(true);
            entry.setSummoned(true); // 契约当场实体在场，标记为已召唤。
            entry.setLevel(1);
            // 实体外观快照：品种 / 毛色等在契约当场就存下，保证召唤后外观一致。
            entry.setEntitySnapshot(target.saveWithoutId(new CompoundTag()));
            // 名字：非物种名的自定义名才写入档案（空串→物种名，不写冗余）。
            if (!isSpeciesName(petName)) {
                entry.setName(petName);
            }
            FurkinArchiveData.get(serverLevel).putEntry(entry);
        }

        // 给实体本身也设置 CustomName（头顶显示 / 命名牌一致性）。
        if (petName != null && !petName.getString().isEmpty()) {
            target.setCustomName(petName);
            target.setCustomNameVisible(true);
        }

        // 消耗一张契约。
        hand.shrink(1);

        // 同步能力数据到客户端（头顶图标等客户端表现依赖）。
        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target),
                new SyncFurkinDataPacket(target.getId(), data.serializeNBT()));

        FurkinMod.LOGGER.info("Furkin contracted: {} (id={}) by {}",
                target.getName().getString(), companionId, player.getName().getString());
    }

    /**
     * 解析宠物名：输入为空串 → 回退物种显示名；否则用玩家输入。
     */
    private static Component resolveName(String name, LivingEntity target) {
        if (name != null && !name.trim().isEmpty()) {
            return Component.literal(name.trim());
        }
        // 回退物种显示名（可读名）。
        return Component.translatable(FurkinSpeciesRegistry.byEntityType(target.getType())
                .map(FurkinSpecies::getNameKey)
                .orElseGet(() -> target.getType().getDescriptionId()));
    }

    /** 判断一个名字是否为「物种默认名」（本地化组件），用于决定是否写入档案。 */
    private static boolean isSpeciesName(Component name) {
        // 名字是 translatable 组件即视为「物种默认名」（未被玩家自定义）。
        return name.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents;
    }

    /**
     * 统计某主人当前「已激活（实体在场）」的绒亲数量。
     * 契约与召唤共用同一上限，故统计口径与 {@link FurkinCompanionManager#countSummoned} 一致。
     */
    private static int countActive(ServerLevel level, UUID ownerUuid) {
        int count = 0;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof LivingEntity living) {
                FurkinData d = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (d != null && d.isCompanion() && ownerUuid.equals(d.getOwnerUuid())) {
                    count++;
                }
            }
        }
        return count;
    }
}
