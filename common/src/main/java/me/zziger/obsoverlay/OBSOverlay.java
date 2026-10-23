package me.zziger.obsoverlay;

import me.zziger.obsoverlay.api.IOverlayAPI;
import me.zziger.obsoverlay.api.impl.DummyOverlayAPI;
import me.zziger.obsoverlay.api.impl.NormalOverlayAPI;
import me.zziger.obsoverlay.component.AllDefaultOverlayComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OBSOverlay {
    public static final String MOD_ID = "obs_overlay";
    public static final Logger LOGGER = LoggerFactory.getLogger("obs_overlay");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static OverlayRenderer renderer = null;
    private static IOverlayAPI api = new DummyOverlayAPI();
    private static boolean initialized = false;

    public static boolean getIsInitialized() {
        return initialized;
    }

    /**
     * This method can be invoked even if mode is not initialized.
     * Call this method each time you need the API, do not cache the result.
     * If the library is not initialized - it will return a dummy API instance, which does nothing
     * @return API instance
     */
    public static IOverlayAPI getAPI() {
        return api;
    }

    /**
     * This method can return internal renderer instance, null if not initialized
     * If possible, better use {@link #getAPI()} instead.
     * @return Internal renderer instance, or null if renderer is not initialized
     */
    @Nullable
    public static OverlayRenderer getRenderer() {
        return renderer;
    }

    public static void init() {
        OBSOverlayConfig.init();
        AllDefaultOverlayComponents.init();
    }

    public static void initRender() {
        shutdownRender();
        try {
            renderer = OverlayRenderer.create();
            api = new NormalOverlayAPI(renderer);
            initialized = true;
        } catch (Throwable e) {
            LOGGER.error("Failed to initialize OBS Overlay render", e);
            shutdownRender();
            OverlayUtils.showToast(Component.literal("Failed to initialize OBS Overlay"),
                    Component.literal(errorMessage(e)));
        }
    }

    public static void handleRenderFailure(RuntimeException error) {
        LOGGER.error("OBS Overlay rendering disabled after a backend error", error);
        shutdownRender();
        OverlayUtils.showToast(Component.literal("OBS Overlay rendering disabled"),
                Component.literal(errorMessage(error)));
    }

    public static void shutdownRender() {
        OverlayRenderer previous = renderer;
        renderer = null;
        initialized = false;
        api = new DummyOverlayAPI();
        GuiOverlayManager.clear();
        if (previous != null) {
            try {
                previous.close();
            } catch (RuntimeException error) {
                LOGGER.error("Failed to close OBS Overlay render resources", error);
            }
        }
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }
}
