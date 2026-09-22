package com.wanancat.furkin.internal.menu;

import com.wanancat.furkin.internal.equipment.MobEquipmentContainer;
import com.wanancat.furkin.internal.inventory.FurkinInventory;
import com.wanancat.furkin.internal.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 绒亲面板菜单 —— 行囊槽位 + 装备槽位 + 玩家背包槽位。
 *
 * <p><b>形状对标官方 {@code ChestMenu}</b>：9 列、行数可变、玩家背包位置随行数平移。
 * 坐标偏移 {@code (rows - 4) * 18} 与常量 {@code 103}/{@code 161} 抄自官方
 * {@code ChestMenu} 的字节码，因此可以直接套用原版 {@code generic_54.png} 纹理，
 * 不需要任何新贴图。</p>
 *
 * <p><b>与官方箱子的三处差别</b>：</p>
 * <ul>
 *   <li>行囊容器不是 {@code SimpleContainer}，而是挂在实体 capability 上的
 *       {@link FurkinInventory}（格数随 {@code travel_pouch} 等级变）；</li>
 *   <li>多一段<b>装备槽</b>：容器是 {@link MobEquipmentContainer}（转发原版实体的四个盔甲槽），
 *       位置占用行囊区首行 —— 与行囊槽坐标完全重合，靠页签可见性互斥；</li>
 *   <li>槽位带「页签可见性」：每个槽位声明自己在哪些页签可见（见 {@link TabSlot}），
 *       页签不匹配时既不渲染也不响应鼠标。</li>
 * </ul>
 *
 * <p><b>双端分工</b>（与官方箱子完全一致）：服务端拿 capability 里的真容器与真实体；
 * 客户端只拿格数，用同格数空容器占位，内容由 {@code ClientboundContainerSetSlotPacket}
 * 逐格灌入。</p>
 */
public class FurkinPouchMenu extends AbstractContainerMenu {

    /**
     * 面板在「槽位区与玩家背包段之间」额外插入的高度（设计稿 §4.1 定案 N1）。
     *
     * <p>技能页抬头由两行变三行（名称+物种 / 等级+经验 / 技能点+生命+护甲），多出的
     * <b>恰好 12px</b>（原两行的行位 18 / 30 → 三行的 6 / 18 / 30 与列表顶 42，比原口径各留一行）。
     * 初版按「12 + 一段呼吸位」定为 24；2026-09-22 乌狸反馈「面板高度可以往回缩一下」
     * ⇒ 去掉那 12px 呼吸位，回到与抬头增量等值的 <b>12</b>（呼吸位由空出的屏高自然给出，
     * 见下）。三个页签<b>同一高度</b>：{@code imageHeight} 是屏级字段、{@code Slot.y} 是 final，
     * 按页签给不同高度只能靠「切页时重开菜单」，代价远大于统一加高。</p>
     *
     * <p>缩到 12 的连带收益：面板矮 12 ⇒ 居中后 {@code topPos} 大 6 ⇒ 画在面板上沿之外的
     * 页签离屏幕顶也宽 6px（乌狸同轮反馈「页签上边缘显得窄」）。</p>
     *
     * <p><b>常量放在菜单侧</b>：槽位纵坐标（本类的业务）与面板高度（屏侧读它）必须同源 ——
     * 两处各写一个数，改一处漏一处就会出现「背包槽与格子错位」。</p>
     */
    public static final int PANEL_EXTRA_HEIGHT = 12;

    /** 行囊列数（与原版箱子一致）。 */
    public static final int COLUMNS = 9;
    /** 玩家背包行数（主背包 3 行）。 */
    private static final int PLAYER_ROWS = 3;
    /** 玩家背包 + 快捷栏总槽位数（3 × 9 + 9 = 36）。 */
    public static final int PLAYER_SLOTS = PLAYER_ROWS * COLUMNS + COLUMNS;

    /**
     * 页签编号 —— 与 {@code FurkinPanelScreen} 的常量一一对应（技能页没有槽位，故此处无常量）。
     *
     * <p>编号同时被当作 {@link #tabMask} 的位序使用，故必须是 0 起的连续小整数。</p>
     */
    public static final int TAB_POUCH = 1;
    public static final int TAB_EQUIP = 2;

    /** 有效性距离上限的平方（8 格）—— 对齐官方 {@code HorseInventoryMenu} 的判据。 */
    private static final double RANGE_SQR = 64.0;

