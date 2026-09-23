package com.wanancat.furkin.internal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix4f;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinAttachHandler;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 绒亲头顶状态图标渲染（纯客户端）。
 *
 * <p>在 {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES} 阶段（世界矩阵干净），
 * 遍历在场已契约绒亲，在其头顶画一个面向玩家的绒球标识
 * （{@code furkin_mark.png}），作为唯一视觉辨识（设计稿 §3.4.2，
 * 客户端配置 {@code showStatusIcon} 控制开关）。</p>
 *
 * <p><b>版本差异</b>：1.20.1 原实现使用 {@code AFTER_ENTITIES}，1.19.2 没有该阶段。
 * 这里选取 {@code AFTER_PARTICLES}：它位于实体、方块实体和粒子之后，是最接近
 * 「实体绘制完成」的稳定节点。若实机遮挡关系不理想，后备阶段为
 * {@code AFTER_WEATHER}。</p>
 *
 * <p><b>端位纪律</b>：本类只在 {@code Dist.CLIENT} 加载，绝不进入逻辑端。</p>
 */
@Mod.EventBusSubscriber(modid = FurkinMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FurkinStatusIconRenderer {

    /** 头顶图标的贴图。 */
    private static final ResourceLocation MARK_TEXTURE =
            new ResourceLocation(FurkinMod.MODID, "textures/entity/furkin_mark.png");

    /** 图标边长（世界单位）。 */
    private static final float ICON_SIZE = 0.5f;

    /** 图标悬浮于头顶的高度偏移（世界单位）。抬到名牌文字之上，避免图标与名字重叠。 */
    private static final float Y_OFFSET = 0.8f;

    private FurkinStatusIconRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (!FurkinClientConfig.SHOW_STATUS_ICON.get()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        MultiBufferSource buffers = mc.renderBuffers().bufferSource();
        Vec3 cam = event.getCamera().getPosition();

        // 遍历在场实体，只画「已契约」的绒亲。
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            FurkinData data = FurkinAttachHandler.getData(living);
            if (data == null || !data.isCompanion()) {
                continue;
            }

            // 距离判定：与原版名牌同进同出（读实体自身 NAMETAG_DISTANCE 属性，
            // 判定式与 ForgeHooksClient.isNameplateInRenderDistance 一致）。
            if (!isWithinNameplateDistance(living)) {
                continue;
            }

            renderIcon(pose, buffers, living, cam);
        }
    }

    /**
     * 是否处于名牌渲染距离内。与原版名牌判定同源：
     * 读实体自身 {@code ForgeMod.NAMETAG_DISTANCE} 属性，比较玩家到实体的平方距离。
     * 与原版 {@code isNameplateInRenderDistance} 口径一致 ⇒ 图标与名牌严格同进同出。
     */
    private static boolean isWithinNameplateDistance(LivingEntity living) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        if (living.getAttribute(ForgeMod.NAMETAG_DISTANCE.get()) == null) {
            return false;
        }
        double dist = living.getAttributeValue(ForgeMod.NAMETAG_DISTANCE.get());
        return living.distanceToSqr(mc.player) <= dist * dist;
    }

    private static void renderIcon(PoseStack pose, MultiBufferSource buffers, LivingEntity living, Vec3 cam) {
        float y = living.getBbHeight() + Y_OFFSET;
        // 平滑插值（实体渲染用 lerp 位置，避免抖动）。
        float partialTicks = Minecraft.getInstance().getFrameTime();
        double dx = living.xOld + (living.getX() - living.xOld) * partialTicks - cam.x;
        double dy = living.yOld + (living.getY() - living.yOld) * partialTicks + y - cam.y;
        double dz = living.zOld + (living.getZ() - living.zOld) * partialTicks - cam.z;

        pose.pushPose();
        pose.translate(dx, dy, dz);
        // Billboard：面向相机。
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());

        float half = ICON_SIZE / 2f;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(MARK_TEXTURE));
        Matrix4f mat = pose.last().pose();
        // entityTranslucent 顶点格式 = NEW_ENTITY（位置+颜色+UV+overlay+光照+法线），须逐项填满。
        int fullBright = LightTexture.FULL_BRIGHT;

        vc.vertex(mat, -half, -half, 0f).color(255, 255, 255, 255).uv(0f, 0f).overlayCoords(0, 10).uv2(fullBright).normal(0f, 0f, 1f).endVertex();
        vc.vertex(mat, -half, half, 0f).color(255, 255, 255, 255).uv(0f, 1f).overlayCoords(0, 10).uv2(fullBright).normal(0f, 0f, 1f).endVertex();
        vc.vertex(mat, half, half, 0f).color(255, 255, 255, 255).uv(1f, 1f).overlayCoords(0, 10).uv2(fullBright).normal(0f, 0f, 1f).endVertex();
        vc.vertex(mat, half, -half, 0f).color(255, 255, 255, 255).uv(1f, 0f).overlayCoords(0, 10).uv2(fullBright).normal(0f, 0f, 1f).endVertex();

        pose.popPose();
    }
}
