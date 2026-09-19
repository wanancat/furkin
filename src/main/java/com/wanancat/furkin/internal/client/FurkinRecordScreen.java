package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordListPacket;
import com.wanancat.furkin.internal.network.RequestSummonPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 绒亲录列表界面 —— 右键绒亲录物品打开，列出本人「全部」绒亲
 * （含已召唤 / 已收回 / 已死亡，见设计稿 §3.4.1），点「召唤」按状态分流：
 * 未召唤 → 召唤重建实体；已召唤 → 传送到主人身边；已死亡 → 无按钮（走复活流程 M4）。
 *
 * <p>纯 {@link Screen}（不绑 {@code AbstractContainerMenu}）：本界面无槽位，
 * 仅「展示列表 + 点选召唤」。数据由服务端经 {@link RecordListPacket} 下发，
 * 召唤经 {@link RequestSummonPacket} 上行。</p>
 *
 * <p><b>端位隔离</b>：本类 {@link OnlyIn}{@code (Dist.CLIENT)}，服务端加载时
 * 方法体被 RuntimeDistCleaner 替换为抛异常，避免「DEDICATED_SERVER 加载 Screen」崩溃。
 * 打开入口统一走 {@link #open(List)} 静态方法，由网络包经 {@code DistExecutor} 间接调用。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FurkinRecordScreen extends Screen {

    /** 列表数据。 */
    private final List<RecordListPacket.Entry> entries;

    /** 滚动偏移（列表过长时用，M1 先做最简：不做滚动，够用）。 */
    private static final int ITEM_HEIGHT = 24;
    private static final int LIST_TOP = 40;
    private static final int LIST_LEFT = 30;
    private static final int LIST_WIDTH = 200;

    public FurkinRecordScreen(List<RecordListPacket.Entry> entries) {
        super(Component.translatable("furkin.screen.record.title"));
        this.entries = entries;
    }

    /** 打开绒亲录列表界面（客户端专用入口，服务端不可调用）。 */
    public static void open(List<RecordListPacket.Entry> entries) {
        Minecraft.getInstance().setScreen(new FurkinRecordScreen(entries));
    }

    @Override
    protected void init() {
        super.init();
        int y = LIST_TOP;
        for (RecordListPacket.Entry entry : entries) {
            final var id = entry.getCompanionId();
            // 已死亡 → 无召唤按钮（复活走 M4 流程，不在 M1 提供入口）。
            if (entry.isAlive()) {
                Component label = Component.literal(
                        entry.getSpeciesName() + "  Lv." + entry.getLevel());
                // 已召唤/未召唤共用「召唤」字样：未召唤→重建实体，已召唤→传送身边（用户定：B+召唤）。
                Button summonBtn = Button.builder(
                                Component.translatable("furkin.screen.record.summon"),
                                btn -> requestSummon(id))
                        .bounds(LIST_LEFT, y, 60, 20)
                        .build();
                addRenderableWidget(summonBtn);
            }
            y += ITEM_HEIGHT;
        }

        // 关闭按钮。
        addRenderableWidget(Button.builder(Component.translatable("furkin.screen.record.close"),
                        btn -> onClose())
                .bounds(this.width / 2 - 40, this.height - 30, 80, 20)
                .build());
    }

    /** 点「召唤」：上行请求召唤包（服务端按状态分流召唤 / 传送）。 */
    private void requestSummon(java.util.UUID companionId) {
        FurkinNetwork.channel().sendToServer(new RequestSummonPacket(companionId));
        // 召唤 / 传送后关闭界面（结果以 action bar / 聊天反馈，界面无需停留）。
        onClose();
    }

    /** 列表条目显示名：有自定义名用名字，否则回退物种名。 */
    private net.minecraft.network.chat.MutableComponent entryLabel(RecordListPacket.Entry entry) {
        if (entry.hasName()) {
            return Component.literal(entry.getName());
        }
        return Component.translatable(entry.getSpeciesName());
    }

    /** 条目状态后缀组件：已召唤→绿色「在场」，已死亡→红色「已亡」，否则空。 */
    private net.minecraft.network.chat.Component stateSuffix(RecordListPacket.Entry entry) {
        if (!entry.isAlive()) {
            return Component.literal(" ").append(
                    Component.translatable("furkin.screen.record.state_dead"))
                    .withStyle(net.minecraft.ChatFormatting.RED);
        }
        if (entry.isSummoned()) {
            return Component.literal(" ").append(
                    Component.translatable("furkin.screen.record.state_summoned"))
                    .withStyle(net.minecraft.ChatFormatting.GREEN);
        }
        return Component.empty();
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);

        // 标题。
        gui.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // 列表：物种名 + 等级 + 状态标记 + 召唤按钮。
        int y = LIST_TOP + 5;
        for (RecordListPacket.Entry entry : entries) {
            gui.drawString(this.font,
                    entryLabel(entry).append(Component.literal("  Lv." + entry.getLevel()))
                            .append(stateSuffix(entry)),
                    LIST_LEFT + 70, y, 0xFFFFFF);
            y += ITEM_HEIGHT;
        }

        if (entries.isEmpty()) {
            gui.drawCenteredString(this.font,
                    Component.translatable("furkin.screen.record.empty"),
                    this.width / 2, this.height / 2, 0xAAAAAA);
        }

        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        // 不暂停游戏（列表是轻量浮层，保持世界运行）。
        return false;
    }
}
