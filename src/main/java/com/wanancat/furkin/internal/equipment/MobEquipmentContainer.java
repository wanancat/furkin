package com.wanancat.furkin.internal.equipment;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 绒亲装备容器 —— 把原版实体的四个盔甲槽包成 {@link Container}，供 {@code Slot} 使用。
 *
 * <p><b>为什么必须自写</b>（2026-09-22 javap 取证）：{@code Slot} 的构造器硬要
 * {@link Container}，而 {@code Mob} 的父接口链里<b>没有</b> {@code Container}。Forge 确实提供了
 * 实体装备槽的包装器 {@code EntityEquipmentInvWrapper}，但它实现的是
 * {@code IItemHandlerModifiable}；反方向的 {@code InvWrapper(Container)} 又是另一个方向。
 * 两个都不合用 ⇒ 只能自己写一层。这不是重复造轮子，是官方确无此物。</p>
 *
 * <p><b>真正的真相源是实体自己的装备槽</b>（设计稿已定第 3 条）：本类<b>不持有任何物品</b>，
 * 所有读写都转发 {@link LivingEntity#getItemBySlot} / {@link LivingEntity#setItemSlot}。
 * 不额外存一份的代价是取用即转发，好处是「实体存档 / 原版属性结算 / 原版装备同步」三条链路
 * 全部自动生效 —— 收下这份数据，就不用再管它。</p>
 *
 * <p><b>双端分工</b>（与 {@code FurkinInventory} 同款）：服务端拿到真实体，读写直通装备槽；
 * 客户端拿不到实体（{@code null}），退化为一份 4 格空缓冲，内容由
 * {@code ClientboundContainerSetSlotPacket} 逐格灌入 —— 官方箱子的客户端侧正是这个模式。</p>
 *
 * <p><b>索引 → 槽位的对应</b>：{@code 0 = 头 / 1 = 胸 / 2 = 腿 / 3 = 脚}，与界面上从左到右
 * 的排列同序。注意<b>不能</b>用 {@code EquipmentSlot.byTypeAndIndex(ARMOR, i)} —— 那个顺序是
 * {@code 脚→腿→胸→头}（跟随 {@code getIndex()}），正好和界面相反。</p>
 */
public class MobEquipmentContainer implements Container {

    /** 槽位数 —— 原版四个盔甲槽。 */
    public static final int SLOT_COUNT = 4;

    /** 容器级堆叠上限（与官方箱子一致）；实际写入的栈还会按物品自身上限再收一层。 */
    private static final int MAX_STACK = 64;

    /** 索引 → 原版槽位，顺序即界面顺序（见类注释）。 */
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** 持有实体；仅服务端非 null（客户端由能力对象自身占位）。 */
    @Nullable
    private final LivingEntity entity;

    /** 客户端占位存储；服务端为 {@code null}（不浪费 4 格）。 */
    @Nullable
    private final NonNullList<ItemStack> clientBuffer;

    public MobEquipmentContainer(@Nullable LivingEntity entity) {
        this.entity = entity;
        this.clientBuffer = entity == null
                ? NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
                : null;
    }

    // ===== Container 实现 =====

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (!isValid(slot)) {
            return ItemStack.EMPTY;
        }
        return entity != null ? entity.getItemBySlot(SLOTS[slot]) : clientBuffer.get(slot);
    }

    /**
     * 取出部分物品。
     *
     * <p><b>不能借用 {@code ContainerHelper.removeItem}</b>：那个工具是给
     * {@code NonNullList} 用的，它对「切完后原栈变空」的处理是把<b>列表项</b>置空。
     * 而我们的原栈是实体装备槽里的那个对象 —— {@code split} 会就地削去数量（归零时对象
     * 内部变成空气），但槽位本身仍引用着这个空气对象。故这里在归零时显式写一次空格，
     * 免得槽里留一个「看得见但读不出」的僵尸栈。</p>
     */
    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack current = getItem(slot);
        if (current.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = current.split(amount);
        if (current.isEmpty()) {
            write(slot, ItemStack.EMPTY);
        }
        setChanged();
        return taken;
    }

    /** 取出整格且不发变更通知（对齐官方 {@code ContainerHelper.takeItem} 的语义）。 */
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (!isValid(slot)) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = getItem(slot);
        write(slot, ItemStack.EMPTY);
        return taken;
    }

    /**
     * 写入格子。
     *
     * <p>对齐官方 {@code SimpleContainer#setItem} 的「就地收敛超量栈」，并比它多收一层：
     * 上限取 {@code min(64, 物品自身上限)}。装备槽里理论上只会有盔甲（自身上限为 1），
     * 但第三方模组可能给出可堆叠的饰品，那时候按物品自己的上限走才是对的。</p>
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!isValid(slot)) {
            return;
        }
        int limit = limitFor(stack);
        if (!stack.isEmpty() && stack.getCount() > limit) {
            stack.setCount(limit);
        }
        write(slot, stack);
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return MAX_STACK;
    }

    /**
     * 变更通知 —— <b>刻意留空</b>。
     *
     * <p>理由（2026-09-22 javap 取证）：装备内容有两套官方同步各自负责，本层无事可做。</p>
     * <ul>
     *   <li><b>实体侧</b>：写入走 {@code Mob#setItemSlot} → 原版装备变更检测 → 自动推给追踪者，
     *       并随实体存档；</li>
     *   <li><b>菜单侧</b>：{@code AbstractContainerMenu#broadcastChanges} 每 tick 逐个比对
     *       {@code slot.getItem()}（实时转发到实体槽）与 {@code remoteSlots}，不等就发同步包 ——
     *       <b>不需要</b>容器主动通知。</li>
     * </ul>
     *
     * <p>而 {@code Container} 契约里 {@code setChanged} 的唯一用途就是通知挂在容器上的监听器；
     * 本容器不提供监听器注册（{@code FurkinInventory} 提供，是因为行囊有「格数变化须重开菜单」
     * 这类容器级事件，装备不存在这类事件）。官方 {@code SimpleContainer} 在无监听器时同样是空转。</p>
     */
    @Override
    public void setChanged() {
        // 见 javadoc：装备的同步由原版实体与菜单两套机制各自完成。
    }

    /**
     * 容器有效性：恒 {@code true}。
     *
     * <p>与 {@code FurkinInventory} / 官方 {@code SimpleContainer} 同口径 ——
     * 「宠物得在附近才能开面板」属 <b>Menu 层</b>判据（见 {@code FurkinPouchMenu#stillValid}），
     * 不在容器层。</p>
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            write(i, ItemStack.EMPTY);
        }
        setChanged();
    }

    /**
     * 准入判据 —— <b>判据本体在这里，但闸门不在这里</b>。
     *
     * <p><b>别误以为它会自动生效</b>（2026-09-22 javap 取证）：{@code Slot#mayPlace} 字节码
     * 是 {@code iconst_1; ireturn}，恒真、<b>不转发</b>到本方法 ⇒ 界面上的每次放入都要靠
     * {@code FurkinPouchMenu.TabSlot#mayPlace} 显式转发过来。本方法真正直接服务的调用方是
     * <b>物流侧</b>（漏斗 / 管道经 {@code Container} 契约读它）。</p>
     *
     * <p>判据本体见 {@link EquipValidator#fits}。</p>
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return isValid(slot) && EquipValidator.fits(SLOTS[slot], stack);
    }

    // ===== 内部 =====

    /**
     * 索引 → 原版槽位。
     *
     * <p>公开出去是给属性计算用的（{@link EquipBonus} 得按槽位去问
     * {@code ItemStack#getAttributeModifiers}）—— 顺序表的真相源只应有一份，
     * 让调用方自己再维护一张「索引 → 槽位」的表，迟早会与这里错开。
     * 调用方需自行保证下标合法（本类各读路径都已过 {@link #isValid}）。</p>
     */
    public static EquipmentSlot slotFor(int index) {
        return SLOTS[index];
    }

    private void write(int slot, ItemStack stack) {
        if (entity != null) {
            entity.setItemSlot(SLOTS[slot], stack);
        } else {
            clientBuffer.set(slot, stack);
        }
    }

    private static boolean isValid(int slot) {
        return slot >= 0 && slot < SLOT_COUNT;
    }

    private static int limitFor(ItemStack stack) {
        return Math.min(MAX_STACK, stack.getMaxStackSize());
    }
}
