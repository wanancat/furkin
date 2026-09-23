package com.wanancat.furkin.internal.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.attribute.AttributeDisplay;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.contract.FurkinCombatMode;
import com.wanancat.furkin.internal.config.FurkinClientConfig;
import com.wanancat.furkin.internal.equipment.EquipBonus;
import com.wanancat.furkin.internal.equipment.MobEquipmentContainer;
import com.wanancat.furkin.internal.growth.FurkinGrowth;
import com.wanancat.furkin.internal.menu.FurkinPouchMenu;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.OpenFurkinScreenPacket;
import com.wanancat.furkin.internal.network.RecordActionPacket;
import com.wanancat.furkin.internal.network.ResetSkillsPacket;
import com.wanancat.furkin.internal.network.SelectTabPacket;
import com.wanancat.furkin.internal.network.UnlockSkillPacket;
import com.wanancat.furkin.internal.skill.SkillTree;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p><b>布局</b>：沿用官方 {@code generic_54.png}（176 × <code>114 + rows * 18 + Δ</code>，
 * Δ = {@link FurkinPouchMenu#PANEL_EXTRA_HEIGHT} 是技能页抬头由两行变三行所需的那一段，
 * 三个页签统一加高），页签画在面板上沿之外、洗点按钮画在下沿之外，故面板内部布局与原版箱子
 * 逐像素一致。
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

    /**
     * 单个空槽框在 {@link #PANEL_TEXTURE} 里的取材矩形。
     *
     * <p><b>为什么要单独取一格</b>：行囊页是把整行 blit 出来的（槽框随行数一起带出），
     * 而装备页只有 4 格 —— 整行会带出 9 个假框。故这里只取一格来铺。</p>
     *
     * <p>坐标由逐像素扫描纹理得出：框横跨 {@code x=7..24}、{@code y=17..34}，恰好 18×18
     * （取证留档 {@code .outputs/m3_probe_slotframe_2026-09-22.txt}）。</p>
     */
    private static final int SLOT_FRAME_U = 7;
    private static final int SLOT_FRAME_V = 17;
    private static final int SLOT_FRAME_SIZE = 18;

    /**
     * 装备页摘要行的排版常量。
     *
     * <p>{@code EQUIP_SUMMARY_GAP} = 摘要起点与装备槽右缘之间的间隙；
     * {@code SUMMARY_ITEM_GAP} = 摘要里两项之间的间隙（按一个空格宽度取 4px）。</p>
     *
     * <p><b>为什么是 8px</b>（2026-09-22 她截图报「摘要和左边装备槽贴太近」）：初版取 2px，
     * 与槽框右缘（{@code x=80}）几乎贴住，视觉上像被槽框压着。8px 是「一眼能分开」的最小值，
     * 且仍留得下：可用宽度从 86px 缩到 80px，实测单项 49px 绰绰有余；
     * 即便日后改用短名映射（两项 ≈78px）也仍在 80px 之内。</p>
     */
    private static final int EQUIP_SUMMARY_GAP = 8;
    private static final int SUMMARY_ITEM_GAP = 4;

    /**
     * 「玩家背包段」在 {@link #PANEL_TEXTURE} 里的取材矩形 —— 两页共用同一份数。
     *
     * <p>{@code v=126} 起 96 高 = 「物品栏」标签区 + 3 行背包 + 快捷栏 + 面板下沿。
     * 这一段的<b>槽框是烘死在纹理里的</b>（不来自任何容器），所以哪一页要显示玩家背包，
     * 哪一页就必须 blit 它 —— 否则物品照画、格子却是空的。</p>
     *
     * <p><b>为什么抽成常量</b>：行囊页与装备页各写一遍字面量时，改了一处漏另一处就会出现
     * 「一页有格子一页没有」（2026-09-22 乌狸截图报「切换到装备页后物品栏 slot 格子没了」）。
     * 起点偏移不在这里 —— 它是 {@code rows * 18 + 17}，由 {@link #playerSegmentTop()} 统一给出。</p>
     */
    private static final int PLAYER_SEGMENT_V = 126;
    private static final int PLAYER_SEGMENT_H = 96;

    public static final int TAB_SKILLS = 0;
    public static final int TAB_POUCH = FurkinPouchMenu.TAB_POUCH;
    public static final int TAB_EQUIP = FurkinPouchMenu.TAB_EQUIP;

    /**
     * 页签几何。
     *
     * <p><b>宽度已到上限</b>：三个页签横向刚好排满面板内宽 ——
     * {@code TAB_MARGIN + 3 × TAB_WIDTH + 2 × TAB_GAP = 8 + 156 + 4 = 168}，面板只有 176。
     * 想再宽，只能同时牺牲留白或间隙（故 2026-09-22 乌狸「再加宽一点空间」是按<b>纵向</b>办的）。</p>
     *
     * <p><b>高度取 18</b>（原 16）：官方按钮素材是 200×20、九宫格上下各切 4px，压到 16 时中间底板
     * 只剩 8px，那条「上边框」在视觉上就顶到了文字（乌狸反馈「上边缘因为官方素材具有纹理，
     * 会显得比下边缘窄」）。抬到 18 让上边不再压着文字；同一轮把面板缩了 12px
     * （{@link FurkinPouchMenu#PANEL_EXTRA_HEIGHT} 24 → 12），居中后 {@code topPos} 大 6
     * ⇒ 页签离屏幕顶也宽出 4px。</p>
     */
    private static final int TAB_WIDTH = 52;
    private static final int TAB_HEIGHT = 18;
    private static final int TAB_GAP = 2;
    private static final int TAB_MARGIN = 8;

    /**
     * 「摘要放不下」的诊断日志只打一次。
     *
     * <p>渲染每帧都会调 {@code renderEquipSummary}，不去重会把日志刷满；而这条日志恰恰是
     * 「摘要到底能放几项、每项多宽」的<b>唯一硬数据</b> —— 别再靠字宽估算（2026-09-22 已栽一次）。</p>
     */
    private boolean summaryWidthLogged;

    private static final int SKILL_BUTTON_WIDTH = 22;
    private static final int SKILL_BUTTON_HEIGHT = 20;

    /**
     * 行内「+1」按钮直接借<b>官方按钮贴图</b>绘制（{@code widgets.png}），不再自绘色块。
     *
     * <p><b>为什么自绘会突兀</b>：原来用 {@code fill} 画的是自选青绿，它是整屏唯一不跟资源包的
     * 色块 —— 旁边的页签 / 洗点按钮都是官方 {@code Button}，玩家换 GUI 包后两者立刻分家。</p>
     *
     * <p><b>画法照抄 1.19.2 官方 {@code AbstractWidget#renderButton}</b>（javap 核实）：按钮底图是
     * 「左半 + 右半」两次 blit —— 右半的 u 起点为 {@code 200 − 宽度/2}，纵向整高拉伸。
     * 1.20.1 用的 {@code GuiGraphics#blitNineSliced} 在 1.19.2 <b>没有对应 API</b>，故改走这条官方
     * 等价路径：与模组内其它原版 {@code Button} 像素同源，换素材包也不会分家。</p>
     */
    private static final int BUTTON_TEX_WIDTH = 200;

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
     * <p><b>为什么下限是 24 而不是更小</b>：下限若压到十几像素，「铺满可用区」就永远成立 ——
     * 内容总高恰好等于控件高度，官方 {@code scrollbarVisible()}（{@code innerHeight > height}）
     * 永不成立，滚动条一辈子不出现。宁可保持一行读得舒服的 24px、超出部分交给滚动，
     * 也不要为了「硬塞」把行压扁（8px 字体在 14px 行里没有行距）。</p>
     */
    private static final int ROW_HEIGHT_MIN = 24;
    private static final int ROW_HEIGHT_MAX = 32;

    private static final int COLOR_LABEL = 0x404040;
    private static final int COLOR_HINT = 0x707070;
    private static final int COLOR_MAXED = 0x2E7D32;

    /**
     * 技能页抬头三行的纵坐标（相对 {@code topPos}）。
     *
     * <p>行距沿用现有口径（8px 字 + 4px 行距 = 12px）：行 1 名称+物种 / 行 2 等级+经验 /
     * 行 3 技能点+生命。抬头带的下沿就是列表顶（{@link #listTop()}），两者必须一起动。</p>
     *
     * <p><b>为什么首行取 6</b>（2026-09-22 乌狸要求「技能页抬头条收掉，留出视觉空间」）：
     * 技能页不画页签标题，标题条那 17px 留空纯属浪费。首行上移到 6 正是原版
     * {@code AbstractContainerScreen} 画标题的纵坐标 —— 整块上移 12px 后，让出的这一段
     * 全归技能列表（{@link #listTop()} 由行 3 派生，自动跟着走）。</p>
     */
    private static final int HEADER_ROW_1_Y = 6;
    private static final int HEADER_ROW_2_Y = 18;
    private static final int HEADER_ROW_3_Y = 30;

    /**
     * 抬头「悬停出属性明细」的命中带（相对 {@code topPos}）。
     *
     * <p>上沿取 4（比首行文字高 2px 留余量），下沿取 40（收在列表顶 42 之前）——
     * 与技能行的悬停区（从 {@link #listTop()} 起）严格不重叠，两处 tooltip 不会打架。
     * 两者都跟着 {@code HEADER_ROW_*_Y} 走，<b>改行位必须同步改这里</b>（否则「画得下、
     * 却悬停不到」）。</p>
     */
    private static final int HEADER_HOVER_TOP_Y = 4;
    private static final int HEADER_HOVER_BOTTOM_Y = 40;

    /**
     * 悬停明细要显示的属性 —— <b>N5 起动态枚举</b>（设计稿 §4.1 定案 N5）。
     *
     * <p>初版在这里写死 8 项候选（7 条可同步 ＋ 攻击伤害），代价是第三方模组加的属性
     * （如「最大魔力值」）永远不显示。现在集合由
     * {@link AttributeDisplay#displayableAttributes(LivingEntity)} 给出 ——
     * <b>注册表 ∩ 该生物实有 ∩ 未被屏蔽</b>，本类只负责缓存与渲染。</p>
     *
     * <p><b>为什么必须缓存</b>：明细是<b>每帧</b>渲染的，而枚举要遍历整个属性注册表并逐条查
     * {@code hasAttribute}（60fps × 属性条数 × 两次查表）。缓存键取<b>实体类型</b> ——
     * 同一只宠物类型不变则清单不变；收回再召唤虽然换了实体（类型相同）也照旧复用，
     * 类型变了（换物种 / 第三方生物）自动重算。</p>
     *
     * <p><b>实体不可得时清单为空</b>（不再像定案 2 时期那样列 8 行占位）：动态枚举的前提就是
     * 「这只生物实有哪些属性」，没有实体就答不出这个问题；而抬头里的生命 / 护甲此时本来
     * 也已降级为 {@value #UNAVAILABLE}，不会出现「有行无值」的自相矛盾。</p>
     */
    private List<Attribute> hoverAttributes = Collections.emptyList();

    /** {@link #hoverAttributes} 的缓存键（实体类型）。 */
    private EntityType<?> hoverAttributesType;

    /**
     * 悬停明细配色：属性名 / 总值 / 基础值 / 技能加成 / 装备加成。
     *
     * <p>「基础值」= 总 − 技能 − 装备 的残差，改版前在明细里叫「其他」（同一个量，只是显示口径变了）。</p>
     */
    private static final ChatFormatting COLOR_ATTR_NAME = ChatFormatting.WHITE;
    private static final ChatFormatting COLOR_ATTR_TOTAL = ChatFormatting.YELLOW;
    private static final ChatFormatting COLOR_ATTR_SKILL = ChatFormatting.GREEN;
    private static final ChatFormatting COLOR_ATTR_EQUIP = ChatFormatting.BLUE;
    private static final ChatFormatting COLOR_ATTR_BASE = ChatFormatting.GRAY;

    private final UUID companionId;

    /**
     * 最近一次技能快照 —— 「技能加成表」与「服务端权威总值」都从它取。
     *
     * <p>之所以整份留住（而不是各剥成两个字段）：这两张表只在开屏 / 刷新时换，
     * 而 {@code skillBonusOf} / {@code serverTotalOf} 的查表口径属于包自己的契约，
     * 屏侧复制一份只会多一处可能跑偏的地方。</p>
     */
    private OpenFurkinScreenPacket skillData;

    private String companionName;
    private int skillPoints;
    private List<OpenFurkinScreenPacket.SkillView> skills = Collections.emptyList();

    /** 页签按钮，下标 = 页签编号；本菜单不提供的页为 {@code null}（不建控件、不留空位）。 */
    private final Button[] tabButtons = new Button[3];
    private Button resetButton;

    /** 战斗模式循环按钮（装备页可见；文字即当前档位）。 */
    private Button modeButton;

    /** 战斗模式按钮宽度 —— 「战斗模式：主动」这类中文字面量的实测安全宽度。 */
    private static final int MODE_BUTTON_WIDTH = 90;

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
        // 末尾再 + PANEL_EXTRA_HEIGHT：技能页抬头由两行变三行所需的那一段（三个页签统一加高，
        // 见 FurkinPouchMenu#PANEL_EXTRA_HEIGHT）。
        this.imageHeight = 114 + menu.getRows() * 18 + FurkinPouchMenu.PANEL_EXTRA_HEIGHT;
        // ⚠️ 紧跟其后重算「物品栏」标签纵坐标，否则标签与槽位脱钩。
        // 官方 AbstractContainerScreen 构造器里写的是 inventoryLabelY = imageHeight - 94，
        // 而那句的取值时刻在 super(...) 内 —— 那时 imageHeight 还是默认 166，算出来恒为 72。
        // 本屏在 super 之后才改 imageHeight，于是 72 被留了下来：行囊 1 行时正确值是 38，
        // 标签会掉到玩家背包第二行上（2026-09-21 乌狸截图报「物品栏标题错位」）。
        // 官方箱子屏 ContainerScreen 正是这么修的（改完 imageHeight 立刻重算，见其构造器字节码）。
        // 91 不是巧合：玩家背包首行 y = 103 + (rows - 4) × 18 + Δ，与本式相减恒为 11px 间隙
        //（Δ 在两边同时出现，自动抵消 —— 故加高不需要动这里）。
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
        this.skillData = packet;
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
        // 走 applyTab 而不是直接 setActiveTab —— 服务端也得知道这一页（Shift 落点靠它分流）。
        applyTab(resolveInitialTab());

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
            this.tabButtons[tab] = this.addRenderableWidget(new Button(
                    this.leftPos + TAB_MARGIN + step * slotIndex, tabY, TAB_WIDTH, TAB_HEIGHT,
                    Component.translatable(tabLabelKey(current)),
                    b -> switchTab(current)));
            slotIndex++;
        }

        this.resetButton = this.addRenderableWidget(new Button(
                this.leftPos + TAB_MARGIN, this.topPos + this.imageHeight + 4, 60, 20,
                Component.translatable("furkin.screen.furkin.reset"),
                b -> confirmReset()));

        // 战斗模式循环按钮 —— 面板下沿、洗点按钮右侧（技能页可见，2026-09-22 她定）。
        // 放这里而非装备槽右侧：那块「摘要区」只有 80px，中文下一项属性就 49~60px
        // （见 renderEquipSummary 的实测注释），再切一块给按钮会挤掉装备加成摘要。
        // 下沿这行天然有空位 —— 洗点按钮宽度 60、起点 8，右侧 72 起正好放得下。
        this.modeButton = this.addRenderableWidget(new Button(
                this.leftPos + TAB_MARGIN + 64, this.topPos + this.imageHeight + 4,
                MODE_BUTTON_WIDTH, 20,
                Component.translatable("furkin.screen.furkin.combat_mode", "?"),
                b -> cycleCombatMode()));

        // 换屏实例不带旧乐观值过来（重开屏时镜像才是真源）。
        this.pendingMode = null;
        this.pendingModeTicks = 0;

        updateTabState();
    }

    /**
     * 每 tick 校一次乐观值 —— 服务端镜像追平后让位，被拒则超时放弃。
     *
     * <p>选择 tick 而非 render：render 每帧 60 次、且可能因窗口最小化而暂停；
     * tick 稳定 20 次/秒，正好匹配能力同步的节奏。本屏是容器屏，
     * {@code AbstractContainerScreen#containerTick} 由 {@code Screen#tick} 调用。</p>
     */
    @Override
    public void containerTick() {
        super.containerTick();
        reconcilePendingMode();
        if (this.modeButton != null && this.modeButton.visible) {
            updateTabState();
        }
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

    /** 点页签按钮：落状态 + 上行同步 + 刷新按钮态。 */
    private void switchTab(int tab) {
        applyTab(tab);
        updateTabState();
    }

    /**
     * 落页签状态并上行同步。
     *
     * <p>本地这一份驱动 {@code Slot#isActive()}（渲染与鼠标命中）；上行那一份给服务端用 ——
     * {@code quickMoveStack}（Shift 点击）是在<b>服务端</b>执行的，得靠它决定落点是装备槽
     * 还是行囊（见 {@code FurkinPouchMenu#quickMoveStack}）。两处必须同时更新，故收进
     * 一个方法：以后新增切页签的入口时，不会再漏掉一半。</p>
     */
    private void applyTab(int tab) {
        this.menu.setActiveTab(tab);
        lastTab = tab;
        FurkinNetwork.channel().sendToServer(new SelectTabPacket(tab));
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
        // 战斗模式按钮：**技能页**可见、洗点按钮右侧（2026-09-22 她定 —— 先前误放装备页）。
        this.modeButton.visible = active == TAB_SKILLS;
        this.modeButton.active = active == TAB_SKILLS && combatMode() != null;
        // ⚠️ 显示值：本地乐观值尚未被镜像追平前先show它，追平后让位给镜像
        // （见 reconcilePendingMode —— 否则连点后的乐观值会永久盖住真值）。
        this.modeButton.setMessage(Component.translatable("furkin.screen.furkin.combat_mode",
                Component.translatable(modeKey(displayedCombatMode()))));
        // 非技能页不显示列表容器：它同时负责「吃掉内容区点击」，关掉才轮得到槽位。
        this.skillList.visible = active == TAB_SKILLS;
    }

    /**
     * 当前该显示的战斗档位 —— 乐观值优先，镜像追平后自动让位。
     *
     * <p>服务端回推能力镜像有延迟（{@code setMode} 那次 sync 到下一 tick 才落到客户端），
     * 这段时间里 {@link #combatMode()} 还是旧值；若直接读它，点完按钮文字不会变
     * （2026-09-22 她报的「页签点击后没有实时变化文字」）。故先读 {@link #pendingMode}。</p>
     */
    private FurkinCombatMode displayedCombatMode() {
        return pendingMode != null ? pendingMode : combatMode();
    }

    /**
     * 镜像追平后清掉乐观值 —— 让 {@link #combatMode()} 重新成为唯一真源。
     *
     * <p>判定「追平」= 服务端画像已经等于本地乐观值。若不清理，乐观值会永久压住镜像；
     * 若服务端<b>拒绝</b>了这次切档（未召唤 / 非主人 / 无效），镜像永远不会等于乐观值，
     * 此时靠 {@link #PENDING_MODE_TIMEOUT_TICKS} 兜底超时放弃，避免卡在错误显示上。</p>
     */
    private void reconcilePendingMode() {
        if (pendingMode == null) {
            return;
        }
        FurkinCombatMode mirror = combatMode();
        if (mirror == pendingMode || ++pendingModeTicks > PENDING_MODE_TIMEOUT_TICKS) {
            pendingMode = null;
            pendingModeTicks = 0;
        }
    }

    /** 连点乐观值的存活上限（tick）—— 服务端拒绝切档时用它兜底放弃，约 2 秒。 */
    private static final int PENDING_MODE_TIMEOUT_TICKS = 40;

    // ===== 渲染 =====

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        //AbstractContainerScreen.render 只调 renderBg + Screen.render，不会自绘背景暗化，
        // 这一步必须显式做（官方各容器屏同理）。
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, partialTick);

        // 技能页两处悬停，命中区互不重叠（抬头带下沿 52 < 列表顶 54）：
        // 抬头带 → 全部属性的明细；技能行 → 描述与前置说明。
        if (this.menu.getActiveTab() == TAB_SKILLS) {
            if (isOverSkillHeader(mouseX, mouseY)) {
                this.renderTooltip(pose, skillAttributeLines(), Optional.empty(), mouseX, mouseY);
            } else {
                int index = skillRowIndexAt(mouseX, mouseY);
                if (index >= 0) {
                    // 多行 tooltip 必须走「List + Optional」那个重载：`renderTooltip(Font, List<? extends
                    // FormattedCharSequence>, int, int)` 收的不是 Component 列表（javap 核实）。
                    this.renderTooltip(pose, skillTooltip(this.skills.get(index)),
                            Optional.empty(), mouseX, mouseY);
                }
            }
        }

        // 装备页：摘要行只放得下两项，完整清单走悬停（挂在摘要行上，不是槽位上 ——
        // 两类 tooltip 的 x 区间不相交：装备槽只占到 leftPos+80，摘要区自 leftPos+88 起，
        // 中间 8px 是死区，同一个像素不可能同时命中两边）。
        if (this.menu.getActiveTab() == TAB_EQUIP) {
            List<EquipBonus.Entry> bonuses = equipBonuses();
            if (!bonuses.isEmpty() && isOverEquipSummary(mouseX, mouseY)) {
                this.renderTooltip(pose, equipBonusLines(bonuses),
                        Optional.empty(), mouseX, mouseY);
            }
        }

        // 槽位物品的原版 tooltip（名称 + 属性修饰符）。
        // ⚠️ `AbstractContainerScreen.render` 只负责画槽位本身与高亮框，**不会**自己弹 tooltip ——
        // 每个官方容器屏都要在自己的 render 覆写里显式补这一句（javap 核实：HopperScreen 1 处、
        // CraftingScreen 2 处）。少了它，行囊 / 装备 / 玩家背包三处的槽位就是「有物品、悬停没反应」。
        // 内部自带守卫（carrying 为空 && hoveredSlot != null && hoveredSlot.hasItem()），
        // 拖拽中或悬停空格子都不会弹，所以逐帧无条件调用是安全的。
        this.renderTooltip(pose, mouseX, mouseY);
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
    protected void renderBg(PoseStack pose, float partialTick, int mouseX, int mouseY) {
        int active = this.menu.getActiveTab();
        if (active == TAB_POUCH) {
            // 行囊页分三段 blit：
            //   段一 = 标题条 + 行囊区（槽框行数正好 = 行囊行数），高度只能取到行囊区下沿 ——
            //          再往下取就是**下一行的假槽框**（纹理里烘死了 6 行，而 rows 可能小于 6）；
            //   段二 = Δ 净板带（补上抬头加高那一段，取自同张纹理的无槽框行）；
            //   段三 = 玩家背包段（那 36 格槽框同样烘死在纹理里，不 blit 就只有物品没有格子）。
            int slotAreaBottom = pouchSlotAreaBottom();
            blit(pose, PANEL_TEXTURE, this.leftPos, this.topPos, 0, 0,
                    this.imageWidth, slotAreaBottom - this.topPos);
            blitNetBoard(pose, slotAreaBottom, playerSegmentTop());
            blit(pose, PANEL_TEXTURE, this.leftPos, playerSegmentTop(),
                    0, PLAYER_SEGMENT_V, this.imageWidth, PLAYER_SEGMENT_H);
            return;
        }
        if (active == TAB_EQUIP) {
            renderEquipPanel(pose);
            renderEquipPage(pose);
            renderEquipSummary(pose);
        } else {
            // 技能页整屏净板；只画抬头 / 空态提示，技能行由 SkillListWidget 在控件层绘制。
            renderFlatPanel(pose);
            renderSkillHeader(pose);
        }
    }

    /**
     * 行囊区（含标题条）的下沿 —— 行囊页段一 blit 的高度上限。
     *
     * <p>取「标题条 17 + 行囊行数 × 18」。<b>不能取到玩家背包段起点</b>：纹理里行囊槽框
     * 恒有 6 行，而 {@code rows} 常小于 6，越界那几行会被当成槽框 blit 出来（假格子）。</p>
     */
    private int pouchSlotAreaBottom() {
        return this.topPos + 17 + this.menu.getRows() * 18;
    }

    /** 玩家背包段的屏幕起点 —— 两页共用（行囊页的段二也在这里收尾）。 */
    private int playerSegmentTop() {
        return pouchSlotAreaBottom() + FurkinPouchMenu.PANEL_EXTRA_HEIGHT;
    }

    /**
     * 画一块贴图 —— 1.19.2 没有 {@code GuiGraphics#blit(ResourceLocation, ...)}，
     * 官方等价路径是「先绑贴图，再 {@code GuiComponent.blit}」（blend / shader 由后者自带，javap 核实）。
     *
     * <p>默认纹理尺寸取 256×256：与 1.20.1 {@code GuiGraphics#blit(ResourceLocation, x, y, u, v, w, h)}
     * 的默认值一致（两侧都收敛到 {@code (..., 256, 256)}），故逐像素等价。</p>
     */
    private static void blit(PoseStack pose, ResourceLocation texture,
                             int x, int y, int u, int v, int width, int height) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(pose, x, y, u, v, width, height, 256, 256);
    }

    /**
     * 画一个官方样式的按钮底图 —— 1.19.2 官方 {@code AbstractWidget#renderButton} 的等价画法：
     * 左半 {@code u=0} + 右半 {@code u=200−宽度/2} 两次 blit，纵向整高拉伸。
     *
     * <p>1.20.1 的 {@code GuiGraphics#blitNineSliced} 在 1.19.2 没有对应 API，故照抄官方两段画法 ——
     * 与同屏其它原版 {@code Button} 像素同源，不会出现「换素材包后两者分家」。</p>
     */
    private static void blitButton(PoseStack pose, int x, int y, int width, int height, int v) {
        RenderSystem.setShaderTexture(0, AbstractWidget.WIDGETS_LOCATION);
        GuiComponent.blit(pose, x, y, 0, v, width / 2, height, 256, 256);
        GuiComponent.blit(pose, x + width / 2, y, BUTTON_TEX_WIDTH - width / 2, v, width / 2, height, 256, 256);
    }

    /**
     * 竖着铺「净板条」（取自 {@link #PANEL_TEXTURE} 的无槽框行，不新增素材），铺到 {@code bodyEndY} 为止。
     *
     * <p>自绘底板与行囊页的 Δ 净板带共用这一段 —— 两处若各写一遍，改了一处就会
     * 露出「一页净板、一页突兀色块」的不一致。</p>
     */
    private void blitNetBoard(PoseStack pose, int fromY, int bodyEndY) {
        for (int y = fromY; y < bodyEndY; y += FLAT_BODY_H) {
            int segment = Math.min(FLAT_BODY_H, bodyEndY - y);
            blit(pose, PANEL_TEXTURE, this.leftPos, y, 0, FLAT_BODY_V, this.imageWidth, segment);
        }
    }

    /**
     * 自绘底板 —— 从 {@link #PANEL_TEXTURE} 里取「顶 / 净板条 / 底」三段拼出任意高的面板。
     *
     * <p>取材区间由逐行扫描纹理得到（{@code v=0..16} 标题条、{@code v=125..138} 无槽框的
     * 纯板条、{@code v=220..221} 阴影 + 黑边），故与行囊页逐像素同构；用 blit 而非硬编码
     * 颜色，换 GUI 资源包时也不会与其余界面脱节。</p>
     */
    private void renderFlatPanel(PoseStack pose) {
        renderFlatPanel(pose, this.topPos + this.imageHeight - (FLAT_BOTTOM_H + 1));
    }

    /**
     * 自绘底板（限定下界）—— 净板只铺到 {@code bodyEndY} 为止，底边视情况补。
     *
     * <p>装备页要用这个重载：它的下半屏交给 {@link #renderEquipPanel} 去 blit 官方的玩家背包段，
     * 而那一整段自带下沿（纹理 220 / 221 两行），净板若铺到底就与它叠在一起。</p>
     *
     * @param bodyEndY 净板条铺到哪一行（不含）；恰好等于面板下沿时才补画阴影 + 黑边
     */
    private void renderFlatPanel(PoseStack pose, int bodyEndY) {
        int left = this.leftPos;
        int width = this.imageWidth;
        // 行囊页把纹理 220 / 221 两行落在面板的 imageHeight-3 / -2 行上，自绘板保持同一落点
        // （imageHeight-1 那一行原版也不画），两页切换时下沿不会跳。
        int bottomY = this.topPos + this.imageHeight - (FLAT_BOTTOM_H + 1);

        blit(pose, PANEL_TEXTURE, left, this.topPos, 0, FLAT_TOP_V, width, FLAT_TOP_H);
        blitNetBoard(pose, this.topPos + FLAT_TOP_H, bodyEndY);
        // 只有下半屏没有别的来源时才补底边（装备页的下段已含 220 / 221 两行）。
        if (bodyEndY >= bottomY) {
            blit(pose, PANEL_TEXTURE, left, bottomY, 0, FLAT_BOTTOM_V, width, FLAT_BOTTOM_H);
        }
    }

    /**
     * 装备页底板 —— 上段自绘净板，下段照抄行囊页的玩家背包段。
     *
     * <p><b>为什么下段必须照抄</b>：玩家背包那 36 格的槽框是烘死在纹理里的，不来自任何容器；
     * 行囊页靠第二段 blit 把它们带出来。装备页若只铺净板，那 36 格就只剩物品、没有格子
     * （2026-09-22 乌狸截图报「切换到装备页后物品栏 slot 格子没了」）。</p>
     *
     * <p>上段保持净板 —— 那块位置在行囊页是行囊槽框，装备页换成 4 个装备槽，
     * 框由 {@link #renderEquipPage} 按槽位坐标单独画（装备槽与行囊首行坐标重合，
     * 整行 blit 会带出 9 个假框）。</p>
     */
    private void renderEquipPanel(PoseStack pose) {
        renderFlatPanel(pose, playerSegmentTop());
        blit(pose, PANEL_TEXTURE, this.leftPos, playerSegmentTop(),
                0, PLAYER_SEGMENT_V, this.imageWidth, PLAYER_SEGMENT_H);
    }

    @Override
    protected void renderLabels(PoseStack pose, int mouseX, int mouseY) {
        // 标题随页签走（2026-09-22 乌狸定）：行囊页「随身行囊」、装备页「绒亲装备」，
        // 技能页**不画标题** —— 那一行直接留给「名称 + 物种」（见 renderSkillHeader 的行 1）。
        // 菜单侧传进来的 this.title（「绒亲」）仍保留给旁白 / 日志，只是不再画到面板上。
        int active = this.menu.getActiveTab();
        Component tabTitle = tabTitle(active);
        if (tabTitle != null) {
            this.font.draw(pose, tabTitle, this.titleLabelX, this.titleLabelY, COLOR_LABEL);
        }
        // 「物品栏」标签只在槽位真正可见的页显示 —— 行囊页与装备页都会露出玩家背包。
        if (active == TAB_POUCH || active == TAB_EQUIP) {
            this.font.draw(pose, this.playerInventoryTitle,
                    this.inventoryLabelX, this.inventoryLabelY, COLOR_LABEL);
        }
    }

    /** 页签抬头；技能页无抬头（{@code null}）—— 见 {@link #renderLabels}。 */
    private static Component tabTitle(int tab) {
        if (tab == TAB_POUCH) {
            return Component.translatable("furkin.screen.furkin.title.pouch");
        }
        if (tab == TAB_EQUIP) {
            return Component.translatable("furkin.screen.furkin.title.equip");
        }
        return null;
    }

    /**
     * 装备页内容 —— 为每个装备槽画一个空槽框（实时内容由框架的 {@code renderSlot} 画）。
     *
     * <p><b>为什么不自己算坐标</b>：装备槽与行囊首行槽位<b>坐标完全重合</b>，所以不能靠坐标
     * 分辨；而槽位坐标的唯一真相源是 {@code FurkinPouchMenu.registerSlots}。故这里直接遍历
     * 菜单槽位、按<b>容器身份</b>筛出装备区，再取各槽自己的 {@code x} / {@code y} ——
     * 菜单里哪天挪了槽位，这里自动跟上（本屏其余布局计算也是这个口径）。</p>
     */
    private void renderEquipPage(PoseStack pose) {
        MobEquipmentContainer equipment = this.menu.getEquipment();
        for (Slot slot : this.menu.slots) {
            if (slot.container != equipment) {
                continue;
            }
            // 槽框比槽位坐标向外扩 1px：官方 renderSlot 把物品画在 (leftPos + slot.x) 处，
            // 而 18×18 的框要再往外一圈 —— 纹理里框落在 (7,17)、槽位在 (8,18)。
            blit(pose, PANEL_TEXTURE,
                    this.leftPos + slot.x - 1, this.topPos + slot.y - 1,
                    SLOT_FRAME_U, SLOT_FRAME_V, SLOT_FRAME_SIZE, SLOT_FRAME_SIZE);
        }
    }

    /**
     * 装备页摘要 —— 四个装备槽的属性合计，画在装备槽右侧那一行。
     *
     * <p><b>宽度是硬约束，且必须按实际字宽判别</b>（2026-09-22 实机两轮返工）：起点到面板右内边
     * 只有 {@code equipSummaryRight() - equipSummaryLeft()} = <b>80px</b>（间隙取 8px 后；
     * 取 2px 时是 86px）。早期按「中文 9px、一项约 34px」目测估算得出「两项放得下」，<b>是错的</b> ——
     * 实机诊断日志给出的实测值（{@code run_client_2026-09-22_0456.log}）：
     * {@code available=86px drawn=1/3 widths=[49, 52, 60]}，三项依次是
     * {@code +13 护甲值} / {@code +3 盔甲韧性} / {@code +0.1 击退抗性}
     * ⇒ 两项 = 49 + 4 + 52 = <b>105px</b>，间隙取 2 还是 8 都放不下（中文下物理上限就是一项）。
     * ⇒ 不再按「固定两项」下结论，改为<b>逐项用 {@code font.width(...)} 实测、放不下即停</b>：
     * 字体、语言、属性名长度任一变化都不会再溢出。硬上限仍是
     * {@link EquipBonus#SUMMARY_MAX} 项（优先级序列见设计稿 §六 ④）。</p>
     *
     * <p>放不下的项<b>不截断、不加省略号</b> —— 摘要里出现半截的值，比不显示更容易被误读；
     * 完整清单一律交给悬停明细。</p>
     *
     * <p>颜色既写进 {@code Component} 样式、也作为 {@code drawString} 的参数传一遍：
     * 同一个颜色给两个来源，换渲染路径（或样式被吞）时也不会掉色。</p>
     */
    private void renderEquipSummary(PoseStack pose) {
        List<EquipBonus.Entry> bonuses = equipBonuses();
        if (bonuses.isEmpty()) {
            return;
        }
        final int right = equipSummaryRight();
        int x = equipSummaryLeft();
        int y = equipSummaryTop() + 4;
        int drawn = 0;
        for (EquipBonus.Entry entry : bonuses) {
            if (drawn >= EquipBonus.SUMMARY_MAX) {
                break;
            }
            MutableComponent part = EquipBonus.format(entry);
            int width = this.font.width(part);
            if (x + (drawn == 0 ? 0 : SUMMARY_ITEM_GAP) + width > right) {
                break;
            }
            if (drawn > 0) {
                x += SUMMARY_ITEM_GAP;
            }
            this.font.draw(pose, part, x, y, EquipBonus.style(entry).getColor());
            x += width;
            drawn++;
        }
        logSummaryOverflowOnce(bonuses, drawn, right);
    }

    /**
     * 有属性项没画下时，把「可用宽度 / 各项实测字宽」打一次日志。
     *
     * <p>用途是把「摘要能放几项」从估算变成实测数据 —— 换语言、换字体包、或以后新增属性时，
     * 这条日志直接给出「要不要缩短属性名」的答案，而不是再赌一次目测。</p>
     */
    private void logSummaryOverflowOnce(List<EquipBonus.Entry> bonuses, int drawn, int right) {
        if (drawn >= bonuses.size() || this.summaryWidthLogged) {
            return;
        }
        this.summaryWidthLogged = true;
        List<Integer> widths = new ArrayList<>();
        List<String> items = new ArrayList<>();
        for (EquipBonus.Entry entry : bonuses) {
            MutableComponent part = EquipBonus.format(entry);
            widths.add(this.font.width(part));
            items.add(part.getString());
        }
        FurkinMod.LOGGER.info(
                "[equip summary] available={}px (left={} right={}) drawn={}/{} widths={} items={}",
                right - equipSummaryLeft(), equipSummaryLeft(), right, drawn, bonuses.size(), widths, items);
    }

    /**
     * 摘要行起点 —— 四个装备槽右缘再留一道间隙。
     *
     * <p>槽位横排自 {@code x=8} 起、步进 18（真相源在 {@code FurkinPouchMenu.registerSlots}），
     * 故右缘 = {@code 8 + 4 × 18}。这块位置是装备页<b>唯一</b>动得了的空地：玩家背包槽要同时
     * 属于行囊页与装备页、位置不能挪（设计稿新定②），而装备槽又钉在行囊区首行左侧四格。</p>
     */
    private int equipSummaryLeft() {
        return this.leftPos + 8 + MobEquipmentContainer.SLOT_COUNT * 18 + EQUIP_SUMMARY_GAP;
    }

    /**
     * 摘要行右界 —— 面板右内边，与页签 / 抬头共用同一个边距。
     *
     * <p>抽成一个方法而不是各写一遍字面量：<b>绘制与命中区必须用同一个边界</b>，
     * 否则「画得下、却悬停不到」这类差几像素的毛病会改一处漏一处。</p>
     */
    private int equipSummaryRight() {
        return this.leftPos + this.imageWidth - TAB_MARGIN;
    }

    /** 摘要行顶端 —— 与装备槽同一行（槽位 y=18）。 */
    private int equipSummaryTop() {
        return this.topPos + 18;
    }

    /**
     * 鼠标是否落在摘要行上。
     *
     * <p>命中区取<b>整行余量</b>（一直到面板右边距）而不是实际文字宽度：文字宽度随数值变化，
     * 拿它做命中区会让「差一像素」的悬停莫名其妙失效；而这块区域在装备页本就是空的，
     * 划过去没有第二个人跟它抢。</p>
     */
    private boolean isOverEquipSummary(double mouseX, double mouseY) {
        int top = equipSummaryTop();
        return mouseX >= equipSummaryLeft()
                && mouseX < equipSummaryRight()
                && mouseY >= top && mouseY < top + SLOT_FRAME_SIZE;
    }

    /**
     * 算出装备合计（装备页用）。
     *
     * <p>{@code showEquipTooltip} 一关，摘要与悬停明细同时消失（两者是同一份数据的两种呈现，
     * 半开半关只会让玩家说不清自己到底关掉了什么）。判据 7 只测「摘要行消失」，本条同样满足。</p>
     *
     * <p>开关的<b>判读</b>统一走 {@link #equipBonusesEnabled()} —— 技能页属性明细里的「装备」项
     * 也读那一个判据，不各自再读一遍配置。</p>
     */
    private List<EquipBonus.Entry> equipBonuses() {
        return equipBonusesEnabled() ? EquipBonus.total(this.menu.getEquipment()) : Collections.emptyList();
    }

    /**
     * 装备加成要不要显示 —— <b>唯一的判读点</b>（2026-09-22 定）。
     *
     * <p>三个消费者共用：装备页摘要行、装备页悬停明细、<b>技能页属性明细里的「装备」项</b>。
     * 批 6 初版那处直接调了 {@code EquipBonus.total(...)}，绕过了开关 —— 关掉开关后装备页隐身、
     * 技能页却照旧亮着装备数，正是本类自己写过的那种「半开半关」。收到这里后，
     * 开关一关三处一起隐身，且配置只在这一处被读到。</p>
     */
    private static boolean equipBonusesEnabled() {
        return FurkinClientConfig.SHOW_EQUIP_TOOLTIP.get();
    }

    /**
     * 悬停明细 —— 摘要放不下的项都在这里（逐属性一行，原版格式与配色）。
     *
     * <p>只在真有加成时调用：四槽全空、或装备不给任何属性时，一个只写着标题的空框
     * 比不弹更让人困惑。</p>
     */
    private List<Component> equipBonusLines(List<EquipBonus.Entry> bonuses) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("furkin.screen.furkin.equip_bonus")
                .withStyle(ChatFormatting.GRAY));
        for (EquipBonus.Entry entry : bonuses) {
            lines.add(EquipBonus.format(entry));
        }
        return lines;
    }

    /**
     * 技能页抬头（三行）与空态提示（技能行本身由 {@link SkillListWidget} 画）。
     *
     * <p>三行的取数各有出处（设计稿 §4.1 字段表）：名称 / 技能点来自快照包；物种由客户端按
     * 实体类型自解（注册表双侧填充，无需下发）；等级 / 经验读客户端的能力镜像；生命是
     * 实体的<b>活值</b> —— 面板开着时宠物挨打 / 回血都看得见，不是开屏那一刻的定格快照。
     * （护甲 2026-09-22 起不再占抬头位置，只在悬停属性明细里显示，理由见行 3 处注释。）</p>
     */
    private void renderSkillHeader(PoseStack pose) {
        int x = this.leftPos + TAB_MARGIN;
        LivingEntity companion = companionEntity();

        // 行 1：名称 ＋ 物种。
        MutableComponent nameRow = Component.literal(this.companionName == null ? "" : this.companionName);
        Component species = speciesName(companion);
        if (species != null) {
            nameRow.append(Component.literal("  ")).append(species);
        }
        this.font.draw(pose, nameRow, x, this.topPos + HEADER_ROW_1_Y, COLOR_LABEL);

        // 行 2：等级 ＋ 经验（当前 / 升级所需）。
        // 「升级所需」由等级现算（纯算术，客户端可直接调 FurkinGrowth）—— 不必为它多开包字段。
        FurkinData data = companionData(companion);
        int level = data == null ? 0 : data.getLevel();
        int xp = data == null ? 0 : data.getXp();
        MutableComponent xpRow = Component.translatable("furkin.screen.furkin.level")
                .append(Component.literal(" " + level))
                .append(Component.literal("   "))
                .append(Component.translatable("furkin.screen.furkin.xp"))
                .append(Component.literal(" " + xp + "/" + FurkinGrowth.xpNeededForNextLevel(level)));
        this.font.draw(pose, xpRow, x, this.topPos + HEADER_ROW_2_Y, COLOR_LABEL);

        // 行 3：技能点 ＋ 生命。
        // ⚠️ **护甲已从这一行撤下**（2026-09-22 乌狸定）：三项并排时字面量下限就已顶到 158px，
        // 而可用宽只有 160px（176 − 2×8）—— 两位小数比一位多出的 6px 就会顶出面板右缘。
        // 护甲没有丢：它在**悬停属性明细**里照常显示（那是独立 tooltip，不受面板宽度约束）。
        MutableComponent statusRow = Component.translatable("furkin.screen.furkin.skill_points")
                .append(Component.literal(" " + this.skillPoints))
                .append(Component.literal("   "))
                .append(Component.translatable("furkin.screen.furkin.health"))
                .append(Component.literal(" " + healthText(companion)));
        this.font.draw(pose, statusRow, x, this.topPos + HEADER_ROW_3_Y, COLOR_LABEL);

        if (this.skills.isEmpty()) {
            GuiComponent.drawCenteredString(pose, this.font, Component.translatable("furkin.screen.furkin.empty"),
                    this.leftPos + this.imageWidth / 2,
                    (listTop() + listBottom()) / 2, COLOR_HINT);
        }
    }

    // ===== 属性区（抬头 + 悬停明细） =====

    /** 实体不可得时的占位 —— 用占位而不是 0：0 会被读成「真实数值就是 0」。 */
    private static final String UNAVAILABLE = "--";

    /** 数值文本（原版属性格式，最多两位小数）—— 面板上所有数值的统一口径。 */
    private static String plain(double value) {
        return ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(value);
    }

    /**
     * 明细括号里的单项文本 —— <b>只有负项带负号</b>，正项不带 {@code +}。
     *
     * <p>三项之间已经用 {@code +} 相连，值自己再带一个 {@code +} 会变成 {@code +20+0+13} 那种
     * 重复号。乌狸的原话示例即 {@code 20+0+0}（设计稿 §4.1 定案 3）。</p>
     */
    private static MutableComponent term(double value) {
        return Component.literal(value < 0.0D ? "-" + plain(-value) : plain(value));
    }

    /**
     * 面板对着的实体 —— 客户端唯一的取法（设计稿 §4.1 取证⑥）。
     *
     * <p>{@code ClientLevel#getEntity(int)} 是 public，而按 UUID 取实体的
     * {@code getEntities()} 是 protected ⇒ 服务端必须把 entityId 随菜单下发，屏侧只能靠它。
     * 取不到（entityId 缺失 / 实体已卸载）时返回 null，字段逐项降级为占位。</p>
     */
    private LivingEntity companionEntity() {
        int entityId = this.menu.getEntityId();
        if (entityId < 0) {
            return null;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return level.getEntity(entityId) instanceof LivingEntity living ? living : null;
    }

    /** 实体的能力镜像（等级 / 经验 / 技能点的真相源 —— 能力挂在所有 {@code LivingEntity} 上，双侧都有）。 */
    private FurkinData companionData(LivingEntity companion) {
        return companion == null
                ? null
                : companion.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
    }

    /**
     * 当前战斗模式 —— 读客户端能力镜像（{@code combat_mode} 在 {@code syncNBT} 里，
     * 服务端切档后会主动下发，见 {@code FurkinCombatModeHandler#setMode}）。
     * 取不到时返回 {@code null}（按钮置灰）。
     */
    private FurkinCombatMode combatMode() {
        FurkinData data = companionData(companionEntity());
        return data == null ? null : data.getCombatMode();
    }

    /**
     * 上一次点按钮算出的目标档位 —— <b>连点时的基准</b>。
     *
     * <p>本镜像要等下一 tick 才被能力数据覆盖，若两次点击落在同一 tick 内（或镜像还没刷新），
     * {@link #combatMode()} 会返回<b>同一个旧值</b> ⇒ 连点两下只会切一档（第二下算出的
     * 「下一档」和第一下相同）。故记住本屏自己最后一次的目标，优先用它算下一档。</p>
     */
    private FurkinCombatMode pendingMode;

    /** {@link #pendingMode} 已存活的 tick 数 —— 服务端拒绝切档时用它兜底放弃。 */
    private int pendingModeTicks;

    /** 档位 → lang key（与绒亲录界面同一套 key）。 */
    private static String modeKey(FurkinCombatMode mode) {
        return "furkin.combat_mode." + (mode == null ? "follow" : mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * 点「战斗模式」：按当前档位算下一档，上行绒亲录同一套管理动作包。
     *
     * <p>复用 {@code RecordActionPacket.SET_COMBAT_MODE} + {@code FurkinCombatModeHandler}
     * ⇒ 与命令 {@code /furkin mode}、录内按钮<b>同一套规则</b>，不另开链路。</p>
     *
     * <p>⚠️ <b>用 4 参构造器</b>（不带 {@code refreshRecord}）—— 本面板<b>不要</b>服务端重发
     * 绒亲录列表：那会让客户端 {@code open()} 出录界面，点一下面板就被弹走
     * （2026-09-22 她报「页签点战斗模式→跳转到绒亲录了」）。本面板自己就地刷新
     * （乐观更新 + {@link #containerTick} 对账），不靠重发。</p>
     */
    private void cycleCombatMode() {
        // 基准优先取本地已点出的目标（连点正确），其次取镜像。
        FurkinCombatMode current = pendingMode != null ? pendingMode : combatMode();
        if (current == null) {
            return;
        }
        FurkinCombatMode[] all = FurkinCombatMode.values();
        FurkinCombatMode next = all[(current.ordinal() + 1) % all.length];
        pendingMode = next;
        pendingModeTicks = 0;
        // 末位不传 refreshRecord ⇒ 默认 false ⇒ 面板点档位不会被拽去录界面。
        FurkinNetwork.channel().sendToServer(new RecordActionPacket(
                RecordActionPacket.Action.SET_COMBAT_MODE, this.companionId, null, next));
        // ⚠️ 本地先乐观更新按钮文字（2026-09-22 她报「点击后没有实时变化」）：
        // 服务端切档后会回推能力镜像，但那是**下一 tick** 的事，本屏不会自动重刷 ——
        // 面板是容器屏，没有 onSkillData 那样的刷新入口。先改文字让点击立刻有反馈，
        // 真值仍以服务端镜像为准（下次 updateTabState / 重开屏会覆盖）。
        this.modeButton.setMessage(Component.translatable("furkin.screen.furkin.combat_mode",
                Component.translatable(modeKey(next))));
    }

    /** 物种显示名 —— 按实体类型查注册表自解，无需服务端下发（设计稿 §4.1 取证⑧）。 */
    private Component speciesName(LivingEntity companion) {
        if (companion == null) {
            return null;
        }
        return FurkinSpeciesRegistry.byEntityType(companion.getType())
                .map(species -> (Component) Component.translatable(species.getNameKey()))
                .orElse(null);
    }

    /** 生命文本「当前/上限」；实体不可得时为占位。数值走 {@link #plain}，与其余属性同口径。 */
    private String healthText(LivingEntity companion) {
        Double max = readAttribute(companion, Attributes.MAX_HEALTH);
        return max == null ? UNAVAILABLE : plain(companion.getHealth()) + "/" + plain(max);
    }

    /**
     * 读实体某属性的当前值；<b>实体不可得、或该实体根本没注册这条属性</b>时返回 {@code null}。
     *
     * <p>必须这么读 —— 原版 {@code LivingEntity#getAttributeValue} 内部走
     * {@code AttributeSupplier#getAttributeInstance}，属性不在该生物的 supplier 里
     * <b>直接抛 {@link IllegalArgumentException}</b>，不是返 0。2026-09-22 实机踩到：
     * 猫身上没有 {@code attack_speed}，面板一悬停就崩在渲染线程
     * （{@code Can't find attribute minecraft:generic.attack_speed}）。
     * 安全口径是 {@code getAttributes().hasAttribute(...)} 与 {@code getAttribute(...)}
     * 这两个（返 false / 返 null）。</p>
     */
    private static Double readAttribute(LivingEntity entity, Attribute attribute) {
        if (entity == null || !entity.getAttributes().hasAttribute(attribute)) {
            return null;
        }
        return entity.getAttributeValue(attribute);
    }

    /** 鼠标是否落在抬头带（属性明细的命中区）。 */
    private boolean isOverSkillHeader(double mouseX, double mouseY) {
        return mouseX >= this.leftPos + TAB_MARGIN
                && mouseX < this.leftPos + this.imageWidth - TAB_MARGIN
                && mouseY >= this.topPos + HEADER_HOVER_TOP_Y
                && mouseY < this.topPos + HEADER_HOVER_BOTTOM_Y;
    }

    /**
     * 悬停明细的属性清单 —— 按<b>实体类型</b>缓存（见 {@link #hoverAttributes} 的说明）。
     *
     * <p>注意这里<b>只缓存清单、不缓存数值</b>：清单由物种决定（一个会话内基本不变），
     * 而数值必须是活值（面板开着时宠物挨打 / 回血都要跟着变）。</p>
     */
    private List<Attribute> hoverAttributes(LivingEntity companion) {
        if (companion == null) {
            return Collections.emptyList();
        }
        EntityType<?> type = companion.getType();
        if (type != this.hoverAttributesType) {
            this.hoverAttributes = AttributeDisplay.displayableAttributes(companion);
            this.hoverAttributesType = type;
        }
        return this.hoverAttributes;
    }

    /**
     * 悬停明细 —— 每项属性<b>一行</b>：{@code 名称 总值 (基础+技能+装备)}，如
     * {@code 最大生命值 20 (20+0+0)}（2026-09-22 乌狸定，原为「总值 / 技能 / 装备 / 其他」两行）。
     *
     * <p><b>三项可加</b>：第一项「基础」取残差（总 − 技能 − 装备），故括号内三数相加
     * <b>恒等于</b>前面的总值 —— 一行同时验到「服务端技能表」与「客户端装备表」两个来源口径一致。</p>
     *
     * <p>括号里的「装备」项<b>受 {@code showEquipTooltip} 管</b>（与装备页摘要 / 悬停同一判据）：
     * 关掉开关时该项不显示，残差把它吞进去，等式照旧成立（见 {@link #equipBonusesEnabled()}）。</p>
     *
     * <p>「总值」取数分两路（设计稿 §4.1 有意接受的不对称）：client-syncable 属性读
     * <b>客户端实体</b>（实时、权威）；<b>非</b>同步属性（攻击伤害 / 跟随范围 / 击退抗性 /
     * 攻击击退）客户端那份缺技能 modifier ⇒ 改用<b>服务端下发值</b>。见 {@link #attributeTotal}。</p>
     *
     * <p><b>列哪几项是动态的</b>（N5）：清单来自 {@link #hoverAttributes(LivingEntity)}
     * （注册表 ∩ 该生物实有 ∩ 未屏蔽），第三方模组加的属性会自然出现。</p>
     */
    private List<Component> skillAttributeLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("furkin.screen.furkin.attributes").withStyle(ChatFormatting.GRAY));

        LivingEntity companion = companionEntity();
        Map<ResourceLocation, Double> equipSums = equipAdditionSums();
        // 「装备」项受装备加成的总开关管（判据同装备页摘要 / 悬停，见 equipBonusesEnabled）：
        // 关掉时这一项不显示，残差（基础）随即把它吞进去 —— 于是「括号内各项相加 = 前面的总值」
        // 恒成立，开关怎么拨都不破等式。开关每帧只读一次（提到循环外）。
        boolean showEquip = equipBonusesEnabled();
        // 清单已由 AttributeDisplay 收口「注册表 ∩ 该生物实有 ∩ 未屏蔽」，此处不再逐条判
        // hasAttribute —— 那条判据同时是崩溃防线（getAttributeValue 对未注册属性会抛）。
        for (Attribute attribute : hoverAttributes(companion)) {
            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
            if (id == null) {
                continue;
            }
            Double total = attributeTotal(attribute, id, companion);
            double skill = this.skillData == null ? 0.0D : this.skillData.skillBonusOf(id.toString());
            double equip = showEquip ? equipSums.getOrDefault(id, 0.0D) : 0.0D;
            // 「基础」取残差（总 − 技能 − 装备），故三数相加恒等于总值。
            // 总值读不出时残差同样读不出，同时省掉括号 —— 免得把 -- 当成 0 算出个负数。
            Double base = total == null ? null : total - skill - equip;

            MutableComponent line = Component.translatable(attribute.getDescriptionId())
                    .withStyle(COLOR_ATTR_NAME)
                    .append(Component.literal(" " + (total == null ? UNAVAILABLE : plain(total)))
                            .withStyle(COLOR_ATTR_TOTAL));
            if (base != null) {
                // 三项之间的 "+" 与括号走中性灰（原「技能 / 装备 / 其他」三个标签的位置），
                // 颜色只留给三个数 —— 与改动前的色义一致：黄=总、灰=基础、绿=技能、蓝=装备。
                line.append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                        .append(term(base).withStyle(COLOR_ATTR_BASE))
                        .append(Component.literal("+").withStyle(ChatFormatting.GRAY))
                        .append(term(skill).withStyle(COLOR_ATTR_SKILL));
                if (showEquip) {
                    line.append(Component.literal("+").withStyle(ChatFormatting.GRAY))
                            .append(term(equip).withStyle(COLOR_ATTR_EQUIP));
                }
                line.append(Component.literal(")").withStyle(ChatFormatting.GRAY));
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * 某属性的「总值」；<b>读不出时返回 {@code null}</b>（调用方显示 {@link #UNAVAILABLE}）。
     *
     * <p>可同步 ⇒ 读客户端实体（实时）；非同步（攻击伤害）⇒ 客户端那份只有实体类型的默认基值、
     * 技能 modifier 从不上网（设计稿 §4.1 取证⑦），故取服务端下发值；服务端没给这条
     * （旧包 / 该属性不在下发之列）则回落到客户端那份 —— 少一段技能加成，但好过显示 0。</p>
     */
    private Double attributeTotal(Attribute attribute, ResourceLocation id, LivingEntity companion) {
        Double local = readAttribute(companion, attribute);
        if (local == null || attribute.isClientSyncable()) {
            return local;
        }
        Double server = this.skillData == null ? null : this.skillData.serverTotalOf(id.toString());
        return server != null ? server : local;
    }

    /** 四个装备槽给出的属性加成（按属性合并，只取 addition —— 与「三段可加」同口径）。 */
    private Map<ResourceLocation, Double> equipAdditionSums() {
        Map<ResourceLocation, Double> sums = new HashMap<>();
        for (EquipBonus.Entry entry : EquipBonus.total(this.menu.getEquipment())) {
            if (entry.operation() != AttributeModifier.Operation.ADDITION) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(entry.attribute());
            if (id != null) {
                sums.merge(id, entry.amount(), Double::sum);
            }
        }
        return sums;
    }

    /**
     * 画一行技能：名字 + 等级进度 + 行内「+1」。
     *
     * <p>在 {@link SkillListWidget#renderContents} 里调用 —— 框架已经把 pose 平移了
     * {@code -scrollAmount}，故此处一律用<b>未滚动的绝对坐标</b>，滚动由框架负责。</p>
     */
    private void renderSkillRow(PoseStack pose, int index, boolean hoveredRow, boolean hoveredButton) {
        OpenFurkinScreenPacket.SkillView skill = this.skills.get(index);
        int rowHeight = rowHeight();
        int y = rowY(index);
        int left = listLeft();
        int right = listLeft() + listWidth();

        boolean maxed = skill.isMaxed();
        // 可点性判据只有一个出口（见 buttonEnabled）—— 渲染与命中必须同源。
        boolean enabled = buttonEnabled(skill);

        if (hoveredRow) {
            GuiComponent.fill(pose, left, y, right, y + rowHeight, 0x22FFFFFF);
        }

        int textY = y + (rowHeight - 8) / 2;
        this.font.draw(pose, Component.translatable(skill.getNameKey()), left + 2, textY,
                COLOR_LABEL);
        String levelText = skill.isInfinite()
                ? String.valueOf(skill.getCurrentLevel())
                : skill.getCurrentLevel() + "/" + skill.getMaxLevel();
        this.font.draw(pose, levelText, left + SKILL_LEVEL_COLUMN, textY,
                maxed ? COLOR_MAXED : COLOR_HINT);

        // 按钮左缘走共用出口（见 buttonLeft）：渲染与命中必须同源，且已右收留出滚动条间隙。
        int bx = buttonLeft();
        int by = y + (rowHeight - SKILL_BUTTON_HEIGHT) / 2;
        // 三态直接映射到官方贴图行：不可用 → 禁用行，悬停 → 高亮行，其余 → 普通行。
        int textureV = enabled
                ? (hoveredButton ? BUTTON_V_HOVER : BUTTON_V_NORMAL)
                : BUTTON_V_DISABLED;
        blitButton(pose, bx, by, SKILL_BUTTON_WIDTH, SKILL_BUTTON_HEIGHT, textureV);
        GuiComponent.drawCenteredString(pose, this.font, Component.literal("+1"),
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
     * 滚轮路由 —— 1.19.2 的 {@code AbstractScrollWidget.mouseScrolled} 要求控件已获得焦点；
     * 面板初开时列表尚未聚焦，首次滚轮会被直接忽略。鼠标位于列表范围时先补焦，
     * 再复用官方滚动逻辑，避免要求玩家先点击一次。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.skillList != null && this.skillList.visible
                && mouseX >= listLeft() && mouseX < listLeft() + listWidth() + SCROLLBAR_WIDTH
                && mouseY >= listTop() && mouseY < listBottom()) {
            this.setFocused(this.skillList);
            this.skillList.focusForScroll();
            return this.skillList.mouseScrolled(mouseX, mouseY, delta);
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
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
     * <p>1.19.2 的 {@code renderBackground} 是 {@code private}，子类无法覆写或调用。当前
     * 覆写公开的 {@code renderButton}，沿用官方裁剪、滚动位移、内容绘制与滚动条绘制，
     * 仅省略黑色背景和边框。迁移细节与迁回 1.20.1 的处理见
     * {@code docs/wp6-scroll-widget-api.md}。</p>
     */
    private final class SkillListWidget extends AbstractScrollWidget {

        SkillListWidget(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        /** 为未点击时的滚轮路由补上控件内部焦点；{@code Screen#setFocused} 不会设置此布尔值。 */
        void focusForScroll() {
            setFocused(true);
        }

        /** 内容总高 = 行数 × 行高；超过控件高度即出现滚动条（框架的 {@code scrollbarVisible}）。 */
        @Override
        protected int getInnerHeight() {
            return FurkinPanelScreen.this.skills.size() * rowHeight();
        }

        /** 1.19.2 将它声明为抽象方法；语义与 1.20.1 官方默认实现相同。 */
        @Override
        protected boolean scrollbarVisible() {
            return getInnerHeight() > getHeight();
        }

        /** 滚轮一格 = 一行。 */
        @Override
        protected double scrollRate() {
            return rowHeight();
        }

        /**
         * 复用 1.19.2 官方 {@code renderButton} 的绘制流程，但不调用其私有背景方法。
         *
         * <p>只省略黑色背景和边框；裁剪、滚动位移、内容绘制与官方滚动条仍按下述顺序执行。</p>
         */
        @Override
        public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
            if (!this.visible) {
                return;
            }

            enableScissor(
                    this.x + 1,
                    this.y + 1,
                    this.x + this.width - 1,
                    this.y + this.height - 1);

            pose.pushPose();
            pose.translate(0.0D, -scrollAmount(), 0.0D);
            renderContents(pose, mouseX, mouseY, partialTick);
            pose.popPose();

            disableScissor();
            renderDecorations(pose);
        }

        /**
         * 人读支持。
         *
         * <p>1.19.2 的念白入口是 {@code NarratableEntry} 继承来的
         * {@code updateNarration}；1.20.1 才拆成 final {@code updateNarration} 与
         * protected {@code updateWidgetNarration}。这里仍复用按钮的默认念白。</p>
         */
        @Override
        public void updateNarration(NarrationElementOutput narration) {
            this.defaultButtonNarrationText(narration);
        }

        @Override
        protected void renderContents(PoseStack pose, int mouseX, int mouseY, float partialTick) {
            // 渲染用的是「未滚动的绝对坐标」（框架已把 pose 平移 -scrollAmount），
            // 而鼠标坐标是屏幕坐标 —— 两者相差一个滚动量，命中一律走 rowScreenY。
            int hoveredRow = rowIndexAtScreen(mouseY);
            for (int i = 0; i < FurkinPanelScreen.this.skills.size(); i++) {
                renderSkillRow(pose, i, hoveredRow == i, buttonHit(mouseX, mouseY, i));
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

    /**
     * 技能列表可用区上沿：让出标题条与抬头三行。
     *
     * <p>= 第三行文字 y（{@link #HEADER_ROW_3_Y}）＋ 12（行距）。<b>面板加高 Δ 不改变列表可用高度</b>：
     * {@link #listBottom()} 也随 {@code imageHeight} 同增 Δ，故加高的收益只落在抬头。</p>
     */
    private int listTop() {
        return this.topPos + HEADER_ROW_3_Y + 12;
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
