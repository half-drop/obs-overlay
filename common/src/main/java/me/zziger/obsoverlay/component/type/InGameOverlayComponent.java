package me.zziger.obsoverlay.component.type;

import me.zziger.obsoverlay.OverlayFramebufferType;
import net.minecraft.util.Identifier;

public class InGameOverlayComponent extends DefaultOverlayComponent {
    public InGameOverlayComponent(Identifier id, boolean defaultOverlay, boolean canAutoHide) {
        super(id, defaultOverlay, canAutoHide);
    }

    @Override
    public void beforeBeginDraw() {
    }

    @Override
    public void beforeEndDraw() {
    }

    @Override
    public OverlayFramebufferType getFramebufferType() {
        return OverlayFramebufferType.DEPTH;
    }
}
