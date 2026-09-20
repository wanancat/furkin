package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.menu.FurkinPouchMenu;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.OpenFurkinScreenPacket;
import com.wanancat.furkin.internal.network.ResetSkillsPacket;
import com.wanancat.furkin.internal.network.UnlockSkillPacket;
import com.wanancat.furkin.internal.skill.SkillTree;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 * <p><b>技能列表的滚动</b>：用官方 {@link AbstractScrollWidget}（不自己造滚动条）。
 * 它已经把三件事做完了 —— {@code enableScissor} 剪裁、{@code pose.translate(0, -scrollAmount)}
 * 平移内容、以及官方的滚动条绘制（含拖动与滚轮）。我们只需要给出内容高度
 * （{@link SkillListWidget#getInnerHeight()}）与滚轮步长（{@link SkillListWidget#scrollRate()}），
 * 并在 {@code renderContents} 里按绝对坐标画行 —— 平移已由框架加在 pose 上。</p>
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
    private static final int SKILL_BUTTON_HEIGHT = 14;

    /**
     * 行内「+1」按钮直接借<b>官方按钮贴图</b>绘制（{@code widgets.png}），不再自绘色块。
     *
     * <p><b>为什么自绘会突兀</b>：原来用 {@code fill} 画的是自选青绿，它是整屏唯一不跟资源包的
     * 色块 —— 旁边的页签 / 洗点按钮都是官方 {@code Button}，玩家换 GUI 包后两者立刻分家。</p>
     *
     * <p><b>参数照抄 {@code AbstractButton.renderWidget} 的字节码实参</b>：
     * {@code (20, 4, 200, 20, 0, v)} = 横向切片 20 / 纵向切片 4 / 贴图 200×20 / u=0 / v 见下。
     * 官方 {@code blitNineSliced} 内部先做 {@code min(切片, 尺寸/2)} 降级，故 22×14 这种远小于
     * 200×20 的小按钮也能拼出正常的左右圆角，不需要为素材把按钮做宽。</p>
     */
    private static final int BUTTON_SLICE_X = 20;
    private static final int BUTTON_SLICE_Y = 4;
    private static final int BUTTON_TEX_WIDTH = 200;
    private static final int BUTTON_TEX_HEIGHT = 20;

    /** 官方按钮三态在贴图里的 v 偏移（{@code AbstractButton.getTextureY()} = 46 + state × 20）。 */
    private static final int BUTTON_V_DISABLED = 46;
    private static final int BUTTON_V_NORMAL = 66;
    private static final int BUTTON_V_HOVER = 86;

    /** 按钮文字色 —— 与 {@code AbstractWidget.getFGColor()} 同源（可用白 / 禁用灰）。 */
    private static final int COLOR_BUTTON_TEXT = 0xFFFFFF;
    private static final int COLOR_BUTTON_TEXT_OFF = 0xA0A0A0;
    /** 技能名与等级文本之间的固定间距 —— 保证等级列左对齐成一条竖线。 */
    private static final int SKILL_LEVEL_COLUMN = 74;

    /**
     * 技能列表为滚动条预留的宽度。
     *
     * <p>官方 {@code AbstractScrollWidget} 把滚动条画在<b>控件右缘之外</b>
     * （{@code getX() + width} 起、固定 8px 宽，见其 {@code renderScrollBar} 字节码），
     * 故列表要主动右缩 8px 把这 8px 让出来，否则滚动条会压在面板边框上。</p>
     */
    private static final int SCROLLBAR_WIDTH = 8;

    /**
     * 行内「+1」按钮与列表右缘（也就是官方滚动条的左缘）之间的间隙。
     *
     * <p>滚动条画在列表控件右缘之外、8px 宽（见 {@link #SCROLLBAR_WIDTH} 与
     * {@code AbstractScrollWidget.renderScrollBar}）。按钮若也锚在列表右缘，两者必然
     * 零间隙贴死，视觉上按钮像被滚动条切掉一截（乌狸 2026-09-21 反馈）。右收 6px 后
     * 两者分开，<b>滚动条本身位置不动</b>。</p>
     */
    private static final int BUTTON_RIGHT_INSET = 6;

    /**
     * 行高区间：条目少时宽松（撑满可用区），条目多时收紧到 {@value #ROW_HEIGHT_MIN} 为止。
     *
     * <p><b>为什么下限是 20 而不是更小</b>：下限若压到十几像素，「铺满可用区」就永远成立 ——
     * 内容总高恰好等于控件高度，官方 {@code scrollbarVisible()}（{@code innerHeight > height}）
     * 永不成立，滚动条一辈子不出现。宁可保持一行读得舒服的 20px、超出部分交给滚动，
     * 也不要为了「硬塞」把行压扁（8px 字体在 14px 行里没有行距）。</p>
     */
    private static final int ROW_HEIGHT_MIN = 20;
    private static final int ROW_HEIGHT_MAX = 26;

    private static final int COLOR_LABEL = 0x404040;
    private static final int COLOR_HINT = 0x707070;
    private static final int COLOR_MAXED = 0x2E7D32;

    private final UUID companionId;

    private String companionName;
    private int skillPoints;
    private List<OpenFurkinScreenPacket.SkillView> skills = Collections.emptyList();

    /** 页签按钮，下标 = 页签编号；本菜单不提供的页为 {@code null}（不建控件、不留空位）。 */
    private final Button[] tabButtons = new Button[3];
    private Button resetButton;

    /** 技能列表的滚动容器（官方组件，见类注释）。非技能页时 {@code visible = false}。 */
    private SkillListWidget skillList;

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
        // ⚠️ 紧跟其后重算「物品栏」标签纵坐标，否则标签与槽位脱钩。
        // 官方 AbstractContainerScreen 构造器里写的是 inventoryLabelY = imageHeight - 94，
        // 而那句的取值时刻在 super(...) 内 —— 那时 imageHeight 还是默认 166，算出来恒为 72。
        // 本屏在 super 之后才改 imageHeight，于是 72 被留了下来：行囊 1 行时正确值是 38，
        // 标签会掉到玩家背包第二行上（2026-09-21 乌狸截图报「物品栏标题错位」）。
        // 官方箱子屏 ContainerScreen 正是这么修的（改完 imageHeight 立刻重算，见其构造器字节码）。
        // 91 不是巧合：玩家背包首行 y = 103 + (rows - 4) × 18，与本式相减恒为 11px 间隙。
        this.inventoryLabelY = this.imageHeight - 94;
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
        // 列表长度若变了，原滚动量可能超出新范围（框架只在 set 时 clamp），故重设一次。
        if (this.skillList != null) {
            this.skillList.reclampScroll();
        }
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

        // 列表先加：控件按加入顺序接收点击，列表要让出优先级给页签按钮之外的区域也无妨，
        // 但先加能保证它在内容区「吃掉」点击，不会被后续控件抢先。
        this.skillList = this.addRenderableWidget(new SkillListWidget(
                listLeft(), listTop(), listWidth(), listBottom() - listTop()));

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
        // 非技能页不显示列表容器：它同时负责「吃掉内容区点击」，关掉才轮得到槽位。
        this.skillList.visible = active == TAB_SKILLS;
    }

    // ===== 渲染 =====

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        //AbstractContainerScreen.render 只调 renderBg + Screen.render，不会自绘背景暗化，
        // 这一步必须显式做（官方各容器屏同理）。
        this.renderBackground(gui);
        super.render(gui, mouseX, mouseY, partialTick);

        // 技能行只放得下名字与等级，描述改走悬停 tooltip；前置不满足时一并说明缺什么。
        if (this.menu.getActiveTab() == TAB_SKILLS) {
            int index = skillRowIndexAt(mouseX, mouseY);
            if (index >= 0) {
                // 多行 tooltip 必须走「List + Optional」那个重载：`renderTooltip(Font, List<? extends
                // FormattedCharSequence>, int, int)` 收的不是 Component 列表（javap 核实）。
                gui.renderTooltip(this.font, skillTooltip(this.skills.get(index)),
                        Optional.empty(), mouseX, mouseY);
            }
        }
    }

    /**
     * 组一条技能行的悬停提示：描述 + （未满足时）前置清单。
     *
     * <p>只在<b>前置未满足</b>时追加前置区。理由：面板顶部已经写着当前技能点，
     * 「点数不够」玩家能从屏幕上推出来；而「差哪条前置」是屏幕上<b>推不出来</b>的信息 ——
     * 不写出来，玩家只能看到一个灰按钮和一堆不知道为什么点不动的行。满级行同理不显示
     * 前置（那里「满级」才是相关的失效原因）。</p>
     */
    private List<Component> skillTooltip(OpenFurkinScreenPacket.SkillView skill) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(skill.getDescriptionKey()));
        if (skill.isMaxed() || skill.isPrereqMet()) {
            return lines;
        }
        lines.add(Component.empty());
        lines.add(Component.translatable("furkin.screen.furkin.requires").withStyle(ChatFormatting.GRAY));
        for (SkillTree.Requirement req : skill.getUnmet()) {
            // requiredLevel 为 1 时（解锁前置 / 门限目标级为 1）说「需先学会」比「达到 Lv.1」顺口。
            String key = req.requiredLevel() <= 1
                    ? "furkin.screen.furkin.require_unlock"
                    : "furkin.screen.furkin.require_level";
            lines.add(Component.translatable(key,
                    Component.translatable(req.nameKey()),
                    Component.literal(String.valueOf(req.requiredLevel())))
                    .withStyle(ChatFormatting.RED));
        }
        return lines;
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
            // 只画抬头 / 空态提示；技能行由 SkillListWidget 在控件层绘制（见类注释）。
            renderSkillHeader(gui);
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

    /** 技能页的抬头与空态提示（技能行本身由 {@link SkillListWidget} 画）。 */
    private void renderSkillHeader(GuiGraphics gui) {
        // 头部第二行（标题条已占第一行）：宠物名 + 技能点。
        Component header = Component.literal(this.companionName == null ? "" : this.companionName + "  ")
                .append(Component.translatable("furkin.screen.furkin.skill_points"))
                .append(Component.literal(": " + this.skillPoints));
        gui.drawString(this.font, header, this.leftPos + TAB_MARGIN, this.topPos + 18, COLOR_LABEL, false);

        if (this.skills.isEmpty()) {
            gui.drawCenteredString(this.font, Component.translatable("furkin.screen.furkin.empty"),
                    this.leftPos + this.imageWidth / 2,
                    (listTop() + listBottom()) / 2, COLOR_HINT);
        }
    }

    /**
     * 画一行技能：名字 + 等级进度 + 行内「+1」。
     *
     * <p>在 {@link SkillListWidget#renderContents} 里调用 —— 框架已经把 pose 平移了
     * {@code -scrollAmount}，故此处一律用<b>未滚动的绝对坐标</b>，滚动由框架负责。</p>
     */
    private void renderSkillRow(GuiGraphics gui, int index, boolean hoveredRow, boolean hoveredButton) {
        OpenFurkinScreenPacket.SkillView skill = this.skills.get(index);
        int rowHeight = rowHeight();
        int y = rowY(index);
        int left = listLeft();
        int right = listLeft() + listWidth();

        boolean maxed = skill.isMaxed();
        // 可点性判据只有一个出口（见 buttonEnabled）—— 渲染与命中必须同源。
        boolean enabled = buttonEnabled(skill);

        if (hoveredRow) {
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

        // 按钮左缘走共用出口（见 buttonLeft）：渲染与命中必须同源，且已右收留出滚动条间隙。
        int bx = buttonLeft();
        int by = y + (rowHeight - SKILL_BUTTON_HEIGHT) / 2;
        // 三态直接映射到官方贴图行：不可用 → 禁用行，悬停 → 高亮行，其余 → 普通行。
        int textureV = enabled
                ? (hoveredButton ? BUTTON_V_HOVER : BUTTON_V_NORMAL)
                : BUTTON_V_DISABLED;
        gui.blitNineSliced(AbstractWidget.WIDGETS_LOCATION, bx, by,
                SKILL_BUTTON_WIDTH, SKILL_BUTTON_HEIGHT,
                BUTTON_SLICE_X, BUTTON_SLICE_Y, BUTTON_TEX_WIDTH, BUTTON_TEX_HEIGHT,
                0, textureV);
        gui.drawCenteredString(this.font, Component.literal("+1"),
                bx + SKILL_BUTTON_WIDTH / 2, by + (SKILL_BUTTON_HEIGHT - 8) / 2,
                enabled ? COLOR_BUTTON_TEXT : COLOR_BUTTON_TEXT_OFF);
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

    /**
     * 拖动路由 —— <b>必须显式转发给列表控件</b>。
     *
     * <p>硬事实（javap 字节码核实）：{@code AbstractContainerScreen.mouseDragged} 全程
     * <b>不调用 super</b>（全类仅此一处 mouseDragged，无任何委派），故控件永远收不到拖动事件 ——
     * 官方的 {@code AbstractScrollWidget} 正因为依赖 {@code mouseDragged}（要求
     * {@code isFocused() && scrolling}），在容器屏里默认拖不动。点击与松开是委派的
     * （{@code Screen.mouseClicked} / {@code Screen.mouseReleased}），只有拖动这条断了。</p>
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.skillList != null && this.skillList.visible
                && this.skillList.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
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
        final int refundPoints = refund;
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                (it.unimi.dsi.fastutil.booleans.BooleanConsumer) confirmed -> {
                    if (confirmed) {
                        FurkinNetwork.channel().sendToServer(new ResetSkillsPacket(this.companionId));
                    }
                    Minecraft.getInstance().setScreen(this);
                },
                Component.translatable("furkin.screen.furkin.reset"),
                Component.translatable("furkin.screen.furkin.reset_confirm", refundPoints),
                Component.translatable("furkin.screen.furkin.reset_confirm_yes"),
                Component.translatable("furkin.screen.furkin.reset_confirm_cancel")));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===== 技能列表的滚动容器 =====

    /**
     * 技能列表本体 —— 官方 {@link AbstractScrollWidget} 的子类。
     *
     * <p>框架负责：剪裁（{@code enableScissor}）、内容平移（{@code pose.translate(0, -scrollAmount)}）、
     * 滚动条绘制与拖动、滚轮。本类只回答「内容多高」「一格滚多少」「怎么画内容」。</p>
     *
     * <p>不画官方那圈 {@code renderBorder} 边框（覆盖 {@code renderBackground} 为空）：列表区
     * 的底板由 {@link #renderFlatPanel} 提供，加边框会与面板样式打架。</p>
     */
    private final class SkillListWidget extends AbstractScrollWidget {

        SkillListWidget(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        /** 内容总高 = 行数 × 行高；超过控件高度即出现滚动条（框架的 {@code scrollbarVisible}）。 */
        @Override
        protected int getInnerHeight() {
            return FurkinPanelScreen.this.skills.size() * rowHeight();
        }

        /** 滚轮一格 = 一行。 */
        @Override
        protected double scrollRate() {
            return rowHeight();
        }

        /** 列表区不加官方边框（底板已由 renderBg 画好）。 */
        @Override
        protected void renderBackground(GuiGraphics gui) {
        }

        /**
         * 人读支持。
         *
         * <p>{@code updateWidgetNarration} 在 {@code AbstractWidget} 里是抽象方法，而
         * {@code AbstractScrollWidget} <b>并没有</b>实现它（javap 确认：它的方法表里没有这一项），
         * 故任何子类都必须自己补上，否则编译不过。这里退回按钮的默认念白。</p>
         */
        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
            this.defaultButtonNarrationText(narration);
        }

        @Override
        protected void renderContents(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            // 渲染用的是「未滚动的绝对坐标」（框架已把 pose 平移 -scrollAmount），
            // 而鼠标坐标是屏幕坐标 —— 两者相差一个滚动量，命中一律走 rowScreenY。
            int hoveredRow = rowIndexAtScreen(mouseY);
            for (int i = 0; i < FurkinPanelScreen.this.skills.size(); i++) {
                renderSkillRow(gui, i, hoveredRow == i, buttonHit(mouseX, mouseY, i));
            }
        }

        /** 滚动量只在 {@code setScrollAmount} 里被 clamp；内容变矮时要把现值重设一次才收敛。 */
        void reclampScroll() {
            setScrollAmount(scrollAmount());
        }

        /**
         * 当前滚动量。
         *
         * <p>必须由子类开口：{@code scrollAmount()} 是 {@code protected}，而
         * {@code AbstractScrollWidget} 与本屏不在同一个包，外层类无法直接调用。</p>
         */
        double scroll() {
            return scrollAmount();
        }
    }

    // ===== 布局计算（渲染与命中必须用同一套，故抽出来共用） =====

    /** 技能列表可用区上沿：让出标题条与「名字 + 技能点」两行。 */
    private int listTop() {
        return this.topPos + 30;
    }

    private int listBottom() {
        return this.topPos + this.imageHeight - 6;
    }

    /** 列表左缘（内容与命中同源）。 */
    private int listLeft() {
        return this.leftPos + TAB_MARGIN;
    }

    /** 列表宽度：面板内宽减去两侧留白，再让出右侧 8px 给官方滚动条。 */
    private int listWidth() {
        return this.imageWidth - TAB_MARGIN * 2 - SCROLLBAR_WIDTH;
    }

    /**
     * 行内「+1」按钮的左缘 —— <b>渲染与命中判定的唯一坐标出口</b>。
     *
     * <p>这两处必须共用同一次计算：各自算一遍时，任何一处改动漏了另一处，就会出现
     * 「看得见却点不到」（或反过来）的错位，且不改代码根本看不出来。故只留这一个方法。</p>
     *
     * @see #BUTTON_RIGHT_INSET
     */
    private int buttonLeft() {
        return listLeft() + listWidth() - SKILL_BUTTON_WIDTH - BUTTON_RIGHT_INSET;
    }

    /**
     * 行高：条目少时撑满可用区（上限 {@value #ROW_HEIGHT_MAX}），多了就收到
     * {@value #ROW_HEIGHT_MIN} 为止，再放不下由 {@link SkillListWidget} 滚动。
     */
    private int rowHeight() {
        int count = Math.max(1, this.skills.size());
        return Math.max(ROW_HEIGHT_MIN, Math.min(ROW_HEIGHT_MAX, (listBottom() - listTop()) / count));
    }

    private int rowY(int index) {
        return listTop() + index * rowHeight();
    }

    /** 屏幕 Y → 行下标；已计入滚动量，且越界（在列表区外）返回 -1。 */
    private int rowIndexAtScreen(double mouseY) {
        if (mouseY < listTop() || mouseY >= listBottom() || this.skills.isEmpty()) {
            return -1;
        }
        int index = (int) Math.floor((mouseY - rowScreenY(0)) / (double) rowHeight());
        return index >= 0 && index < this.skills.size() ? index : -1;
    }

    /** 某一行在屏幕上的纵坐标 = 未滚动坐标 − 滚动量。 */
    private int rowScreenY(int index) {
        int scroll = this.skillList == null ? 0 : (int) this.skillList.scroll();
        return rowY(index) - scroll;
    }

    /** 该行的「+1」按钮是否被鼠标压住（纯几何，不含可点性判断）。 */
    private boolean buttonHit(double mouseX, double mouseY, int index) {
        int bx = buttonLeft();
        int by = rowScreenY(index) + (rowHeight() - SKILL_BUTTON_HEIGHT) / 2;
        return mouseX >= bx && mouseX < bx + SKILL_BUTTON_WIDTH
                && mouseY >= by && mouseY < by + SKILL_BUTTON_HEIGHT;
    }

    /**
     * 「+1」按钮此刻是否可点 —— <b>渲染与命中判定的唯一判据出口</b>。
     *
     * <p>三条与服务端 {@code SkillProgress.tryUnlock} 的准入一一对应：未满级、点数够、
     * 前置满足（{@code unmet} 由 {@link com.wanancat.furkin.internal.skill.SkillTree}
     * 算出并随快照下发），所以「这里能点」和「服务端会放行」不会各说各话。</p>
     *
     * <p><b>为什么必须抽成一个方法</b>：这两处曾经不一致 —— 渲染侧带了前置判定、命中侧漏了，
     * 结果「前置未满足」的行按钮是灰的却点得动，发出一个注定被服务端拒掉的包（动作栏白弹
     * 一句「前置技能未满足」）。同一类错位在 {@link #buttonLeft()} 上也踩过一次。</p>
     */
    private boolean buttonEnabled(OpenFurkinScreenPacket.SkillView skill) {
        return !skill.isMaxed()
                && this.skillPoints >= skill.getCost()
                && skill.isPrereqMet();
    }

    /** 命中哪一行的「+1」按钮（且该行确实可加点）；未命中返回 -1。 */
    private int skillButtonIndexAt(double mouseX, double mouseY) {
        int index = rowIndexAtScreen(mouseY);
        if (index < 0 || !buttonHit(mouseX, mouseY, index)) {
            return -1;
        }
        return buttonEnabled(this.skills.get(index)) ? index : -1;
    }

    /** 命中哪一整行（用于悬停显示描述）；未命中返回 -1。 */
    private int skillRowIndexAt(double mouseX, double mouseY) {
        if (mouseX < listLeft() || mouseX >= listLeft() + listWidth()) {
            return -1;
        }
        return rowIndexAtScreen(mouseY);
    }
}
