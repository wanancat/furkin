package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 技能运行时校准器 —— 在技能树热重载后重建已加载绒亲的持久技能效果。
 *
 * <p>资源重载监听器可能运行在非服务器 tick 线程，因此只设置一个待处理标志；服务器线程在 tick 末尾
 * 消费标志并遍历当前已加载维度、已加载实体。未加载区块不会被强制加载，区块卸载期间留在 NBT 里的旧
 * modifier 会在实体再次入世时由 {@link #rebuild(LivingEntity, FurkinData)} 清理。</p>
 *
 * <p>成本边界：一次重载最多遍历当前已加载实体列表，复杂度 O(已加载实体数)，不做跨区块扫描、不加载
 * 区块、不在普通 tick 中轮询。实体入世校准只处理该实体。</p>
 */
public final class SkillRuntimeCalibrator {

    private static final AtomicBoolean REBUILD_PENDING = new AtomicBoolean();

    private SkillRuntimeCalibrator() {
    }

    /** 由资源重载监听器在替换技能树后置位；允许合并同一 tick 内的重复重载。 */
    public static void requestRebuild() {
        REBUILD_PENDING.set(true);
    }

    /** 在服务器线程 tick 末尾执行一次待处理重建。 */
    public static void onServerTick(MinecraftServer server) {
        if (!REBUILD_PENDING.compareAndSet(true, false)) {
            return;
        }
        rebuildLoaded(server, SkillRegistry.tree());
    }

    /**
     * 按给定技能树重建所有已加载绒亲的持久技能效果。
     *
     * @return 本次实际重建的绒亲数量，供审计夹具和运行记录使用
     */
    public static int rebuildLoaded(MinecraftServer server, SkillTree tree) {
        int rebuilt = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data == null || !data.isCompanion()) {
                    continue;
                }
                SkillEffectApplier.rebuildAll(living, tree, data.getSkillLevels());
                rebuilt++;
            }
        }
        return rebuilt;
    }

    /** 单个实体入世或召唤时的定向校准。 */
    public static void rebuild(LivingEntity target, FurkinData data) {
        SkillEffectApplier.rebuildAll(target, SkillRegistry.tree(), data.getSkillLevels());
    }
}
