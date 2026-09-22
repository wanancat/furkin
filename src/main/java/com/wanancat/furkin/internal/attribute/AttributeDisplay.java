package com.wanancat.furkin.internal.attribute;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 「面板要显示哪些属性」的**唯一判据** —— 客户端（悬停明细）与服务端（权威值下发表）共用
 * （设计稿 §4.1 定案 N5「动态词条」）。
 *
 * <p><b>为什么要有这个类</b>：初版把候选清单写死在客户端（7 条可同步 ＋ 攻击伤害），
 * 于是第三方模组加的属性（如「最大魔力值」）永远不显示。N5 起改为
 * <b>「遍历属性注册表 ∩ 该生物实有」</b>：注册表里有什么、这只生物身上注册了哪些，
 * 就显示哪些 —— 与模块、物种都解耦。</p>
 *
 * <h2>三条取数事实（都有字节码依据，别凭印象改）</h2>
 * <ol>
 *   <li><b>能不能本地枚举</b>：{@code LivingEntity} 的属性容器是
 *       {@code new AttributeMap(DefaultAttributes.getSupplier(type))}，而 {@code getSupplier}
 *       只读一张静态表（双侧可用）；且 {@code AttributeMap#hasAttribute} 是<b>两分支 OR</b>
 *       （先查运行时实例、再查类型级 supplier）⇒ 「这只生物有哪些属性」<b>客户端自己答得出来</b>，
 *       不必问服务端。</li>
 *   <li><b>值的实时性分两路</b>：{@code isClientSyncable()} 的属性（最大生命 / 护甲 / 移速 …）
 *       由原版随实体同步，客户端读到的就是权威值；<b>非</b>同步属性（攻击伤害 / 击退抗性 /
 *       攻击击退 …）的技能 modifier <b>从不上网</b> —— 原版
 *       {@code ClientboundUpdateAttributesPacket} 只发 {@code UUID + amount + operation}、
 *       不发名字，客户端那份只有实体类型的默认基值 ⇒ 这几条的<b>总值必须由服务端下发</b>，
 *       见 {@link #serverTotalAttributes(LivingEntity)}。</li>
 *   <li><b>顺序</b>：注册表的迭代顺序不作为展示顺序依据（不依赖其内部容器是否有序）——
 *       展示顺序由 {@link #PREFERRED_ORDER} 指定偏好前缀、其余按注册名排序，两端一致且稳定。</li>
 * </ol>
 *
 * <p><b>调用侧要缓存</b>：本类每帧调用都会遍历整个注册表，而悬停明细是<b>每帧</b>渲染的
 * （60fps × 属性条数 × 两次查表）。调用方应在「实体类型变化时」算一次并缓存
 * （见 {@code FurkinPanelScreen#hoverAttributes} 的缓存点）。</p>
 */
public final class AttributeDisplay {

    private AttributeDisplay() {
    }

    /**
     * 屏蔽的**技术属性** —— 面板不显示的内部量（AI / 物理 / 寻路），放进去只是噪音。
     *
     * <p><b>形态：模组自带的明确清单</b>（2026-09-22 乌狸定案：「按自带黑名单来改」）。
     * <b>不用</b>「按命名空间前缀屏蔽」（如整个 {@code forge:}），原因两条 ——
     * ① 通配会连同一命名空间下**面向玩家**的属性一起误伤（一个模组里两类属性并存是常态）；
     * ② 别家模组的**内部**属性（如 {@code somemod:internal_x}）前缀方案根本拦不住。
     * 这也是社区通行形状：Apothic Attributes 的 {@code "Hidden Attributes"} 默认值同样只是
     * 加载器技术属性清单，JEI 一系也长成「自带黑名单 ＋ 排除项」。
     * 代价是清单要跟着上游补，但**每一条都是显式决定**（这正是取舍的用意）。</p>
     *
     * <p>清单混列两种来源：{@code forge:} 的 6 条是 Forge 给每个生物注册的加载器技术属性；
     * {@code minecraft:generic.follow_range} 是原版的寻路参数（跟随距离，默认 16 格）。</p>
     *
     * <p>用 id 字符串而不是 {@code ForgeMod.XXX.get()}：那些是 {@code RegistryObject}，
     * 在静态初始化里取会撞上「注册表尚未就绪」的时序问题；而 id 是稳定的注册键 ——
     * 取证：{@code javap -p -c net.minecraftforge.common.ForgeMod} 里
     * {@code ATTRIBUTES.register("swim_speed"|"nametag_distance"|"entity_gravity"|
     * "block_reach"|"entity_reach"|"step_height_addition", …)}（命名空间 {@code forge}）；
     * 原版那条见 {@code javap -p -c net.minecraft.world.entity.ai.attributes.Attributes}
     * 的 {@code ldc "generic.follow_range"}（命名空间 {@code minecraft}）。</p>
     */
    private static final Set<String> HIDDEN_IDS = Set.of(
            "minecraft:generic.follow_range",
            "forge:swim_speed",
            "forge:nametag_distance",
            "forge:entity_gravity",
            "forge:block_reach",
            "forge:entity_reach",
            "forge:step_height_addition");

    /**
     * <b>展示顺序偏好</b> —— 只决定「谁排前面」，<b>不决定「谁显示」</b>（显示集合由
     * {@link #displayableAttributes(LivingEntity)} 动态得出）。
     *
     * <p>这 5 条是 2026-09-22 乌狸验收过的排布（判据 2），保留在最前是为了不改动
     * 已认可的手感；其余属性（击退抗性 / 攻击击退 / 第三方属性 …）按注册名
     * 排在后面。用 {@code Attributes} 常量而非注册表反查：它们是普通静态字段，
     * 静态初始化里取是安全的。</p>
     */
    private static final List<Attribute> PREFERRED_ORDER = List.of(
            Attributes.MAX_HEALTH,
            Attributes.ATTACK_DAMAGE,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.MOVEMENT_SPEED);

    /** 该属性是否应当出现在面板里（未注册 / 在屏蔽表里 ⇒ 否）。 */
    public static boolean isDisplayable(Attribute attribute) {
        if (attribute == null) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        return id != null && !HIDDEN_IDS.contains(id.toString());
    }

    /** 属性 id（未注册时为空串，便于排序与打印，不返回 null）。 */
    public static String idOf(Attribute attribute) {
        ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        return id == null ? "" : id.toString();
    }

    /**
     * 客户端要显示的属性清单 = <b>注册表 ∩ 该生物实有 ∩ 未被屏蔽</b>，顺序见 {@link #PREFERRED_ORDER}。
     *
     * <p>实体不可得（已卸载 / entityId 缺失）时返回空表 —— 此时面板其余字段本来就已降级为
     * 占位，不该凭空列一堆读不出值的属性。</p>
     */
    public static List<Attribute> displayableAttributes(LivingEntity entity) {
        if (entity == null) {
            return List.of();
        }
        List<Attribute> preferred = new ArrayList<>();
        List<Attribute> rest = new ArrayList<>();
        for (Attribute attribute : ForgeRegistries.ATTRIBUTES) {
            if (!isDisplayable(attribute) || !entity.getAttributes().hasAttribute(attribute)) {
                continue;
            }
            (PREFERRED_ORDER.contains(attribute) ? preferred : rest).add(attribute);
        }
        preferred.sort(Comparator.comparingInt(PREFERRED_ORDER::indexOf));
        rest.sort(Comparator.comparing(AttributeDisplay::idOf));
        preferred.addAll(rest);
        return preferred;
    }

    /**
     * 服务端要下发**权威总值**的属性 = {@link #displayableAttributes} 里那些
     * <b>非 client-syncable</b> 的（客户端读不到它们带 modifier 的值，见类注释事实 2）。
     *
     * <p>返回值的顺序无关紧要（收端按 id 查表），故直接走注册表序，不做排序。</p>
     */
    public static List<Attribute> serverTotalAttributes(LivingEntity entity) {
        if (entity == null) {
            return List.of();
        }
        List<Attribute> needing = new ArrayList<>();
        for (Attribute attribute : ForgeRegistries.ATTRIBUTES) {
            if (isDisplayable(attribute)
                    && !attribute.isClientSyncable()
                    && entity.getAttributes().hasAttribute(attribute)) {
                needing.add(attribute);
            }
        }
        return needing;
    }
}