    private final Container pouch;
    private final int pouchSlots;
    /** 装备容器：服务端转发实体的四个原版盔甲槽，客户端为占位缓冲。 */
    private final MobEquipmentContainer equipment;
    private final int rows;
    private final UUID companionId;
    /** 持有实体；仅服务端非 null（客户端 menu 不做距离校验）。 */
    private final Entity holder;

    /**
     * 面板对着的那只实体的网络 id —— <b>客户端定位实体唯一的入口</b>。
     *
     * <p><b>为什么必须有它</b>（设计稿 §4.1 取证⑥）：{@code ClientLevel#getEntity(int)} 是 public，
     * 而按 UUID 取实体的 {@code getEntities()} 是 protected ⇒ 客户端手里只有 UUID 是取不到实体的。
     * 技能页要读实体上的实时属性（生命 / 护甲 / 各属性），必须先拿到这个 int。</p>
     *
     * <p><b>为什么走菜单开屏缓冲而不是两个自定义包</b>：它与 {@code companionId} 是同一件事
     * （「这块面板对着谁」），同源同处；塞进 {@code OpenFurkinScreenPacket} 或
     * {@code SyncFurkinDataPacket} 都等于把身份拆成两处、各自维护一份。</p>
     */
    private final int entityId;

    /**
     * 当前页签 —— 双端各存一份，值来源相同。
     *
     * <p><b>客户端那一份</b>只影响 {@link Slot#isActive()}（渲染与鼠标命中）；
     * <b>服务端那一份</b>由 {@code SelectTabPacket} 同步而来（见 {@link #setActiveTab}），
     * 供 {@link #quickMoveStack} 决定 Shift 点击的落点。两件事本来是一件：服务端搬运
     * <b>不看</b> {@code isActive()}（取证：{@code AbstractContainerMenu} 全类 0 处调用它），
     * 所以页签不同步上去，服务端就无从知道用户在翻哪一页。</p>
     *
     * <p><b>已知边界</b>：服务端拿到的是客户端声明的值，不校验真伪。最坏结果是物品落进
     * 另一个容器，不会凭空产生或消失 —— 单机自用无碍；多人环境若要收紧，需要补一层
     * 「该页签在本菜单是否可用」的校验。</p>
     */
    private int activeTab = TAB_POUCH;

    /** 服务端构造：持有真实容器与实体。{@code entityId} 由调用方给（服务端即 {@code holder.getId()}）。 */
    public FurkinPouchMenu(int windowId, Inventory playerInv, Container pouch,
                           UUID companionId, Entity holder, int entityId) {
        super(ModMenus.FURKIN_POUCH.get(), windowId);
        this.pouch = pouch;
        this.pouchSlots = pouch.getContainerSize();
        // 行数 = 格数向上取整到整行，且至少 1 行。
        // 「至少 1 行」是给面板一个高度下限：0 格时行囊页根本不出现（见 FurkinPanelScreen
        // 的 tabAvailable），但技能页的技能列表仍要用这块高度；若允许 0 行，面板只有 114 高，
        // 技能条目一多就会画到面板外面去。
        this.rows = Math.max(1, (this.pouchSlots + COLUMNS - 1) / COLUMNS);
        this.companionId = companionId;
        this.holder = holder;
        this.entityId = entityId;
        // 装备容器只在拿得到活体时才真的有转发目标：客户端 menu 的 holder 是 null
        // （实体不在客户端），此时容器退化为 4 格占位缓冲，内容由容器同步包灌入。
        this.equipment = new MobEquipmentContainer(
                holder instanceof LivingEntity living ? living : null);

        registerSlots(playerInv);
    }

    /** 客户端网络工厂入口（由 {@link ModMenus} 的方法引用调用）。 */
    public static FurkinPouchMenu fromNetwork(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        // ⚠️ 读取顺序必须与 FurkinRecordActionHandler#openMenu 的写入顺序一致。
        int slotCount = buf.readVarInt();
        UUID companionId = buf.readUUID();
        int entityId = buf.readVarInt();
        return new FurkinPouchMenu(windowId, playerInv,
                new FurkinInventory(slotCount), companionId, null, entityId);
    }

