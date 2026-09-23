package com.wanancat.furkin.internal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * 改名输入框 —— 录内「改名」按钮弹出，为已契约绒亲改名。
 *
 * <p>确认：发 {@link RecordActionPacket}(RENAME, companionId, name) 上行；
 * 取消 / ESC：关闭，不发包。留空确认 → 服务端回退物种名。</p>
 *
 * <p>与契约命名 {@link ContractNameScreen} 的区别：针对已存在的绒亲（companionId）、
 * 预填当前名字，并通过显式父屏返回绒亲录。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RenameScreen extends Screen {

    /** 打开本屏的绒亲录；取消、ESC 或确认后返回该实例。 */
    private final Screen parent;

    /** 绒亲身份 UUID。 */
    private final UUID companionId;

    private EditBox nameInput;

    private RenameScreen(Screen parent, UUID companionId, String currentName) {
        super(Component.translatable("furkin.screen.rename.title"));
        this.parent = parent;
        this.companionId = companionId;
        this.prefill = currentName;
    }

    /** 预填名字（空则无预填）。 */
    private final String prefill;

    /** 打开改名输入框（客户端专用入口）。 */
    public static void open(Screen parent, UUID companionId, String currentName) {
        Minecraft.getInstance().setScreen(new RenameScreen(parent, companionId, currentName));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;

        this.nameInput = new EditBox(this.font, cx - 100, this.height / 2 - 20, 200, 20,
                Component.translatable("furkin.screen.rename.hint"));
        this.nameInput.setMaxLength(32);
        String hint = Component.translatable("furkin.screen.rename.hint").getString();
        this.nameInput.setSuggestion(hint);
        this.nameInput.setResponder(value -> this.nameInput.setSuggestion(value.isEmpty() ? hint : null));
        if (prefill != null && !prefill.isEmpty()) {
            this.nameInput.setValue(prefill);
        }
        addRenderableWidget(this.nameInput);

        // 确认。
        addRenderableWidget(new Button(cx - 100, this.height / 2 + 10, 95, 20,
                Component.translatable("furkin.screen.rename.confirm"),
                btn -> confirm()));

        // 取消。
        addRenderableWidget(new Button(cx + 5, this.height / 2 + 10, 95, 20,
                Component.translatable("furkin.screen.rename.cancel"),
                btn -> returnToParent()));

        setInitialFocus(this.nameInput);
    }

    /** 确认：发改名动作包（留空服务端回退物种名）。 */
    private void confirm() {
        String name = this.nameInput.getValue().trim();
        FurkinNetwork.channel().sendToServer(
                new RecordActionPacket(RecordActionPacket.Action.RENAME, companionId, name, null, true));
        returnToParent();
    }

    /**
     * 返回显式父屏。1.19.2 的官方 {@link Screen#onClose()} 会执行
     * {@code Minecraft.popGuiLayer()}；本屏由 {@code setScreen(...)} 打开，GUI 层栈中
     * 没有绒亲录层，因此必须显式恢复父屏。
     */
    private void returnToParent() {
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        GuiComponent.drawCenteredString(pose, this.font, this.title, this.width / 2, this.height / 2 - 45, 0xFFFFFF);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
