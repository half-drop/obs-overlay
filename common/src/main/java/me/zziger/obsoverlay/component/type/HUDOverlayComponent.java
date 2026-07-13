package me.zziger.obsoverlay.component.type;

import net.minecraft.util.Identifier;

public class HUDOverlayComponent extends DefaultOverlayComponent {
    public HUDOverlayComponent(Identifier id, boolean defaultOverlay, boolean canAutoHide) {
        super(id, defaultOverlay, canAutoHide);
    }

    @Override
    public void beforeBeginDraw() {
    }

    @Override
    public void beforeEndDraw() {
    }
}
