package me.zziger.obsoverlay;

import me.zziger.obsoverlay.mixin.accessor.GuiRenderStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

public final class GuiOverlayManager {
    private static final Set<Object> OVERLAY_LAYERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> HIDDEN_LAYERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Deque<Boolean> HIDDEN_SCOPES = new ArrayDeque<>();
    private static GuiRenderState activeState;

    private GuiOverlayManager() {
    }

    public static void begin(boolean hidden) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return;
        begin(client.gameRenderer.gameRenderState().guiRenderState, hidden);
    }

    static void begin(GuiRenderState state, boolean hidden) {
        activeState = state;
        // A nested component inherits its enclosing screen's policy. Hidden content
        // stays hidden even when an inner component only requests an OBS overlay.
        HIDDEN_SCOPES.addLast(hidden || Boolean.TRUE.equals(HIDDEN_SCOPES.peekLast()));
        state.nextStratum();
    }

    public static Boolean currentHiddenPolicy() {
        return HIDDEN_SCOPES.peekLast();
    }

    public static void runWithPolicy(Boolean hidden, Runnable draw) {
        if (hidden == null) {
            draw.run();
            return;
        }
        begin(hidden);
        try {
            draw.run();
        } finally {
            end();
        }
    }

    public static void end() {
        if (HIDDEN_SCOPES.isEmpty() || activeState == null) return;
        HIDDEN_SCOPES.removeLast();
        activeState.nextStratum();
        if (HIDDEN_SCOPES.isEmpty()) activeState = null;
    }

    public static void onNewStratum(GuiRenderState state) {
        if (state != activeState || HIDDEN_SCOPES.isEmpty()) return;
        var layers = ((GuiRenderStateAccessor) state).obsOverlay$getRootLayers();
        Object layer = layers.getLast();
        (HIDDEN_SCOPES.getLast() ? HIDDEN_LAYERS : OVERLAY_LAYERS).add(layer);
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
        HIDDEN_SCOPES.clear();
        activeState = null;
    }
}
