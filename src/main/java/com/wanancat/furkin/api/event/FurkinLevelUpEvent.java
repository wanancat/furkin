package com.wanancat.furkin.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;

/**
 * 绒亲升级事件（设计稿 §4.1「三个事件钩子」之一）。
 *
 * <p><b>只可监听，不可取消</b>（与契约 / 复活不同）—— 设计稿明确「升级只能『听』，
 * 契约 / 复活才能『拦』」。故本类<b>不标 {@code @Cancelable}</b>：升级是成长系统
 * 内部的确定性结算，第三方只能观测（记录、广播、联动），不能阻止宠物升级。</p>
 *
 * <p>触发时机：宠物经验溢出、等级提升的<b>那一刻</b>。若一次加经验跨了多级，
 * 每级触发一次（或按实现聚合 —— 见 {@code FurkinGrowth} 的说明）。</p>
 *
 * <p>字段：</p>
 * <ul>
 *   <li>{@link #getCompanion()} —— 升级的宠物实体（在场）</li>
 *   <li>{@link #getOwner()} —— 主人（可能为 null，若主人离线但宠物仍因故升级）</li>
 *   <li>{@link #getOldLevel()} —— 升级前等级</li>
 *   <li>{@link #getNewLevel()} —— 升级后等级</li>
 * </ul>
 *
 * <p>本类处于 {@code api} 包，属「对外识别型」命名（设计稿 §9 判据②），
 * 事件名带项目词根 {@code Furkin}（设计稿 §4.1「api 面 10 个类一律带词根」）。</p>
 */
public final class FurkinLevelUpEvent extends Event {

    /** 升级的宠物实体。 */
    private final LivingEntity companion;

    /** 主人（可能 null）。 */
    private final ServerPlayer owner;

    /** 升级前等级。 */
    private final int oldLevel;

    /** 升级后等级。 */
    private final int newLevel;

    public FurkinLevelUpEvent(LivingEntity companion, ServerPlayer owner, int oldLevel, int newLevel) {
        this.companion = companion;
        this.owner = owner;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    /** 升级的宠物实体。 */
    public LivingEntity getCompanion() {
        return companion;
    }

    /** 主人（可能为 null）。 */
    public ServerPlayer getOwner() {
        return owner;
    }

    /** 升级前等级。 */
    public int getOldLevel() {
        return oldLevel;
    }

    /** 升级后等级。 */
    public int getNewLevel() {
        return newLevel;
    }
}
