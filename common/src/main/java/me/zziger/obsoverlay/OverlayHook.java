package me.zziger.obsoverlay;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import me.zziger.obsoverlay.error.OverlayHookException;
import me.zziger.obsoverlay.modules.Kernel32;
import me.zziger.obsoverlay.modules.MinHook;
import net.minecraft.client.Minecraft;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;


public class OverlayHook {
    private static final int MH_OK = 0;
    private static final int MH_ERROR_ALREADY_INITIALIZED = 1;
    private static boolean libraryInitialized = false;
    private static final List<Handler> handlerList = new CopyOnWriteArrayList<>();
    private static MinHook minHook;
    // JNA callbacks must remain strongly reachable while native code can call them.
    private static MinHook.wglSwapBuffers swapCallback;
    private static volatile Function originalSwapBuffers;
    private static Pointer hookTarget;
    private static boolean handlerFailureLogged;

    public interface Handler {
        void run();
    }

    public static void subscribe(Handler handler) {
        handlerList.add(handler);
    }

    public static void unsubscribe(Handler handler) {
        handlerList.remove(handler);
    }

    public static MinHook getMinHook() {
        if (minHook == null) return minHook = Native.load("MinHook", MinHook.class);
        return minHook;
    }

    public static synchronized void init() {
        if (libraryInitialized) return;
        initLibrary();
        initHook();
        libraryInitialized = true;
    }

    private static void initLibrary() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new OverlayHookException("OBS Overlay is only supported on Windows");
        }

        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        if (arch.contains("aarch")) {
            throw new OverlayHookException("OBS Overlay is only supported on x64 and x86 systems");
        }

        boolean is64 = arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64") || arch.equals("ia64");
        File nativeDir = new File(Minecraft.getInstance().gameDirectory, "native");
        File copyLibFile = new File(nativeDir, "MinHook.dll");

        // A failed hook installation can be retried without rewriting an already loaded DLL.
        if (minHook != null) return;

        try (InputStream libFile = OBSOverlay.class.getResourceAsStream(is64 ? "/lib/MinHook.x64.dll" : "/lib/MinHook.x86.dll")) {
            if (libFile == null) {
                throw new OverlayHookException("Failed to get MinHook DLL");
            }
            Files.createDirectories(nativeDir.toPath());
            Files.copy(libFile, copyLibFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new OverlayHookException("Failed to copy dependency DLL: " + e.getMessage());
        }

        System.setProperty("jna.library.path", nativeDir.getAbsolutePath());
        OBSOverlay.LOGGER.info("Copied dependency DLL successfully");
    }

    private static void initHook() {
        // A native rollback failure must keep its callback alive until removal succeeds.
        if (hookTarget != null) removeUnfinishedHook();

        Pointer module = Kernel32.INSTANCE.GetModuleHandleA("opengl32.dll");
        if (module == null) {
            throw new OverlayHookException("OpenGL is not loaded. Select the OpenGL rendering backend and restart Minecraft.");
        }
        Pointer proc = Kernel32.INSTANCE.GetProcAddress(module, "wglSwapBuffers");
        if (proc == null) {
            throw new OverlayHookException("Failed to locate OpenGL wglSwapBuffers");
        }

        boolean hookCreated = false;
        try {
            MinHook minhook = getMinHook();
            int initializeStatus = minhook.MH_Initialize();
            if (initializeStatus != MH_OK && initializeStatus != MH_ERROR_ALREADY_INITIALIZED) {
                checkStatus("initialize", initializeStatus);
            }
            PointerByReference reference = new PointerByReference();
            swapCallback = OverlayHook::onSwapBuffers;
            checkStatus("create wglSwapBuffers hook", minhook.MH_CreateHook(proc, swapCallback, reference));
            hookCreated = true;
            hookTarget = proc;
            if (reference.getValue() == null) {
                throw new OverlayHookException("MinHook did not provide the original wglSwapBuffers function");
            }
            originalSwapBuffers = Function.getFunction(reference.getValue(), Function.ALT_CONVENTION);
            checkStatus("enable wglSwapBuffers hook", minhook.MH_EnableHook(proc));
        } catch (RuntimeException | Error e) {
            if (hookCreated) {
                // Keep MinHook itself initialized: other native users may share the library.
                try {
                    removeUnfinishedHook();
                } catch (RuntimeException | Error cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            } else {
                swapCallback = null;
                originalSwapBuffers = null;
            }
            OBSOverlay.LOGGER.error("Failed to initialize MinHook", e);
            throw e;
        }
    }

    private static void removeUnfinishedHook() {
        // MH_RemoveHook also disables the hook if it was enabled.
        checkStatus("remove unfinished wglSwapBuffers hook", minHook.MH_RemoveHook(hookTarget));
        hookTarget = null;
        swapCallback = null;
        originalSwapBuffers = null;
    }

    private static boolean onSwapBuffers(Pointer hDc) {
        try {
            handlerList.forEach(Handler::run);
            handlerFailureLogged = false;
        } catch (Throwable e) {
            // An overlay failure must not prevent the original native buffer swap.
            if (!handlerFailureLogged) {
                handlerFailureLogged = true;
                OBSOverlay.LOGGER.error("Failed to render OBS Overlay during buffer swap", e);
            }
        }
        return (boolean) originalSwapBuffers.invoke(Boolean.class, new Object[]{hDc});
    }

    private static void checkStatus(String operation, int status) {
        if (status != MH_OK) {
            throw new OverlayHookException("Failed to " + operation + " (MinHook status " + status + ")");
        }
    }
}
