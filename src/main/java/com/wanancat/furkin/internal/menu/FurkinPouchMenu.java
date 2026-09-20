package com.wanancat.furkin.internal.menu;

import com.wanancat.furkin.internal.inventory.FurkinInventory;
import com.wanancat.furkin.internal.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 绒亲面板菜单 —— 行囊槽位 + 玩家背包槽位。
 *
 * <p><b>形状对标官方 {@code ChestMenu}</b>：9 列、行数可变、玩家背包位置随行数平移。
 * 坐标偏移 {@code (rows - 4) * 18} 与常量 {@code 103}/{@code 161} 抄自官方
 * {@code ChestMenu} 的字节码，因此可以直接套用原版 {@code generic_54.png} 纹理，
 * 不需要任何新贴图。</p>
 *
 * <p><b>与官方箱子的两处差别</b>：</p>
 * <ul>
 *   <li>容器不是 {@code SimpleContainer}，而是挂在实体 capability 上的
 *       {@link FurkinInventory}（格数随 {@code travel_pouch} 等级变）；</li>
 *   <li>槽位带「页签可见性」—— 切到技能 / 装备页时整屏槽位不渲染、不响应鼠标。</li>
 * </ul>
 *
 * <p><b>双端分工</b>（与官方箱子完全一致）：服务端拿 capability 里的真容器；
 * 客户端只拿格数，用同格数空容器占位，内容由 {@code ClientboundContainerSetSlotPacket}
 * 逐格灌入。</p>
 */
public class FurkinPouchMenu extends AbstractContainerMenu {

    /** 行囊列数（与原版箱子一致）。 */
    public static final int COLUMNS = 9;
    /** 玩家背包行数（主背包 3 行）。 */
    private static final int PLAYER_ROWS = 3;
    /** 玩家背包 + 快捷栏总槽位数（3 × 9 + 9 = 36）。 */
    public static final int PLAYER_SLOTS = PLAYER_ROWS * COLUMNS + COLUMNS;

    /**
     * 「行囊」页签编号 —— 只有这一页的槽位可见、可鼠标交互。
     *
     * <p>0 预留给技能页、2 给装备页（与 {@code FurkinPanelScreen} 的常量对齐）；
     * 之所以本类只认这一个值，是因为另外两页没有槽位语义。</p>
     */
    public static final int TAB_POUCH = 1;

    /** 有效性距离上限的平方（8 格）—— 对齐官方 {@code HorseInventoryMenu} 的判据。 */
    private static final double RANGE_SQR = 64.0;

    private final Container pouch;
    private final int pouchSlots;
    private final int rows;
    private final UUID companionId;
    /** 持有实体；仅服务端非 null（客户端 menu 不做距离校验）。 */
    private final Entity holder;

    /**
     * 客户端当前页签 —— 只影响 {@link Slot#isActive()}（渲染与鼠标命中）。
     *
     * <p><b>已知边界</b>：服务端从不读本字段，故服务端的槽位恒为有效。
     * 客户端在非行囊页不会发出指向这些槽位的点击包（{@code findSlot} 跳过非 active 槽位），
     * 但构造恶意包仍会被服务端接受。单机自用无碍，多人环境需要再加一层服务端侧校验。</p>
     */
    private int activeTab = TAB_POUCH;

    /** 服务端构造：持有真实容器与实体。 */
    public FurkinPouchMenu(int windowId, Inventory playerInv, Container pouch,
                           UUID companionId, Entity holder) {
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

        registerSlots(playerInv);
    }

    /** 客户端网络工厂入口（由 {@link ModMenus} 的方法引用调用）。 */
    public static FurkinPouchMenu fromNetwork(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        int slotCount = buf.readVarInt();
        UUID companionId = buf.readUUID();
        return new FurkinPouchMenu(windowId, playerInv,
                new FurkinInventory(slotCount), companionId, null);
    }

    /** 注册全部槽位：行囊区在前，玩家背包在后（顺序决定 {@link #quickMoveStack} 的分段）。 */
    private void registerSlots(Inventory playerInv) {
        // 行囊：9 列自 (8, 18) 铺开，步进 18（与官方箱子同原点）。
        for (int i = 0; i < this.pouchSlots; i++) {
            this.addSlot(new TabSlot(this.pouch, i,
                    8 + (i % COLUMNS) * 18, 18 + (i / COLUMNS) * 18));
        }

        // 玩家背包：位置随行囊行数平移，使面板始终与纹理对齐。
        int offset = (this.rows - 4) * 18;
        for (int r = 0; r < PLAYER_ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                this.addSlot(new TabSlot(playerInv, 9 + r * COLUMNS + c,
                        8 + c * 18, 103 + r * 18 + offset));
            }
        }
        for (int c = 0; c < COLUMNS; c++) {
            this.addSlot(new TabSlot(playerInv, c, 8 + c * 18, 161 + offset));
        }
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

    public int getActiveTab() {
        return activeTab;
    }

    /** 切换页签（仅客户端调用）。 */
    public void setActiveTab(int tab) {
        this.activeTab = tab;
    }

    /** 行囊容器（服务端为真容器，客户端为占位容器）。 */
    public Container getPouch() {
        return pouch;
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
     * Shift 点击的双向搬运 —— 照官方 {@code ChestMenu} 的分段规则：
     * 行囊区 → 往玩家背包塞；玩家背包区 → 往行囊塞。
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

        if (index < this.pouchSlots) {
            if (!this.moveItemStackTo(stack, this.pouchSlots, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, 0, this.pouchSlots, false)) {
            return ItemStack.EMPTY;
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
     * 只在「行囊页」可见、可鼠标命中的槽位。
     *
     * <p>覆盖 {@link Slot#isActive()} 是官方给的正路：{@code AbstractContainerScreen}
     * 的 {@code render} / {@code renderSlot} / {@code findSlot} 三处都会查它，
     * 返回 false 的槽位既不渲染也不响应鼠标。之所以不能用「把槽位挪到屏幕外」代替 ——
     * {@code Slot.x} / {@code Slot.y} 是 final，构造后无法再动。</p>
     */
    private final class TabSlot extends Slot {

        TabSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return activeTab == TAB_POUCH;
        }
    }
}
