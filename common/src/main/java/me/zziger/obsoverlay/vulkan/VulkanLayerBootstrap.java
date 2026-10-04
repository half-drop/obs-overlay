package me.zziger.obsoverlay.vulkan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import me.zziger.obsoverlay.error.OverlayHookException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkLayerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Runs before Minecraft creates the Vulkan loader or an instance. */
public final class VulkanLayerBootstrap {
    public static final String LAYER_NAME = "VK_LAYER_HALFDROP_obs_overlay";
    private static final Logger LOGGER = LoggerFactory.getLogger("obs_overlay");
    private static boolean attempted;
    // Keep the bridge loaded for as long as Vulkan may dispatch through the layer.
    private static volatile VulkanNative library;
    private static volatile String failureReason = "The Vulkan layer has not been prepared";

    private VulkanLayerBootstrap() {
    }

    public static synchronized void prepare() {
        if (attempted) return;
        attempted = true;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows");
        boolean linuxTest = os.startsWith("linux") && Boolean.getBoolean("obs_overlay.vulkan.linuxTest");
        if ((!windows && !linuxTest) || !(architecture.equals("amd64") || architecture.equals("x86_64"))) {
            failureReason = "OBS Overlay Vulkan requires Windows x86_64";
            return;
        }

        try {
            String filename = windows ? "obs_overlay_vulkan.dll" : "libobs_overlay_vulkan.so";
            String platform = windows ? "windows-x86_64" : "linux-x86_64";
            String override = System.getProperty("obs_overlay.vulkan.library");
            byte[] contents;
            if (override != null && !override.isBlank()) {
                contents = Files.readAllBytes(Path.of(override).toRealPath());
            } else {
                String resource = "/assets/obs_overlay/native/" + platform + "/" + filename;
                try (InputStream stream = VulkanLayerBootstrap.class.getResourceAsStream(resource)) {
                    if (stream == null) throw new IOException("Missing packaged Vulkan layer: " + resource);
                    contents = stream.readAllBytes();
                }
            }

            byte[] expectedDigest = MessageDigest.getInstance("SHA-256").digest(contents);
            String digest = HexFormat.of().formatHex(expectedDigest);
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "obs-overlay-vulkan", digest)
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path nativeFile = directory.resolve(filename);
            // Normalize test overrides too, so JNA and the Vulkan loader open the same file.
            writeOnce(nativeFile, contents);
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(nativeFile)), expectedDigest)) {
                throw new IOException("The extracted Vulkan layer does not match the packaged library");
            }

            VulkanNative loaded = Native.load(nativeFile.toString(), VulkanNative.class,
                    Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name()));
            if (loaded.obsvkGetAbiVersion() != VulkanNative.ABI_VERSION) {
                throw new IOException("The Vulkan layer and Java bridge have different ABI versions");
            }
            writeManifest(directory, nativeFile);
            addLayerPath(directory, windows);
            library = loaded;
            failureReason = "";
            LOGGER.info("Prepared OBS Overlay Vulkan layer{}", linuxTest ? " for Linux testing" : "");
        } catch (Exception | LinkageError error) {
            failureReason = "Failed to prepare OBS Overlay Vulkan layer: " + error.getMessage();
            LOGGER.error(failureReason, error);
        }
    }

    public static PointerBuffer enabledLayers(PointerBuffer original) {
        if (library == null) return original;
        if (original != null) {
            for (int i = original.position(); i < original.limit(); i++) {
                long address = original.get(i);
                if (address != 0 && LAYER_NAME.equals(MemoryUtil.memUTF8(address))) return original;
            }
        }
        if (!isLayerDiscoverable()) {
            failureReason = "The Vulkan loader did not discover " + LAYER_NAME + "; restart Minecraft after checking its layer configuration";
            LOGGER.error(failureReason);
            return original;
        }

        // The caller's VulkanInstance constructor owns this frame until vkCreateInstance returns.
        MemoryStack stack = MemoryStack.stackGet();
        PointerBuffer layers = stack.mallocPointer((original == null ? 0 : original.remaining()) + 1);
        if (original != null) {
            for (int i = original.position(); i < original.limit(); i++) layers.put(original.get(i));
        }
        layers.put(stack.UTF8(LAYER_NAME));
        layers.flip();
        return layers;
    }

    static VulkanNative requireLibrary() {
        VulkanNative result = library;
        if (result == null) throw new OverlayHookException(failureReason);
        return result;
    }

    public static String failureReason() {
        return failureReason;
    }

    private static boolean isLayerDiscoverable() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            if (VK12.vkEnumerateInstanceLayerProperties(count, null) != VK12.VK_SUCCESS) return false;
            VkLayerProperties.Buffer layers = VkLayerProperties.calloc(count.get(0), stack);
            int status = VK12.vkEnumerateInstanceLayerProperties(count, layers);
            if (status != VK12.VK_SUCCESS && status != VK12.VK_INCOMPLETE) return false;
            for (int i = 0; i < count.get(0); i++) {
                if (LAYER_NAME.equals(layers.get(i).layerNameString())) return true;
            }
            return false;
        }
    }

    private static void writeManifest(Path directory, Path nativeFile) throws IOException {
        JsonObject layer = new JsonObject();
        layer.addProperty("name", LAYER_NAME);
        layer.addProperty("type", "GLOBAL");
        layer.addProperty("library_path", nativeFile.toAbsolutePath().toString());
        layer.addProperty("api_version", "1.3.0");
        layer.addProperty("implementation_version", Integer.toString(VulkanNative.ABI_VERSION));
        layer.addProperty("description", "OBS Overlay private HUD compositor");
        JsonObject functions = new JsonObject();
        functions.addProperty("vkNegotiateLoaderLayerInterfaceVersion", "obsvkNegotiateLoaderLayerInterfaceVersion");
        layer.add("functions", functions);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("file_format_version", "1.2.0");
        manifest.add("layer", layer);
        Path target = directory.resolve("obs_overlay_vulkan.json");
        byte[] contents = new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8);
        if (Files.exists(target) && MessageDigest.isEqual(Files.readAllBytes(target), contents)) return;
        Path temporary = Files.createTempFile(directory, "obs-overlay-manifest-", ".tmp");
        try {
            Files.write(temporary, contents);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeOnce(Path target, byte[] contents) throws IOException {
        if (Files.exists(target)) {
            verifyContents(target, contents);
            return;
        }
        Path temporary = Files.createTempFile(target.getParent(), "obs-overlay-", ".tmp");
        try {
            Files.write(temporary, contents);
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                }
            } catch (IOException failure) {
                // Another client may have installed the same version while we were writing it.
                if (!Files.exists(target)) throw failure;
                verifyContents(target, contents);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verifyContents(Path target, byte[] contents) throws IOException {
        if (!MessageDigest.isEqual(Files.readAllBytes(target), contents)) {
            throw new IOException("Existing Vulkan layer file does not match this version: " + target.getFileName());
        }
    }

    private static void addLayerPath(Path directory, boolean windows) {
        Environment environment = windows ? new WindowsEnvironment() : new PosixEnvironment();
        String primary = environment.get("VK_LAYER_PATH");
        String variable = primary == null ? "VK_ADD_LAYER_PATH" : "VK_LAYER_PATH";
        String current = primary == null ? environment.get(variable) : primary;
        String extra = directory.toString();
        if (current != null) {
            for (String entry : current.split(Pattern.quote(File.pathSeparator))) {
                if (windows ? entry.equalsIgnoreCase(extra) : entry.equals(extra)) return;
            }
        }
        environment.set(variable, current == null || current.isEmpty() ? extra : current + File.pathSeparator + extra);
    }

    private interface Environment {
        String get(String name);

        void set(String name, String value);
    }

    private static final class WindowsEnvironment implements Environment {
        private final WindowsEnvironmentApi api = Native.load("kernel32", WindowsEnvironmentApi.class);

        @Override
        public String get(String name) {
            WString key = new WString(name);
            api.SetLastError(0);
            int size = api.GetEnvironmentVariableW(key, null, 0);
            if (size == 0) return api.GetLastError() == 203 ? null : "";
            char[] buffer = new char[size];
            int length = api.GetEnvironmentVariableW(key, buffer, buffer.length);
            if (length >= buffer.length) throw new IllegalStateException("Vulkan layer environment changed while reading it");
            return new String(buffer, 0, length);
        }

        @Override
        public void set(String name, String value) {
            if (api.SetEnvironmentVariableW(new WString(name), new WString(value)) == 0) {
                throw new IllegalStateException("Could not update " + name + ": Windows error " + api.GetLastError());
            }
        }
    }

    public interface WindowsEnvironmentApi extends StdCallLibrary {
        int GetEnvironmentVariableW(WString name, char[] buffer, int size);

        int SetEnvironmentVariableW(WString name, WString value);

        void SetLastError(int error);

        int GetLastError();
    }

    private static final class PosixEnvironment implements Environment {
        private final PosixEnvironmentApi api = Native.load("c", PosixEnvironmentApi.class);

        @Override
        public String get(String name) {
            Pointer value = api.getenv(name);
            return value == null ? null : value.getString(0, StandardCharsets.UTF_8.name());
        }

        @Override
        public void set(String name, String value) {
            if (api.setenv(name, value, 1) != 0) throw new IllegalStateException("Could not update " + name);
        }
    }

    public interface PosixEnvironmentApi extends Library {
        Pointer getenv(String name);

        int setenv(String name, String value, int overwrite);
    }
}
