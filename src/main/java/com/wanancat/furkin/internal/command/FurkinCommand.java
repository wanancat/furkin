package com.wanancat.furkin.internal.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.contract.FurkinCombatMode;
import com.wanancat.furkin.internal.contract.FurkinCombatModeHandler;
import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import com.wanancat.furkin.internal.growth.FurkinGrowth;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.skill.Skill;
import com.wanancat.furkin.internal.skill.SkillProgress;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;
import java.util.UUID;

/**
 * furkin 调试 / 管理命令。
 *
 * <p>提供召唤入口（玩家 UI 待绒亲录界面批次补齐），以及列出本人绒亲的调试入口。
 * 召唤走 {@link FurkinCompanionManager#summon}，受活跃上限约束。</p>
 */
public final class FurkinCommand {

    private FurkinCommand() {
    }

    /** mode 参数的建议器：列出四档，按已输入前缀过滤。 */
    private static final SuggestionProvider<CommandSourceStack> MODE_SUGGESTIONS =
            (CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) -> {
                String input = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (FurkinCombatMode mode : FurkinCombatMode.values()) {
                    String name = mode.name().toLowerCase(Locale.ROOT);
                    if (name.startsWith(input)) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            };

    /** skill_id 参数的建议器：列出全部已加载技能 id（完整 namespace:path），按前缀过滤。 */
    private static final SuggestionProvider<CommandSourceStack> SKILL_SUGGESTIONS =
            (CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) -> {
                String input = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Skill skill : SkillRegistry.tree().all()) {
                    String id = skill.getId().toString();
                    if (id.toLowerCase(Locale.ROOT).startsWith(input)) {
                        builder.suggest(id);
                    }
                }
                return builder.buildFuture();
            };

