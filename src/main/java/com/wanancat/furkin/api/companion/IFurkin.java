package com.wanancat.furkin.api.companion;

import java.util.UUID;

/**
 * 只读查询接口 —— 第三方读伴侣状态用（设计稿 §4）。
 *
 * <p>提供对一只绒亲的只读视图：身份、主人、等级、经验、技能点、状态。
 * 第三方只能读，不能改（写操作走 internal 的事件 / 命令）。</p>
 */
public interface IFurkin {

    /** 宠物身份 UUID（跨实体唯一档案主键）。 */
    UUID getCompanionId();

    /** 主人 UUID。 */
    UUID getOwnerUuid();

    /** 当前等级。 */
    int getLevel();

    /** 当前经验。 */
    int getXp();

    /** 可用技能点。 */
    int getSkillPoints();

    /** 当前状态机状态（未契约 / 已契约 / 已倒下）。 */
    String getState();

    /** 是否已契约。 */
    boolean isCompanion();
}
