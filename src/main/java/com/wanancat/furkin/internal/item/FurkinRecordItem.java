package com.wanancat.furkin.internal.item;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordListPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.record.FurkinDisplayOrder;
import com.wanancat.furkin.internal.record.RecordAttributes;
import com.wanancat.furkin.internal.registry.ModCreativeTab;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 绒亲录 —— 打开档案的入口（设计稿 §2.2 物品层）。
 *
 * <p>物品本体 NBT 里什么都不放，只是「打开档案」的入口。右键时在服务端
 * 打包「本人已收回的绒亲列表」发给客户端，客户端打开列表界面选条目召唤。</p>
 *
 * <p>交互逻辑不内聚在物品类里，而是：物品 {@code use} 触发数据打包 →
 * 网络包下发 → 客户端 {@code FurkinRecordScreen} 渲染。保持「物品是数据、
 * 动作是事件 / 界面是客户端」的边界。</p>
 */
public class FurkinRecordItem extends Item {

    public FurkinRecordItem() {
        super(new Item.Properties().stacksTo(1).tab(ModCreativeTab.FURKIN_TAB));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 仅在服务端处理（打包档案 → 下发列表 → 客户端开屏）。
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            sendRecordList(serverPlayer);
        }

        // 返回 success 而非 consume：不消耗物品、不播默认挥手动画（M5 打磨可调）。
        return InteractionResultHolder.success(stack);
    }

    /**
     * 打包本人「全部」绒亲列表（含已召唤 / 已收回 / 已死亡）并<b>开屏</b>下发给该玩家。
     *
     * <p>右键绒亲录物品走这里 —— 客户端收到后 {@code setScreen} 打开界面。</p>
     */
    public static void sendRecordList(ServerPlayer player) {
        send(player, true);
    }

    /**
     * 打包同上，但下发的是<b>刷新用途</b>的包（客户端不会重开屏，只喂给已在的录界面）。
     *
     * <p><b>static 且 public</b>（2026-09-22）：录内按钮点完<b>不再关屏</b>，服务端处理完动作后
     * 要重发一份列表让界面就地刷新，故 {@code RecordActionPacket} 侧也要调它 ——
     * 本方法不依赖物品实例，改成工具方法即可复用。</p>
     *
     * <p>⚠️ 与 {@link #sendRecordList} 的区别只在 {@code openScreen} 标志：本方法
     * <b>不重新 setScreen</b>。否则技能面板切档也会被弹进录界面（2026-09-22 她报的 bug）。</p>
     */
    public static void refreshRecordList(ServerPlayer player) {
        send(player, false);
    }

    /** 打包列表并按 {@code openScreen} 下发（唯一实现，两个入口共用）。 */
    private static void send(ServerPlayer player, boolean openScreen) {
        FurkinArchiveData archive = FurkinArchiveData.get(player.getLevel());
        UUID me = player.getUUID();

        List<RecordListPacket.Entry> list = new ArrayList<>();
        for (FurkinArchiveEntry entry : archive.allEntries()) {
            // 列「本人」全部（设计稿 §3.4.1：录里能看到自己拥有的全部宠物，
            // 含已收回、已死亡）。召唤 / 传送 / 复活等动作由客户端按状态分流。
            if (me.equals(entry.getOwnerUuid())) {
                // 物种显示名：优先走物种注册表的 nameKey（furkin.species.cat 等），
                // 找不到再回退原版 EntityType 的 descriptionId（entity.minecraft.cat）。
                String speciesKey = resolveSpeciesKey(entry);
                // 名字：档案里存的自定义名（Component），序列化为字符串下发。
                String name = entry.getName() == null
                        ? null
                        : entry.getName().getString();
                // 属性两条路径（2026-09-22 定）：在场宠物实时读活体（快照只在收回 /
                // 死亡时落，穿脱装备不更新，走快照必旧值）；已收回 / 已死亡走快照。
                List<RecordAttributes.Line> attributes;
                if (entry.isSummoned()) {
                    LivingEntity living = FurkinCompanionManager
                            .findLivingByCompanionId(player.getLevel(), entry.getCompanionId());
                    // 实体找不到（理论不该发生）→ 回退快照，防御性兜底。
                    attributes = living != null
                            ? RecordAttributes.computeLive(living)
                            : RecordAttributes.compute(entry);
                } else {
                    attributes = RecordAttributes.compute(entry);
                }
                list.add(new RecordListPacket.Entry(
                        entry.getCompanionId(), speciesKey, entry.getLevel(),
                        entry.getXp(), entry.getSkillPoints(), name,
                        entry.isSummoned(), entry.isAlive(),
                        entry.getCombatMode(),
                        attributes));
            }
        }

        // 默认排序（2026-09-22 定）：物种 > 等级（降序）> id（升序）。
        // 口径集中定义在 FurkinDisplayOrder（与 /furkin list 同源，两处共用一份比较逻辑）。
        list.sort(FurkinDisplayOrder.RECORD_ENTRY);

        FurkinNetwork.channel().send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new RecordListPacket(list, openScreen));
    }

    /**
     * 解析物种显示名 key：优先物种注册表的 {@code nameKey}，找不到回退原版
     * {@code EntityType#getDescriptionId()}。返回的是本地化 key，客户端用
     * {@code Component.translatable} 渲染，中英文环境均正确。
     */
    private static String resolveSpeciesKey(FurkinArchiveEntry entry) {
        if (entry.getSpecies() != null) {
            java.util.Optional<FurkinSpecies> species =
                    FurkinSpeciesRegistry.byEntityType(entry.getSpecies());
            if (species.isPresent()) {
                return species.get().getNameKey();
            }
            return entry.getSpecies().getDescriptionId();
        }
        return "furkin.species.unknown";
    }
}
