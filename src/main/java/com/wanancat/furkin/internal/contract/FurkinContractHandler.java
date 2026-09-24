package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.equipment.EquipmentSlots;
import com.wanancat.furkin.internal.item.FurkinContractItem;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 契约动作处理器 —— 功能① 的核心逻辑（设计稿 §3.1）。
 *
 * <p>流程：右键已注册物种 → 边界校验 → 服务端记录待确认会话 → 客户端命名 →
 * 服务端重新校验并执行契约。契约即建档，与是否合成绒亲录无关。</p>
 *
 * <p>边界规则（不消耗契约）：已契约 / 已倒下 / 不在注册表内 / 非生物实体 /
 * 已被其他玩家拥有 / 主手状态或距离发生变化。</p>
 */
public final class FurkinContractHandler {

    private static final long CONTRACT_CONFIRM_TIMEOUT_TICKS = 600L;
    private static final int MAX_CONTRACT_NAME_LENGTH = 32;

    /** 服务端线程专用：玩家 UUID → 当前待确认契约。新请求覆盖旧请求。 */
    private static final Map<UUID, PendingContract> PENDING_CONTRACTS = new HashMap<>();

    private FurkinContractHandler() {
    }

    /**
     * 尝试契约（第一步：边界校验 + 请求命名）。
     *
     * <p>成功通过所有边界校验后，<b>不立刻落契约</b>，而是记录服务端会话并发送
     * {@link com.wanancat.furkin.internal.network.RequestContractNamePacket} 请求客户端
     * 弹命名框。玩家确认后经
     * {@link com.wanancat.furkin.internal.network.ConfirmContractPacket} 回调
     * {@link #confirmContract}，由服务端重新校验并真正落契约。</p>
     *
     * <p>返回 true 表示「已发出命名请求」（契约尚未落地），false 表示「边界不通过，无动作」。</p>
     *
     * @param player 发起契约的玩家（主人）
     * @param target 被契约的实体
     * @param hand   契约物品所在堆叠
     * @return 是否已发出命名请求
     */
    public static boolean tryContract(ServerPlayer player, LivingEntity target, ItemStack hand) {
        // 任意新的契约尝试都先使旧请求失效，避免旧命名窗口在新请求失败后仍可确认。
        PENDING_CONTRACTS.remove(player.getUUID());

        ServerLevel level = player.serverLevel();
        if (!passesContractChecks(player, target, hand, level)) {
            return false;
        }

        PENDING_CONTRACTS.put(player.getUUID(), new PendingContract(
                target.getUUID(),
                target.getId(),
                level.dimension(),
                level.getGameTime(),
                player.getInventory().selected,
                hand));

        FurkinNetwork.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new com.wanancat.furkin.internal.network.RequestContractNamePacket(target.getId()));
        return true;
    }

    /**
     * 服务端确认契约（第二步：命名确认后的唯一权威入口）。
     *
     * <p>会话先被消费，因此任何确认包都最多执行一次。随后按当前服务端状态重新查找目标，
     * 校验维度、实体身份、存活、注册表、能力、归属、活跃上限、主手、距离和名字。</p>
     *
     * @param player   发起确认的玩家
     * @param entityId 客户端回传的实体 ID
     * @param name     玩家输入的名字（空串 = 留空，回退物种名）
     */
    public static void confirmContract(ServerPlayer player, int entityId, String name) {
        PendingContract pending = PENDING_CONTRACTS.remove(player.getUUID());
        if (pending == null) {
            return;
        }

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        if (pending.isExpired(now)
                || !level.dimension().equals(pending.dimension)
                || entityId != pending.entityId) {
            return;
        }

        Entity entity = level.getEntity(pending.targetUuid);
        if (!(entity instanceof LivingEntity target)
                || !target.getUUID().equals(pending.targetUuid)) {
            return;
        }

        ItemStack hand = player.getMainHandItem();
        if (!passesContractChecks(player, target, hand, level)) {
            return;
        }

        if (player.getInventory().selected != pending.selectedSlot
                || !ItemStack.matches(hand, pending.expectedHand)
                || !isValidContractName(name)) {
            return;
        }

        executeContract(player, target, hand, name.trim());
    }

    /** 玩家登出时清理待确认会话，避免无效记录长期残留。 */
    public static void clearPendingContract(UUID playerId) {
        PENDING_CONTRACTS.remove(playerId);
    }

    /**
     * 契约执行前的公共权威校验。
     *
     * <p>只返回状态，不改写能力、档案、名字或物品。活跃上限失败沿用现有 action bar 提示。</p>
     */
    private static boolean passesContractChecks(
            ServerPlayer player, LivingEntity target, ItemStack hand, ServerLevel level) {
        if (target == player || target instanceof Player) {
            return false;
        }
        if (target.level() != level || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        if (!FurkinSpeciesRegistry.isRegisteredEntity(target)) {
            return false;
        }

        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || data.isCompanion()) {
            return false;
        }

        if (target instanceof TamableAnimal tamable) {
            UUID ownerUuid = tamable.getOwnerUUID();
            if (ownerUuid != null && !ownerUuid.equals(player.getUUID())) {
                return false;
            }
        }

        if (!player.canReach(target, 3.0D)) {
            return false;
        }

        if (countActive(level, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
            FurkinMod.LOGGER.info("Furkin contract blocked: active limit reached for {}",
                    player.getName().getString());
            player.displayClientMessage(
                    Component.translatable("furkin.msg.active_limit",
                            FurkinServerConfig.ACTIVE_LIMIT.get()),
                    true);
            return false;
        }

        return hand != null
                && !hand.isEmpty()
                && hand.getItem() instanceof FurkinContractItem;
    }

    /** 客户端输入框限制为 32 字符；服务端独立拒绝超长、控制字符和旧版格式标记。 */
    private static boolean isValidContractName(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == '\u00a7') {
                return false;
            }
        }
        return name.trim().length() <= MAX_CONTRACT_NAME_LENGTH;
    }

    /**
     * 真正执行契约。只能由已经通过 {@link #confirmContract} 权威校验的路径调用。
     *
     * @param player 主人
     * @param target 被契约实体
     * @param hand   已校验的契约物品堆叠
     * @param name   已校验并 trim 的名字（空串 = 留空，回退物种名）
     */
    private static void executeContract(ServerPlayer player, LivingEntity target, ItemStack hand, String name) {
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || data.isCompanion()) {
            return;
        }

        // 防御性复检活跃上限。正常路径已在确认入口检查，这里防止同一 tick 内的状态变化。
        if (target.level() instanceof ServerLevel level
                && countActive(level, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
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

        // 契约即封印装备掉落（M3.2）：装备槽从这一刻起归玩家所有，宠物死亡时不该把它掉出世界。
        // 与「死亡侧写装备快照」配对，缺一不可 —— 只关掉落而不存快照 = 装备凭空消失（设计稿 §2.3）。
        EquipmentSlots.sealDrops(target);

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
                new SyncFurkinDataPacket(target.getId(), data.syncNBT()));

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

    /** 短生命周期的服务端待确认契约。只在服务端线程访问。 */
    private static final class PendingContract {

        private final UUID targetUuid;
        private final int entityId;
        private final ResourceKey<Level> dimension;
        private final long issuedAtGameTime;
        private final int selectedSlot;
        private final ItemStack expectedHand;

        private PendingContract(UUID targetUuid, int entityId, ResourceKey<Level> dimension,
                                long issuedAtGameTime, int selectedSlot, ItemStack expectedHand) {
            this.targetUuid = targetUuid;
            this.entityId = entityId;
            this.dimension = dimension;
            this.issuedAtGameTime = issuedAtGameTime;
            this.selectedSlot = selectedSlot;
            this.expectedHand = expectedHand.copy();
        }

        private boolean isExpired(long now) {
            return now < issuedAtGameTime
                    || now - issuedAtGameTime > CONTRACT_CONFIRM_TIMEOUT_TICKS;
        }
    }
}
