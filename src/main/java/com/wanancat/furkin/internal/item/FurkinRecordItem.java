package com.wanancat.furkin.internal.item;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordListPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
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
        super(new Item.Properties().stacksTo(1));
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

    /** 打包本人「全部」绒亲列表（含已召唤 / 已收回 / 已死亡），下发给该玩家。 */
    private void sendRecordList(ServerPlayer player) {
        FurkinArchiveData archive = FurkinArchiveData.get(player.serverLevel());
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
                list.add(new RecordListPacket.Entry(
                        entry.getCompanionId(), speciesKey, entry.getLevel(),
                        entry.getXp(), entry.getSkillPoints(), name,
                        entry.isSummoned(), entry.isAlive()));
            }
        }

        FurkinNetwork.channel().send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new RecordListPacket(list));
    }

    /**
     * 解析物种显示名 key：优先物种注册表的 {@code nameKey}，找不到回退原版
     * {@code EntityType#getDescriptionId()}。返回的是本地化 key，客户端用
     * {@code Component.translatable} 渲染，中英文环境均正确。
     */
    private String resolveSpeciesKey(FurkinArchiveEntry entry) {
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
