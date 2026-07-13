package me.zziger.obsoverlay;

import me.zziger.obsoverlay.mixin.accessor.GuiRenderStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class GuiOverlayManager {
    private static final Set<Object> OVERLAY_LAYERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> HIDDEN_LAYERS = Collections.newSetFromMap(new IdentityHashMap<>());

    private GuiOverlayManager() {
    }

    public static void begin(boolean hidden) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return;
        GuiRenderState state = client.gameRenderer.gameRenderState().guiRenderState;
        state.nextStratum();
        var layers = ((GuiRenderStateAccessor) state).obsOverlay$getRootLayers();
        Object layer = layers.getLast();
        (hidden ? HIDDEN_LAYERS : OVERLAY_LAYERS).add(layer);
    }

    public static void end() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return;
        client.gameRenderer.gameRenderState().guiRenderState.nextStratum();
    }

    public static boolean isOverlayLayer(Object layer) {
        return OVERLAY_LAYERS.contains(layer);
    }

    public static boolean isHiddenLayer(Object layer) {
        return HIDDEN_LAYERS.contains(layer);
    }

    public static void clear() {
        OVERLAY_LAYERS.clear();
        HIDDEN_LAYERS.clear();
    }
}
