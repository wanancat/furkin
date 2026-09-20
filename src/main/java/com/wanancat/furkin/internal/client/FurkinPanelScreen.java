package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.menu.FurkinPouchMenu;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.OpenFurkinScreenPacket;
import com.wanancat.furkin.internal.network.ResetSkillsPacket;
import com.wanancat.furkin.internal.network.UnlockSkillPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 绒亲面板 —— 一个 {@link AbstractContainerScreen} 外壳 + 三页签（技能 / 行囊 / 装备）。
 *
 * <p><b>为什么必须是容器屏</b>：行囊页的槽位内容与拖拽只有一条官方同步通道
 * （{@code AbstractContainerMenu} + {@code ClientboundContainerSetSlotPacket}），
 * 不存在「纯 {@code Screen} 里塞一个容器」的做法；而技能页与行囊页又不可能各自
 * 是一个独立屏再互相切 —— 页签切换只能是同一个屏对象的内部状态。故三者共用本类。</p>
 *
 * <p><b>布局</b>：沿用官方 {@code generic_54.png}（176 × <code>114 + rows * 18</code>），
 * 页签画在面板上沿之外、洗点按钮画在下沿之外，故面板内部布局与原版箱子逐像素一致。
 * 非行囊页时整屏槽位 {@code isActive() == false}（见 {@code FurkinPouchMenu.TabSlot}），
 * 内容区由本类自绘。</p>
 *
 * <p><b>技能数据不走 Menu</b>：技能列表是变长结构，{@code ContainerData} 是 {@code int[]}
 * 装不下，故仍走 {@link OpenFurkinScreenPacket}（S→C）。因为开屏包与菜单包谁先到不保证，
 * 本类用 {@link #pendingSkillData} 做「先到先存、{@code init} 时套用」的兜底。</p>
 *
 * <p><b>端位隔离</b>：本类 {@link OnlyIn}{@code (Dist.CLIENT)}。</p>
 */
@OnlyIn(Dist.CLIENT)
public class FurkinPanelScreen extends AbstractContainerScreen<FurkinPouchMenu> {

    /**
     * 面板纹理 —— 与原版 6 行箱子同一张，不新增资源。
     *
     * <p><b>为什么技能 / 装备页不能直接 blit 它</b>：这张图里<b>烘死了 6 行 18×18 空槽框</b>
     * （槽框色 {@code #8B8B8B} / {@code #373737}），整张 blit 会在没有槽位的页面上透出一片
     * 假格子 —— 且格子数与真实槽位无关。</p>
     *
     * <p><b>解法 = 从同一张图里取「净板条」拼</b>（逐行扫描过纹理，无槽框色的连续区间）：
     * {@code v=0..16} 标题条、{@code v=125..138} 纯板条（可竖直平铺）、{@code v=220..221}
     * 阴影 + 黑边。三段拼出来的底板与行囊页逐像素同构，且跟随资源包（硬编码颜色会在
     * 换 GUI 资源包时与其余界面花掉）。</p>
     */
    private static final ResourceLocation PANEL_TEXTURE =
            new ResourceLocation("textures/gui/container/generic_54.png");

    /** 自绘底板的取材段（含义见 {@link #PANEL_TEXTURE} 的说明）。 */
    private static final int FLAT_TOP_V = 0;
    private static final int FLAT_TOP_H = 17;
    private static final int FLAT_BODY_V = 125;
    private static final int FLAT_BODY_H = 14;
    private static final int FLAT_BOTTOM_V = 220;
    private static final int FLAT_BOTTOM_H = 2;

    public static final int TAB_SKILLS = 0;
    public static final int TAB_POUCH = FurkinPouchMenu.TAB_POUCH;
    public static final int TAB_EQUIP = 2;

    private static final int TAB_WIDTH = 52;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_GAP = 2;
    private static final int TAB_MARGIN = 8;

    private static final int SKILL_BUTTON_WIDTH = 22;
    private static final int SKILL_BUTTON_HEIGHT = 12;
    /** 技能名与等级文本之间的固定间距 —— 保证等级列左对齐成一条竖线。 */
    private static final int SKILL_LEVEL_COLUMN = 74;

    private static final int COLOR_LABEL = 0x404040;
    private static final int COLOR_HINT = 0x707070;
    private static final int COLOR_MAXED = 0x2E7D32;
    private static final int COLOR_BUTTON = 0xFF4E625E;
    private static final int COLOR_BUTTON_HOVER = 0xFF6E8C86;
    private static final int COLOR_BUTTON_OFF = 0xFFBFBFBF;
    private static final int COLOR_BUTTON_BORDER = 0xFF3A4644;

    private final UUID companionId;

    private String companionName;
    private int skillPoints;
    private List<OpenFurkinScreenPacket.SkillView> skills = Collections.emptyList();

    /** 页签按钮，下标 = 页签编号；本菜单不提供的页为 {@code null}（不建控件、不留空位）。 */
    private final Button[] tabButtons = new Button[3];
    private Button resetButton;

    /**
     * 技能快照缓存。
     *
     * <p>服务端是「先发技能包、再开菜单」，但两条链路谁先抵达客户端不保证；
     * 屏还没建时先把快照存这里，{@code init} 时若身份匹配就套用。</p>
     *
     * <p>行囊格数变化时服务端会<b>重开菜单</b>（旧菜单的槽位布局作废），而那一刻老屏还在、
     * 新屏尚未建立 —— 故 {@link #onSkillData} 必须<b>无条件缓存</b>，否则新屏建出来就是
     * 一张空技能列表。</p>
     */
    private static OpenFurkinScreenPacket pendingSkillData;

    /**
     * 最近停留的页签 —— 菜单重开（行囊格数变化）后回到同一页。
     *
     * <p>页签切换是纯客户端行为、不发包，服务端并不知道当前停在哪一页；而重开菜单只能由
     * 服务端发起。故这份「界面偏好」只能由客户端自己记 —— 换一只绒亲沿用同一页签是合理的。</p>
     */
    private static int lastTab = FurkinPouchMenu.TAB_POUCH;

    public FurkinPanelScreen(FurkinPouchMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.companionId = menu.getCompanionId();
        this.imageWidth = 176;
        // 高度随行囊行数变：114 = 上段（标题 + 行囊区）与下段（玩家背包）之外的部分。
        this.imageHeight = 114 + menu.getRows() * 18;
    }

    // ===== 入口 =====

    /**
     * 技能快照到达（服务端在下发菜单前后各可能发一次）。
     *
     * <p>两件事都要做：<b>无条件存缓存</b>（行囊格数变化会让服务端重开菜单，新屏要比对这份
     * 快照），并且若当前正开着同一只的面板则原地刷新。</p>
     */
    public static void onSkillData(OpenFurkinScreenPacket packet) {
        pendingSkillData = packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof FurkinPanelScreen panel
                && panel.companionId != null
                && panel.companionId.equals(packet.getCompanionId())) {
            panel.apply(packet);
        }
    }

    /** 套用技能快照（不重建控件：技能行是自绘的，数据变了直接重绘即可）。 */
    private void apply(OpenFurkinScreenPacket packet) {
        this.companionName = packet.getName();
        this.skillPoints = packet.getSkillPoints();
        this.skills = packet.getSkills();
    }

    // ===== 生命周期 =====

    @Override
    protected void init() {
        // super.init() 用 imageWidth / imageHeight 算 leftPos / topPos，故上面必须在构造器里设好。
        super.init();

        // 技能快照可能先于开屏到达。
        if (pendingSkillData != null
                && this.companionId != null
                && this.companionId.equals(pendingSkillData.getCompanionId())) {
            apply(pendingSkillData);
            pendingSkillData = null;
        }

        int tabY = Math.max(2, this.topPos - TAB_HEIGHT - 2);
        int step = TAB_WIDTH + TAB_GAP;

        // 开屏落在哪一页：沿用上次停留的页（菜单重开后不该被弹回默认页）。
        this.menu.setActiveTab(resolveInitialTab());

        // 页签按固定顺序左起排列，不提供的页跳过 —— 不留空位、不出现点不动的空页。
        Arrays.fill(this.tabButtons, null);
        int slotIndex = 0;
        for (int tab : TAB_ORDER) {
            if (!tabAvailable(tab)) {
                continue;
            }
            final int current = tab;
            this.tabButtons[tab] = this.addRenderableWidget(Button.builder(
                            Component.translatable(tabLabelKey(current)),
                            b -> switchTab(current))
                    .bounds(this.leftPos + TAB_MARGIN + step * slotIndex, tabY, TAB_WIDTH, TAB_HEIGHT)
                    .build());
            slotIndex++;
        }

        this.resetButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("furkin.screen.furkin.reset"),
                        b -> confirmReset())
                .bounds(this.leftPos + TAB_MARGIN, this.topPos + this.imageHeight + 4, 60, 20)
                .build());

        updateTabState();
    }

    /** 页签的固定展示顺序。 */
    private static final int[] TAB_ORDER = {TAB_SKILLS, TAB_POUCH, TAB_EQUIP};

    /**
     * 本菜单是否提供该页。
     *
     * <p>行囊未解锁（0 格）时不提供「行囊」页：那一页既没有槽位可拖、也不该露出一片
     * 点不动的空槽（乌狸 2026-09-21 反馈）。行囊解锁后由服务端重开菜单，页签自然出现。</p>
     */
    private boolean tabAvailable(int tab) {
        return tab != TAB_POUCH || this.menu.getPouchSlots() > 0;
    }

    /** 开屏落到哪一页：沿用上次停留的页；该页在本菜单不可用则回落技能页。 */
    private int resolveInitialTab() {
        return tabAvailable(lastTab) ? lastTab : TAB_SKILLS;
    }

    private static String tabLabelKey(int tab) {
        return switch (tab) {
            case TAB_POUCH -> "furkin.screen.furkin.tab.pouch";
            case TAB_EQUIP -> "furkin.screen.furkin.tab.equip";
            default -> "furkin.screen.furkin.tab.skills";
        };
    }

    /** 切换页签：只改菜单字段（驱动槽位可见性）与按钮态，并记住本页以备菜单重开。 */
    private void switchTab(int tab) {
        this.menu.setActiveTab(tab);
        lastTab = tab;
        updateTabState();
    }

    private void updateTabState() {
        int active = this.menu.getActiveTab();
        for (int tab = 0; tab < this.tabButtons.length; tab++) {
            Button button = this.tabButtons[tab];
            if (button != null) {
                // 「当前页」的按钮置灰不可点，未提供的页没有按钮（null）。
                button.active = tab != active;
            }
        }
        this.resetButton.visible = active == TAB_SKILLS;
        this.resetButton.active = active == TAB_SKILLS;
    }

    // ===== 渲染 =====

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        //AbstractContainerScreen.render 只调 renderBg + Screen.render，不会自绘背景暗化，
        // 这一步必须显式做（官方各容器屏同理）。
        this.renderBackground(gui);
        super.render(gui, mouseX, mouseY, partialTick);

        // 技能行只放得下名字与等级，描述改走悬停 tooltip。
        if (this.menu.getActiveTab() == TAB_SKILLS) {
            int index = skillRowIndexAt(mouseX, mouseY);
            if (index >= 0) {
                gui.renderTooltip(this.font,
                        Component.translatable(this.skills.get(index).getDescriptionKey()),
                        mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int active = this.menu.getActiveTab();
        if (active == TAB_POUCH) {
            // 行囊页直接套官方 ChestScreen 的分段 blit：上段含标题条与行囊区（槽框行数正好
            // = 行囊行数），下段固定 96 高（玩家背包 + 快捷栏）。
            int rows = this.menu.getRows();
            gui.blit(PANEL_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, rows * 18 + 17);
            gui.blit(PANEL_TEXTURE, this.leftPos, this.topPos + rows * 18 + 17,
                    0, 126, this.imageWidth, 96);
            return;
        }
        // 技能 / 装备页没有槽位，必须自绘底板（原版纹理里烘死了 6 行空槽框）。
        renderFlatPanel(gui);
        if (active == TAB_EQUIP) {
            renderEquipPage(gui);
        } else {
            renderSkillPage(gui, mouseX, mouseY);
        }
    }

    /**
     * 自绘底板 —— 从 {@link #PANEL_TEXTURE} 里取「顶 / 净板条 / 底」三段拼出任意高的面板。
     *
     * <p>取材区间由逐行扫描纹理得到（{@code v=0..16} 标题条、{@code v=125..138} 无槽框的
     * 纯板条、{@code v=220..221} 阴影 + 黑边），故与行囊页逐像素同构；用 blit 而非硬编码
     * 颜色，换 GUI 资源包时也不会与其余界面脱节。</p>
     */
    private void renderFlatPanel(GuiGraphics gui) {
        int left = this.leftPos;
        int width = this.imageWidth;
        // 行囊页把纹理 220 / 221 两行落在面板的 imageHeight-3 / -2 行上，自绘板保持同一落点
        // （imageHeight-1 那一行原版也不画），两页切换时下沿不会跳。
        int bottomY = this.topPos + this.imageHeight - (FLAT_BOTTOM_H + 1);

        gui.blit(PANEL_TEXTURE, left, this.topPos, 0, FLAT_TOP_V, width, FLAT_TOP_H);
        for (int y = this.topPos + FLAT_TOP_H; y < bottomY; y += FLAT_BODY_H) {
            int segment = Math.min(FLAT_BODY_H, bottomY - y);
            gui.blit(PANEL_TEXTURE, left, y, 0, FLAT_BODY_V, width, segment);
        }
        gui.blit(PANEL_TEXTURE, left, bottomY, 0, FLAT_BOTTOM_V, width, FLAT_BOTTOM_H);
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_LABEL, false);
        // 「物品栏」标签只在槽位真正可见的页显示。
        if (this.menu.getActiveTab() == TAB_POUCH) {
            gui.drawString(this.font, this.playerInventoryTitle,
                    this.inventoryLabelX, this.inventoryLabelY, COLOR_LABEL, false);
        }
    }

    private void renderEquipPage(GuiGraphics gui) {
        gui.drawCenteredString(this.font,
                Component.translatable("furkin.screen.furkin.equip_placeholder"),
                this.leftPos + this.imageWidth / 2,
                this.topPos + this.imageHeight / 2, COLOR_HINT);
    }

    private void renderSkillPage(GuiGraphics gui, int mouseX, int mouseY) {
        // 头部第二行（标题条已占第一行）：宠物名 + 技能点。
        Component header = Component.literal(this.companionName == null ? "" : this.companionName + "  ")
                .append(Component.translatable("furkin.screen.furkin.skill_points"))
                .append(Component.literal(": " + this.skillPoints));
        gui.drawString(this.font, header, this.leftPos + TAB_MARGIN, this.topPos + 18, COLOR_LABEL, false);

        if (this.skills.isEmpty()) {
            gui.drawCenteredString(this.font, Component.translatable("furkin.screen.furkin.empty"),
                    this.leftPos + this.imageWidth / 2,
                    (listTop() + listBottom()) / 2, COLOR_HINT);
            return;
        }

        int rowHeight = rowHeight();
        for (int i = 0; i < this.skills.size(); i++) {
            renderSkillRow(gui, i, rowHeight, mouseX, mouseY);
        }
    }

    /** 画一行技能：名字 + 等级进度 + 行内「+1」。 */
    private void renderSkillRow(GuiGraphics gui, int index, int rowHeight, int mouseX, int mouseY) {
        OpenFurkinScreenPacket.SkillView skill = this.skills.get(index);
        int y = rowY(index);
        int left = this.leftPos + TAB_MARGIN;
        int right = this.leftPos + this.imageWidth - TAB_MARGIN;

        boolean maxed = skill.isMaxed();
        boolean affordable = this.skillPoints >= skill.getCost();
        boolean enabled = !maxed && affordable;

        if (mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + rowHeight) {
            gui.fill(left, y, right, y + rowHeight, 0x22FFFFFF);
        }

        int textY = y + (rowHeight - 8) / 2;
        gui.drawString(this.font, Component.translatable(skill.getNameKey()), left + 2, textY,
                COLOR_LABEL, false);
        String levelText = skill.isInfinite()
                ? String.valueOf(skill.getCurrentLevel())
                : skill.getCurrentLevel() + "/" + skill.getMaxLevel();
        gui.drawString(this.font, levelText, left + SKILL_LEVEL_COLUMN, textY,
                maxed ? COLOR_MAXED : COLOR_HINT, false);

        int bx = right - SKILL_BUTTON_WIDTH;
        int by = y + (rowHeight - SKILL_BUTTON_HEIGHT) / 2;
        boolean onButton = hoveredAt(mouseX, mouseY, bx, by);
        int bg = enabled ? (onButton ? COLOR_BUTTON_HOVER : COLOR_BUTTON) : COLOR_BUTTON_OFF;
        gui.fill(bx, by, bx + SKILL_BUTTON_WIDTH, by + SKILL_BUTTON_HEIGHT, bg);
        gui.renderOutline(bx, by, SKILL_BUTTON_WIDTH, SKILL_BUTTON_HEIGHT, COLOR_BUTTON_BORDER);
        gui.drawCenteredString(this.font, Component.literal("+1"),
                bx + SKILL_BUTTON_WIDTH / 2, by + 2, enabled ? 0xFFFFFF : 0xFF6E6E6E);
    }

    // ===== 交互 =====

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.menu.getActiveTab() == TAB_SKILLS && button == 0) {
            int index = skillButtonIndexAt(mouseX, mouseY);
            if (index >= 0) {
                requestUnlock(this.skills.get(index));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 点「+1」：上行加点请求，服务端校验（前置 / 等级门限 / 点数）。 */
    private void requestUnlock(OpenFurkinScreenPacket.SkillView skill) {
        FurkinNetwork.channel().sendToServer(
                new UnlockSkillPacket(this.companionId, new ResourceLocation(skill.getId())));
    }

    /** 点「洗点」：弹二次确认框。 */
    private void confirmReset() {
        int refund = 0;
        for (OpenFurkinScreenPacket.SkillView s : this.skills) {
            refund += s.getCurrentLevel();
        }
        final int finalRefund = refund;
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                (it.unimi.dsi.fastutil.booleans.BooleanConsumer) confirmed -> {
                    if (confirmed) {
                        FurkinNetwork.channel().sendToServer(new ResetSkillsPacket(this.companionId));
                    }
                    Minecraft.getInstance().setScreen(this);
                },
                Component.translatable("furkin.screen.furkin.reset"),
                Component.translatable("furkin.screen.furkin.reset_confirm", finalRefund),
                Component.translatable("furkin.screen.furkin.reset_confirm_yes"),
                Component.translatable("furkin.screen.furkin.reset_confirm_cancel")));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===== 布局计算（渲染与命中必须用同一套，故抽出来共用） =====

    /** 技能列表可用区上沿：让出标题条与「名字 + 技能点」两行。 */
    private int listTop() {
        return this.topPos + 30;
    }

    private int listBottom() {
        return this.topPos + this.imageHeight - 6;
    }

    /** 行高自适应：条目少时宽松，条目多时收紧（下限 14 保证 8px 字体仍有行距）。 */
    private int rowHeight() {
        int count = Math.max(1, this.skills.size());
        return Math.max(14, Math.min(26, (listBottom() - listTop()) / count));
    }

    private int rowY(int index) {
        return listTop() + index * rowHeight();
    }

    /** 命中哪一行的「+1」按钮（且该行确实可加点）；未命中返回 -1。 */
    private int skillButtonIndexAt(double mouseX, double mouseY) {
        int rowHeight = rowHeight();
        int bx = this.leftPos + this.imageWidth - TAB_MARGIN - SKILL_BUTTON_WIDTH;
        for (int i = 0; i < this.skills.size(); i++) {
            int by = rowY(i) + (rowHeight - SKILL_BUTTON_HEIGHT) / 2;
            if (mouseX >= bx && mouseX < bx + SKILL_BUTTON_WIDTH
                    && mouseY >= by && mouseY < by + SKILL_BUTTON_HEIGHT) {
                OpenFurkinScreenPacket.SkillView skill = this.skills.get(i);
                return skill.isMaxed() || this.skillPoints < skill.getCost() ? -1 : i;
            }
        }
        return -1;
    }

    /** 命中哪一整行（用于悬停显示描述）；未命中返回 -1。 */
    private int skillRowIndexAt(double mouseX, double mouseY) {
        int left = this.leftPos + TAB_MARGIN;
        int right = this.leftPos + this.imageWidth - TAB_MARGIN;
        if (mouseX < left || mouseX >= right) {
            return -1;
        }
        int rowHeight = rowHeight();
        int index = (int) ((mouseY - listTop()) / rowHeight);
        if (mouseY < listTop() || index < 0 || index >= this.skills.size()) {
            return -1;
        }
        return index;
    }

    private static boolean hoveredAt(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + SKILL_BUTTON_WIDTH
                && mouseY >= y && mouseY < y + SKILL_BUTTON_HEIGHT;
    }
}
