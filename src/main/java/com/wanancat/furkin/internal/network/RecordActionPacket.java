package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinCombatMode;
import com.wanancat.furkin.internal.contract.FurkinCombatModeHandler;
import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import com.wanancat.furkin.internal.item.FurkinRecordItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：绒亲录管理动作（收回 / 解绑 / 改名 / 重获魂石 / 切战斗模式）。
 *
 * <p>服务端收到后按 {@link Action} 分派到 {@link FurkinRecordActionHandler}（战斗模式
 * 则分派到 {@link FurkinCombatModeHandler} —— 与命令同一套规则），并按结果回一条反馈
 * （成功 action bar，失败失败原因）。</p>
 */
public final class RecordActionPacket {

    /** 管理动作类型。 */
    public enum Action {
        DISMISS,
        UNBIND,
        RENAME,
        REACQUIRE_SOULSTONE,
        SET_COMBAT_MODE
    }

    private final Action action;
    private final UUID companionId;
    /** 仅 RENAME 使用（可空）。 */
    private final String name;
    /** 仅 SET_COMBAT_MODE 使用（可空）。 */
    private final FurkinCombatMode combatMode;

    /**
     * 处理完是否让服务端<b>重发一份绒亲录列表</b>（默认 {@code false}）。
     *
     * <p><b>为什么需要这个开关</b>（2026-09-22 她报「页签点战斗模式→跳转到绒亲录了」）：
     * 本包有两个发送方 —— <b>绒亲录</b>（点完不关屏，靠服务端重发列表换新屏刷新自身）
     * 与 <b>技能面板</b>（点完只改档位，面板自己就地刷新，<b>不该</b>被拽去录界面）。
     * 原先无条件重发 ⇒ 面板点一下就被弹进绒亲录。</p>
     *
     * <p>⇒ 判据：**「要不要刷新录」是发送方的意图，不是包本身的语义**。
     * 谁需要谁置位，别的调用方一律默认不带。</p>
     */
    private final boolean refreshRecord;

    /** 兼容既有调用（无战斗模式、不刷新录）。 */
    public RecordActionPacket(Action action, UUID companionId, String name) {
        this(action, companionId, name, null);
    }

    /** 兼容既有调用（无战斗模式、不刷新录）。 */
    public RecordActionPacket(Action action, UUID companionId, String name,
                              FurkinCombatMode combatMode) {
        this(action, companionId, name, combatMode, false);
    }

    public RecordActionPacket(Action action, UUID companionId, String name,
                              FurkinCombatMode combatMode, boolean refreshRecord) {
        this.action = action;
        this.companionId = companionId;
        this.name = name;
        this.combatMode = combatMode;
        this.refreshRecord = refreshRecord;
    }

    public static void encode(RecordActionPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action);
        buf.writeUUID(packet.companionId);
        buf.writeUtf(packet.name == null ? "" : packet.name);
        // 战斗模式：无则写哨兵序数 -1（writeEnum 写的是序数，不能写 null）。
        buf.writeVarInt(packet.combatMode == null ? -1 : packet.combatMode.ordinal());
        buf.writeBoolean(packet.refreshRecord);
    }

    public static RecordActionPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        int modeOrdinal = buf.readVarInt();
        FurkinCombatMode mode = modeOrdinal >= 0 && modeOrdinal < FurkinCombatMode.values().length
                ? FurkinCombatMode.values()[modeOrdinal]
                : null;
        boolean refreshRecord = buf.readBoolean();
        return new RecordActionPacket(action, id, name.isEmpty() ? null : name, mode, refreshRecord);
    }

    public static void handle(RecordActionPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                applyServer(player, packet);
            }
        });
        ctx.setPacketHandled(true);
    }

    /** 服务端应用：按动作分派，回反馈。 */
    private static void applyServer(ServerPlayer player, RecordActionPacket packet) {
        String msgKey;
        boolean ok;
        // 战斗模式回执要带档位名（`furkin.msg.mode_set` 是「战斗模式：%s」带参格式）。
        Component msgArg = null;

        switch (packet.action) {
            case DISMISS -> {
                ok = FurkinRecordActionHandler.dismiss(player, packet.companionId);
                msgKey = ok ? "furkin.msg.dismissed" : "furkin.msg.dismiss_failed";
            }
            case UNBIND -> {
                FurkinRecordActionHandler.Result r =
                        FurkinRecordActionHandler.unbind(player, packet.companionId);
                ok = r == FurkinRecordActionHandler.Result.OK;
                msgKey = ok ? "furkin.msg.unbound" : "furkin.msg.unbind_failed";
            }
            case RENAME -> {
                FurkinRecordActionHandler.Result r =
                        FurkinRecordActionHandler.rename(player, packet.companionId, packet.name);
                ok = r == FurkinRecordActionHandler.Result.OK;
                msgKey = ok ? "furkin.msg.renamed" : "furkin.msg.rename_failed";
            }
            case REACQUIRE_SOULSTONE -> {
                FurkinRecordActionHandler.Result r =
                        FurkinRecordActionHandler.reacquireSoulstone(player, packet.companionId);
                ok = r == FurkinRecordActionHandler.Result.OK;
                msgKey = switch (r) {
                    case OK -> "furkin.msg.soulstone_reacquired";
                    case NOT_FOUND, NOT_OWNER -> "furkin.msg.not_owner";
                    case NOT_DEAD -> "furkin.msg.soulstone_not_dead";
                    case ON_COOLDOWN -> "furkin.msg.soulstone_cooldown";
                    case NO_DIAMOND -> "furkin.msg.soulstone_no_diamond";
                    default -> "furkin.msg.soulstone_reacquire_failed";
                };
            }
            case SET_COMBAT_MODE -> {
                FurkinCombatModeHandler.Result r =
                        FurkinCombatModeHandler.setMode(player, packet.companionId, packet.combatMode);
                ok = r == FurkinCombatModeHandler.Result.OK;
                msgKey = switch (r) {
                    case OK -> "furkin.msg.mode_set";
                    case NOT_FOUND, NOT_OWNER -> "furkin.msg.mode_not_owner";
                    case NOT_SUMMONED -> "furkin.msg.mode_not_summoned";
                    case INVALID_MODE -> "furkin.msg.mode_invalid";
                    case APPLY_FAILED -> "furkin.msg.mode_failed";
                    default -> "furkin.msg.mode_failed";
                };
                // ⚠️ `furkin.msg.mode_set` = 「战斗模式：%s」是**带参**文案 —— 不传参就会把
                // 字面的 "%s" 显示出来（2026-09-22 她报的正是这个）。档位名走 lang，
                // 让客户端按自身语言渲染。
                if (ok && packet.combatMode != null) {
                    msgArg = Component.translatable(
                            "furkin.combat_mode." + packet.combatMode.name().toLowerCase(Locale.ROOT));
                }
            }
            default -> {
                return;
            }
        }

        player.displayClientMessage(
                msgArg == null ? Component.translatable(msgKey)
                        : Component.translatable(msgKey, msgArg),
                ok);

        // 录内按钮点完不关屏（2026-09-22 她定）⇒ 主动重发一份列表，让界面就地刷新。
        // ⚠️ 用 refreshRecordList（openScreen=false）—— **不重开屏**。
        // 技能面板也走本包切档，若无条件重发 + 开屏，面板点一下就被拽去录界面
        // （2026-09-22 她报的那个 bug）。且只对「绒亲录」发来的包做（refreshRecord）。
        if (packet.refreshRecord) {
            FurkinRecordItem.refreshRecordList(player);
        }
    }
}
