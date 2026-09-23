package com.wanancat.furkin.internal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wanancat.furkin.internal.network.ConfirmContractPacket;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 契约命名输入框 —— 契约第一步通过后弹出，让玩家为绒亲命名。
 *
 * <p>确认：发 {@link ConfirmContractPacket}（实体 ID + 名字）真正落契约；
 * 取消 / ESC：直接关闭，不发任何包 → 放弃本次契约（契约不消耗）。</p>
 *
 * <p>留空确认 → 服务端回退物种名（猫 / 狗）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ContractNameScreen extends Screen {

    /** 待契约实体 ID。 */
    private final int entityId;

    private EditBox nameInput;
    private Button confirmBtn;

    private ContractNameScreen(int entityId) {
        super(Component.translatable("furkin.screen.contract_name.title"));
        this.entityId = entityId;
    }

    /** 打开命名输入框（客户端专用入口）。 */
    public static void open(int entityId) {
        Minecraft.getInstance().setScreen(new ContractNameScreen(entityId));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;

        // 输入框。
        this.nameInput = new EditBox(this.font, cx - 100, this.height / 2 - 20, 200, 20,
                Component.translatable("furkin.screen.contract_name.hint"));
        this.nameInput.setMaxLength(32);
        String hint = Component.translatable("furkin.screen.contract_name.hint").getString();
        this.nameInput.setSuggestion(hint);
        this.nameInput.setResponder(value -> this.nameInput.setSuggestion(value.isEmpty() ? hint : null));
        addRenderableWidget(this.nameInput);

        // 确认按钮。
        this.confirmBtn = new Button(cx - 100, this.height / 2 + 10, 95, 20,
                Component.translatable("furkin.screen.contract_name.confirm"),
                btn -> confirm());
        addRenderableWidget(this.confirmBtn);

        // 取消按钮。
        addRenderableWidget(new Button(cx + 5, this.height / 2 + 10, 95, 20,
                Component.translatable("furkin.screen.contract_name.cancel"),
                btn -> onClose()));

        setInitialFocus(this.nameInput);
    }

    /** 确认：发确认契约包（名字留空服务端回退物种名）。 */
    private void confirm() {
        String name = this.nameInput.getValue().trim();
        FurkinNetwork.channel().sendToServer(new ConfirmContractPacket(entityId, name));
        onClose();
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        GuiComponent.drawCenteredString(pose, this.font, this.title, this.width / 2, this.height / 2 - 45, 0xFFFFFF);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        // 不暂停游戏（命名是轻量浮层）。
        return false;
    }

    @Override
    public void onClose() {
        // 关闭即放弃契约（不发送确认包）。
        super.onClose();
    }
}
