package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.OpenFurkinScreenPacket;
import com.wanancat.furkin.internal.network.ResetSkillsPacket;
import com.wanancat.furkin.internal.network.UnlockSkillPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/**
 * 绒亲界面 —— 显示某只绒亲的技能面板（设计稿 §3.2 UI 方案「聚合壳 + 面板化」）。
 *
 * <p>M2 阶段只装技能面板（技能不需要槽位 ⇒ 无容器语义），装备面板留 M3 绑容器。
 * 因此本类暂为纯 {@link Screen}（非 {@code AbstractContainerScreen}），
 * 打开走 {@link OpenFurkinScreenPacket}（S→C）。</p>
 *
 * <p>交互：每个可见技能一个「加点」按钮 → 上行 {@link UnlockSkillPacket}；
 * 底部「洗点」按钮 → 弹 {@link ConfirmScreen} 二次确认 → 上行 {@link ResetSkillsPacket}。
 * 不做本地乐观更新，以服务端回包（SyncFurkinDataPacket）为准。</p>
 *
 * <p><b>端位隔离</b>：本类 {@link OnlyIn}{@code (Dist.CLIENT)}。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FurkinScreen extends Screen {

    private final UUID companionId;
    private final String name;
    private int skillPoints;
    private List<OpenFurkinScreenPacket.SkillView> skills;

    private static final int LIST_TOP = 44;
    private static final int ITEM_HEIGHT = 26;
    private static final int LIST_LEFT = 24;

    public FurkinScreen(OpenFurkinScreenPacket packet) {
        super(Component.translatable("furkin.screen.furkin.title"));
        this.companionId = packet.getCompanionId();
        this.name = packet.getName();
        this.skillPoints = packet.getSkillPoints();
        this.skills = packet.getSkills();
    }

    /** 打开界面（客户端专用入口）。若已开着同一只的界面，则原地刷新数据并重排。 */
    public static void open(OpenFurkinScreenPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof FurkinScreen current
                && current.companionId.equals(packet.getCompanionId())) {
            current.refresh(packet);
            return;
        }
        mc.setScreen(new FurkinScreen(packet));
    }

    /** 原地刷新：替换数据并重排控件（加点 / 洗点后服务端回包触发）。 */
    private void refresh(OpenFurkinScreenPacket packet) {
        this.skillPoints = packet.getSkillPoints();
        this.skills = packet.getSkills();
        this.init();
    }

    @Override
    protected void init() {
        super.init();

        int y = LIST_TOP;
        for (OpenFurkinScreenPacket.SkillView skill : skills) {
            addSkillRow(skill, y);
            y += ITEM_HEIGHT;
        }

        // 底部：洗点按钮（左）+ 关闭按钮（右）。
        addRenderableWidget(Button.builder(
                        Component.translatable("furkin.screen.furkin.reset"),
                        btn -> confirmReset())
                .bounds(this.width / 2 - 100, this.height - 30, 90, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("furkin.screen.furkin.close"),
                        btn -> onClose())
                .bounds(this.width / 2 + 10, this.height - 30, 90, 20)
                .build());
    }

    /** 为一条技能渲染「名字 + 等级进度 + 加点按钮」。 */
    private void addSkillRow(OpenFurkinScreenPacket.SkillView skill, int y) {
        // 加点按钮放右侧。
        int btnW = 44;
        addRenderableWidget(Button.builder(
                        Component.literal("+1"),
                        btn -> requestUnlock(skill))
                .bounds(this.width - 20 - btnW, y, btnW, 20)
                .build());
    }

    /** 点「+1」：上行加点请求，服务端校验。 */
    private void requestUnlock(OpenFurkinScreenPacket.SkillView skill) {
        ResourceLocation skillId = new ResourceLocation(skill.getId());
        FurkinNetwork.channel().sendToServer(new UnlockSkillPacket(companionId, skillId));
    }

    /** 点「洗点」：弹二次确认框。 */
    private void confirmReset() {
        int refund = 0;
        for (OpenFurkinScreenPacket.SkillView s : skills) {
            refund += s.getCurrentLevel();
        }
        if (refund == 0) {
            // 没有可退的，直接提示（仍允许洗点，但无意义，简单处理为仍弹确认）。
        }
        final int finalRefund = refund;
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                (it.unimi.dsi.fastutil.booleans.BooleanConsumer) confirmed -> {
                    if (confirmed) {
                        FurkinNetwork.channel().sendToServer(new ResetSkillsPacket(companionId));
                    }
                    Minecraft.getInstance().setScreen(this);
                },
                Component.translatable("furkin.screen.furkin.reset"),
                Component.translatable("furkin.screen.furkin.reset_confirm", finalRefund),
                Component.translatable("furkin.screen.furkin.reset_confirm_yes"),
                Component.translatable("furkin.screen.furkin.reset_confirm_cancel")));
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);

        // 标题 + 名字。
        gui.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // 显示名 + 技能点。
        Component header = Component.literal(name == null ? "" : name + "  ")
                .append(Component.translatable("furkin.screen.furkin.skill_points"))
                .append(Component.literal(": " + skillPoints));
        gui.drawCenteredString(this.font, header, this.width / 2, 28, 0xAAAAAA);

        // 技能列表文字（按钮由 init 添加）。
        int y = LIST_TOP + 3;
        for (OpenFurkinScreenPacket.SkillView skill : skills) {
            String levelStr = skill.isInfinite()
                    ? String.valueOf(skill.getCurrentLevel())
                    : skill.getCurrentLevel() + " / " + skill.getMaxLevel();
            Component line = Component.translatable(skill.getNameKey())
                    .append(Component.literal("  " + levelStr))
                    .append(Component.literal("   "))
                    .append(Component.translatable(skill.getDescriptionKey()));
            gui.drawString(this.font, line, LIST_LEFT, y, 0xFFFFFF);
            y += ITEM_HEIGHT;
        }

        if (skills.isEmpty()) {
            gui.drawCenteredString(this.font,
                    Component.translatable("furkin.screen.furkin.empty"),
                    this.width / 2, this.height / 2, 0xAAAAAA);
        }

        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
