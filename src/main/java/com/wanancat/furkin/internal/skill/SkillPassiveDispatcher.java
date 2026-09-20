package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.registry.ModMobEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 被动技能事件分发器 —— 把「事件驱动」的被动技能挂到游戏事件上（设计稿 §1）。
 *
 * <p><b>为什么需要它</b>：{@link SkillEffect} 接口是「一次性挂载」模型
 * （{@code apply}/{@code remove}），只适合属性修正这类静态效果；被动技能
 * （流血、闪避、免死、夜视、群猎等）需要「受击时 / 每 tick」触发，
 * 故新增本分发器，在既有事件位点（{@code CommonEvents}）插入查询调用。</p>
 *
 * <p><b>不持久化激活态</b>：事件触发时即时读 {@code getSkillLevels()}，
 * 天然满足洗点移除 / 召唤重挂 / 解绑失效，无需额外状态清理。</p>
 *
 * <p><b>路由方式</b>：按 {@code skillId} 显式 {@code if} 路由到具体被动逻辑，
 * 而非泛化的「event 字符串反射」——类型安全、可读性好、编译器可查。</p>
 *
 * <p><b>四类触发位点</b>：</p>
 * <ul>
 *   <li>{@link #onCompanionAttack} —— 攻击侧（流血撕咬）。</li>
 *   <li>{@link #onCompanionAttacked} —— 受害侧早段（灵巧身法闪避；{@code LivingAttackEvent}，
 *       取消即整个受击作废，连音效闪帧一并压掉）。</li>
 *   <li>{@link #onCompanionHurt} —— 受害侧晚段（九命猫免死；{@code LivingHurtEvent}，
 *       需要改写伤害量，只能在这一层）。</li>
 *   <li>{@link #onServerTick} —— 周期侧（守夜者夜视、群猎战术叠层）。</li>
 * </ul>
 */
public final class SkillPassiveDispatcher {

    // ===== 技能 id =====

    /** 流血撕咬（攻击侧）。 */
    private static final ResourceLocation BLEEDING_BITE =
            new ResourceLocation(FurkinMod.MODID, "bleeding_bite");
    /** 灵巧身法（受害侧 · 闪避）。 */
    private static final ResourceLocation NIMBLE_GRACE =
            new ResourceLocation(FurkinMod.MODID, "nimble_grace");
    /** 九命猫（受害侧 · 免死）。 */
    private static final ResourceLocation NINE_LIVES =
            new ResourceLocation(FurkinMod.MODID, "nine_lives");
    /** 守夜者（周期侧 · 夜视）。 */
    private static final ResourceLocation NIGHT_WATCH =
            new ResourceLocation(FurkinMod.MODID, "night_watch");
    /** 群猎战术（周期侧 · 叠层增益）。 */
    private static final ResourceLocation PACK_TACTICS =
            new ResourceLocation(FurkinMod.MODID, "pack_tactics");

    /** 犬类物种 id —— 群猎战术按「犬类队友」计数。 */
    private static final ResourceLocation DOG_SPECIES =
            new ResourceLocation(FurkinMod.MODID, "dog");

    // ===== 数值（待数值定稿阶段统一复核）=====

    /** 灵巧身法：每级闪避概率。 */
    private static final float DODGE_CHANCE_PER_LEVEL = 0.08f;

    /** 九命猫：冷却 tick = 10 分钟。 */
    private static final int NINE_LIVES_COOLDOWN_TICKS = 10 * 60 * 20;

    /** 守夜者：生效半径（格）。 */
    private static final double NIGHT_WATCH_RADIUS = 16.0;
    /** 守夜者：夜视时长（tick）—— 短时长 + 周期刷新，绒亲离开 / 天亮后自然失效。 */
    private static final int NIGHT_VISION_DURATION_TICKS = 300;

    /** 群猎战术：每级每层的攻击加成（Lv.1 / 2 / 3）。 */
    private static final double[] PACK_BONUS_PER_STACK = {0.10, 0.15, 0.20};
    /** 群猎战术：最大叠层数。 */
    private static final int PACK_MAX_STACKS = 3;
    /** 群猎战术：计数半径（格）。 */
    private static final double PACK_RADIUS = 16.0;

    /** 群猎战术的动态属性 modifier 固定 UUID（每次刷新先摘后挂，幂等）。 */
    private static final UUID PACK_TACTICS_UUID =
            UUID.nameUUIDFromBytes("furkin.skill:pack_tactics".getBytes(StandardCharsets.UTF_8));

    /** 周期被动节拍：每 20 tick（= 1 秒）检查一次。 */
    private static final int TICK_INTERVAL = 20;

    private SkillPassiveDispatcher() {
    }

    // ===== 攻击侧 =====

    /**
     * 攻击侧被动：绒亲（attacker）攻击目标（target）时触发。
     *
     * <p>在 {@code CommonEvents#onLivingHurt} 里调用。当前实现流血撕咬。</p>
     *
     * @param attacker 攻击者（可能不是绒亲，方法内部自检）
     * @param target   被攻击目标
     */
    public static void onCompanionAttack(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null) {
            return;
        }
        FurkinData data = attacker.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return;
        }

        // 流血撕咬：攻击附加流血，伤害随等级递增（Lv.1/2/3 → 1/2/3 点）。
        int bleedLevel = data.getSkillLevels().getOrDefault(BLEEDING_BITE, 0);
        if (bleedLevel > 0 && target.isAlive()) {
            applyBleeding(target, bleedLevel);
        }
    }

    // ===== 受害侧 =====

    /**
     * 受害侧被动（早段）：绒亲（victim）被攻击时触发，位点在 {@code hurt()} 最前端。
     *
     * <p>在 {@code CommonEvents#onLivingAttack} 里调用。</p>
     *
     * <p><b>为什么闪避必须放在这一层</b>：取消 {@code LivingHurtEvent} 只作废伤害量，
     * 而受击的<b>变红闪帧与音效</b>在 {@code hurt()} 更早的段落里已经播出去了 —— 实机表现就是
     * 「明明闪避了，宠物还是全身变红 + 惨叫」。{@code LivingAttackEvent} 在 {@code hurt()}
     * 入口处触发，取消后整个受击流程直接返回：不掉血、不出声、不闪红。</p>
     *
     * <ul>
     *   <li><b>灵巧身法</b>：按 {@code +8% × 等级} 概率闪避，命中则整个取消该次攻击。
     *       只对「有实体攻击者」的攻击生效（摔落 / 虚空 / 指令伤害不可闪避）。</li>
     * </ul>
     *
     * @param victim 被攻击的绒亲（方法内部自检）
     * @param event  攻击事件（可取消）
     */
    public static void onCompanionAttacked(LivingEntity victim, LivingAttackEvent event) {
        if (victim == null || event == null) {
            return;
        }
        FurkinData data = victim.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return;
        }
        int dodgeLevel = data.getSkillLevels().getOrDefault(NIMBLE_GRACE, 0);
        if (dodgeLevel <= 0) {
            return;
        }
        // 只闪避「有实体攻击者」的攻击。
        if (!(event.getSource().getEntity() instanceof LivingEntity)) {
            return;
        }
        if (victim.getRandom().nextFloat() >= DODGE_CHANCE_PER_LEVEL * dodgeLevel) {
            return;
        }
        event.setCanceled(true);
        // 验收可观测性：闪避是概率行为，且取消后不再有任何原版受击表现，
        // 不打日志就完全无法分辨「闪了」和「没打中」。
        FurkinMod.LOGGER.info("Furkin passive: nimble_grace dodge proc (level={}, avoided={})",
                dodgeLevel, event.getAmount());
    }

    /**
     * 受害侧被动（晚段）：绒亲（victim）受击时触发。
     *
     * <p>在 {@code CommonEvents#onLivingHurt} 里<b>先于</b>伤害登记调用 ——
     * 免死会改写伤害量，此后按改写后的值登记伤害。</p>
     *
     * <p><b>本方法只处理「需要改伤害量」的被动</b>（九命猫把致命伤害削到 0 挡下死亡）。
     * 闪避这类「要整个取消受击」的被动必须放在 {@link #onCompanionAttacked}（更早的
     * {@code LivingAttackEvent}），否则伤害虽被取消，<b>闪帧与音效照播</b>。</p>
     *
     * <ul>
     *   <li><b>九命猫</b>：受到致命伤害（≥ 当前生命）时，<b>完全复刻原版不死图腾</b>
     *       （见 {@link #applyTotemRevival}），随后进入 10 分钟冷却。</li>
     * </ul>
     *
     * @param victim 受击的绒亲（方法内部自检）
     * @param event  受击事件（可改伤害量）
     */
    public static void onCompanionHurt(LivingEntity victim, LivingHurtEvent event) {
        if (victim == null || event == null) {
            return;
        }
        FurkinData data = victim.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return;
        }
        Map<ResourceLocation, Integer> levels = data.getSkillLevels();

        // 九命猫：致命伤害 → 完全复刻原版不死图腾（LivingEntity#checkTotemDeathProtection）。
        int livesLevel = levels.getOrDefault(NINE_LIVES, 0);
        if (livesLevel <= 0 || event.getAmount() < victim.getHealth()) {
            return;
        }
        // 原版口径：带 BYPASSES_INVULNERABILITY 标签的伤害（/kill、虚空）救不了。
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        long now = victim.level().getGameTime();
        long readyAt = data.getCooldowns().getOrDefault(NINE_LIVES, 0L);
        if (now < readyAt) {
            // 冷却中挨致命伤 —— 这正是「看不出有没有生效」的现场，必须留痕。
            FurkinMod.LOGGER.info(
                    "Furkin passive: nine_lives on cooldown ({}s left), fatal hit lands",
                    (readyAt - now) / 20);
            return;
        }
        data.getCooldowns().put(NINE_LIVES, now + NINE_LIVES_COOLDOWN_TICKS);
        float incoming = event.getAmount();
        // 先挡下这次致命伤（伤害归零会让后续 actuallyHurt 直接 return，血量不再被改），
        // 再由 applyTotemRevival 把生命值设回 1 —— 合起来等价于原版
        // 「死亡判定前拦下 + setHealth(1.0F)」两步。
        event.setAmount(0.0f);
        applyTotemRevival(victim);
        FurkinMod.LOGGER.info("Furkin passive: nine_lives proc (health={}, incoming={}, kept=1)",
                victim.getHealth(), incoming);
    }

    /**
     * 复刻原版不死图腾的触发效果 —— 逐项对齐 1.20.1 的
     * {@code LivingEntity#checkTotemDeathProtection}（已用 javap 核对字节码，非凭印象）：
     *
     * <ol>
     *   <li>{@code setHealth(1.0F)} —— 生命值归 1；</li>
     *   <li>{@code removeAllEffects()} —— 清空<b>全部</b>状态效果（原版如此，不只清负面）；</li>
     *   <li>再生 II 45s（900t）/ 吸收 II 5s（100t）/ 抗火 I 40s（800t）—— 顺序与时长逐项照搬；</li>
     *   <li>{@code broadcastEntityEvent(entity, (byte)35)} —— 图腾粒子与音效由客户端处理，
     *       服务端只需广播这 1 个字节。</li>
     * </ol>
     *
     * <p>⚠️ 刻意<b>不</b>使用 {@code EffectCures.PROTECTED_BY_TOTEM} / {@code removeEffectsCuredBy}：
     * 那套 API 在 1.20.1 <b>并不存在</b>（1.20.5+ 才引入），1.20.1 原版用的就是无参的
     * {@code removeAllEffects()}。</p>
     *
     * @param entity 触发免死的绒亲
     */
    private static void applyTotemRevival(LivingEntity entity) {
        entity.setHealth(1.0f);
        entity.removeAllEffects();
        entity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        entity.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        entity.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
        entity.level().broadcastEntityEvent(entity, (byte) 35);
    }

    // ===== 周期侧 =====

    /**
     * 周期被动：服务端 tick 时触发，遍历所有维度中在场绒亲。
     *
     * <p>每 {@link #TICK_INTERVAL} tick 执行一次（事件每 tick 都来，节流在方法内做）。</p>
     *
     * @param server 服务端实例（由 {@code CommonEvents#onServerTick} 传入）
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTickCount() % TICK_INTERVAL != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data == null || !data.isCompanion()) {
                    continue;
                }
                tickCompanion(living, data);
            }
        }
    }

    /** 单只绒亲的周期被动结算。 */
    private static void tickCompanion(LivingEntity companion, FurkinData data) {
        Map<ResourceLocation, Integer> levels = data.getSkillLevels();
        if (levels.isEmpty()) {
            return;
        }
        updateNightWatch(companion, data, levels);
        updatePackTactics(companion, levels);
    }

    /**
     * 守夜者：夜晚 + 绒亲距主人 {@link #NIGHT_WATCH_RADIUS} 格内 → 使<b>主人</b>获得夜视。
     * 短时长持续刷新，绒亲离开 / 天亮后自然失效。
     */
    private static void updateNightWatch(LivingEntity companion, FurkinData data,
                                         Map<ResourceLocation, Integer> levels) {
        if (levels.getOrDefault(NIGHT_WATCH, 0) <= 0) {
            return;
        }
        if (companion.level().isDay()) {
            return;
        }
        ServerPlayer owner = resolveOwner(companion, data);
        if (owner == null) {
            return;
        }
        if (companion.distanceToSqr(owner) > NIGHT_WATCH_RADIUS * NIGHT_WATCH_RADIUS) {
            return;
        }
        owner.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                NIGHT_VISION_DURATION_TICKS, 0, true, false));
    }

    /**
     * 群猎战术：附近每只犬类队友 +1 层，最多叠 {@link #PACK_MAX_STACKS} 层，
     * 按等级给予 {@code +10% / +15% / +20%} 攻击（MULTIPLY_BASE）。
     *
     * <p>用 transient modifier「先摘后挂」—— 队友走散后即时回落，不留残留；
     * transient 不入档，实体重载后由本 tick 逻辑自动重建。</p>
     */
    private static void updatePackTactics(LivingEntity companion, Map<ResourceLocation, Integer> levels) {
        AttributeInstance attack = companion.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack == null) {
            return;
        }
        int level = levels.getOrDefault(PACK_TACTICS, 0);
        int stacks = 0;
        double bonus = 0.0;
        if (level > 0) {
            stacks = Math.min(countPackMates(companion), PACK_MAX_STACKS);
            double perStack = PACK_BONUS_PER_STACK[Math.min(level, PACK_BONUS_PER_STACK.length) - 1];
            bonus = perStack * stacks;
        }
        AttributeModifier previous = attack.getModifier(PACK_TACTICS_UUID);
        double previousBonus = previous == null ? 0.0 : previous.getAmount();
        attack.removeModifier(PACK_TACTICS_UUID);
        if (bonus > 0.0) {
            attack.addTransientModifier(new AttributeModifier(
                    PACK_TACTICS_UUID,
                    "furkin.skill.pack_tactics",
                    bonus,
                    AttributeModifier.Operation.MULTIPLY_BASE));
        }
        // 只在加成变化时留痕（本方法每 20 tick 刷新一次，逐次打日志会刷屏）。
        if (previousBonus != bonus) {
            FurkinMod.LOGGER.info("Furkin passive: pack_tactics level={} stacks={} bonus={} (was {})",
                    level, stacks, bonus, previousBonus);
        }
    }

    // ===== 工具 =====

    /** 给目标施加流血：持续固定 4 秒，每秒伤害随技能等级递增（Lv.1/2/3 → 1/2/3 点）。 */
    private static void applyBleeding(LivingEntity target, int level) {
        // 亡灵免疫：与「中毒」对亡灵无效的原版口径一致，亡灵根本不挂流血（无粒子、无图标、无掉血）。
        if (target.isInvertedHealAndHarm()) {
            return;
        }
        int durationTicks = 4 * 20;
        int amplifier = level - 1;
        target.addEffect(new MobEffectInstance(ModMobEffects.BLEEDING.get(), durationTicks, amplifier));
    }

    /** 统计半径内「犬类绒亲队友」数量（不含自己）。 */
    private static int countPackMates(LivingEntity companion) {
        int count = 0;
        for (LivingEntity ignored : companion.level().getEntitiesOfClass(
                LivingEntity.class,
                companion.getBoundingBox().inflate(PACK_RADIUS),
                e -> e != companion && isDogCompanion(e))) {
            count++;
        }
        return count;
    }

    /** 某实体是否为已契约的犬类绒亲。 */
    private static boolean isDogCompanion(LivingEntity entity) {
        FurkinData data = entity.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return false;
        }
        return FurkinSpeciesRegistry.byEntityType(entity.getType())
                .map(species -> DOG_SPECIES.equals(species.getId()))
                .orElse(false);
    }

    /** 解析绒亲主人（可能 null —— 主人离线 / 不同维度）。 */
    private static ServerPlayer resolveOwner(LivingEntity companion, FurkinData data) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        UUID ownerUuid = data.getOwnerUuid();
        if (ownerUuid == null) {
            return null;
        }
        return serverLevel.getServer().getPlayerList().getPlayer(ownerUuid);
    }
}
