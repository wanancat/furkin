package com.wanancat.furkin.internal.growth;

import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 战斗参与追踪表（运行时、仅服务端、不持久化）。
 *
 * <p>负责「登记参与者 + 累计伤害 + 记录最近伤害时间」，供死亡结算时判定
 * 「谁参与过这场战斗」。设计稿 §3.2 战斗经验、口径 A：目标死亡时，每个
 * 参与过伤害的绒亲各拿目标原版经验 × 系数（各算各的，多宠收益叠加）。</p>
 *
 * <p>表结构：{@code targetUuid → Entry(participants, lastDamageMillis)}，
 * 其中 {@code participants = attackerUuid → 累计伤害}。</p>
 *
 * <p><b>压力极小</b>：表只在「战斗进行中」非空；目标一死即整条清空；
 * 另有「脱离战斗超时」兜底（打残后跑路、目标又没死时，10 秒无新伤害即清）。
 * 写入仅在 {@code LivingHurtEvent}（每下伤害一次），无每 tick 轮询。</p>
 */
public final class CombatParticipationTracker {

    /** 目标脱离战斗多久（毫秒）自动清掉追踪记录。默认 10 秒。 */
    private static final long COMBAT_TIMEOUT_MILLIS = 10_000L;

    /** 单目标参与者条目：参与者表 + 最近一次伤害时间戳。 */
    private static final class TargetEntry {
        final Map<UUID, Float> participants = new HashMap<>();
        long lastDamageMillis;
    }

    /** targetUuid → TargetEntry。 */
    private static final Map<UUID, TargetEntry> TABLE = new HashMap<>();

    private CombatParticipationTracker() {
    }

    /**
     * 登记一次伤害：把「攻击者」记进「目标」的参与者表，累计伤害并刷新时间戳。
     * 只在服务端调用。
     *
     * @param target   受伤目标
     * @param attacker 造成伤害的来源实体（玩家 / 绒亲 / 其他）
     * @param amount   本次伤害量
     */
    public static void recordDamage(LivingEntity target, LivingEntity attacker, float amount) {
        if (target == null || attacker == null || amount <= 0) {
            return;
        }
        TargetEntry entry = TABLE.computeIfAbsent(target.getUUID(), k -> new TargetEntry());
        entry.participants.merge(attacker.getUUID(), amount, Float::sum);
        entry.lastDamageMillis = System.currentTimeMillis();
    }

    /**
     * 目标死亡：取出并清空该目标的参与者表，返回副本。
     * 调用后该目标在表中的记录即被移除。
     *
     * @param target 死亡目标
     * @return 参与者表（attackerUuid → 累计伤害），无记录则为空 Map
     */
    public static Map<UUID, Float> takeAndClear(LivingEntity target) {
        if (target == null) {
            return new HashMap<>();
        }
        TargetEntry entry = TABLE.remove(target.getUUID());
        return entry != null ? entry.participants : new HashMap<>();
    }

    /**
     * 兜底清理：移除所有「脱离战斗超过 {@link #COMBAT_TIMEOUT_MILLIS}」的目标记录。
     * 挂在服务器 tick 上低频调用（本实现由 {@code CommonEvents} 的 ServerTickEvent 触发）。
     */
    public static void tickCleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, TargetEntry>> it = TABLE.entrySet().iterator();
        while (it.hasNext()) {
            TargetEntry entry = it.next().getValue();
            if (now - entry.lastDamageMillis > COMBAT_TIMEOUT_MILLIS) {
                it.remove();
            }
        }
    }

    /** 测试 / 调试用：当前表内目标数量。 */
    public static int trackedTargetCount() {
        return TABLE.size();
    }
}
