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
 * <p>通道只做「服务端 → 客户端」单向同步（{@link NetworkDirection#PLAY_TO_CLIENT}）。</p>
 */
public final class FurkinNetwork {

    private static final String PROTOCOL_VERSION = "1";

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
    }

    /** 暴露通道给包发送方（服务端契约/召唤/收回后广播）。 */
    public static SimpleChannel channel() {
        return CHANNEL;
    }
}
