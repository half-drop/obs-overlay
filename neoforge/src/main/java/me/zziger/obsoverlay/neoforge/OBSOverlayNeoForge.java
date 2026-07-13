package me.zziger.obsoverlay.neoforge;

import me.zziger.obsoverlay.OBSOverlayConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import me.zziger.obsoverlay.OBSOverlay;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(OBSOverlay.MOD_ID)
public final class OBSOverlayNeoForge {
    public OBSOverlayNeoForge(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::clientSetup);
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (client, parent) -> OBSOverlayConfig.getScreenSupplier(parent).get());
    }

    private void clientSetup(FMLClientSetupEvent event) {
        OBSOverlay.init();
    }
}
