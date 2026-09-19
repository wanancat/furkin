package com.wanancat.furkin.internal.client;

import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.RecordActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 * 预填当前名字。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RenameScreen extends Screen {

    /** 绒亲身份 UUID。 */
    private final UUID companionId;

    private EditBox nameInput;

    private RenameScreen(UUID companionId, String currentName) {
        super(Component.translatable("furkin.screen.rename.title"));
        this.companionId = companionId;
        this.prefill = currentName;
    }

    /** 预填名字（空则无预填）。 */
    private final String prefill;

    /** 打开改名输入框（客户端专用入口）。 */
    public static void open(UUID companionId, String currentName) {
        Minecraft.getInstance().setScreen(new RenameScreen(companionId, currentName));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;

        this.nameInput = new EditBox(this.font, cx - 100, this.height / 2 - 20, 200, 20,
                Component.translatable("furkin.screen.rename.hint"));
        this.nameInput.setMaxLength(32);
        this.nameInput.setHint(Component.translatable("furkin.screen.rename.hint"));
        if (prefill != null && !prefill.isEmpty()) {
            this.nameInput.setValue(prefill);
        }
        addRenderableWidget(this.nameInput);

        // 确认。
        addRenderableWidget(Button.builder(Component.translatable("furkin.screen.rename.confirm"),
                        btn -> confirm())
                .bounds(cx - 100, this.height / 2 + 10, 95, 20)
                .build());

        // 取消。
        addRenderableWidget(Button.builder(Component.translatable("furkin.screen.rename.cancel"),
                        btn -> onClose())
                .bounds(cx + 5, this.height / 2 + 10, 95, 20)
                .build());

        setInitialFocus(this.nameInput);
    }

    /** 确认：发改名动作包（留空服务端回退物种名）。 */
    private void confirm() {
        String name = this.nameInput.getValue().trim();
        FurkinNetwork.channel().sendToServer(
                new RecordActionPacket(RecordActionPacket.Action.RENAME, companionId, name));
        onClose();
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);
        gui.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 45, 0xFFFFFF);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
