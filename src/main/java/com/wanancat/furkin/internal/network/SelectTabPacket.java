package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.menu.FurkinPouchMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：告知当前所在页签。
 *
 * <p><b>为什么需要这个包</b>：页签原本是纯客户端状态（只驱动 {@code Slot#isActive()} 的
 * 渲染与鼠标命中），服务端一个字节都读不到 —— 取证确认 {@code AbstractContainerMenu}
 * 全类 <b>0 处</b>调用 {@code isActive()}。但 {@code quickMoveStack}（Shift 点击）是在
 * <b>服务端</b>执行的，它必须知道「用户现在在看哪一页」才能决定落点：装备页要把装备放进
 * 装备槽，行囊页要放进随身行囊。故把页签上行同步一次。</p>
 *
 * <p><b>已知边界</b>：服务端拿到的是<b>客户端声明</b>的页签，未做二次校验 —— 构造恶意包
 * 可以伪造页签。单机自用无碍（最坏结果是物品落进另一个容器，不会凭空产生或消失）；
 * 多人环境若要收紧，需要在服务端加一层「该页签在本菜单是否可用」的校验。</p>
 *
 * <p><b>为何整页切换才发包</b>：页签只在用户点按钮时变，频率极低；不需要每 tick 同步，
 * 也不需要回执 —— 万一丢包，下一次切页签会带上正确值。</p>
 */
public final class SelectTabPacket {

    private final int tab;

    public SelectTabPacket(int tab) {
        this.tab = tab;
    }

    public static void encode(SelectTabPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.tab);
    }

    public static SelectTabPacket decode(FriendlyByteBuf buf) {
        return new SelectTabPacket(buf.readVarInt());
    }

    public static void handle(SelectTabPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            // 当前打开的菜单不是绒亲面板就忽略 —— 页签对别的菜单没有意义。
            if (player != null && player.containerMenu instanceof FurkinPouchMenu menu) {
                menu.setActiveTab(packet.tab);
            }
        });
        ctx.setPacketHandled(true);
    }
}
