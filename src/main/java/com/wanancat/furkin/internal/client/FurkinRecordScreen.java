package com.wanancat.furkin.internal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wanancat.furkin.internal.contract.FurkinCombatMode;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordActionPacket;
import com.wanancat.furkin.internal.network.RecordListPacket;
import com.wanancat.furkin.internal.network.RequestSummonPacket;
import com.wanancat.furkin.internal.record.RecordAttributes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 绒亲录列表界面 —— 右键绒亲录物品打开，列出本人「全部」绒亲
 * （含已召唤 / 已收回 / 已死亡，见设计稿 §3.4.1），左侧列表选中某只 → 右侧详情卡
 * 显示它的管理动作 + 属性区（服务端算好随列表包下发，脱实体可算，见 {@link RecordAttributes}）。
 *
 * <p><b>布局</b>（M5 打磨，2026-09-22 定）：左列表（可选中）+ 右详情卡。管理动作
 * （召唤 / 收回 / 改名 / 模式 / 解绑 / 重获魂石）从「每条目右侧」移到「右侧详情卡」，按选中
 * 条目状态分流。属性区沿用技能页属性区那套「名称 总值 (基础+技能+装备)」配色与格式。</p>
 *
 * <p><b>详情卡按钮版式</b>（2026-09-22 她定稿，三行两列，锚底）：
 * <pre>
 *   存活：  召唤 丨 收回          改动：  重获魂石
 *           改名 丨 模式                   解绑
 *           解绑
 * </pre>
 * 「收回」仅已召唤时出现（未召唤时第一行只有召唤）；「模式」仅已召唤时可点（未召唤置灰）。</p>
 *
 * <p>「召唤」按状态分流（未召唤→重建实体；已召唤→传送身边）。管理动作经
 * {@link RecordActionPacket} 上行（<b>带 {@code refreshRecord = true}</b>，让服务端回发列表
 * 刷新本屏），服务端统一走 {@code FurkinRecordActionHandler}（规则一套）。</p>
 *
 * <p><b>端位隔离</b>：本类 {@link OnlyIn}{@code (Dist.CLIENT)}，服务端加载时
 * 方法体被 RuntimeDistCleaner 替换为抛异常。打开入口统一走 {@link #open(List)}，
 * 由网络包经 {@code DistExecutor} 间接调用。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FurkinRecordScreen extends Screen {

    /** 列表数据（服务端回发刷新时**就地替换**，见 {@link #acceptRefresh}）。 */
    private List<RecordListPacket.Entry> entries;

    /** 当前选中的条目下标（-1 = 未选中）。 */
    private int selectedIndex = -1;

    /** 左列表组件（选中后只刷右侧详情、不重建它，避免滚动位置被重置回顶部）。 */
    private RecordEntryList listWidget;

    /** 右侧详情卡的管理按钮（刷新详情时精确清除，避免误删关闭按钮）。 */
    private final List<Button> detailButtons = new ArrayList<>();

    // ===== 布局常量（左列表 + 右详情，M5 打磨） =====

    /** 左列表区。 */
    private static final int LIST_LEFT = 20;
    private static final int LIST_TOP = 40;
    private static final int LIST_ITEM_HEIGHT = 22;
    private static final int LIST_WIDTH = 150;

    /** 右详情区。 */
    private static final int DETAIL_LEFT = 190;
    private static final int DETAIL_TOP = 40;

    /** 属性行高（属性滚动列表 itemHeight）。 */
    private static final int ATTR_LINE_HEIGHT = 11;
    /** 属性滚动列表区顶部（「属性」小标题下一行）。 */
    private static final int ATTR_LIST_TOP = DETAIL_TOP + 32;

    /** 属性明细配色 —— 与技能页属性区一致（黄=总、灰=基础、绿=技能、蓝=装备、白=名）。 */
    private static final ChatFormatting COLOR_ATTR_NAME = ChatFormatting.WHITE;
    private static final ChatFormatting COLOR_ATTR_TOTAL = ChatFormatting.YELLOW;
    private static final ChatFormatting COLOR_ATTR_BASE = ChatFormatting.GRAY;
    private static final ChatFormatting COLOR_ATTR_SKILL = ChatFormatting.GREEN;
    private static final ChatFormatting COLOR_ATTR_EQUIP = ChatFormatting.BLUE;

    public FurkinRecordScreen(List<RecordListPacket.Entry> entries) {
        super(Component.translatable("furkin.screen.record.title"));
        this.entries = entries;
        if (!entries.isEmpty()) {
            this.selectedIndex = 0;
        }
    }

    /** 打开绒亲录列表界面（客户端专用入口，服务端不可调用）。 */
    public static void open(List<RecordListPacket.Entry> entries) {
        Minecraft.getInstance().setScreen(new FurkinRecordScreen(entries));
    }

    /**
     * 服务端回发的<b>刷新</b>数据（`openScreen = false` 的那份）。
     *
     * <p><b>就地替换</b>而不是换屏实例（2026-09-22 重做）：换新实例会丢选中
     * （新屏默认选中第 0 条，而列表按「物种 &gt; 等级 &gt; id」排序、改名或升级会让条目换位，
     * 按下标记更糟）。就地替换则能<b>按 id 保住选中</b>，也保住了滚动位置。</p>
     *
     * @param fresh 服务端刚算出的新列表
     */
    public void acceptRefresh(List<RecordListPacket.Entry> fresh) {
        UUID keep = selectedEntry() == null ? null : selectedEntry().getCompanionId();
        this.entries = fresh;
        // 按 id 找回选中（条目可能换位 / 甚至消失 —— 被解绑后就不在列表里了）。
        this.selectedIndex = -1;
        if (keep != null) {
            for (int i = 0; i < fresh.size(); i++) {
                if (keep.equals(fresh.get(i).getCompanionId())) {
                    this.selectedIndex = i;
                    break;
                }
            }
        }
        // 原选中项没了（被解绑）→ 退回第一条（还有的话）。
        if (this.selectedIndex < 0 && !fresh.isEmpty()) {
            this.selectedIndex = 0;
        }
        // 整屏重建：条目集合变了，左右两侧都要跟着走；rebuild 内负责恢复滚动量。
        rebuild();
    }

    /**
     * 收到刷新包时的总入口 —— <b>不重开屏</b>，交给已在的录界面就地更新。
     *
     * <p>若当前屏不是绒亲录（比如她在技能面板上点了档位），则<b>什么都不做</b>：
     * 那份数据本就不是冲她正看的界面来的，弹屏是错的（2026-09-22 她报的 bug）。</p>
     */
    public static void handleRefresh(List<RecordListPacket.Entry> entries) {
        if (Minecraft.getInstance().screen instanceof FurkinRecordScreen record) {
            record.acceptRefresh(entries);
        }
    }

    @Override
    protected void init() {
        super.init();
        rebuild();
    }

    /** 按当前选中态重建：左列表（原版滚动列表）+ 右属性滚动列表 + 锚底按钮。 */
    private void rebuild() {
        double previousScroll = listWidget == null ? 0.0D : listWidget.getScrollAmount();
        clearWidgets();

        // 左列表：AbstractSelectionList（自带滚动条 / 滚轮 / 拖拽），条目自绘。
        listWidget = new RecordEntryList(
                Math.max(LIST_ITEM_HEIGHT, this.height - LIST_TOP - 40));
        List<RecordEntryList.ItemRow> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            rows.add(listWidget.new ItemRow(i));
        }
        listWidget.replaceRows(rows);
        if (selectedIndex >= 0 && selectedIndex < rows.size()) {
            listWidget.setSelected(rows.get(selectedIndex));
        }
        listWidget.setScrollAmount(previousScroll);
        addRenderableWidget(listWidget);

        // 关闭按钮（固定底部，只加一次）。
        addRenderableWidget(new Button(this.width / 2 - 40, this.height - 30, 80, 20,
                Component.translatable("furkin.screen.record.close"),
                btn -> onClose()));

        // 右详情卡：属性滚动列表 + 管理按钮（锚定底部）。
        refreshDetail();
    }

    /**
     * 只刷新右侧详情卡（属性列表 + 按钮），<b>不动左列表</b>。
     * 选中条目时走这里而非 {@link #rebuild()}，否则重建会重置 scrollAmount、
     * 列表跳回顶部（2026-09-22 修）。
     */
    private void refreshDetail() {
        // 清掉旧的详情卡组件（属性列表 + 管理按钮），保留左列表与关闭按钮。
        clearDetailWidgets();

        RecordListPacket.Entry selected = selectedEntry();
        if (selected != null) {
            if (!selected.getAttributes().isEmpty()) {
                RecordAttributeList attrList = new RecordAttributeList(
                        Math.max(ATTR_LINE_HEIGHT, detailButtonsTop() - 6 - ATTR_LIST_TOP));
                List<RecordAttributeList.LineRow> lineRows = new ArrayList<>();
                for (RecordAttributes.Line line : selected.getAttributes()) {
                    lineRows.add(attrList.new LineRow(line));
                }
                attrList.replaceRows(lineRows);
                addRenderableWidget(attrList);
            }
            addDetailButtons(selected);
        }
    }

    /**
     * 详情卡按钮区顶端 —— 属性滚动列表的下界。
     *
     * <p><b>为什么抽出来</b>（2026-09-22）：属性列表原先把高度写成 {@code height - 90}，
     * 这个 90 是「两行按钮」时代的硬编码。按钮加到三行后属性列表仍按旧式算高，
     * 底缘会伸进按钮区（小窗口下更明显）。改为两边共用本方法 —— 改行数不会再有一边漏改。</p>
     */
    private int detailButtonsTop() {
        return this.height - 40 - DETAIL_BUTTONS_HEIGHT;
    }

    /** 详情卡按钮区总高 —— 三行按钮（20px）+ 两个行距（4px）。 */
    private static final int DETAIL_BUTTONS_HEIGHT = 20 * 3 + 4 * 2;

    /** 移除右侧详情卡组件（属性列表 + 管理按钮），保留左列表与关闭按钮。 */
    private void clearDetailWidgets() {
        // 属性滚动列表。
        for (net.minecraft.client.gui.components.events.GuiEventListener child
                : new ArrayList<>(this.children())) {
            if (child instanceof RecordAttributeList) {
                this.removeWidget(child);
            }
        }
        // 管理按钮（精确按引用删，不误伤关闭按钮）。
        for (Button btn : detailButtons) {
            this.removeWidget(btn);
        }
        detailButtons.clear();
    }

    /** 选中某条，重画。 */
    private void select(int index) {
        if (index < 0 || index >= entries.size()) {
            return;
        }
        this.selectedIndex = index;
        refreshDetail();
    }

    private RecordListPacket.Entry selectedEntry() {
        if (selectedIndex < 0 || selectedIndex >= entries.size()) {
            return null;
        }
        return entries.get(selectedIndex);
    }

    /** 左列表条目标签：名字 + 状态标记（选中态加亮色前缀）。 */
    private MutableComponent listLabel(RecordListPacket.Entry entry, boolean selected) {
        MutableComponent label = entryLabel(entry).append(stateSuffix(entry));
        if (selected) {
            return label.withStyle(ChatFormatting.YELLOW);
        }
        return label.withStyle(ChatFormatting.WHITE);
    }

    /** 右详情卡：管理按钮（三行两列，锚定详情卡底部 —— 属性再多也不被推出屏）。 */
    private void addDetailButtons(RecordListPacket.Entry entry) {
        UUID id = entry.getCompanionId();
        boolean alive = entry.isAlive();
        boolean summoned = entry.isSummoned();
        int btnW = 90;      // 单格按钮宽
        int btnH = 20;
        int gap = 4;
        int rowGap = 4;
        int col2 = DETAIL_LEFT + btnW + gap;

        // 锚底：三行按钮占 20×3+4×2=68px，底部对齐关闭按钮上方 10px。
        // ⚠️ 2026-09-22 加「战斗模式」后由两行（44px）变三行，这里必须同步 ——
        // 否则第三行会压到关闭按钮上。行数与 detailButtonsTop() 共用同一常量。
        int y = detailButtonsTop();

        if (alive) {
            // 第一行：召唤（左）｜ 收回（右，仅已召唤时）。
            detailButton(Component.translatable("furkin.screen.record.summon"),
                    btn -> requestSummon(id), DETAIL_LEFT, y, btnW, btnH);
            if (summoned) {
                detailButton(Component.translatable("furkin.screen.record.dismiss"),
                        btn -> requestAction(RecordActionPacket.Action.DISMISS, id, null),
                        col2, y, btnW, btnH);
            }
            y += btnH + rowGap;
            // 第二行（2026-09-22 她定稿）：改名（左）｜ 模式（右）。
            detailButton(Component.translatable("furkin.screen.record.rename"),
                    btn -> openRename(id, entry), DETAIL_LEFT, y, btnW, btnH);
            // 战斗模式循环。与命令同门槛 —— 未召唤时不可切（置灰）。
            Button modeBtn = detailButton(combatModeLabel(entry),
                    btn -> requestAction(RecordActionPacket.Action.SET_COMBAT_MODE, id, null,
                            nextMode(entry.getCombatMode())),
                    col2, y, btnW, btnH);
            modeBtn.active = summoned;
            y += btnH + rowGap;
            // 第三行：解绑（左格，独占一行）。
            detailButton(Component.translatable("furkin.screen.record.unbind"),
                    btn -> requestAction(RecordActionPacket.Action.UNBIND, id, null),
                    DETAIL_LEFT, y, btnW, btnH);
        } else {
            // 已亡：第一行「重获魂石」占左格（2026-09-22 定，不横跨）；第二行「解绑」放左格。
            detailButton(Component.translatable("furkin.screen.record.reacquire_soulstone"),
                    btn -> requestAction(RecordActionPacket.Action.REACQUIRE_SOULSTONE, id, null),
                    DETAIL_LEFT, y, btnW, btnH);
            y += btnH + rowGap;
            detailButton(Component.translatable("furkin.screen.record.unbind"),
                    btn -> requestAction(RecordActionPacket.Action.UNBIND, id, null),
                    DETAIL_LEFT, y, btnW, btnH);
        }
    }

    /**
     * 战斗模式按钮文字 —— 显示<b>当前档位</b>（点击切到下一档）。
     *
     * <p>与命令 {@code /furkin list} 的口径一致：档位名走 lang（命令侧因不走 lang 而写字面量，
     * 界面侧一律走 key，中英环境均正确）。</p>
     */
    private Component combatModeLabel(RecordListPacket.Entry entry) {
        return Component.translatable("furkin.screen.record.combat_mode",
                Component.translatable(modeKey(entry.getCombatMode())));
    }

    /** 档位 → lang key。 */
    private static String modeKey(FurkinCombatMode mode) {
        return "furkin.combat_mode." + mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 单键循环：follow → passive → protect → aggressive → follow（与命令档位顺序一致）。 */
    private static FurkinCombatMode nextMode(FurkinCombatMode current) {
        FurkinCombatMode[] all = FurkinCombatMode.values();
        return all[(current.ordinal() + 1) % all.length];
    }

    /** 加一个详情卡按钮，并记入 {@link #detailButtons}（刷新详情时精确清除）。 */
    private Button detailButton(Component label, Button.OnPress onPress, int x, int y, int w, int h) {
        Button btn = new Button(x, y, w, h, label, onPress);
        addRenderableWidget(btn);
        detailButtons.add(btn);
        return btn;
    }

    /** 点「召唤」：上行请求召唤包（服务端按状态分流召唤 / 传送）；不关屏（2026-09-22 她定）。 */
    private void requestSummon(UUID companionId) {
        FurkinNetwork.channel().sendToServer(new RequestSummonPacket(companionId));
    }

    /**
     * 点「收回 / 改名 / 解绑 / 重获魂石 / 战斗模式」：上行管理动作包，服务端统一处理。
     *
     * <p><b>不关屏</b>（2026-09-22 她定）：按钮点一下就把界面关掉，等于每看一次状态都要重开录。
     * 改为留在原地，并<b>向服务端要一份新列表</b>刷新自身 —— 服务端处理完会回发
     * <b>刷新用途</b>（{@code openScreen = false}）的列表包，经 {@link #handleRefresh}
     * <b>就地</b>更新本屏（不换屏实例 ⇒ 选中与滚动位置都保住）。</p>
     */
    private void requestAction(RecordActionPacket.Action action, UUID companionId, String name) {
        // 末位 true = 让服务端回发列表刷新本录（本屏不关屏，靠它就地更新）。
        FurkinNetwork.channel().sendToServer(
                new RecordActionPacket(action, companionId, name, null, true));
    }

    /** 点「战斗模式」：上行同一管理动作包，带目标档位（服务端走 FurkinCombatModeHandler）。 */
    private void requestAction(RecordActionPacket.Action action, UUID companionId, String name,
                              FurkinCombatMode mode) {
        FurkinNetwork.channel().sendToServer(
                new RecordActionPacket(action, companionId, name, mode, true));
    }

    /** 打开改名输入框（预填当前名字）。 */
    private void openRename(UUID companionId, RecordListPacket.Entry entry) {
        String current = entry.hasName() ? entry.getName() : "";
        RenameScreen.open(this, companionId, current);
    }

    /** 右详情卡抬头里的物种显示名：speciesKey 是本地化 key，转成已本地化文本。 */
    private String speciesDisplay(RecordListPacket.Entry entry) {
        return Component.translatable(entry.getSpeciesName()).getString();
    }

    /** 列表条目显示名：有自定义名用名字，否则回退物种名。 */
    private MutableComponent entryLabel(RecordListPacket.Entry entry) {
        if (entry.hasName()) {
            return Component.literal(entry.getName());
        }
        return Component.translatable(entry.getSpeciesName());
    }

    /** 条目状态后缀组件：已召唤→绿色「在场」，已死亡→红色「已亡」，否则空。 */
    private Component stateSuffix(RecordListPacket.Entry entry) {
        if (!entry.isAlive()) {
            return Component.literal(" ").append(
                    Component.translatable("furkin.screen.record.state_dead"))
                    .withStyle(ChatFormatting.RED);
        }
        if (entry.isSummoned()) {
            return Component.literal(" ").append(
                    Component.translatable("furkin.screen.record.state_summoned"))
                    .withStyle(ChatFormatting.GREEN);
        }
        return Component.empty();
    }

    /** 数值文本（原版属性格式，最多两位小数）—— 与技能页属性区同口径。 */
    private static String plain(double value) {
        return ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(value);
    }

    /** 明细括号里的单项文本 —— 只有负项带负号（与技能页 {@code term} 同口径）。 */
    private static MutableComponent term(double value) {
        return Component.literal(value < 0.0D ? "-" + plain(-value) : plain(value));
    }

    /** 一行属性明细：{@code 名称 总值 (基础+技能+装备)}，色义同技能页属性区。 */
    private MutableComponent attributeLine(RecordAttributes.Line line) {
        String id = line.attributeId();
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(id));
        MutableComponent name = attribute != null
                ? Component.translatable(attribute.getDescriptionId())
                : Component.literal(id);

        MutableComponent result = name.withStyle(COLOR_ATTR_NAME)
                .append(Component.literal(" " + plain(line.total())).withStyle(COLOR_ATTR_TOTAL))
                .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                .append(term(line.base()).withStyle(COLOR_ATTR_BASE))
                .append(Component.literal("+").withStyle(ChatFormatting.GRAY))
                .append(term(line.skill()).withStyle(COLOR_ATTR_SKILL))
                .append(Component.literal("+").withStyle(ChatFormatting.GRAY))
                .append(term(line.equip()).withStyle(COLOR_ATTR_EQUIP))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        return result;
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);

        // 标题。
        GuiComponent.drawCenteredString(pose, this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // 右详情卡：选中条目的头部信息 + 属性区文字。
        RecordListPacket.Entry selected = selectedEntry();
        if (selected != null) {
            renderDetail(pose, selected);
        }

        if (entries.isEmpty()) {
            GuiComponent.drawCenteredString(pose, this.font,
                    Component.translatable("furkin.screen.record.empty"),
                    this.width / 2, this.height / 2, 0xAAAAAA);
        }

        super.render(pose, mouseX, mouseY, partialTick);
    }

    /** 右详情卡固定文字：抬头（名字+物种+Lv/经验/技能点/状态）+「属性」小标题 + 空提示。 */
    private void renderDetail(PoseStack pose, RecordListPacket.Entry entry) {
        // 抬头：名字（物种）+ 等级 + 经验 + 技能点 + 状态。
        // 三个标签复用技能页同一套 key（2026-09-22：原先硬编码中文，英文环境下会露中文）。
        MutableComponent name = entryLabel(entry);
        // 物种、等级、经验、技能点和状态优先保留；名字只占扣除这些信息后的剩余宽度。
        MutableComponent details = Component.literal("（" + speciesDisplay(entry) + "）")
                .append(Component.literal("  "))
                .append(Component.translatable("furkin.screen.furkin.level"))
                .append(Component.literal(" " + entry.getLevel()))
                .append(Component.literal("   "))
                .append(Component.translatable("furkin.screen.furkin.xp"))
                .append(Component.literal(" " + entry.getXp()))
                .append(Component.literal("   "))
                .append(Component.translatable("furkin.screen.furkin.skill_points"))
                .append(Component.literal(" " + entry.getSkillPoints()))
                .append(stateSuffix(entry));

        int maxWidth = this.width - DETAIL_LEFT - 8;
        int detailsWidth = this.font.width(details);
        if (detailsWidth >= maxWidth) {
            drawTruncatedString(pose, details, DETAIL_LEFT, DETAIL_TOP, 0xFFFFFF, maxWidth);
        } else {
            int nameWidth = drawTruncatedString(pose, name, DETAIL_LEFT, DETAIL_TOP,
                    0xFFFFFF, maxWidth - detailsWidth);
            GuiComponent.drawString(pose, this.font, details,
                    DETAIL_LEFT + nameWidth, DETAIL_TOP, 0xFFFFFF);
        }

        // 「属性」小标题（属性行本身在滚动列表里）。
        GuiComponent.drawString(pose, this.font,
                Component.translatable("furkin.screen.furkin.attributes").withStyle(ChatFormatting.GRAY),
                DETAIL_LEFT, DETAIL_TOP + 20, 0xFFFFFF);

        // 无属性提示（空列表不建组件，这里补提示）。
        if (entry.getAttributes().isEmpty()) {
            GuiComponent.drawString(pose, this.font,
                    Component.translatable("furkin.screen.record.no_attributes").withStyle(ChatFormatting.GRAY),
                    DETAIL_LEFT, ATTR_LIST_TOP, 0xAAAAAA);
        }
    }

    /** 按像素宽度绘制单行组件，返回实际绘制宽度；超宽时截断并追加省略号，样式由 Font.split 保留。 */
    private int drawTruncatedString(PoseStack pose, Component text, int x, int y, int color, int maxWidth) {
        if (maxWidth <= 0) {
            return 0;
        }
        int textWidth = this.font.width(text);
        if (textWidth <= maxWidth) {
            GuiComponent.drawString(pose, this.font, text, x, y, color);
            return textWidth;
        }

        String ellipsis = "...";
        int ellipsisWidth = this.font.width(ellipsis);
        if (maxWidth <= ellipsisWidth) {
            String shortened = this.font.plainSubstrByWidth(ellipsis, maxWidth);
            GuiComponent.drawString(pose, this.font, shortened, x, y, color);
            return this.font.width(shortened);
        }

        List<FormattedCharSequence> lines = this.font.split(text, maxWidth - ellipsisWidth);
        if (lines.isEmpty()) {
            return 0;
        }
        FormattedCharSequence first = lines.get(0);
        int firstWidth = this.font.width(first);
        GuiComponent.drawString(pose, this.font, first, x, y, color);
        GuiComponent.drawString(pose, this.font, ellipsis, x + firstWidth, y, color);
        return firstWidth + ellipsisWidth;
    }

    /**
     * 左列表：复用原版 {@link AbstractSelectionList}（世界列表 / 服务器列表同款 ——
     * 滚动条、滚轮、拖拽、选中态管理全是官方现成，铁律「先查官方再造轮子」）。
     * 条目 {@link ItemRow} 自绘文字与选中高亮，点击回调 {@link FurkinRecordScreen#select}。
     */
    private class RecordEntryList extends AbstractSelectionList<RecordEntryList.ItemRow> {

        /** @param viewHeight 可视高度（滚动区域高；超过即出滚动条）。 */
        RecordEntryList(int viewHeight) {
            super(Minecraft.getInstance(), LIST_WIDTH, viewHeight,
                    LIST_TOP, LIST_TOP + viewHeight, LIST_ITEM_HEIGHT);
            setLeftPos(LIST_LEFT);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            // 原版选中框（renderSelection）以整个 rowWidth 居中画 1px 边框，右缘画到
            // x0+width —— 比滚动条右缘(x0+width-1)还宽 1px，横穿滚动条（2026-09-22
            // 字节码取证）。选中视觉由 ItemRow 自绘黄底承担，原版框关掉。
            setRenderSelection(false);
        }

        /** 公开条目替换（父类 {@code replaceEntries} 是 protected，外部调不到）。 */
        public void replaceRows(List<ItemRow> rows) {
            this.replaceEntries(rows);
        }

        /**
         * ⚠️ 必须覆写（2026-09-22 字节码取证）：基类 {@code getRowWidth()} 写死返回 220，
         * 而 {@code getRowLeft() = x0 + width/2 - rowWidth/2 + 2} —— 列表宽 150 时算出
         * 负的条目起点（-13），整行文字画到屏幕外。原版世界列表同样覆写了本方法。
         */
        @Override
        public int getRowWidth() {
            return this.width;
        }

        /**
         * ⚠️ 必须覆写（2026-09-22 字节码取证）：基类 {@code getScrollbarPosition()}
         * 写死 {@code width/2 + 124}（全屏宽列表的公式，不看列表 x0）——窄列表下
         * 滚动条跑到详情区中间。按世界列表做法贴回列表右缘。
         */
        @Override
        protected int getScrollbarPosition() {
            return this.x0 + this.width - 7;
        }

        /** 无障碍朗读占位（模组未接叙述体系，空实现以满足抽象门禁，同 §9.5 处理）。 */
        @Override
        public void updateNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }

        /** 一行条目：名字 + 状态标记；点击选中并刷新右侧详情卡。 */
        public class ItemRow extends AbstractSelectionList.Entry<ItemRow> {

            final int index;

            ItemRow(int index) {
                this.index = index;
            }

            @Override
            public void render(PoseStack pose, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                RecordListPacket.Entry entry = entries.get(this.index);
                boolean selected = FurkinRecordScreen.this.selectedIndex == this.index;
                // 选中淡黄底 / 悬停淡白底；右缘精确收到滚动条 thumb 左缘（2026-09-22：
                // 原先 left+width-7 比 thumb 左缘多 2px，黄底压过滚动条一点点）。
                int rowRight = RecordEntryList.this.getScrollbarPosition();
                if (selected) {
                    GuiComponent.fill(pose, left + 1, top, rowRight, top + height, 0x66FFFF55);
                } else if (hovering) {
                    GuiComponent.fill(pose, left + 1, top, rowRight, top + height, 0x22FFFFFF);
                }
                int textLeft = left + 4;
                drawTruncatedString(pose, listLabel(entry, selected), textLeft,
                        top + (height - 8) / 2, 0xFFFFFF, rowRight - textLeft - 4);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    RecordEntryList.this.setSelected(this);
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    select(this.index);
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * 右详情卡属性滚动列表 —— 与 {@link RecordEntryList} 同一套原版组件。
     * 区域固定（「属性」标题下 ~ 锚底按钮上方），属性行数再多也不突破底边。
     */
    private class RecordAttributeList extends AbstractSelectionList<RecordAttributeList.LineRow> {

        /** @param viewHeight 可视高度（滚动区域高；超过即出滚动条）。 */
        RecordAttributeList(int viewHeight) {
            super(Minecraft.getInstance(), FurkinRecordScreen.this.width - DETAIL_LEFT - 8, viewHeight,
                    ATTR_LIST_TOP, ATTR_LIST_TOP + viewHeight, ATTR_LINE_HEIGHT);
            setLeftPos(DETAIL_LEFT);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            setRenderSelection(false);   // 同 RecordEntryList：原版选中框超宽，关掉。
        }

        /**
         * 将只显示一部分的属性行裁切在列表视口内。
         *
         * <p>1.19.2 的 {@code AbstractSelectionList.renderList(...)} 没有启用裁剪：
         * 只要条目与 {@code y0}/{@code y1} 相交就会完整绘制，滚动时文字可能进入
         * 上方固定的“属性”标题区域。此处沿用官方
         * {@code AbstractScrollWidget.renderButton(...)} 的裁剪方式。</p>
         */
        @Override
        public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
            enableScissor(this.x0, this.y0, this.x1, this.y1);
            super.render(pose, mouseX, mouseY, partialTick);
            disableScissor();
        }

        /** ⚠️ 基类写死 220，不覆写条目起点会算到屏幕外（见 RecordEntryList 同名注释）。 */
        @Override
        public int getRowWidth() {
            return this.width;
        }

        /** ⚠️ 基类写死全屏公式，不覆写滚动条会跑到列表外（见 RecordEntryList 同名注释）。 */
        @Override
        protected int getScrollbarPosition() {
            return this.x0 + this.width - 7;
        }

        /** 公开条目替换（父类 {@code replaceEntries} 是 protected）。 */
        public void replaceRows(List<LineRow> rows) {
            this.replaceEntries(rows);
        }

        /** 无障碍朗读占位（同 §9.5 处理）。 */
        @Override
        public void updateNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }

        /** 一行属性明细（不可点击，纯展示）。 */
        public class LineRow extends AbstractSelectionList.Entry<LineRow> {

            final RecordAttributes.Line line;

            LineRow(RecordAttributes.Line line) {
                this.line = line;
            }

            @Override
            public void render(PoseStack pose, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                GuiComponent.drawString(pose, FurkinRecordScreen.this.font,
                        attributeLine(line), left, top + 1, 0xFFFFFF);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        // 不暂停游戏（列表是轻量浮层，保持世界运行）。
        return false;
    }
}
