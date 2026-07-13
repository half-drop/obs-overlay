package me.zziger.obsoverlay;

import net.minecraft.client.gui.screens.Screen;

public class ScreenOverlayRenderer {

    public static void beforeScreenRender(Screen instance) {
        boolean overlay = OBSOverlayConfig.isScreenOverlayed(instance);
        if (overlay) {
            GuiOverlayManager.begin(false);
        }
    }

    public static void afterScreenRender(Screen instance) {
        boolean overlay = OBSOverlayConfig.isScreenOverlayed(instance);
        if (overlay) {
            GuiOverlayManager.end();
        }
    }
}
