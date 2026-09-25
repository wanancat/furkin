package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 网络通道 —— 把绒亲能力数据从服务端同步到客户端。
 *
 * <p><b>为什么需要同步</b>：{@code FurkinData} 只在服务端写入，Forge Capability 默认
 * 不同步到客户端。客户端遍历实体时拿到的是自己 attach 的全新 {@code WILD} 数据，
 * 导致头顶图标、等级显示等客户端表现永远拿不到「已契约」真相。故加本通道。</p>
 *
 * <p><b>两个方向都在用</b>：能力数据同步走「服务端 → 客户端」，玩家操作请求走
 * 「客户端 → 服务端」（加点 / 洗点 / 召回 / 契约 / 页签）。方向是<b>每包</b>在注册时指定的
 * （{@link NetworkDirection}），不是通道级属性 —— 早前这里写过「本通道只做单向同步」，
 * 那句在挂上第一个上行包时就失效了。</p>
 */
public final class FurkinNetwork {

    /**
     * 当前线格式版本。
     *
     * <p>消息 ID 只允许追加，不得插入、重排或复用。新增/删除包、修改方向、字段或字段顺序、
     * 枚举顺序或处理器对载荷的解释语义时，必须递增本版本。只修改注释、日志或服务端内部校验
     * 且不改变线格式时，不递增。</p>
     *
     * <p>协议 `1` 是内部不一致的历史遗留，视作已废弃；协议 `2` 从 `77c1012` 起对应当前
     * `0-10` 包结构。完整包清单和变更记录见
     * {@code docs/code_review_2026-09-24/wp-04_protocol_version_governance.md}。</p>
     */
    private static final String PROTOCOL_VERSION = "2";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(FurkinMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private FurkinNetwork() {
    }

    /** 注册所有网络包。须在模组构造器（客户端与服务端共同的 setup）中调用。 */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(
                id++,
                SyncFurkinDataPacket.class,
                SyncFurkinDataPacket::encode,
                SyncFurkinDataPacket::decode,
                SyncFurkinDataPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 服务端 → 客户端：绒亲录列表（打开界面）。
        CHANNEL.registerMessage(
                id++,
                RecordListPacket.class,
                RecordListPacket::encode,
                RecordListPacket::decode,
                RecordListPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 客户端 → 服务端：请求召唤。
        CHANNEL.registerMessage(
                id++,
                RequestSummonPacket.class,
                RequestSummonPacket::encode,
                RequestSummonPacket::decode,
                RequestSummonPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 服务端 → 客户端：请求为待契约实体命名（契约第一步）。
        CHANNEL.registerMessage(
                id++,
                RequestContractNamePacket.class,
                RequestContractNamePacket::encode,
                RequestContractNamePacket::decode,
                RequestContractNamePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 客户端 → 服务端：命名确认，真正落契约（契约第二步）。
        CHANNEL.registerMessage(
                id++,
                ConfirmContractPacket.class,
                ConfirmContractPacket::encode,
                ConfirmContractPacket::decode,
                ConfirmContractPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 客户端 → 服务端：绒亲录管理动作（收回 / 解绑 / 改名）。
        CHANNEL.registerMessage(
                id++,
                RecordActionPacket.class,
                RecordActionPacket::encode,
                RecordActionPacket::decode,
                RecordActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 客户端 → 服务端：解锁（加点）技能。
        CHANNEL.registerMessage(
                id++,
                UnlockSkillPacket.class,
                UnlockSkillPacket::encode,
                UnlockSkillPacket::decode,
                UnlockSkillPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 客户端 → 服务端：洗点（消耗洗点药水，清空技能 + 退点）。
        CHANNEL.registerMessage(
                id++,
                ResetSkillsPacket.class,
                ResetSkillsPacket::encode,
                ResetSkillsPacket::decode,
                ResetSkillsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 客户端 → 服务端：同步当前页签（决定 Shift 点击的落点是装备槽还是行囊）。
        CHANNEL.registerMessage(
                id++,
                SelectTabPacket.class,
                SelectTabPacket::encode,
                SelectTabPacket::decode,
                SelectTabPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 服务端 → 客户端：打开绒亲界面（技能面板）。
        CHANNEL.registerMessage(
                id++,
                OpenFurkinScreenPacket.class,
                OpenFurkinScreenPacket::encode,
                OpenFurkinScreenPacket::decode,
                OpenFurkinScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 服务端 → 客户端：绒亲录动作结果（强制解绑确认资格）。
        CHANNEL.registerMessage(
                id++,
                RecordActionResultPacket.class,
                RecordActionResultPacket::encode,
                RecordActionResultPacket::decode,
                RecordActionResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    /** 暴露通道给包发送方（服务端契约/召唤/收回后广播）。 */
    public static SimpleChannel channel() {
        return CHANNEL;
    }
}
