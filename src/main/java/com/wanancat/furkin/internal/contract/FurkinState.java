package com.wanancat.furkin.internal.contract;

/**
 * 绒亲身份状态机三态。
 *
 * <p><b>铁律（设计稿 §3.1）</b>：本枚举管「身份」，原版 {@code TAME} 管「行为」，
 * 二者绝不互相回读。{@link #WILD} 的语义是「未契约」——包括已被原版驯服的宠物。</p>
 *
 * <pre>
 * WILD ──契约──▶ COMPANION ──死亡──▶ FALLEN ──复活──▶ COMPANION
 * </pre>
 */
public enum FurkinState {
    /** 未契约（含原版已驯服但尚未被绒亲契约的个体）。 */
    WILD,
    /** 已契约，即「绒亲」。 */
    COMPANION,
    /** 已倒下（死亡待复活），留下魂石。 */
    FALLEN
}
