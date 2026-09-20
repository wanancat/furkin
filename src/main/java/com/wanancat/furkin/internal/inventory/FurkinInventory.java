package com.wanancat.furkin.internal.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 绒亲随身行囊容器 —— 格数可变（随 travel_pouch 等级扩张）。
 *
 * <p><b>为什么不用官方 {@code SimpleContainer}</b>：它的 {@code size} / {@code items}
 * 都是 {@code private final}，子类无法原地改格数。官方 {@code AbstractHorse} 的做法是
 * 整只换掉容器对象（{@code createInventory()} 新建 + 逐格 {@code copy()} 搬迁 + 重挂监听器）。
 * 但本容器挂在 {@code FurkinData} 上，还要求被 Menu / 数据同步长期持有引用，
 * 换对象会让旧引用指向废弃容器（数据错位）。故自实现 {@link Container}，
 * <b>格数原地可变</b>；除格数管理外的每个方法都逐条对齐官方 {@code SimpleContainer} 的语义。</p>
 *
 * <p><b>持久化</b>：走官方 {@link ContainerHelper#saveAllItems} /
 * {@link ContainerHelper#loadAllItems}，NBT 结构与原版箱子一致
 * （{@code Items:[{Slot:0b, id:"...", Count:1b}]}）。每项带 {@code Slot} 定位，
 * 还原时不会像 {@code SimpleContainer#fromTag}（内部走 {@code addItem}）那样被重排。</p>
 */
public class FurkinInventory implements Container {

    /** 格数硬上限（防配置写错导致异常膨胀）。 */
    public static final int HARD_MAX_SIZE = 256;

    private NonNullList<ItemStack> items;
    private final List<ContainerListener> listeners = new ArrayList<>();

    public FurkinInventory(int size) {
        this.items = NonNullList.withSize(clampSize(size), ItemStack.EMPTY);
    }

    // ===== 格数管理 =====

    /**
     * 调整格数（原地，容器对象身份不变）。
     *
     * <p>扩容：原有格子留在原位，新增格子为空列表项。
     * 缩容：只保留前 {@code newSize} 格，被挤出的物品<b>不静默丢弃</b>，
     * 按格子顺序原样返回给调用方处置（掉落 / 拒绝操作）。</p>
     *
     * @return 被挤出的物品（可能为空列表）；扩容时恒为空
     */
    public List<ItemStack> resize(int newSize) {
        int target = clampSize(newSize);
        int current = this.items.size();
        if (target == current) {
            return Collections.emptyList();
        }
        NonNullList<ItemStack> next = NonNullList.withSize(target, ItemStack.EMPTY);
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < Math.max(current, target); i++) {
            ItemStack stack = i < current ? this.items.get(i) : ItemStack.EMPTY;
            if (i < target) {
                next.set(i, stack);
            } else if (!stack.isEmpty()) {
                overflow.add(stack);
            }
        }
        this.items = next;
        setChanged();
        return overflow;
    }

    /**
     * 往行囊里塞物品：先补满同类型格子，再按堆叠上限逐格铺开占用空格。
     *
     * <p>语义 = 官方 {@code Inventory#add}「反复调用 {@code addResource} 直到放不进去为止」的
     * 最终效果：<b>一次调用尽量铺满所有可用空间</b>，返回<b>装不下的剩余</b>；
     * 传入的栈不被修改（内部先 {@code copy()}）。</p>
     *
     * <p><b>为什么空格也必须按 {@code maxStackSize} 切分</b>：官方
     * {@code SimpleContainer#moveItemToEmptySlots} 是「整堆丢进第一个空格后 return」，
     * 之所以成立，是靠上层的 {@code Inventory#add} 循环调用兜底 —— 单次调用它<b>放不完</b>。
     * 而本方法是产出技能（藏骨 / 拾荒）与调试命令的唯一入口，必须单次调用即铺满，
     * 否则 {@code addItem(600 根骨头)} 会把 600 根压进一格（超过原版堆叠上限，非法状态）。</p>
     */
    public ItemStack addItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        // 第一遍：补满已有的同类型格子。
        for (int i = 0; i < items.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining)) {
                int space = Math.min(getMaxStackSize(), slot.getMaxStackSize()) - slot.getCount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getCount());
                    slot.grow(moved);
                    remaining.shrink(moved);
                    setChanged();
                }
            }
        }
        // 第二遍：按堆叠上限逐格铺开剩余部分。
        int perSlot = Math.min(getMaxStackSize(), remaining.getMaxStackSize());
        for (int i = 0; i < items.size() && !remaining.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                int moved = Math.min(perSlot, remaining.getCount());
                ItemStack placed = remaining.copy();
                placed.setCount(moved);
                items.set(i, placed);
                remaining.shrink(moved);
                setChanged();
            }
        }
        return remaining;
    }

    // ===== Container 实现 =====

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    /**
     * 写入格子。
     *
     * <p>对齐官方 {@code SimpleContainer#setItem}：非空栈若 {@code count} 超过
     * {@link #getMaxStackSize()}，<b>就地收敛到上限</b>。这是容器层的最后一道防线 ——
     * 将来 {@code AbstractContainerMenu} 拖放、以及任何外部调用都从这里进，
     * 不能依赖调用方自觉传合法 count。</p>
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.size()) {
            items.set(slot, stack);
            if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
                stack.setCount(getMaxStackSize());
            }
            setChanged();
        }
    }

    @Override
    public void setChanged() {
        for (ContainerListener listener : listeners) {
            listener.containerChanged(this);
        }
    }

    /**
     * 容器有效性：恒 {@code true}。
     *
     * <p>与官方 {@code SimpleContainer} 一致 —— 「宠物得在附近才能开行囊」属 <b>Menu 层</b>判据
     * （官方 {@code HorseInventoryMenu#stillValid} 就是在 Menu 里查 8 格距离），不在容器层。</p>
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        clearItems();
        setChanged();
    }

    // ===== 持久化 =====

    /** 序列化全部非空格子（NBT 键 {@code Items}，每项带 {@code Slot}，与官方箱子同构）。 */
    public CompoundTag createTag() {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, items);
        return tag;
    }

    /**
     * 反序列化。
     *
     * <p><b>顺序关键</b>：官方 {@link ContainerHelper#loadAllItems} 对越界 {@code Slot}
     * 是<b>静默跳过</b>（不报错、不警告），所以必须先把容量撑到能容下存档里的 Slot，
     * 再读物品 —— 否则高编号格子里的东西会被无声吞掉。</p>
     *
     * <p><b>另一处坑</b>：{@code loadAllItems} 内部是 {@code items.set(slot, ItemStack.of(tag))}，
     * <b>绕过了本类的 {@link #setItem} clamp</b>，所以存档里若已存在超过堆叠上限的非法栈
     * （旧版本写入 / 手工改档 / {@code Count} 被当成 byte 截断），会原样读进来。
     * 故读完必须逐格收敛 —— 这同时是旧档的自愈路径。</p>
     */
    public void fromTag(CompoundTag tag) {
        int needed = maxUsedSlot(tag) + 1;
        if (needed > items.size()) {
            this.items = NonNullList.withSize(clampSize(needed), ItemStack.EMPTY);
        }
        clearItems();
        ContainerHelper.loadAllItems(tag, items);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && stack.getCount() > stack.getMaxStackSize()) {
                stack.setCount(stack.getMaxStackSize());
            }
        }
        setChanged();
    }

    // ===== 监听 =====

    /** 挂容器监听器（去重 —— 防同一监听器重复注册导致回调翻倍）。 */
    public void addListener(ContainerListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(ContainerListener listener) {
        listeners.remove(listener);
    }

    // ===== 内部 =====

    /**
     * 逐格置空，且<b>不发通知</b>。
     *
     * <p>刻意不用 {@code NonNullList#clear()} —— 它的清空语义与「列表长度是否变化」的关系
     * 在官方文档中并未约定，而本类的 {@link #getContainerSize()} 直接返回 {@code items.size()}，
     * 一旦长度被改就会凭空改掉格数。逐格置空可确保容量恒定。</p>
     */
    private void clearItems() {
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
    }

    /** 存档里出现过的最大 {@code Slot} 下标（无物品返回 -1）。 */
    private static int maxUsedSlot(CompoundTag tag) {
        ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
        int max = -1;
        for (int i = 0; i < list.size(); i++) {
            int slot = list.getCompound(i).getByte("Slot") & 255;
            if (slot > max) {
                max = slot;
            }
        }
        return max;
    }

    private static int clampSize(int size) {
        if (size < 0) {
            return 0;
        }
        return Math.min(size, HARD_MAX_SIZE);
    }
}
