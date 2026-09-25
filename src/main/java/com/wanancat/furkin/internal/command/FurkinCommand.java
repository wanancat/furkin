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
import com.wanancat.furkin.internal.inventory.FurkinInventory;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.record.FurkinDisplayOrder;
import com.wanancat.furkin.internal.skill.Skill;
import com.wanancat.furkin.internal.skill.SkillProgress;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
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
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext) {
        dispatcher.register(
                Commands.literal("furkin")
                        // 仅 OP 可用（2026-09-22 乌狸定）：此前对任意玩家开放（因召唤入口曾是命令），
                        // 现绒亲录界面已补齐玩家侧入口，命令收窄为调试 / 管理用途。
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("summon")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .executes(ctx -> summon(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "pet_id")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("forget")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        .executes(ctx -> forget(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "pet_id"), false))
                                        .then(Commands.literal("force")
                                                .executes(ctx -> forget(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pet_id"), true)))))
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
                        .then(Commands.literal("pouch")
                                .then(Commands.argument("pet_id", StringArgumentType.word())
                                        // clear 必须注册在 item 参数之前：Brigadier 按注册顺序逐个试子节点，
                                        // 参数节点对任意输入都能解析成功，若排在前面会把 "clear" 吃成物品 id
                                        // （解析为 minecraft:clear → Unknown item）。
                                        .then(Commands.literal("clear")
                                                .executes(ctx -> pouchClear(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pet_id"))))
                                        // ⚠️ item 用 ItemArgument（非 ResourceLocationArgument）—— 后者
                                        // 没有 listSuggestions，Tab 无补全（2026-09-22 字节码取证）。
                                        // ItemArgument 走 ItemParser.fillSuggestions → SharedSuggestionProvider
                                        // .suggestResource，与原版 /give 同源：bone→bone、#tag、NBT 全白送。
                                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                                .executes(ctx -> pouchAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pet_id"),
                                                        ItemArgument.getItem(ctx, "item").createItemStack(1, false)))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 6400))
                                                        .executes(ctx -> pouchAdd(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "pet_id"),
                                                                ItemArgument.getItem(ctx, "item").createItemStack(
                                                                        IntegerArgumentType.getInteger(ctx, "count"), false)))))))
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
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        // 走统一分流（2026-09-22 定）：未召唤 → 重建；已召唤 → 传送到身边。
        // 与绒亲录点条目同一条路径（FurkinCompanionManager.summonOrTeleport），
        // 不再出现「录里能拉过来、命令报错」的不一致。
        FurkinCompanionManager.SummonResult r =
                FurkinCompanionManager.summonOrTeleport(player, petId);
        final String sid = shortId(petId.toString());
        switch (r) {
            case SUMMONED -> src.sendSuccess(
                    () -> Component.translatable("furkin.command.summon.success", sid), false);
            case TELEPORTED -> src.sendSuccess(
                    () -> Component.translatable("furkin.command.summon.teleported", sid), false);
            case NOT_FOUND -> src.sendFailure(
                    Component.translatable("furkin.command.companion.not_found", sid));
            case NOT_OWNER -> src.sendFailure(Component.translatable("furkin.msg.not_owner"));
            case NOT_ALIVE -> src.sendFailure(
                    Component.translatable("furkin.command.summon.not_alive", sid));
            case ACTIVE_LIMIT -> src.sendFailure(
                    Component.translatable("furkin.command.summon.active_limit"));
            default -> src.sendFailure(Component.translatable("furkin.command.summon.failed"));
        }
        return 1;
    }

    /**
     * 列出本人全部绒亲。紧凑单行：{@code <短id> 物种 名字 Lv. 经验 技能点 [状态]}。
     *
     * <p><b>短 id</b>：UUID 全长 36 字符，10 只就会把聊天栏刷满 —— 显示只留
     * 前 8 位 + {@code …} + 后 4 位（如 {@code a1b2c3d4…9f0e}），<b>点击复制的仍是完整
     * UUID</b>（复制的是 {@code ClickEvent} 里的值，与显示文本无关）。</p>
     *
     * <p>物种名走本地化（{@code furkin.species.*}，与绒亲录界面同源）；未注册物种回退
     * 实体 descriptionId。</p>
     */
    private static int list(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(player.serverLevel());
        UUID me = player.getUUID();

        // 口径（2026-09-22 定）：物种 > 等级降序 > id 升序，与绒亲录同源（FurkinDisplayOrder）。
        List<FurkinArchiveEntry> mine = new ArrayList<>();
        for (FurkinArchiveEntry entry : archive.allEntries()) {
            if (me.equals(entry.getOwnerUuid())) {
                mine.add(entry);
            }
        }
        mine.sort(FurkinDisplayOrder.ARCHIVE);

        src.sendSuccess(() -> Component.translatable("furkin.command.list.header"), false);
        int shown = 0;
        for (FurkinArchiveEntry entry : mine) {
            shown++;
            final FurkinArchiveEntry e = entry;
            final String fullId = e.getCompanionId().toString();
            final String shortId = shortId(fullId);

            // 名字：自定义名优先，无则用物种名。
            String nameStr = e.getName() == null ? "" : e.getName().getString();

            // 状态后缀：[Fallen] 红 / [Summoned] 绿 / 存活未在场无后缀。
            Component state = !e.isAlive()
                    ? Component.translatable("furkin.command.list.state_dead").withStyle(ChatFormatting.RED)
                    : (e.isSummoned()
                            ? Component.translatable("furkin.command.list.state_summoned")
                                    .withStyle(ChatFormatting.GREEN)
                            : Component.empty());

            // 经验：当前 / 升级所需（与原版面板同口径）。
            int xpNeed = FurkinGrowth.xpNeededForNextLevel(e.getLevel());

            // id 组件：显示短 id，点击复制完整 id，hover 提示。
            Component idPart = Component.literal(shortId).withStyle(Style.EMPTY
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(Boolean.TRUE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, fullId))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                            net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                            Component.literal(fullId).append(
                                    Component.translatable("furkin.command.list.click_to_copy")))));

            src.sendSuccess(() -> Component.literal("  ")
                    .append(idPart)
                    .append(Component.literal("  "))
                    .append(speciesDisplay(e))
                    .append(nameStr.isEmpty() ? Component.empty()
                            : Component.literal("  " + nameStr).withStyle(ChatFormatting.WHITE))
                    .append(Component.translatable("furkin.command.list.level", e.getLevel())
                            .withStyle(ChatFormatting.YELLOW))
                    .append(Component.translatable("furkin.command.list.xp", e.getXp(), xpNeed)
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("furkin.command.list.sp", e.getSkillPoints())
                            .withStyle(ChatFormatting.AQUA))
                    .append(state), false);
        }
        if (shown == 0) {
            src.sendSuccess(() -> Component.translatable("furkin.command.list.none"), false);
        }
        return 1;
    }

    /** UUID 压缩显示：前 8 位 + {@code …} + 后 4 位（如 {@code a1b2c3d4…9f0e}）。 */
    private static String shortId(String full) {
        if (full.length() <= 13) {
            return full;
        }
        return full.substring(0, 8) + "\u2026" + full.substring(full.length() - 4);
    }

    /**
     * 物种显示组件：走物种注册表 nameKey（如 {@code furkin.species.cat}）。
     * ⚠️ 返回 <b>组件</b>而非字符串 —— 命令在服务端执行，若在此 {@code getString()} 会取
     * 服务端语言；下发组件则由客户端按自身语言渲染（与绒亲录界面同源）。
     * 未注册物种回退实体 descriptionId（{@code entity.minecraft.cat}）。
     */
    private static Component speciesDisplay(FurkinArchiveEntry entry) {
        if (entry.getSpecies() == null) {
            return Component.literal("?");
        }
        return com.wanancat.furkin.api.companion.FurkinSpeciesRegistry
                .byEntityType(entry.getSpecies())
                .<Component>map(sp -> Component.translatable(sp.getNameKey()))
                .orElseGet(() -> Component.translatable(entry.getSpecies().getDescriptionId()));
    }

    /** 忘记（解绑）一只属于本人的绒亲。走统一处理器（规则一套，与录内按钮同源）。 */
    private static int forget(CommandSourceStack src, String petIdRaw, boolean force) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        FurkinRecordActionHandler.Result r = force
                ? FurkinRecordActionHandler.forceUnbind(player, petId)
                : FurkinRecordActionHandler.unbind(player, petId);
        final String sid = shortId(petId.toString());
        switch (r) {
            case OK -> src.sendSuccess(() -> Component.translatable(
                    force ? "furkin.msg.force_unbound" : "furkin.msg.unbound"), false);
            case NOT_FOUND -> src.sendFailure(
                    Component.translatable("furkin.msg.unbind_not_found", sid));
            case NOT_OWNER -> src.sendFailure(Component.translatable("furkin.msg.not_owner"));
            case ENTITY_RESOLVED, NOT_SUMMONED -> src.sendFailure(
                    Component.translatable("furkin.msg.force_unbind_not_needed"));
            case ENTITY_UNRESOLVED -> src.sendFailure(
                    Component.translatable("furkin.msg.unbind_entity_unresolved"));
            case CLEANUP_FAILED -> src.sendFailure(Component.translatable(
                    force ? "furkin.msg.force_unbind_failed" : "furkin.msg.unbind_cleanup_failed"));
            default -> src.sendFailure(Component.translatable("furkin.msg.unbind_failed"));
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
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        FurkinRecordActionHandler.Result r = FurkinRecordActionHandler.rename(player, petId, name);
        final String sid = shortId(petId.toString());
        switch (r) {
            // 报出「改成了什么」—— 原名不报（改前值意义不大），新名是关键信息。
            case OK -> src.sendSuccess(() -> Component.translatable(
                    "furkin.command.rename.success", sid,
                    Component.literal(name).withStyle(ChatFormatting.WHITE)), false);
            case NOT_FOUND -> src.sendFailure(
                    Component.translatable("furkin.command.companion.not_found", sid));
            case NOT_OWNER -> src.sendFailure(Component.translatable("furkin.msg.not_owner"));
            default -> src.sendFailure(Component.translatable("furkin.msg.rename_failed"));
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
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        // 校验归属（先查档案确认是本人的）。
        FurkinArchiveData archive = FurkinArchiveData.get(player.serverLevel());
        FurkinArchiveEntry entry = archive.getEntry(petId);
        if (entry == null) {
            src.sendFailure(Component.translatable(
                    "furkin.command.companion.not_found", petId));
            return 0;
        }
        if (!player.getUUID().equals(entry.getOwnerUuid())) {
            src.sendFailure(Component.translatable("furkin.msg.not_owner"));
            return 0;
        }
        if (!entry.isSummoned()) {
            src.sendFailure(Component.translatable("furkin.command.companion.not_summoned"));
            return 0;
        }

        // 找在场实体。
        LivingEntity target = findLivingByCompanionId(player.serverLevel(), petId);
        if (target == null) {
            src.sendFailure(Component.translatable("furkin.command.companion.entity_not_found"));
            return 0;
        }

        FurkinData before = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        int oldLevel = before == null ? 0 : before.getLevel();

        boolean leveled = FurkinGrowth.addXp(target, amount);

        FurkinData after = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        int newLevel = after == null ? oldLevel : after.getLevel();
        int newXp = after == null ? 0 : after.getXp();
        int sp = after == null ? 0 : after.getSkillPoints();

        src.sendSuccess(() -> {
            Component message = Component.translatable(
                    "furkin.command.addxp.success", shortId(petId.toString()), amount,
                    newLevel, newXp, sp);
            return leveled
                    ? message.copy().append(Component.translatable(
                            "furkin.command.addxp.level_up_suffix"))
                    : message;
        }, false);
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
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        FurkinCombatMode mode = FurkinCombatMode.parse(modeRaw);
        if (mode == null) {
            src.sendFailure(Component.translatable("furkin.command.mode.invalid", modeRaw));
            return 0;
        }

        FurkinCombatModeHandler.Result r = FurkinCombatModeHandler.setMode(player, petId, mode);
        final String sid = shortId(petId.toString());
        Component modeName = Component.translatable(
                "furkin.combat_mode." + mode.name().toLowerCase(Locale.ROOT));
        switch (r) {
            case OK -> src.sendSuccess(
                    () -> Component.translatable("furkin.msg.mode_set", modeName), false);
            case NOT_FOUND -> src.sendFailure(
                    Component.translatable("furkin.command.companion.not_found", sid));
            case NOT_OWNER -> src.sendFailure(Component.translatable("furkin.msg.not_owner"));
            case NOT_SUMMONED -> src.sendFailure(
                    Component.translatable("furkin.msg.mode_not_summoned"));
            case APPLY_FAILED -> src.sendFailure(Component.translatable("furkin.msg.mode_failed"));
            default -> src.sendFailure(Component.translatable("furkin.msg.mode_failed"));
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
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
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
            // 宠物 id 用短 id；技能 id 是 ResourceLocation（非 UUID）保持完整，截断无意义。
            case OK -> src.sendSuccess(() -> Component.translatable(
                    "furkin.command.skill.success", finalSkillId, shortId(petId.toString())), false);
            case NOT_FOUND -> src.sendFailure(Component.translatable(
                    "furkin.command.companion.not_found", shortId(petId.toString())));
            case NOT_OWNER -> src.sendFailure(Component.translatable("furkin.msg.not_owner"));
            case NOT_SUMMONED -> src.sendFailure(
                    Component.translatable("furkin.msg.skill_not_summoned"));
            case SKILL_UNKNOWN -> src.sendFailure(
                    Component.translatable("furkin.command.skill.unknown", finalSkillId));
            case SPECIES_MISMATCH -> src.sendFailure(
                    Component.translatable("furkin.msg.skill_species_mismatch"));
            case PREREQUISITES -> src.sendFailure(
                    Component.translatable("furkin.msg.skill_prerequisites"));
            case NOT_ENOUGH_POINTS -> src.sendFailure(
                    Component.translatable("furkin.msg.skill_no_points"));
            case MAXED -> src.sendFailure(Component.translatable("furkin.msg.skill_maxed"));
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
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        LivingEntity target = findLivingByCompanionId(player.serverLevel(), petId);
        if (target == null) {
            src.sendFailure(Component.translatable(
                    "furkin.command.companion.entity_not_found_summon_first"));
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

        FurkinInventory pouch = data == null ? null : data.getPouch();
        int pouchSlots = pouch == null ? 0 : pouch.getContainerSize();
        int[] usage = pouchUsage(pouch);
        final int usedSlots = usage[0];
        final int pouchTotal = usage[1];

        // 属性名用官方注册 id 去掉 "generic." 前缀的部分（2026-09-22 取证：
        // Attributes 的 ldc 常量为 "generic.max_health" 等），不自定义驼峰缩写 ——
        // 免得与官方文档 / 其它模组对照时对不上号。
        src.sendSuccess(() -> Component.translatable(
                "furkin.command.inspect.header", shortId(petId.toString()), level, skillPoints), false);
        src.sendSuccess(() -> Component.translatable(
                "furkin.command.inspect.attributes",
                String.format(Locale.ROOT, "%.2f", attack),
                String.format(Locale.ROOT, "%.2f", maxHealth),
                String.format(Locale.ROOT, "%.2f", currentHealth),
                String.format(Locale.ROOT, "%.2f", armor),
                String.format(Locale.ROOT, "%.3f", speed)), false);
        src.sendSuccess(() -> Component.translatable(
                "furkin.command.inspect.pouch", usedSlots, pouchSlots, pouchTotal), false);
        return 1;
    }

    /**
     * 调试 / 验收入口：往某只在场绒亲的行囊里塞物品。
     *
     * <p>走容器的 {@link FurkinInventory#addItem} —— 与将来「凭空产出」「拾荒」技能是同一个入口，
     * 所以这条命令验到的堆叠 / 满仓行为，就是那些技能会遇到的行为。</p>
     */
    private static int pouchAdd(CommandSourceStack src, String petIdRaw, ItemStack toAdd) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        if (toAdd.isEmpty()) {
            src.sendFailure(Component.translatable("furkin.command.pouch.empty_item"));
            return 0;
        }

        LivingEntity target = findLivingByCompanionId(player.serverLevel(), petId);
        if (target == null) {
            src.sendFailure(Component.translatable(
                    "furkin.command.companion.entity_not_found_summon_first"));
            return 0;
        }
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            src.sendFailure(Component.translatable("furkin.command.companion.data_missing"));
            return 0;
        }

        FurkinInventory pouch = data.getPouch();
        ItemStack leftover = pouch.addItem(toAdd.copy());

        // 回执（2026-09-22 乌狸定）：只报「谁拿到了什么」或「谁的袋子满了」，
        // 不报格数 / 总量等容器统计 —— 命令语义是「往里塞东西」，不是查容量。
        final int placed = toAdd.getCount() - leftover.getCount();
        final int leftoverCount = leftover.getCount();
        final String petName = companionLabel(target, petId);
        final Component itemName = toAdd.getHoverName().copy();

        if (leftoverCount <= 0) {
            src.sendSuccess(() -> Component.translatable(
                    "furkin.command.pouch.added",
                    Component.translatable("furkin.command.pouch.amount", placed)
                            .withStyle(ChatFormatting.AQUA),
                    itemName.copy().withStyle(ChatFormatting.WHITE),
                    petName), false);
        } else if (placed <= 0) {
            // 一点没塞进去：只报满了。
            src.sendSuccess(() -> Component.translatable(
                    "furkin.command.pouch.full",
                    petName,
                    itemName.copy().withStyle(ChatFormatting.WHITE)), false);
        } else {
            // 塞进去一部分：报实际放入量，并说明还有多少没装下。
            src.sendSuccess(() -> Component.translatable(
                    "furkin.command.pouch.partial",
                    Component.translatable("furkin.command.pouch.amount", placed)
                            .withStyle(ChatFormatting.AQUA),
                    itemName.copy().withStyle(ChatFormatting.WHITE),
                    petName,
                    Component.translatable("furkin.command.pouch.amount", leftoverCount)
                            .withStyle(ChatFormatting.RED)), false);
        }
        return 1;
    }

    /**
     * 宠物显示标签：优先实体自定义名（命名牌 / 契约命名），无则短 id。
     * 命令在服务端执行，此处只拼字面量 —— 自定义名已是玩家可见文本，无需再本地化。
     */
    private static String companionLabel(LivingEntity target, UUID petId) {
        Component name = target.getCustomName();
        if (name != null && !name.getString().isEmpty()) {
            return name.getString();
        }
        return shortId(petId.toString());
    }

    /**
     * 清空某只在场绒亲的行囊（调试 / 验收用：回到空仓状态重测堆叠）。
     *
     * <p>走容器的 {@link FurkinInventory#clearContent()}（= 官方 {@code Container#clearContent}），
     * 不清格数、不动其他字段，只把物品倒空。输出带清空前的用量，便于确认倒掉了什么。</p>
     */
    private static int pouchClear(CommandSourceStack src, String petIdRaw) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }

        UUID petId;
        try {
            petId = UUID.fromString(petIdRaw);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.translatable("furkin.msg.invalid_pet_id", petIdRaw));
            return 0;
        }

        LivingEntity target = findLivingByCompanionId(player.serverLevel(), petId);
        if (target == null) {
            src.sendFailure(Component.translatable(
                    "furkin.command.companion.entity_not_found_summon_first"));
            return 0;
        }
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            src.sendFailure(Component.translatable("furkin.command.companion.data_missing"));
            return 0;
        }

        FurkinInventory pouch = data.getPouch();
        final int wasTotal = pouchUsage(pouch)[1];
        final String petName = companionLabel(target, petId);
        pouch.clearContent();

        src.sendSuccess(() -> Component.translatable(
                "furkin.command.pouch.cleared",
                petName,
                Component.translatable("furkin.command.pouch.items", wasTotal)
                        .withStyle(ChatFormatting.AQUA)), false);
        return 1;
    }

    /**
     * 行囊占用统计：返回 {@code [非空格子数, 物品总量]}；容器为 {@code null} 时全 0。
     *
     * <p>为什么要两个数：{@code used} 只说明占了几格，看不出格子里的堆叠是否合法。
     * 两者合读才能一眼判读 —— 例：塞 600 根骨头，正常结果应是
     * {@code slots=18 used=9 total=576 leftover=24}（9 格 × 64 + 剩 24 装不下）；
     * 若出现 {@code used=1 total=600} 就是「整堆压进一格」的老 bug 复发。</p>
     */
    private static int[] pouchUsage(FurkinInventory pouch) {
        if (pouch == null) {
            return new int[]{0, 0};
        }
        int used = 0;
        int total = 0;
        for (int i = 0; i < pouch.getContainerSize(); i++) {
            ItemStack stack = pouch.getItem(i);
            if (!stack.isEmpty()) {
                used++;
                total += stack.getCount();
            }
        }
        return new int[]{used, total};
    }

    /** 读某实体的指定属性当前值；无该属性时返回 0。 */
    private static double attr(LivingEntity target, net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        var instance = target.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }

    /** 按服务器级档案的 UUID / 维度定向查找在场绒亲实体。 */
    private static LivingEntity findLivingByCompanionId(ServerLevel level, UUID companionId) {
        return FurkinCompanionManager.findLivingByCompanionId(level, companionId);
    }
}
