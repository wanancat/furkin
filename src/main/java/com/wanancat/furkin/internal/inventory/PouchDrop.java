package com.wanancat.furkin.internal.inventory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 「行囊物品倒出来」的统一出口（D6：只在场才有包裹）。
 *
 * <p>三个调用点共用本类，语义都是「实体即将消失 / 容量不再容纳这些物品」：</p>
 * <ul>
 *   <li><b>收回</b> —— {@code FurkinCompanionManager.dismiss}</li>
 *   <li><b>死亡</b> —— {@code CommonEvents.markFallenIfCompanion}</li>
 *   <li><b>缩容</b> —— {@code SkillProgress.resetSkills}（洗点后容量归 0）</li>
 * </ul>
 *
 * <p><b>为什么用官方 {@link Containers}</b>：这正是原版对「携带容器的实体消失」的标准处置
 * （马、箱子船死亡时同款）。自己遍历 + 手搓 {@code ItemEntity} 等于重写一遍，
 * 还要自己处理散落偏移与堆叠拆分。注意 {@code dropContents} 只负责<b>丢</b>，
 * <b>不清空容器</b>（它靠 {@code ItemStack.split} 的副作用掏空格子，但不该依赖这一点），
 * 故调用方仍需显式倒空。</p>
 *
 * <p><b>顺序铁律</b>：收回 / 死亡两条路径必须在<b>保存实体快照之前</b>调用 ——
 * 快照走 {@code saveWithoutId}，会带上 {@code ForgeCaps}（行囊 NBT 在其中），
 * 顺序颠倒会让物品随快照回灌到下次召唤，物品「诈尸」回来。</p>
 */
public final class PouchDrop {

    private PouchDrop() {
    }

    /**
     * 把整个行囊倒在实体脚下，并清空行囊。
     *
     * @param owner 行囊主人（用于取坐标与所在维度）
     * @param pouch 行囊容器
     * @return 是否真的倒出了东西（行囊本来就空 / 客户端侧 → {@code false}）
     */
    public static boolean dropAll(LivingEntity owner, FurkinInventory pouch) {
        if (owner == null || pouch == null || pouch.isEmpty()) {
            return false;
        }
        // 掉落是服务端行为：客户端侧调用会凭空生成客户端物品实体。
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        Containers.dropContents(level, owner, pouch);
        pouch.clearContent();
        return true;
    }

    /**
     * 把一批<b>已从容器摘出</b>的物品倒在实体脚下（缩容 / 召唤重算容量的溢出路径）。
     *
     * @param owner  行囊主人
     * @param stacks 溢出物品（容器里已不含它们）
     * @return 是否真的倒出了东西
     */
    public static boolean dropStacks(LivingEntity owner, List<ItemStack> stacks) {
        if (owner == null || stacks == null || stacks.isEmpty()) {
            return false;
        }
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        double x = owner.getX();
        double y = owner.getY();
        double z = owner.getZ();
        boolean dropped = false;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, x, y, z, stack);
                dropped = true;
            }
        }
        return dropped;
    }
}
