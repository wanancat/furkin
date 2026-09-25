package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.network.RecordActionResultPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端专用网络包处理器。
 *
 * <p>共享网络包只负责协议数据、编解码和服务端校验，客户端界面与
 * {@code Minecraft} 调用集中在本类。WP-06 会把其余客户端分发入口一并迁入。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FurkinClientPacketHandler {

    private FurkinClientPacketHandler() {
    }

    /** 处理绒亲录动作结果和强制解绑确认资格。 */
    public static void handleRecordActionResult(RecordActionResultPacket packet) {
        FurkinRecordScreen.handleActionResult(
                packet.getCompanionId(), packet.getAction(), packet.getResult(),
                packet.isForceUnbindAllowed());
    }
}
