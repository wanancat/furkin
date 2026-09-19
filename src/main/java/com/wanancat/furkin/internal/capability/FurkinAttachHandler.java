package com.wanancat.furkin.internal.capability;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把 {@link FurkinData} 能力 attach 到实体。
 *
 * <p>attach 目标：{@link LivingEntity}（所有可成为伴侣的生物实体）。
 * 是否为「可契约物种」由注册表判定，能力本身对所有活体开放挂载，
 * 未契约时 state 恒为 {@code WILD}。</p>
 */
@Mod.EventBusSubscriber(modid = "furkin", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FurkinAttachHandler {

    private FurkinAttachHandler() {
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof LivingEntity) {
            event.addCapability(FurkinCapability.ID, new FurkinProvider());
        }
    }

    /** 便捷读取：从实体取能力，实体无能力或非活体返回 null。 */
    @SuppressWarnings("unchecked")
    public static FurkinData getData(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return null;
        }
        ICapabilityProvider provider = living;
        var cap = provider.getCapability((Capability<FurkinData>) FurkinCapability.FURKIN_DATA).orElse(null);
        return cap;
    }
}
