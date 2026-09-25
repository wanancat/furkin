package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * 强制解绑二次确认页。
 *
 * <p>只有常规解绑返回 {@code ENTITY_UNRESOLVED} 后才由录界面打开。页面只把
 * {@link RecordActionPacket.Action#FORCE_UNBIND} 发给服务端；是否允许以及是否仍需要强制解绑，
 * 全部由服务端重新校验。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ForceUnbindConfirmScreen extends Screen {

    private final UUID companionId;
    private final Screen parent;

    private ForceUnbindConfirmScreen(UUID companionId, Screen parent) {
        super(Component.translatable("furkin.screen.force_unbind.title"));
        this.companionId = companionId;
        this.parent = parent;
    }

    /** 打开确认页；取消或完成确认后回到原绒亲录。 */
    public static void open(UUID companionId, Screen parent) {
        Minecraft.getInstance().setScreen(new ForceUnbindConfirmScreen(companionId, parent));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = this.height / 2 + 42;

        addRenderableWidget(Button.builder(
                        Component.translatable("furkin.screen.force_unbind.confirm"),
                        btn -> confirm())
                .bounds(cx - 112, y, 108, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("furkin.screen.force_unbind.cancel"),
                        btn -> onClose())
                .bounds(cx + 4, y, 108, 20)
                .build());
    }

    private void confirm() {
        FurkinNetwork.channel().sendToServer(
                new RecordActionPacket(RecordActionPacket.Action.FORCE_UNBIND,
                        companionId, null, null, true));
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);
        int cx = this.width / 2;
        int y = this.height / 2 - 48;
        gui.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFF);
        gui.drawCenteredString(this.font,
                Component.translatable("furkin.screen.force_unbind.line1"),
                cx, y + 24, 0xFFDDDD);
        gui.drawCenteredString(this.font,
                Component.translatable("furkin.screen.force_unbind.line2"),
                cx, y + 38, 0xFFDDDD);
        gui.drawCenteredString(this.font,
                Component.translatable("furkin.screen.force_unbind.line3"),
                cx, y + 52, 0xFFDDDD);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}