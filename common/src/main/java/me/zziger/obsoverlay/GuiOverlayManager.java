package me.zziger.obsoverlay;

import me.zziger.obsoverlay.mixin.accessor.GameRendererAccessor;
import me.zziger.obsoverlay.mixin.accessor.GuiRenderStateAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.render.state.GuiRenderState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class GuiOverlayManager {
    private static final Set<Object> OVERLAY_LAYERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> HIDDEN_LAYERS = Collections.newSetFromMap(new IdentityHashMap<>());

    private GuiOverlayManager() {
    }

    public static void begin(boolean hidden) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null) return;
        GuiRenderState state = ((GameRendererAccessor) client.gameRenderer).obsOverlay$getGuiState();
        state.createNewRootLayer();
        var layers = ((GuiRenderStateAccessor) state).obsOverlay$getRootLayers();
        Object layer = layers.getLast();
        (hidden ? HIDDEN_LAYERS : OVERLAY_LAYERS).add(layer);
    }

    public static void end() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null) return;
        ((GameRendererAccessor) client.gameRenderer).obsOverlay$getGuiState().createNewRootLayer();
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
