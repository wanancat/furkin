package com.wanancat.furkin.internal.capability;

import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

/**
 * 绒亲能力注册点。
 *
 * <p>持有 {@link Capability}&lt;{@link FurkinData}&gt; 的静态引用，供全模组取用。
 * 注册走 {@link RegisterCapabilitiesEvent}。</p>
 */
public final class FurkinCapability {

    /** 能力标识（ResourceLocation）。 */
    public static final ResourceLocation ID = new ResourceLocation(FurkinMod.MODID, "furkin_data");

    /** 能力实例。 */
    public static final Capability<FurkinData> FURKIN_DATA = CapabilityManager.get(new CapabilityToken<>() {});

    private FurkinCapability() {
    }

    /** 在 {@link RegisterCapabilitiesEvent} 中调用，注册能力。 */
    public static void register(RegisterCapabilitiesEvent event) {
        event.register(FurkinData.class);
    }
}
