package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.inventory.FurkinInventory;
import com.wanancat.furkin.internal.inventory.PouchDrop;
import com.wanancat.furkin.internal.registry.ModMobEffects;
import com.wanancat.furkin.internal.skill.harvest.HarvestSpec;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
 *   <li>{@link #onServerTick} —— 周期侧（守夜者夜视、群猎战术叠层、凭空产出、拾荒、低血进食）。</li>
 * </ul>
 *
 * <p><b>路由方式的两分</b>（判据 = 逻辑是否同构）：逻辑各自独特的被动（流血 / 闪避 / 免死 /
 * 夜视 / 群猎 / 拾荒 / 进食）按 {@code skillId} 显式 {@code if} 路由；而「同一套逻辑 +
 * 不同参数」的产出类走数据驱动（{@link HarvestSpec}），谁声明谁生效 ——
 * 新增一条产出技能不需要改本类。</p>
 *
 * <p>注意「数据驱动」的边界在<b>参数</b>而不在<b>流程</b>：拾荒与进食各自逻辑独特（只有一条
 * 技能），故走显式路由，但它们的数值（半径 / 门槛 / 冷却）仍从 JSON 读
 * （{@link ForagerSpec} / {@link FeederSpec}），与产出类共用同一套取块与缓存通道
 * （{@link SkillParams}）。把「独特流程」硬塞进数据驱动，会造出一门比它要解决的问题
 * 还复杂的 DSL。</p>
 */
public final class SkillPassiveDispatcher {

    // ===== 技能 id =====

    // 流血撕咬的技能 id 不在此处另立一份 —— 它由 {@link BleedingSpec#SKILL_ID} 提供。
    // 这是全项目唯一一个「effect 侧也要知道是哪个技能」的被动：伤害结算写在
    // MobEffect#applyEffectTick 里，那个方法只拿得到 amplifier、拿不到技能 JSON，
    // 只能反查规格；把 id 定义在规格类里，施加侧与结算侧就共用同一个入口。
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
    /** 拾荒本能（周期侧 · 自动拾取掉落物进行囊）。 */
    private static final ResourceLocation FORAGER =
            new ResourceLocation(FurkinMod.MODID, "forager");
    /** 低血进食（周期侧 · 血量过低时自行进食）。 */
    private static final ResourceLocation SELF_FEEDER =
            new ResourceLocation(FurkinMod.MODID, "self_feeder");

    /** 犬类物种 id —— 群猎战术按「犬类队友」计数。 */
    private static final ResourceLocation DOG_SPECIES =
            new ResourceLocation(FurkinMod.MODID, "dog");

    // ===== 数值 =====
    // 被动技能的数值已全部外露到数据包（各自技能 JSON 的 passive params 块），
    // 故本类不再保留数值常量 —— 取块与解析在 SkillParams + 各 Spec 类里：
    //   流血撕咬 → bleeding_bite{durationTicks,damagePerSecond}（伤害半边由 BleedingEffect 反查）
    //   灵巧身法 → dodge{chancePerLevel}            九命猫 → nine_lives{cooldownTicks}
    //   守夜者   → night_watch{radius,durationTicks} 群猎战术 → pack_tactics{bonusPerStack,maxStacks,radius}
    // 解析结果带缓存，技能树重载（/reload）即失效，改数据不必重启。

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

        // 流血撕咬：攻击附加流血，每秒伤害与持续时长取自技能 JSON 的 params.bleeding_bite。
        int bleedLevel = data.getSkillLevels().getOrDefault(BleedingSpec.SKILL_ID, 0);
        if (bleedLevel > 0 && target.isAlive()) {
            applyBleeding(attacker, target, bleedLevel);
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
     *   <li><b>灵巧身法</b>：按「每级闪避概率 × 等级」闪避（每级概率取自技能 JSON 的
     *       {@code params.dodge.chancePerLevel}），命中则整个取消该次攻击。
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
        DodgeSpec spec = DodgeSpec.of(NIMBLE_GRACE).orElse(null);
        if (spec == null) {
            return;
        }
        // 截顶到 1.0：数据里每级概率写大时退化成「必定闪避」，而不是溢出成负概率。
        float chance = (float) Math.min(1.0, spec.chancePerLevel() * dodgeLevel);
        if (victim.getRandom().nextFloat() >= chance) {
            return;
        }
        event.setCanceled(true);
        // 验收可观测性：闪避是概率行为，且取消后不再有任何原版受击表现，
        // 不打日志就完全无法分辨「闪了」和「没打中」。概率值一并印出 ——
        // 改数据包后要能从日志看出「生效的确实是新值」。
        FurkinMod.LOGGER.info("Furkin passive: nimble_grace dodge proc (level={}, chance={}, avoided={})",
                dodgeLevel, chance, event.getAmount());
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
     *       （见 {@link #applyTotemRevival}），随后进入冷却（时长取自技能 JSON 的
     *       {@code params.nine_lives.cooldownTicks}）。</li>
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
        NineLivesSpec spec = NineLivesSpec.of(NINE_LIVES).orElse(null);
        if (spec == null) {
            return;
        }
        long now = victim.level().getGameTime();
        long readyAt = data.getCooldowns().getOrDefault(NINE_LIVES, 0L);
        if (now < readyAt) {
            // 冷却中挨致命伤 —— 这正是「看不出有没有生效」的现场，必须留痕。
            FurkinMod.LOGGER.info(
                    "Furkin passive: nine_lives on cooldown ({}s left of {}s), fatal hit lands",
                    (readyAt - now) / 20, spec.cooldownTicks() / 20);
            return;
        }
        data.getCooldowns().put(NINE_LIVES, now + spec.cooldownTicks());
        float incoming = event.getAmount();
        // 先挡下这次致命伤（伤害归零会让后续 actuallyHurt 直接 return，血量不再被改），
        // 再由 applyTotemRevival 把生命值设回 1 —— 合起来等价于原版
        // 「死亡判定前拦下 + setHealth(1.0F)」两步。
        event.setAmount(0.0f);
        applyTotemRevival(victim);
        FurkinMod.LOGGER.info("Furkin passive: nine_lives proc (health={}, incoming={}, kept=1, cooldown={}s)",
                victim.getHealth(), incoming, spec.cooldownTicks() / 20);
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
        updateHarvest(companion, data, levels);
        updateForager(companion, data, levels);
        updateFeeder(companion, data, levels);
    }

    /**
     * 守夜者：夜晚 + 绒亲距主人 radius 格内 → 使<b>主人</b>获得夜视。
     * 短时长持续刷新，绒亲离开 / 天亮后自然失效。
     *
     * <p>半径与夜视时长取自技能 JSON 的 {@code params.night_watch}
     * （{@link NightWatchSpec}），不再写死在类常量里。</p>
     */
    private static void updateNightWatch(LivingEntity companion, FurkinData data,
                                         Map<ResourceLocation, Integer> levels) {
        if (levels.getOrDefault(NIGHT_WATCH, 0) <= 0) {
            return;
        }
        if (companion.level().isDay()) {
            return;
        }
        NightWatchSpec spec = NightWatchSpec.of(NIGHT_WATCH).orElse(null);
        if (spec == null) {
            return;
        }
        ServerPlayer owner = resolveOwner(companion, data);
        if (owner == null) {
            return;
        }
        if (companion.distanceToSqr(owner) > spec.radius() * spec.radius()) {
            return;
        }
        owner.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                spec.durationTicks(), 0, true, false));
    }

    /**
     * 群猎战术：附近每只犬类队友 +1 层，最多叠 {@code maxStacks} 层，
     * 按等级给予每层攻击加成（MULTIPLY_BASE）。
     *
     * <p>加成阵列、层数上限、计数半径都取自技能 JSON 的 {@code params.pack_tactics}
     * （{@link PackTacticsSpec}），不再写死在类常量里。</p>
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
        // 取不到规格（数据缺块 / 写错）时按「无加成」处理并摘掉旧 modifier，
        // 与「技能未加点」同形 —— 失效原因由 Spec 类的 WARN 留痕。
        PackTacticsSpec spec = level > 0 ? PackTacticsSpec.of(PACK_TACTICS).orElse(null) : null;
        int stacks = 0;
        double bonus = 0.0;
        if (spec != null) {
            stacks = Math.min(countPackMates(companion, spec.radius()), spec.maxStacks());
            bonus = spec.bonusForLevel(level) * stacks;
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

    /**
     * 凭空产出 —— 定时往行囊里塞东西（藏骨本能 / 捕鱼天赋）。
     *
     * <p><b>为什么这一段是「遍历」而不是像其余被动那样「按 id 显式 if」</b>：流血 / 闪避 /
     * 免死 / 群猎各自一套独特逻辑，路由到具体方法是必要的；而产出是<b>同一套逻辑 + 不同参数</b>，
     * 按 id 写成 if 链等于把「配了几条产出技能」硬编码进代码。这里改成「谁声明了产出规格
     * 就执行谁」（规格见 {@link HarvestSpec}），新增一条产出技能只写 JSON、不改 Java。</p>
     *
     * <p><b>「仅在场计时」为什么天然成立</b>（决策 D12）：本方法只被 {@link #onServerTick} 的
     * <b>在场实体</b>遍历调用 —— 绒亲被收回时实体已不在世界里，根本走不到这里。唯一要补的是
     * 「收回期间服务器时钟仍在走」这个细节，由 {@link #resetPeriodicTimers} 在召唤时抹平。</p>
     *
     * <p><b>首次见到一条产出技能时不产出</b>（计时表里没有该键 = 刚解锁）：只把计时布下，
     * 产出一律等满一个间隔。这样「解锁」与「召唤」两条入口就是同一个口径 ——
     * 无论从哪条路进入产出状态，都要走完一个完整间隔才出货，不存在任何形式的「补产」。</p>
     */
    private static void updateHarvest(LivingEntity companion, FurkinData data,
                                      Map<ResourceLocation, Integer> levels) {
        long now = companion.level().getGameTime();
        for (Map.Entry<ResourceLocation, Integer> entry : levels.entrySet()) {
            int level = entry.getValue();
            if (level <= 0) {
                continue;
            }
            HarvestSpec spec = HarvestSpec.of(entry.getKey()).orElse(null);
            if (spec == null) {
                continue;
            }
            // 计时存的是「下次可产的绝对 game time」。
            // 注意这里必须把「键不存在」与「时间未到」分开判：若像原先那样
            // getOrDefault(id, 0L)，缺键会取到 0 —— 一个早于任何 now 的时间戳 ——
            // 于是「从未产出过」被当成「早该产了」，技能刚解锁那一刻就白送一份，
            // 与 resetPeriodicTimers（召唤时只推后、不补产）的口径正好相反。
            Long nextAt = data.getCooldowns().get(entry.getKey());
            if (nextAt == null) {
                int interval = spec.intervalForLevel(level);
                data.getCooldowns().put(entry.getKey(), now + interval);
                FurkinMod.LOGGER.info("Furkin harvest: [{}] {} level={} armed, first yield in {} ticks",
                        companionTag(companion), entry.getKey().getPath(), level, interval);
                continue;
            }
            if (now < nextAt) {
                continue;
            }
            data.getCooldowns().put(entry.getKey(), now + spec.intervalForLevel(level));
            produceHarvest(companion, data, entry.getKey(), level, spec);
        }
    }

    /**
     * 产出一份物品进行囊；行囊装不下的部分<b>掉在绒亲脚下</b>。
     *
     * <p>为什么溢出要落地而不是丢弃 / 跳过：行囊只有 9~27 格、极易装满，而玩家看不见
     * 「本该产出的东西」—— 静默吞掉等于让他白白亏掉收益且无从察觉。原版对「容器装不下」
     * 的标准处置同样是落地（{@code Inventory#add} 返回剩余，由调用方 drop）。</p>
     */
    private static void produceHarvest(LivingEntity companion, FurkinData data,
                                       ResourceLocation skillId, int level, HarvestSpec spec) {
        ItemStack produced = spec.roll(companion.getRandom());
        // addItem 返回「装不下的剩余」；填得下时为空栈。
        ItemStack leftover = data.getPouch().addItem(produced);
        ResourceLocation producedId = ForgeRegistries.ITEMS.getKey(produced.getItem());
        if (leftover.isEmpty()) {
            FurkinMod.LOGGER.info("Furkin harvest: [{}] {} level={} produced {} x{} into pouch",
                    companionTag(companion), skillId.getPath(), level, producedId, produced.getCount());
            return;
        }
        int stored = produced.getCount() - leftover.getCount();
        // ⚠️ 「掉几个」必须在调用 dropStacks **之前**读出来。
        // 官方 Containers.dropItemStack 内部是 `while (!stack.isEmpty()) stack.split(...)` ——
        // 它会把传入的栈 split 空（原版对「掉落」的契约就是*消耗*调用方给的那个栈）。
        // 在它之后再读 leftover.getCount() 恒为 0，日志会印成「stored 0, dropped 0」这种
        // 自相矛盾的数（实测踩到：分支明明进去了，数量却是 0）。
        int dropped = leftover.getCount();
        PouchDrop.dropStacks(companion, List.of(leftover));
        FurkinMod.LOGGER.info("Furkin harvest: [{}] {} level={} produced {} (stored {}, dropped {} on ground)",
                companionTag(companion), skillId.getPath(), level, producedId, stored, dropped);
    }

    // ===== 周期侧：拾荒 =====

    /**
     * 拾荒本能：把附近掉落物收进行囊（半径见 JSON {@code params.forager.radius}）。
     *
     * <p><b>框架照官方 {@code Mob#aiStep} 的「捡地上东西」逻辑</b>（javap 取证）：
     * ① 先过 {@code ForgeEventFactory.getMobGriefingEvent}；
     * ② 用 {@code level.getEntitiesOfClass(ItemEntity.class, 碰撞箱.inflate(半径))} 取候选；
     * ③ 逐个过滤 {@code isRemoved / getItem().isEmpty() / hasPickUpDelay()}。
     * 其中尊重 {@code hasPickUpDelay()} 是必须的 —— 那是原版给「刚被丢出的物品」留的缓冲
     * （丢出后一段时间不可被拾取），少了它会把玩家刚扔掉的、还没来得及反悔的东西瞬间吸走。
     * （用 {@code hasPickUpDelay()} 而不是读 {@code pickupDelay} 字段：字段是 private，
     * 该方法是官方给外部用的判据。）</p>
     *
     * <p><b>搬运与善后照官方 {@code InventoryCarrier#pickUpItem} 的三段式</b>：
     * 通知（{@code onItemPickup}，内含「丢出的物品被某实体捡走」的进度触发）→
     * 广播（{@code take} 会发 {@code ClientboundTakeItemEntityPacket}，客户端据此播放
     * 物品飞向拾取者的动画）→ 收尾（全装下则 {@code discard} 掉落地物，
     * 只装下一部分则把剩余数量写回落地物，剩下的留在地上等下一轮）。</p>
     *
     * <p>与官方的唯一差别是顺序与门槛：先搬再通知，且<b>一个都没搬进去就完全不碰这个实体</b>
     * （官方靠 {@code canAddItem} 预检达到同样效果）。否则行囊满时每 20 tick 都会白触发
     * 一次进度与拾取广播，玩家会看到物品反复闪动却捡不起来。</p>
     */
    private static void updateForager(LivingEntity companion, FurkinData data,
                                      Map<ResourceLocation, Integer> levels) {
        if (!levels.containsKey(FORAGER)) {
            return;
        }
        ForagerSpec spec = ForagerSpec.of(FORAGER).orElse(null);
        if (spec == null) {
            return;
        }
        FurkinInventory pouch = data.getPouch();
        if (pouch.getContainerSize() <= 0) {
            return;
        }
        // 与官方同源的门：mobGriefing 关掉时绒亲不自动拾取 —— 服主一个开关即可停用。
        if (!ForgeEventFactory.getMobGriefingEvent(companion.level(), companion)) {
            return;
        }
        double radius = spec.radius();
        AABB area = companion.getBoundingBox().inflate(radius, radius, radius);
        for (ItemEntity item : companion.level().getEntitiesOfClass(ItemEntity.class, area)) {
            if (item.isRemoved() || item.getItem().isEmpty() || item.hasPickUpDelay()) {
                continue;
            }
            ItemStack ground = item.getItem();
            int before = ground.getCount();
            // addItem 不改传入的栈（内部先 copy），故 before 与 ground 始终一致。
            ItemStack remaining = pouch.addItem(ground);
            int moved = before - remaining.getCount();
            if (moved <= 0) {
                continue;
            }
            companion.onItemPickup(item);
            companion.take(item, moved);
            if (remaining.isEmpty()) {
                item.discard();
            } else {
                ground.setCount(remaining.getCount());
            }
            FurkinMod.LOGGER.info("Furkin forager: [{}] picked up {} x{} into pouch (left {} on ground)",
                    companionTag(companion), ForgeRegistries.ITEMS.getKey(ground.getItem()), moved, remaining.getCount());
        }
    }

    /**
     * 日志用的宠物标识：**名字 + uuid 前 8 位**。
     *
     * <p>产出 / 拾荒日志原先只印技能与物品，不带归属 —— 两只宠物同时开工时分不清是谁
     * 产的那一条。2026-09-21 排查「洗点后仍有产出」时就卡在这里：日志上一个停了一个没停，
     * 但看不出停的是哪只（乌狸答复「我有操作过狗的洗点」，才判出继续产的是猫）。</p>
     *
     * <p>名字给人看（游戏里叫什么就是什么），uuid 前缀给脚本看 —— 名字可重复、可改名，
     * 只有 uuid 是稳的。</p>
     */
    private static String companionTag(LivingEntity companion) {
        return companion.getName().getString() + "/" + companion.getUUID().toString().substring(0, 8);
    }

    // ===== 周期侧：低血进食 =====

    /**
     * 低血进食：血量低于门槛时，从行囊里取一份食物吃掉。
     *
     * <p><b>「吃」这个动作整个交给官方</b>（javap 取证：{@code LivingEntity#eat(Level, ItemStack)}
     * 是 public）：它内部依次做了进食音效（{@code getEatingSound}）、按概率施加食物自带的
     * 状态效果、扣掉 1 个、发 {@code GameEvent.EAT}（幽匿感测体据此触发）——
     * 自己另写一套只会漏掉其中若干项。唯一缺的是<b>回血</b>：官方玩家进食走 {@code FoodData}
     * 累加、再靠饥饿值自然恢复，而生物没有 {@code FoodData}，故这里把营养值直接当血量补
     * （原版营养值以半颗心为单位，与玩家吃一份的回血量同量级）。</p>
     *
     * <p>冷却与产出类共用 {@code getCooldowns()} 表，但语义不同：这里的值是
     * 「吃完后多久不能再吃」的<b>防连发闸</b>，不是节拍 —— 所以召唤时
     * {@link #resetPeriodicTimers} 不重置它、洗点 {@link #clearPeriodicTimers} 也不清它
     * （两者都以「是否有产出规格」为判据），与九命猫免死冷却的处理一致。</p>
     */
    private static void updateFeeder(LivingEntity companion, FurkinData data,
                                     Map<ResourceLocation, Integer> levels) {
        if (!levels.containsKey(SELF_FEEDER)) {
            return;
        }
        FeederSpec spec = FeederSpec.of(SELF_FEEDER).orElse(null);
        if (spec == null) {
            return;
        }
        float maxHealth = companion.getMaxHealth();
        if (maxHealth <= 0.0f || companion.getHealth() >= maxHealth * spec.threshold()) {
            return;
        }
        long now = companion.level().getGameTime();
        Long nextAt = data.getCooldowns().get(SELF_FEEDER);
        if (nextAt != null && now < nextAt) {
            return;
        }
        FurkinInventory pouch = data.getPouch();
        int slot = findBestFoodSlot(pouch, companion);
        if (slot < 0) {
            return;
        }
        ItemStack food = pouch.removeItem(slot, 1);
        if (food.isEmpty()) {
            return;
        }
        ResourceLocation foodId = ForgeRegistries.ITEMS.getKey(food.getItem());
        FoodProperties properties = food.getFoodProperties(companion);
        float healthBefore = companion.getHealth();
        // 官方进食流程。注意它会把传入的栈吃掉（shrink 1），
        // 所以只能传「刚从行囊取出的那一份」，绝不能传行囊里的原栈。
        companion.eat(companion.level(), food);
        int nutrition = 0;
        if (properties != null) {
            nutrition = properties.getNutrition();
            companion.heal(nutrition);
        }
        data.getCooldowns().put(SELF_FEEDER, now + spec.cooldownTicks());
        FurkinMod.LOGGER.info("Furkin feeder: ate {} (nutrition {}, health {}/{})",
                foodId, nutrition, healthBefore, maxHealth);
    }

    /**
     * 找行囊里「最顶饱」的那份食物（营养值最高者）。
     *
     * <p>为什么不取第一个：低血进食是「救命」场景，行囊里同时有面包与熟牛排时吃哪个，
     * 不该取决于格子顺序。用官方 {@code ItemStack#getFoodProperties(LivingEntity)} 判定可食性
     * —— 它正是 {@code isEdible()} 的数据来源，且将来要做「某物种忌口」可以只在这一层加过滤。</p>
     *
     * @return 可食用且营养值最高的格子下标；行囊里没有食物时返回 -1
     */
    private static int findBestFoodSlot(FurkinInventory pouch, LivingEntity companion) {
        int best = -1;
        int bestNutrition = -1;
        for (int i = 0; i < pouch.getContainerSize(); i++) {
            ItemStack stack = pouch.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            FoodProperties properties = stack.getFoodProperties(companion);
            if (properties != null && properties.getNutrition() > bestNutrition) {
                bestNutrition = properties.getNutrition();
                best = i;
            }
        }
        return best;
    }

    // ===== 周期计时的重置 =====

    /**
     * 重置该绒亲的<b>周期型被动计时</b>（召唤时调用）。
     *
     * <p><b>为什么必须有这一步</b>（把决策 D12「仅在场才计时」做成严格的）：产出计时存在
     * {@code getCooldowns()} 里、语义是「下次可产的<b>绝对</b> game time」。绒亲被收回时实体
     * 消失、本分发器不再 tick 它，但<b>服务器时钟照走</b> —— 若不管，收回 10 分钟后再召唤，
     * 那一刻 {@code now} 早已越过记下的时刻，于是「一回来就凭空冒出一个」，
     * 等于补发了不在场期间攒下的产出。把计时整体推后一个完整间隔，语义就干净了。</p>
     *
     * <p>与 {@link #updateHarvest} 的「缺键只布计时」是<b>同一口径</b>：解锁与召唤两条入口
     * 都不补产，产出永远要等满一个完整间隔。</p>
     *
     * <p>不必在<b>收回</b>侧做对称处理：收回之后实体不复存在，下一次进入「在场」必经召唤。</p>
     *
     * @param companion 刚召唤出来的绒亲
     * @param data      其能力数据（召唤时已挂好）
     */
    public static void resetPeriodicTimers(LivingEntity companion, FurkinData data) {
        if (companion == null || data == null) {
            return;
        }
        long now = companion.level().getGameTime();
        for (Map.Entry<ResourceLocation, Integer> entry : data.getSkillLevels().entrySet()) {
            int level = entry.getValue();
            if (level <= 0) {
                continue;
            }
            HarvestSpec spec = HarvestSpec.of(entry.getKey()).orElse(null);
            if (spec != null) {
                data.getCooldowns().put(entry.getKey(), now + spec.intervalForLevel(level));
            }
        }
    }

    /**
     * 清除该绒亲的<b>周期型被动计时</b>（洗点时调用，与 {@link #resetPeriodicTimers} 对称）。
     *
     * <p><b>为什么必须清</b>：计时表与技能等级是<b>两套独立状态</b>。洗点把等级清零后，技能已
     * 不存在，但这些记录会留成<b>孤儿</b> —— 玩家重新学回来时，那个时刻多半早已过去，于是
     * 「刚学就又立刻产一份」，正好绕过 {@link #updateHarvest} 里「首次只布计时、不产出」的保证。
     * 换句话说：只要还存在一条「等级为 0、但计时表里有过期记录」的路径，那条保证就是漏的。</p>
     *
     * <p><b>只清产出类，不动免死冷却</b>：九命猫的冷却表意是「免死不能连发」的防作弊闸，
     * 不是节拍；洗点若一并清掉，玩家就能用一次洗点换一次即时免死 —— 那是漏洞不是修复。
     * 判据 = 「谁有产出规格」（{@link HarvestSpec}），有规格的才清。</p>
     *
     * @param data 被洗点的绒亲能力数据
     */
    public static void clearPeriodicTimers(FurkinData data) {
        if (data == null) {
            return;
        }
        data.getCooldowns().keySet().removeIf(id -> HarvestSpec.of(id).isPresent());
    }

    // ===== 工具 =====

    /**
     * 给目标施加流血：时长与每秒伤害都取自技能 JSON 的 {@code params.bleeding_bite}
     * （{@link BleedingSpec}）。伤害不在本方法算 —— 它写在
     * {@link com.wanancat.furkin.internal.effect.BleedingEffect#applyEffectTick} 里，
     * 那里只能按技能反查同一份规格（原因见 {@link BleedingSpec} 类注释）。
     *
     * <p><b>取不到规格 = 不施加</b>：与闪避 / 九命 / 群猎口径一致，数据写坏时技能整体禁用，
     * 而不是退回某个内建默认值 —— 后者会让「数据错了」表现成「数值还是老样子」，最难察觉。
     * 留痕由 {@link BleedingSpec} 解析时打一次（否定结果进了缓存，不会逐次重复）。</p>
     *
     * <p><b>日志分两级</b>：目标原本<b>没有</b>流血时打 INFO（这一击是新挂上的，要把生效中的
     * dps / 时长印出来供验收），只是在已有流血上叠时长则降为 DEBUG —— 流血挂在攻击位点，
     * 战斗中每秒触发数次，逐次 INFO 会刷屏（同群猎「只在变化时留痕」的取舍）。</p>
     *
     * @param attacker 施加流血的绒亲（仅用于日志归属）
     * @param target   被咬的目标
     * @param level    流血撕咬的技能等级（≥ 1）
     */
    private static void applyBleeding(LivingEntity attacker, LivingEntity target, int level) {
        // 亡灵免疫：与「中毒」对亡灵无效的原版口径一致，亡灵根本不挂流血（无粒子、无图标、无掉血）。
        // effect 侧另有一道同名判定 —— 那防的是绕过本方法的施加途径（如 /effect give），
        // 两层判的不是同一件事，故都保留。
        if (target.isInvertedHealAndHarm()) {
            return;
        }
        BleedingSpec spec = BleedingSpec.of().orElse(null);
        if (spec == null) {
            return;
        }
        boolean fresh = !target.hasEffect(ModMobEffects.BLEEDING.get());
        target.addEffect(new MobEffectInstance(ModMobEffects.BLEEDING.get(),
                spec.durationTicks(), level - 1));
        if (fresh) {
            FurkinMod.LOGGER.info("Furkin passive: [{}] bleeding_bite -> {} level={} dps={} duration={}t",
                    companionTag(attacker), target.getName().getString(), level,
                    spec.damageForLevel(level), spec.durationTicks());
        } else {
            FurkinMod.LOGGER.debug("Furkin passive: [{}] bleeding_bite restacked -> {} level={} duration={}t",
                    companionTag(attacker), target.getName().getString(), level, spec.durationTicks());
        }
    }

    /**
     * 统计半径内「犬类绒亲队友」数量（不含自己）。
     *
     * @param radius 计数半径（格），由 {@link PackTacticsSpec#radius()} 传入
     */
    private static int countPackMates(LivingEntity companion, double radius) {
        int count = 0;
        for (LivingEntity ignored : companion.level().getEntitiesOfClass(
                LivingEntity.class,
                companion.getBoundingBox().inflate(radius),
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
