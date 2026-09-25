package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.contract.FurkinState;
import com.wanancat.furkin.internal.network.OpenFurkinScreenPacket;
import com.wanancat.furkin.internal.network.RecordActionResultPacket;
import com.wanancat.furkin.internal.network.RecordListPacket;
import com.wanancat.furkin.internal.network.RequestContractNamePacket;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端专用的网络包处理器。
 *
 * <p>共享网络包只负责协议数据、编解码和服务端校验，所有对客户端界面、
 * {@link Minecraft} 和客户端本地状态的调用都集中在这里。本类及其方法只在
 * {@link Dist#CLIENT} 加载，由网络包通过 {@code DistExecutor.unsafeRunWhenOn}
 * 间接调用。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FurkinClientPacketHandler {

    private FurkinClientPacketHandler() {
    }

    /** 把服务端同步的绒亲数据写回本地实体 capability。 */
    public static void handleSyncFurkinData(SyncFurkinDataPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(packet.getEntityId());
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return;
        }
        CompoundTag nbt = packet.getData();
        if (nbt != null && !nbt.isEmpty()) {
            data.deserializeNBT(nbt);
        } else {
            // 空包 = 收回，清回 WILD。
            data.setState(FurkinState.WILD);
        }
    }

    /** 把技能快照交给当前技能面板。 */
    public static void handleSkillData(OpenFurkinScreenPacket packet) {
        FurkinPanelScreen.onSkillData(packet);
    }

    /** 处理绒亲录动作结果和强制解绑确认资格。 */
    public static void handleRecordActionResult(RecordActionResultPacket packet) {
        FurkinRecordScreen.handleActionResult(
                packet.getCompanionId(), packet.getAction(), packet.getResult(),
                packet.isForceUnbindAllowed());
    }

    /** 打开契约命名界面。 */
    public static void openContractName(RequestContractNamePacket packet) {
        ContractNameScreen.open(packet.getEntityId());
    }

    /** 打开或刷新绒亲录界面。 */
    public static void handleRecordList(RecordListPacket packet) {
        if (packet.isOpenScreen()) {
            FurkinRecordScreen.open(packet.getEntries());
        } else {
            FurkinRecordScreen.handleRefresh(packet.getEntries());
        }
    }
}