    /** 注册全部槽位：行囊区 → 装备区 → 玩家背包（顺序决定 {@link #quickMoveStack} 的分段）。 */
    private void registerSlots(Inventory playerInv) {
        // 行囊（页签「行囊」）：9 列自 (8, 18) 铺开，步进 18（与官方箱子同原点）。
        for (int i = 0; i < this.pouchSlots; i++) {
            this.addSlot(new TabSlot(this.pouch, i,
                    8 + (i % COLUMNS) * 18, 18 + (i / COLUMNS) * 18, tabMask(TAB_POUCH)));
        }

        // 装备（页签「装备」）：恒 4 格，自 (8, 18) 横排 —— 与行囊首行同坐标，靠页签互斥。
        // 为什么只能占这块：Slot.x / Slot.y 是 final，Menu 构造后无法再挪位；玩家背包的位置
        // 又必须两页共用（由 (rows - 4) * 18 平移，跟着行囊行数走）⇒ 能动用的只剩行囊区。
        // 两页同坐标不冲突，因为 AbstractContainerScreen 的 renderSlot / findSlot 都查 isActive()。
        for (int i = 0; i < MobEquipmentContainer.SLOT_COUNT; i++) {
            this.addSlot(new TabSlot(this.equipment, i,
                    8 + i * 18, 18, tabMask(TAB_EQUIP)));
        }

        // 玩家背包：位置随行囊行数平移，使面板始终与纹理对齐；两个有槽位的页都要看得见。
        // 末尾 + PANEL_EXTRA_HEIGHT：抬头加高把那一段净板带插在槽位区与背包段之间，
        // 背包槽必须跟着下移同样的量，否则槽位与纹理里的格子错开（见 PANEL_EXTRA_HEIGHT）。
        int playerTabs = tabMask(TAB_POUCH) | tabMask(TAB_EQUIP);
        int offset = (this.rows - 4) * 18 + PANEL_EXTRA_HEIGHT;
        for (int r = 0; r < PLAYER_ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                this.addSlot(new TabSlot(playerInv, 9 + r * COLUMNS + c,
                        8 + c * 18, 103 + r * 18 + offset, playerTabs));
            }
        }
        for (int c = 0; c < COLUMNS; c++) {
            this.addSlot(new TabSlot(playerInv, c, 8 + c * 18, 161 + offset, playerTabs));
        }
    }

    /**
     * 页签编号 → 可见性位掩码。
     *
     * <p>用掩码而不是「所属页签」单个值，唯一原因是玩家背包槽要<b>在两个页签下同时可见</b> ——
     * 单值表达不了「或」。加一层掩码后一个 {@link TabSlot} 就能覆盖全部三种可见性组合，
     * 不必再写第二个近乎相同的 Slot 子类。</p>
     */
    private static int tabMask(int tab) {
        return 1 << tab;
    }

    /** 装备区起始下标（紧随行囊区之后）。 */
    private int equipStart() {
        return this.pouchSlots;
    }

    /** 装备区结束下标 —— 同时是玩家背包的起始下标。 */
    private int equipEnd() {
        return this.pouchSlots + MobEquipmentContainer.SLOT_COUNT;
    }

    /** 玩家背包起始下标（主背包 3 行 + 快捷栏共 36 格，到 {@code slots.size()} 为止）。 */
    private int playerStart() {
        return equipEnd();
    }

    // ===== 对外只读 / 页签 =====

    public int getPouchSlots() {
        return pouchSlots;
    }

    public int getRows() {
        return rows;
    }

    public UUID getCompanionId() {
        return companionId;
    }

    /**
     * 面板对着的实体的网络 id（客户端用它经 {@code ClientLevel#getEntity(int)} 取实体）。
     *
     * <p>取不到实体时为 -1（旧包 / 异常），屏侧据此跳过实体相关字段的显示。</p>
     */
    public int getEntityId() {
        return entityId;
    }

    public int getActiveTab() {
        return activeTab;
    }

    /** 切换页签 —— 客户端点按钮时改本地那份；服务端由 {@code SelectTabPacket} 改它那份。 */
    public void setActiveTab(int tab) {
        this.activeTab = tab;
    }

    /** 行囊容器（服务端为真容器，客户端为占位容器）。 */
    public Container getPouch() {
        return pouch;
    }

    /** 装备容器（服务端转发实体装备槽，客户端为占位缓冲）。 */
    public MobEquipmentContainer getEquipment() {
        return equipment;
    }

    // ===== AbstractContainerMenu =====

    /**
     * 仍有效：实体存活且在 8 格内。
     *
     * <p>{@code holder} 为 null（客户端占位菜单）时恒真 —— 距离校验只由服务端做，
     * 服务端持有真实实体引用。</p>
     */
    @Override
    public boolean stillValid(Player player) {
        if (this.holder == null) {
            return true;
        }
        return this.holder.isAlive() && this.holder.distanceToSqr(player) <= RANGE_SQR;
    }

    /**
     * Shift 点击的双向搬运。
     *
     * <p><b>容器段 → 玩家背包</b>：行囊与装备都往玩家背包塞（装备槽 Shift 点击 = 脱下）。</p>
     *
     * <p><b>玩家背包 → 容器段</b>：落点<b>按当前页签分流</b> —— 装备页只往装备区塞
     * （收不收由 {@link TabSlot#mayPlace} 把关，非装备物品一律被拒），其余页往行囊塞。
     * 页签必须由客户端上行同步（{@code SelectTabPacket}）：本方法在<b>服务端</b>执行，
     * 而服务端的槽位恒为 {@code isActive() == true}，光靠它在哪一页是推不出来的。</p>
     *
     * <p>本方法在 {@code AbstractContainerMenu#doClick} 的 {@code QUICK_MOVE} 分支里被调用；
     * {@code moveItemStackTo} 内部走 {@code Slot#mayPlace}（已 javap 核实），故过滤在
     * Shift 这条路上同样生效。</p>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < equipEnd()) {
            // 行囊 / 装备区 → 玩家背包（装备区 Shift 点击 = 脱下）。
            if (!this.moveItemStackTo(stack, playerStart(), this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 容器段。装备页只认装备区：非装备物品会被 mayPlace 拒掉，于是原地
            // 不动 —— 而不是默默塞进当前页看不见的行囊里（那会让人以为东西丢了）。
            boolean toEquip = activeTab == TAB_EQUIP;
            int from = toEquip ? equipStart() : 0;
            int to = toEquip ? equipEnd() : equipStart();
            if (!this.moveItemStackTo(stack, from, to, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    /**
     * 按「可见页签」显隐的槽位。
     *
     * <p>覆盖 {@link Slot#isActive()} 是官方给的正路：{@code AbstractContainerScreen}
     * 的 {@code render} / {@code renderSlot} / {@code findSlot} 三处都会查它，
     * 返回 false 的槽位既不渲染也不响应鼠标。之所以不能用「把槽位挪到屏幕外」代替 ——
     * {@code Slot.x} / {@code Slot.y} 是 final，构造后无法再动；而装备槽与行囊槽
     * <b>坐标完全重合</b>（都在行囊区首行），页签可见性正是它们唯一的区分手段。</p>
     */
    private final class TabSlot extends Slot {

        /** 可见页签的位掩码（见 {@link #tabMask}）。 */
        private final int tabs;

        TabSlot(Container container, int index, int x, int y, int tabs) {
            super(container, index, x, y);
            this.tabs = tabs;
        }

        @Override
        public boolean isActive() {
            return (tabs & tabMask(activeTab)) != 0;
        }

        /**
         * 准入 —— <b>必须是这里，不能只写在容器的 {@code canPlaceItem}</b>。
         *
         * <p><b>硬事实（2026-09-22 javap 取证）</b>：{@code Slot#mayPlace} 的字节码只有
         * {@code iconst_1; ireturn} —— <b>恒返回 true，从不转发</b>
         * {@code Container#canPlaceItem}。而 {@code AbstractContainerMenu#doClick} 全类有
         * <b>6 处</b> {@code mayPlace} 调用（拖拽放入 / 快速合成 / 数字键交换 / 双击收拢），
         * {@code moveItemStackTo}（Shift 搬运）同样走它 ⇒ 只写 {@code canPlaceItem}
         * 等于一个入口都没挡住（2026-09-22 乌狸实机：鱼戴到了头上）。</p>
         *
         * <p>故闸门转发到容器：这一处同时服务「界面操作」（经 {@code mayPlace}）与
         * 「物流自动化」（漏斗 / 管道读 {@code Container#canPlaceItem}），判据仍只有
         * {@link MobEquipmentContainer#canPlaceItem} 一份。</p>
         *
         * <p>对行囊槽与玩家背包槽是<b>无副作用</b>的转发：{@code Container#canPlaceItem}
         * 是 {@code default → true}，{@code Inventory} 与 {@code FurkinInventory} 都没覆写它
         * （已核 javap）。</p>
         */
        @Override
        public boolean mayPlace(ItemStack stack) {
            return this.container.canPlaceItem(getContainerSlot(), stack);
        }
    }
}
