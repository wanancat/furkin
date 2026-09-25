package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.attribute.AttributeDisplay;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.equipment.EquipmentSlots;
import com.wanancat.furkin.internal.inventory.FurkinInventory;
import com.wanancat.furkin.internal.item.FurkinSoulstoneItem;
import com.wanancat.furkin.internal.menu.FurkinPouchMenu;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.OpenFurkinScreenPacket;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.record.FurkinRevocationData;
import com.wanancat.furkin.internal.registry.ModItems;
import com.wanancat.furkin.internal.skill.Skill;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import com.wanancat.furkin.internal.skill.SkillTree;
import com.wanancat.furkin.internal.skill.effect.AttributeEffect;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        INVALID_NAME,
        NOT_DEAD,
        ON_COOLDOWN,
        NO_DIAMOND,
        ENTITY_UNRESOLVED,
        ENTITY_RESOLVED,
        CLEANUP_FAILED
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
        LivingEntity target = findLivingByCompanionId(player.getLevel(), companionId);
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
        ServerLevel serverLevel = player.getLevel();

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return Result.NOT_FOUND;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return Result.NOT_OWNER;
        }

        if (entry.isSummoned()) {
            LivingEntity target = FurkinEntityLocator.locate(player.getServer(), entry);
            if (target == null) {
                // 实体未加载 / 记录维度失效 / 旧档缺少定位信息：只报失败，不删档，不创建实体。
                return Result.ENTITY_UNRESOLVED;
            }

            // 记录本次成功解析的维度，便于清理失败后的下一次定向重试。
            entry.setEntityLocation(target);
            archive.putEntry(entry);

            FurkinUnbindCleanup.Result cleanup = FurkinUnbindCleanup.cleanup(
                    target, companionId, FurkinUnbindCleanup.Trigger.NORMAL);
            if (!cleanup.success()) {
                return Result.CLEANUP_FAILED;
            }

            // 同步客户端：状态回 WILD 后头顶图标消失。
            FurkinNetwork.channel().send(
                    PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target),
                    new SyncFurkinDataPacket(target.getId(),
                            target.getCapability(FurkinCapability.FURKIN_DATA)
                                    .orElseGet(FurkinData::new).syncNBT()));
        } else {
            try {
                EquipmentSlots.dropArchivedEquipment(player, entry.getEquipmentSnapshot());
            } catch (Exception exception) {
                FurkinMod.LOGGER.error("Furkin unbound archive equipment drop failed: id={}",
                        companionId, exception);
                return Result.CLEANUP_FAILED;
            }
        }

        // 所有清理/归还动作成功后才删除档案。
        archive.removeEntry(companionId);

        FurkinMod.LOGGER.info("Furkin unbound: id={} by {}",
                companionId, player.getName().getString());
        return Result.OK;
    }

    /**
     * 强制解绑：在常规解绑确认实体仍不可解析后，先写服务器级注销墓碑，再删除普通档案。
     *
     * <p>本方法不会创建、召唤或重建实体；原实体以后任意维度入世时由延迟清理路径处理。
     * 如果实体当前已可解析，则返回 {@link Result#ENTITY_RESOLVED}，要求调用方走常规解绑。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @return 结果枚举
     */
    public static Result forceUnbind(ServerPlayer player, UUID companionId) {
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
        if (FurkinEntityLocator.locate(player.getServer(), entry) != null) {
            return Result.ENTITY_RESOLVED;
        }

        try {
            FurkinRevocationData revocations = FurkinRevocationData.get(player.getServer());
            revocations.put(companionId, player.getUUID(), serverLevel.getGameTime());
            if (!revocations.contains(companionId)) {
                FurkinMod.LOGGER.error("Furkin revocation tombstone was not persisted: id={}",
                        companionId);
                return Result.CLEANUP_FAILED;
            }
        } catch (RuntimeException exception) {
            FurkinMod.LOGGER.error("Furkin revocation tombstone write failed: id={}",
                    companionId, exception);
            return Result.CLEANUP_FAILED;
        }

        // 墓碑写入成功后才删除普通档案；实体本身留给入世延迟清理路径处理。
        archive.removeEntry(companionId);
        FurkinMod.LOGGER.info("Furkin force-unbound: id={} by {}",
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
        ServerLevel serverLevel = player.getLevel();

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

    /**
     * 重获一枚已亡绒亲的魂石（兜底动作，M4.3）。
     *
     * <p><b>语义（设计稿 §3.4.2「重获魂石的代价」）</b>：魂石丢了（掉岩浆 / 被捡走 / 随区块
     * 丢失）后，到录里为已亡绒亲重获一枚。代价 = <b>1 钻石 + 冷却</b>（数值进 TOML，
     * M5 平衡再定，占位 600s），且冷却 <b>per-pet</b>（记档案条目，各宠互不影响）。</p>
     *
     * <p>校验链：条目存在 → 本人 → <b>已亡</b> → 冷却已过 → 有钻石。全过则扣钻石、
     * 发一枚绑定该宠物身份的魂石进背包（满则掉脚边）、记下时间戳。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @return 结果枚举（{@code OK} 或具体失败原因，供网络包选反馈文案）
     */
    public static Result reacquireSoulstone(ServerPlayer player, UUID companionId) {
        ServerLevel serverLevel = player.getLevel();

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return Result.NOT_FOUND;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return Result.NOT_OWNER;
        }
        // 只有已亡绒亲才需要重获魂石（存活的没掉魂石这回事）。
        if (entry.isAlive()) {
            return Result.NOT_DEAD;
        }

        // 冷却校验（per-pet）：世界游戏时刻 vs 上次重获时刻 + 冷却秒数×20。
        long now = serverLevel.getGameTime();
        long cooldownTicks = FurkinServerConfig.REVIVE_COOLDOWN_SECONDS.get() * 20L;
        if (entry.getSoulstoneReacquireAt() != 0L
                && now < entry.getSoulstoneReacquireAt() + cooldownTicks) {
            return Result.ON_COOLDOWN;
        }

        // 扣 1 钻石（背包扫描，找到即 shrink）。
        if (!consumeDiamond(player)) {
            return Result.NO_DIAMOND;
        }

        // 发一枚绑定该宠物身份的魂石。
        ItemStack stone = new ItemStack(ModItems.FURKIN_SOULSTONE.get());
        FurkinSoulstoneItem.bindCompanion(stone, companionId);
        giveSoulstone(player, serverLevel, stone);

        // 记时间戳（per-pet 冷却）。
        entry.setSoulstoneReacquireAt(now);
        archive.putEntry(entry);

        FurkinMod.LOGGER.info("Furkin soulstone reacquired: id={} by {}",
                companionId, player.getName().getString());
        return Result.OK;
    }

    /** 从玩家背包扣除 1 颗钻石（主手优先，其次扫背包）。 */
    private static boolean consumeDiamond(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(Items.DIAMOND)) {
            if (!player.isCreative()) {
                main.shrink(1);
            }
            return true;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.DIAMOND)) {
                if (!player.isCreative()) {
                    stack.shrink(1);
                }
                return true;
            }
        }
        return false;
    }

    /** 把魂石放进玩家背包，满则掉脚边（与死亡掉魂石同为 ItemEntity 落物）。 */
    private static void giveSoulstone(ServerPlayer player, ServerLevel level, ItemStack stone) {
        if (!player.getInventory().add(stone)) {
            // 背包满：掉脚边（玩家脚下，非死亡位置）。
            ItemEntity drop = new ItemEntity(
                    level, player.getX(), player.getY(), player.getZ(), stone);
            drop.setPickUpDelay(0);
            level.addFreshEntity(drop);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
        }
    }

    /**
     * 打开某只绒亲的面板（潜行 + 右键本人契约绒亲触发）。
     *
     * <p>两步，顺序不可颠倒：</p>
     * <ol>
     *   <li>下发技能快照（{@link OpenFurkinScreenPacket}）；</li>
     *   <li>打开容器菜单（{@link NetworkHooks#openScreen}）。</li>
     * </ol>
     *
     * <p>先发快照的原因：客户端一收到菜单包就会立刻建屏，屏内 {@code init} 时若技能数据
     * 还没到就只能是空列表。反过来不成立 —— 快照先到时客户端会缓存（见
     * {@code FurkinPanelScreen.onSkillData}），屏后建也能套用。</p>
     *
     * <p>行囊格数随菜单下发：它是「config × travel_pouch 等级」的派生值，
     * 客户端不同步技能等级、算不出来，只能由服务端告知。</p>
     *
     * @return 是否成功打开（非本人 / 不存在 / 未契约返回 false）。
     */
    public static boolean openPanel(ServerPlayer player, LivingEntity target) {
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return false;
        }
        UUID companionId = data.getCompanionId();
        if (companionId == null || !player.getUUID().equals(data.getOwnerUuid())) {
            return false;
        }

        // ① 技能快照。
        sendSkillData(player, target, companionId);

        // ② 容器菜单。
        openMenu(player, target, data, companionId);
        return true;
    }

    /**
     * 打开 / 重开容器菜单（{@link #openPanel} 与 {@link #refreshScreen} 共用同一套规则）。
     *
     * <p>服务端拿真容器（走 {@code MenuProvider} 闭包直接持有），客户端只拿格数占位 ——
     * 行囊格数是「config × travel_pouch 等级」的派生值，客户端不同步技能等级、算不出来。</p>
     */
    private static void openMenu(ServerPlayer player, LivingEntity target,
                                 FurkinData data, UUID companionId) {
        FurkinInventory pouch = data.getPouch();
        int entityId = target.getId();
        NetworkHooks.openScreen(player,
                new SimpleMenuProvider(
                        (windowId, inv, p) -> new FurkinPouchMenu(windowId, inv, pouch, companionId, target, entityId),
                        Component.translatable("furkin.screen.furkin.title")),
                buf -> {
                    // ⚠️ 写入顺序必须与 FurkinPouchMenu.fromNetwork 的读取顺序一致。
                    buf.writeVarInt(pouch.getContainerSize());
                    buf.writeUUID(companionId);
                    // 实体网络 id：技能页要读实体上的实时属性（生命 / 护甲 / 各属性），
                    // 而客户端按 UUID 取实体的路是 protected，只有 getEntity(int) 可用（设计稿 §4.1 取证⑥）。
                    buf.writeVarInt(entityId);
                });
    }

    /**
     * 刷新某只绒亲面板（加点 / 洗点后回传最新技能点与等级；行囊格数变了则重开菜单）。
     *
     * <p>技能数据本身走 {@link OpenFurkinScreenPacket} 原地刷新即可。但<b>行囊格数是槽位布局的
     * 输入</b>：{@code Slot.x} / {@code Slot.y} 是 final，菜单建好后再无法原地增删槽位，玩家背包
     * 的位置也跟着行囊行数走。故格数一变必须重开菜单 —— 否则升级后切回行囊页仍是旧格数、
     * 洗点后也不回收（乌狸 2026-09-21 反馈的 3 条症状同源）。</p>
     *
     * <p>重开是安全的：Forge 的 {@code NetworkHooks.openScreen} 内部走
     * {@code ServerPlayer.doCloseContainer()}（<b>不发</b>关闭包，客户端不会闪一格玩家背包），
     * 客户端随后由 OpenContainer 消息直接换屏；当前页由客户端自己记住（见
     * {@code FurkinPanelScreen#lastTab}），不会被弹回默认页。</p>
     *
     * @return 是否成功回发（实体不存在 / 非本人返回 false）。
     */
    public static boolean refreshScreen(ServerPlayer player, UUID companionId) {
        LivingEntity target = findLivingByCompanionId(player.getLevel(), companionId);
        if (target == null) {
            return false;
        }
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()
                || !player.getUUID().equals(data.getOwnerUuid())) {
            return false;
        }

        // 先回快照：客户端会无条件缓存（见 FurkinPanelScreen.onSkillData），重开出的新屏要用。
        boolean sent = sendSkillData(player, target, companionId);

        int pouchSlots = data.getPouch().getContainerSize();
        if (player.containerMenu instanceof FurkinPouchMenu open
                && companionId.equals(open.getCompanionId())
                && open.getPouchSlots() != pouchSlots) {
            FurkinMod.LOGGER.info("Furkin panel reopened: id={} pouch {} -> {} slots",
                    companionId, open.getPouchSlots(), pouchSlots);
            openMenu(player, target, data, companionId);
        }
        return sent;
    }

    /** 组装并回发技能快照（打开与刷新共用，保证规则一套）。 */
    private static boolean sendSkillData(ServerPlayer player, LivingEntity target, UUID companionId) {
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return false;
        }

        // 名字：优先实体 CustomName，回退档案 name。
        String name = null;
        Component custom = target.getCustomName();
        if (custom != null) {
            name = custom.getString();
        } else {
            FurkinArchiveData archive = FurkinArchiveData.get(player.getLevel());
            FurkinArchiveEntry entry = archive.getEntry(companionId);
            if (entry != null && entry.getName() != null) {
                name = entry.getName().getString();
            }
        }

        int skillPoints = data.getSkillPoints();

        // 物种 id（用于过滤技能可见性）。
        ResourceLocation speciesId = com.wanancat.furkin.api.companion.FurkinSpeciesRegistry
                .byEntityType(target.getType())
                .map(com.wanancat.furkin.api.companion.FurkinSpecies::getId)
                .orElse(null);

        Map<ResourceLocation, Integer> skillLevels = data.getSkillLevels();
        List<OpenFurkinScreenPacket.SkillView> views = new ArrayList<>();
        // 展示顺序走 ordered()（先主干、后物种分支），不用 all() 的加载顺序。
        for (Skill skill : SkillRegistry.tree().ordered()) {
            if (!skill.availableTo(speciesId)) {
                continue;
            }
            int current = skillLevels.getOrDefault(skill.getId(), 0);
            // 未满足的前置：按「升到 current + 1」算，与 SkillProgress 实际提交的 targetLevel 同口径。
            // 已满级的技能也会算出结果，但客户端在满级行不显示前置区（那里显示「满级」更相关）。
            List<SkillTree.Requirement> unmet = SkillRegistry.tree()
                    .unmetRequirements(skill.getId(), skillLevels, current + 1);
            views.add(new OpenFurkinScreenPacket.SkillView(
                    skill.getId().toString(),
                    skill.getNameKey(),
                    skill.getDescriptionKey(),
                    skill.getMaxLevel(),
                    skill.getCost(),
                    current,
                    unmet));
        }

        // 技能加成表：服务端算（客户端既认不出技能 modifier、也读不到技能 JSON，
        // 见 AttributeEffect#totalAdditionBonuses 的说明）。
        // 只收「这只生物身上真有的属性」：技能 modifier 挂不到没有实例的属性上
        // （AttributeEffect.apply 在 getAttribute == null 时直接返回），报出来就是虚数。
        List<OpenFurkinScreenPacket.AttributeAmount> skillBonuses = new ArrayList<>();
        AttributeEffect.totalAdditionBonuses(SkillRegistry.tree(), skillLevels)
                .forEach((attributeId, sum) -> {
                    Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(attributeId);
                    if (attribute != null && target.getAttributes().hasAttribute(attribute)) {
                        skillBonuses.add(new OpenFurkinScreenPacket.AttributeAmount(
                                attributeId.toString(), sum));
                    }
                });

        // 非同步属性的权威总值 —— 集合动态得出（N5）：显示集合 ∩ 该生物实有 ∩ 非 client-syncable。
        // ⚠️ 「该生物实有」这一步由 AttributeDisplay 一并收口，不可省：原版 getAttributeValue 对
        // 「该生物没注册的属性」抛 IllegalArgumentException（AttributeSupplier#getAttributeInstance），
        // 不是返 0 —— 2026-09-22 客户端面板崩在渲染线程的同一根因，服务端这边同样会中招。
        List<OpenFurkinScreenPacket.AttributeAmount> serverTotals = new ArrayList<>();
        for (Attribute attribute : AttributeDisplay.serverTotalAttributes(target)) {
            serverTotals.add(new OpenFurkinScreenPacket.AttributeAmount(
                    AttributeDisplay.idOf(attribute), target.getAttributeValue(attribute)));
        }

        // 补推一次能力镜像：等级 / 经验只在「升级那一刻」才推（见 FurkinGrowth#addXp 的
        // `if (leveledUp)`），面板直接读客户端镜像会拿到滞后的零头。开屏与刷新各补一次，
        // 让抬头读到此刻的准确值 —— 数据源仍只有镜像这一份（设计稿 §4.1 定案 N2）。
        FurkinNetwork.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncFurkinDataPacket(target.getId(), data.syncNBT()));

        FurkinNetwork.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenFurkinScreenPacket(companionId, name, skillPoints, views,
                        skillBonuses, serverTotals));
        return true;
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

    /** 按服务器级档案的 UUID / 维度定向查找在场绒亲实体。 */
    private static LivingEntity findLivingByCompanionId(ServerLevel level, UUID companionId) {
        return FurkinCompanionManager.findLivingByCompanionId(level, companionId);
    }
}