    /** 注册命令。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("furkin")
                        .requires(src -> src.hasPermission(2) || src.getEntity() instanceof ServerPlayer)
                        .then(Commands.literal("summon")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .executes(ctx -> summon(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "pet_id")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("forget")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .executes(ctx -> forget(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "pet_id")))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> rename(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pet_id"),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("addexp")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer())
                                                .executes(ctx -> addExp(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pet_id"),
                                                        IntegerArgumentType.getInteger(ctx, "amount"))))))
                        .then(Commands.literal("mode")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests(MODE_SUGGESTIONS)
                                                .executes(ctx -> setMode(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pet_id"),
                                                        StringArgumentType.getString(ctx, "mode"))))))
                        .then(Commands.literal("skills")
                                .executes(ctx -> listSkills(ctx.getSource())))
                        .then(Commands.literal("skill")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .then(Commands.argument("skill_id", ResourceLocationArgument.id())
                                                .suggests(SKILL_SUGGESTIONS)
                                                .executes(ctx -> unlockSkill(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pet_id"),
                                                        ResourceLocationArgument.getId(ctx, "skill_id"))))))
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .executes(ctx -> inspect(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "pet_id")))))
        );
    }

    /** 召唤：按 pet_id 召唤一只已收回的绒亲。 */
    private static int summon(CommandSourceStack src, String petIdRaw) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Invalid pet id: " + petIdRaw));
            return 0;
        }

        boolean ok = FurkinCompanionManager.summon(player, petId);
        if (ok) {
            src.sendSuccess(() -> Component.literal("Summoned companion " + petId), false);
        } else {
            src.sendFailure(Component.literal(
                    "Summon failed (not found / not owner / not alive / already summoned / active limit)"));
        }
        return 1;
    }

    /** 列出本人全部绒亲。id 可点击 → 直接复制 id（rename / forget / summon 命令共用）。 */
    private static int list(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(player.serverLevel());
        UUID me = player.getUUID();
        src.sendSuccess(() -> Component.literal("Your companions:"), false);
        int shown = 0;
        for (FurkinArchiveEntry entry : archive.allEntries()) {
            if (me.equals(entry.getOwnerUuid())) {
                shown++;
                String speciesStr = entry.getSpecies() == null ? "?"
                        : net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entry.getSpecies()).toString();
                String nameStr = entry.getName() == null ? "" : entry.getName().getString();
                String idStr = entry.getCompanionId().toString();
                String rest = "  species=" + speciesStr
                        + "  name=" + nameStr
                        + "  alive=" + entry.isAlive()
                        + "  summoned=" + entry.isSummoned()
                        + "  level=" + entry.getLevel()
                        + "  xp=" + entry.getXp()
                        + "  skillPoints=" + entry.getSkillPoints();

                // id 组件：点击直接复制 id 本身（原版 copy_to_clipboard），hover 提示。
                // （旧设计的 [SUMMON] 前缀已删：id 改为复制后它不再对应任何行为，且 summoned= 字段已表达状态。）
                src.sendSuccess(() -> Component.literal("  ")
                        .append(Component.literal(idStr).withStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(Boolean.TRUE)
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.COPY_TO_CLIPBOARD, idStr))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to copy ID"))))
                                .append(Component.literal(rest))), false);
            }
        }
        if (shown == 0) {
            src.sendSuccess(() -> Component.literal("  (none)"), false);
        }
        return 1;
    }

    /** 忘记（解绑）一只属于本人的绒亲。走统一处理器（规则一套，与录内按钮同源）。 */
    private static int forget(CommandSourceStack src, String petIdRaw) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Invalid pet id: " + petIdRaw));
            return 0;
        }

        FurkinRecordActionHandler.Result r = FurkinRecordActionHandler.unbind(player, petId);
        switch (r) {
            case OK -> src.sendSuccess(() -> Component.literal("Unbound companion " + petId), false);
            case NOT_FOUND -> src.sendFailure(Component.literal("No such companion: " + petId));
            case NOT_OWNER -> src.sendFailure(Component.literal("Not your companion."));
            default -> src.sendFailure(Component.literal("Unbind failed."));
        }
        return 1;
    }

    /** 改名：给一只属于本人的绒亲改名。走统一处理器。 */
    private static int rename(CommandSourceStack src, String petIdRaw, String name) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Invalid pet id: " + petIdRaw));
            return 0;
        }

        FurkinRecordActionHandler.Result r = FurkinRecordActionHandler.rename(player, petId, name);
        switch (r) {
            case OK -> src.sendSuccess(() -> Component.literal("Renamed companion " + petId), false);
            case NOT_FOUND -> src.sendFailure(Component.literal("No such companion: " + petId));
            case NOT_OWNER -> src.sendFailure(Component.literal("Not your companion."));
            default -> src.sendFailure(Component.literal("Rename failed."));
        }
        return 1;
    }

    /** 加经验：给一只在场绒亲加经验（调试 / 验收用，验证升级链）。 */
    private static int addExp(CommandSourceStack src, String petIdRaw, int amount) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Invalid pet id: " + petIdRaw));
            return 0;
        }

        // 校验归属（先查档案确认是本人的）。
        FurkinArchiveData archive = FurkinArchiveData.get(player.serverLevel());
        FurkinArchiveEntry entry = archive.getEntry(petId);
        if (entry == null) {
            src.sendFailure(Component.literal("No such companion: " + petId));
            return 0;
        }
        if (!player.getUUID().equals(entry.getOwnerUuid())) {
            src.sendFailure(Component.literal("Not your companion."));
            return 0;
        }
        if (!entry.isSummoned()) {
            src.sendFailure(Component.literal("Companion is not summoned — summon it first."));
            return 0;
        }

        // 找在场实体。
        LivingEntity target = findLivingByCompanionId(player.serverLevel(), petId);
        if (target == null) {
            src.sendFailure(Component.literal("Companion entity not found in world."));
            return 0;
        }

        FurkinData before = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        int oldLevel = before == null ? 0 : before.getLevel();

        boolean leveled = FurkinGrowth.addXp(target, amount);

        FurkinData after = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        int newLevel = after == null ? oldLevel : after.getLevel();
        int newXp = after == null ? 0 : after.getXp();
        int sp = after == null ? 0 : after.getSkillPoints();

        src.sendSuccess(() -> Component.literal(
                "Added " + amount + " xp to " + petId
                        + " -> Lv." + newLevel + " (xp=" + newXp + ", skillPoints=" + sp + ")"
                        + (leveled ? " [LEVEL UP]" : "")), false);
        return 1;
    }

    /** 切换战斗模式：follow / passive / protect / aggressive。 */
    private static int setMode(CommandSourceStack src, String petIdRaw, String modeRaw) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Invalid pet id: " + petIdRaw));
            return 0;
        }

        FurkinCombatMode mode = FurkinCombatMode.parse(modeRaw);
        if (mode == null) {
            src.sendFailure(Component.literal(
                    "Invalid mode: " + modeRaw + " (use follow / passive / protect / aggressive)"));
            return 0;
        }

        FurkinCombatModeHandler.Result r = FurkinCombatModeHandler.setMode(player, petId, mode);
        switch (r) {
            case OK -> src.sendSuccess(() -> Component.literal(
                    "Combat mode set to " + mode.name() + " for " + petId), false);
            case NOT_FOUND -> src.sendFailure(Component.literal("No such companion: " + petId));
            case NOT_OWNER -> src.sendFailure(Component.literal("Not your companion."));
            case NOT_SUMMONED -> src.sendFailure(Component.literal("Companion is not summoned — summon it first."));
            default -> src.sendFailure(Component.literal("Set mode failed."));
        }
        return 1;
    }

    /** 列出全部技能定义（id + tier + maxLevel + species，调试 / 验收用）。 */
    private static int listSkills(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("Skills:"), false);
        for (Skill skill : SkillRegistry.tree().all()) {
            String speciesStr = skill.getSpecies().isEmpty() ? "(common)"
                    : skill.getSpecies().toString();
            src.sendSuccess(() -> Component.literal("  " + skill.getId()
                    + "  tier=" + skill.getTier()
                    + "  maxLevel=" + skill.getMaxLevel()
                    + "  cost=" + skill.getCost()
                    + "  species=" + speciesStr), false);
        }
        return 1;
    }

    /**
     * 给某只在场绒亲加点（调试 / 验收用，完整校验走 SkillProgress）。
     * skillId 无命名空间时归一化为 furkin:（手敲不带前缀的兜底，
     * ResourceLocationArgument 默认补 minecraft: 会找不到技能）。
     */
    private static int unlockSkill(CommandSourceStack src, String petIdRaw, ResourceLocation parsedSkillId) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Invalid pet id: " + petIdRaw));
            return 0;
        }

        ResourceLocation skillId = parsedSkillId;
        if (!skillId.getNamespace().equals(FurkinMod.MODID)
                && SkillRegistry.tree().get(skillId).isEmpty()
                && SkillRegistry.tree().get(new ResourceLocation(FurkinMod.MODID, skillId.getPath())).isPresent()) {
            // minecraft: 命名空间下不存在、但 furkin: 下存在 → 归一化。
            skillId = new ResourceLocation(FurkinMod.MODID, skillId.getPath());
        }
        final ResourceLocation finalSkillId = skillId;

        SkillProgress.Result r = SkillProgress.tryUnlock(
                player, petId, finalSkillId, SkillRegistry.tree());

        switch (r) {
            case OK -> src.sendSuccess(() -> Component.literal(
                    "Unlocked " + finalSkillId + " for " + petId), false);
            case NOT_FOUND -> src.sendFailure(Component.literal("No such companion: " + petId));
            case NOT_OWNER -> src.sendFailure(Component.literal("Not your companion."));
            case NOT_SUMMONED -> src.sendFailure(Component.literal("Companion is not summoned."));
            case SKILL_UNKNOWN -> src.sendFailure(Component.literal("Unknown skill: " + finalSkillId));
            case SPECIES_MISMATCH -> src.sendFailure(Component.literal("Skill not available to this species."));
            case PREREQUISITES -> src.sendFailure(Component.literal("Prerequisites not met."));
            case NOT_ENOUGH_POINTS -> src.sendFailure(Component.literal("Not enough skill points."));
            case MAXED -> src.sendFailure(Component.literal("Skill already maxed."));
        }
        return 1;
    }

    /** 打印某只在场绒亲的四类属性当前值（调试 / 验收用，验证 attribute 技能加成）。 */
    private static int inspect(CommandSourceStack src, String petIdRaw) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Invalid pet id: " + petIdRaw));
            return 0;
        }

        LivingEntity target = findLivingByCompanionId(player.serverLevel(), petId);
        if (target == null) {
            src.sendFailure(Component.literal("Companion entity not found in world (summon it first)."));
            return 0;
        }

        // 属性值：直接读 AttributeInstance 的当前值（含所有 modifier 叠加后的最终值）。
        double attack = attr(target, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        double maxHealth = attr(target, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        double armor = attr(target, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        double speed = attr(target, net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        double currentHealth = target.getHealth();

        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        int level = data == null ? 0 : data.getLevel();
        int skillPoints = data == null ? 0 : data.getSkillPoints();

        src.sendSuccess(() -> Component.literal(
                "Inspect " + petId
                        + "  Lv." + level
                        + "  skillPoints=" + skillPoints), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  attack=%.2f  maxHealth=%.2f (cur=%.2f)  armor=%.2f  speed=%.3f",
                attack, maxHealth, currentHealth, armor, speed)), false);
        return 1;
    }

    /** 读某实体的指定属性当前值；无该属性时返回 0。 */
    private static double attr(LivingEntity target, net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        var instance = target.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }

    /** 在世界里按 companionId 查找在场绒亲实体。 */
    private static LivingEntity findLivingByCompanionId(ServerLevel level, UUID companionId) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof LivingEntity living) {
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data != null && companionId.equals(data.getCompanionId())) {
                    return living;
                }
            }
        }
        return null;
    }
}
