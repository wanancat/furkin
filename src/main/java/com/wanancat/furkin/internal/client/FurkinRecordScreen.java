package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordActionPacket;
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
import java.util.UUID;

/**
 * 绒亲录列表界面 —— 右键绒亲录物品打开，列出本人「全部」绒亲
 * （含已召唤 / 已收回 / 已死亡，见设计稿 §3.4.1），每条目按状态提供管理动作：
 * <ul>
 *   <li>存活 + 未召唤：召唤 / 解绑 / 改名</li>
 *   <li>存活 + 已召唤：召唤（=传送身边）/ 收回 / 解绑 / 改名</li>
 *   <li>已死亡：解绑（复活走 M4，不在本界面）</li>
 * </ul>
 *
 * <p>「召唤」按状态分流（未召唤→重建实体；已召唤→传送身边）。管理动作经
 * {@link RecordActionPacket} 上行，服务端统一走 {@code FurkinRecordActionHandler}（规则一套）。</p>
 *
 * <p><b>端位隔离</b>：本类 {@link OnlyIn}{@code (Dist.CLIENT)}，服务端加载时
 * 方法体被 RuntimeDistCleaner 替换为抛异常。打开入口统一走 {@link #open(List)}，
 * 由网络包经 {@code DistExecutor} 间接调用。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FurkinRecordScreen extends Screen {

    /** 列表数据。 */
    private final List<RecordListPacket.Entry> entries;

    /** 布局常量（最简临时版，精细布局留 UI 打磨轮）。 */
    private static final int ITEM_HEIGHT = 26;
    private static final int LIST_TOP = 40;
    private static final int LIST_LEFT = 30;
    /** 名字文字起始 x（按钮在其右侧）。 */
    private static final int LABEL_X = 40;

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
            addActionButtons(entry, y);
            y += ITEM_HEIGHT;
        }

        // 关闭按钮。
        addRenderableWidget(Button.builder(Component.translatable("furkin.screen.record.close"),
                        btn -> onClose())
                .bounds(this.width / 2 - 40, this.height - 30, 80, 20)
                .build());
    }

    /** 按状态为一条目添加管理按钮。 */
    private void addActionButtons(RecordListPacket.Entry entry, int y) {
        UUID id = entry.getCompanionId();
        boolean alive = entry.isAlive();
        boolean summoned = entry.isSummoned();
        // 按钮从右往左排，避免文字被遮挡。
        int btnW = 44;
        int gap = 3;
        int right = this.width - 20;
        int x;

        // 解绑（所有状态都有）。
        x = right - btnW;
        addRenderableWidget(Button.builder(
                        Component.translatable("furkin.screen.record.unbind"),
                        btn -> requestAction(RecordActionPacket.Action.UNBIND, id, null))
                .bounds(x, y, btnW, 20).build());
        right = x - gap;

        // 改名（存活才有）。
        if (alive) {
            x = right - btnW;
            addRenderableWidget(Button.builder(
                            Component.translatable("furkin.screen.record.rename"),
                            btn -> openRename(id, entry))
                    .bounds(x, y, btnW, 20).build());
            right = x - gap;
        }

        // 收回（已召唤才有）。
        if (alive && summoned) {
            x = right - btnW;
            addRenderableWidget(Button.builder(
                            Component.translatable("furkin.screen.record.dismiss"),
                            btn -> requestAction(RecordActionPacket.Action.DISMISS, id, null))
                    .bounds(x, y, btnW, 20).build());
            right = x - gap;
        }

        // 召唤（存活才有；已召唤时语义为「传送身边」）。
        if (alive) {
            x = right - btnW;
            addRenderableWidget(Button.builder(
                            Component.translatable("furkin.screen.record.summon"),
                            btn -> requestSummon(id))
                    .bounds(x, y, btnW, 20).build());
        }
    }

    /** 点「召唤」：上行请求召唤包（服务端按状态分流召唤 / 传送）。 */
    private void requestSummon(UUID companionId) {
        FurkinNetwork.channel().sendToServer(new RequestSummonPacket(companionId));
        onClose();
    }

    /** 点「收回 / 解绑 / 改名」：上行管理动作包，服务端统一处理。 */
    private void requestAction(RecordActionPacket.Action action, UUID companionId, String name) {
        FurkinNetwork.channel().sendToServer(new RecordActionPacket(action, companionId, name));
        onClose();
    }

    /** 打开改名输入框（预填当前名字）。 */
    private void openRename(UUID companionId, RecordListPacket.Entry entry) {
        String current = entry.hasName() ? entry.getName() : "";
        RenameScreen.open(companionId, current);
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

        // 列表文字：名字 + 等级 + 经验 + 技能点 + 状态标记（按钮由 init 添加，独立渲染）。
        int y = LIST_TOP + 3;
        for (RecordListPacket.Entry entry : entries) {
            gui.drawString(this.font,
                    entryLabel(entry)
                            .append(Component.literal("  Lv." + entry.getLevel()))
                            .append(Component.literal("  经验 " + entry.getXp()))
                            .append(Component.literal("  技能点 " + entry.getSkillPoints()))
                            .append(stateSuffix(entry)),
                    LABEL_X, y, 0xFFFFFF);
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
