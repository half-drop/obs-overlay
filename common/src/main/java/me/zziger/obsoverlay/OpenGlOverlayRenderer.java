package me.zziger.obsoverlay;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import me.zziger.obsoverlay.error.OverlayHookException;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

final class OpenGlOverlayRenderer extends OverlayRenderer {
    private static final String VERTEX_SHADER = """
            #version 150
            out vec2 texCoord;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 150
            uniform sampler2D ColorSampler;
            uniform sampler2D DepthSampler;
            uniform int OverrideDepth;
            in vec2 texCoord;
            out vec4 fragColor;
            void main() {
                vec4 color = texture(ColorSampler, texCoord);
                float depth = texture(DepthSampler, texCoord).r;
                if (color.a == 0.0) discard;
                fragColor = color;
                gl_FragDepth = OverrideDepth == 1 && depth < 1.0 ? 0.0 : depth;
            }
            """;

    private int lastFramebuffer;
    private boolean framebufferOverridden;
    private int shaderProgram;
    private int vertexArray;
    private boolean closed;
    private RenderTarget depthBackupFramebuffer;
    private final OverlayHook.Handler swapHandler = this::renderFrame;
    private final Map<OverlayFramebufferType, OverlayFramebuffer> framebuffers = new EnumMap<>(OverlayFramebufferType.class);
    private final Map<RenderTarget, Integer> glFramebuffers = new java.util.IdentityHashMap<>();

    OpenGlOverlayRenderer() {
        String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
        if (!"OpenGL".equals(backend)) {
            throw new OverlayHookException("OBS Overlay requires OpenGL (current backend: " + backend
                    + "). Set Graphics API to Prefer OpenGL in Video Settings and restart Minecraft.");
        }
        OverlayHook.init();
        try {
            initializeFramebuffers();
            initializeShader();
            // Subscribe only after every GL resource is ready; use the same handler on close.
            OverlayHook.subscribe(swapHandler);
        } catch (RuntimeException | Error e) {
            try {
                close();
            } catch (Throwable cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        OverlayHook.unsubscribe(swapHandler);
        framebufferOverridden = false;
        framebuffers.values().forEach(framebuffer -> framebuffer.object.destroyBuffers());
        framebuffers.clear();
        if (depthBackupFramebuffer != null) depthBackupFramebuffer.destroyBuffers();
        depthBackupFramebuffer = null;
        if (shaderProgram != 0) GL20.glDeleteProgram(shaderProgram);
        shaderProgram = 0;
        if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray);
        vertexArray = 0;
        glFramebuffers.values().forEach(GL30::glDeleteFramebuffers);
        glFramebuffers.clear();
    }

    private void initializeFramebuffers() {
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();
        depthBackupFramebuffer = new TextureTarget("OBS Overlay depth backup", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        framebuffers.put(OverlayFramebufferType.DEPTH,
                new OverlayFramebuffer(new TextureTarget("OBS Overlay depth", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)));
        framebuffers.put(OverlayFramebufferType.NORMAL,
                new OverlayFramebuffer(new TextureTarget("OBS Overlay GUI", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)));
        beginFrame();
    }

    private void initializeShader() {
        int vertex = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = 0;
        try {
            fragment = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            shaderProgram = GL20.glCreateProgram();
            GL20.glAttachShader(shaderProgram, vertex);
            GL20.glAttachShader(shaderProgram, fragment);
            GL20.glLinkProgram(shaderProgram);
            if (GL20.glGetProgrami(shaderProgram, GL20.GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException("Failed to link overlay shader: " + GL20.glGetProgramInfoLog(shaderProgram));
            }
            vertexArray = GL30.glGenVertexArrays();
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL_FALSE) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile overlay shader: " + error);
        }
        return shader;
    }

    public RenderTarget getFramebuffer(OverlayFramebufferType type) {
        OverlayFramebuffer framebuffer = framebuffers.get(type);
        return framebuffer == null ? null : framebuffer.object;
    }

    public boolean isFramebufferOverridden() {
        return framebufferOverridden;
    }

    private int getFramebufferId(RenderTarget framebuffer) {
        int id = glFramebuffers.computeIfAbsent(framebuffer, ignored -> GL30.glGenFramebuffers());
        int drawFramebuffer = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL_FRAMEBUFFER, id);
            GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    ((GlTexture) framebuffer.getColorTexture()).glId(), 0);
            if (framebuffer.getDepthTexture() != null) {
                GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D,
                        ((GlTexture) framebuffer.getDepthTexture()).glId(), 0);
            }
        } finally {
            // Looking up the read FBO must not also change the active draw target.
            GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        }
        return id;
    }

    public void backupDepth(boolean overrideDepth) {
        Minecraft client = Minecraft.getInstance();
        GlStateSnapshot state = new GlStateSnapshot();
        try {
            GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, getFramebufferId(depthBackupFramebuffer));
            renderQuad(false, true, overrideDepth, client.gameRenderer.mainRenderTarget());
        } finally {
            state.restore();
        }
    }

    private void backupFramebuffer() {
        lastFramebuffer = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
    }

    public void beginDraw(OverlayFramebufferType type) {
        OverlayFramebuffer framebuffer = framebuffers.get(type);
        if (framebuffer == null) return;
        backupFramebuffer();
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, getFramebufferId(framebuffer.object));
        GlStateManager._enableDepthTest();
        framebuffer.dirty = true;
        framebufferOverridden = true;
    }

    public void beginEmptyDraw() {
        backupFramebuffer();
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        framebufferOverridden = true;
    }

    public void endDraw() {
        // Let the framebuffer mixin accept our restore call, including the default FBO (0).
        framebufferOverridden = false;
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, lastFramebuffer);
    }

    public void onResolutionChanged(Minecraft client) {
        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();
        framebuffers.values().forEach(framebuffer -> framebuffer.object.resize(width, height));
        depthBackupFramebuffer.resize(width, height);
    }

    private void renderQuad(boolean writeDepth, boolean depthTest, boolean overrideDepth, RenderTarget framebuffer) {
        Minecraft client = Minecraft.getInstance();
        if (writeDepth) {
            GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, getFramebufferId(depthBackupFramebuffer));
            GL30.glBlitFramebuffer(0, 0, depthBackupFramebuffer.width, depthBackupFramebuffer.height,
                    0, 0, client.getWindow().getWidth(), client.getWindow().getHeight(),
                    GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        }

        if (depthTest) GL11.glEnable(GL_DEPTH_TEST); else GL11.glDisable(GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL_BLEND);
        GL11.glDisable(GL_CULL_FACE);
        GL11.glDisable(GL_SCISSOR_TEST);
        GL11.glDisable(GL_STENCIL_TEST);
        GL11.glColorMask(true, true, true, true);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        GL11.glViewport(0, 0, client.getWindow().getWidth(), client.getWindow().getHeight());

        GL20.glUseProgram(shaderProgram);
        GL20.glUniform1i(GL20.glGetUniformLocation(shaderProgram, "ColorSampler"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(shaderProgram, "DepthSampler"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(shaderProgram, "OverrideDepth"), overrideDepth ? 1 : 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL_TEXTURE_2D, ((GlTexture) framebuffer.getColorTexture()).glId());
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL_TEXTURE_2D, ((GlTexture) framebuffer.getDepthTexture()).glId());
        GL30.glBindVertexArray(vertexArray);
        GL11.glDrawArrays(GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void beginFrame() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        framebuffers.values().forEach(framebuffer -> {
            encoder.clearColorAndDepthTextures(framebuffer.object.getColorTexture(), new Vector4f(0.0F),
                    framebuffer.object.getDepthTexture(), 0.0);
            framebuffer.dirty = false;
        });
        encoder.clearColorAndDepthTextures(depthBackupFramebuffer.getColorTexture(), new Vector4f(0.0F),
                depthBackupFramebuffer.getDepthTexture(), 0.0);
    }

    private void renderFramebuffer(OverlayFramebufferType type) {
        OverlayFramebuffer framebuffer = framebuffers.get(type);
        if (framebuffer == null || !framebuffer.dirty) return;
        framebuffer.dirty = false;
        GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        renderQuad(type == OverlayFramebufferType.DEPTH, type == OverlayFramebufferType.DEPTH, false, framebuffer.object);
    }

    public void markDirty(OverlayFramebufferType type) {
        OverlayFramebuffer framebuffer = framebuffers.get(type);
        if (framebuffer != null) framebuffer.dirty = true;
    }

    public void renderFrame() {
        // NeoForge's early loading window swaps on a helper thread with a different GL context.
        // Overlay resources belong to Minecraft's render context and must never be used there.
        Minecraft client = Minecraft.getInstance();
        if (closed || client == null || !client.isSameThread() || !RenderSystem.isOnRenderThread()) return;
        GlStateSnapshot state = new GlStateSnapshot();
        try {
            renderFramebuffer(OverlayFramebufferType.DEPTH);
            renderFramebuffer(OverlayFramebufferType.NORMAL);
        } finally {
            state.restore();
        }
    }

    /**
     * The swap-buffer hook runs outside Minecraft's render-pass bookkeeping. Use raw OpenGL calls
     * and restore the actual driver state so RenderSystem's cached state still matches next frame.
     */
    private static final class GlStateSnapshot {
        private final int drawFramebuffer = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readFramebuffer = GL11.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int texture0;
        private final int texture1;
        private final boolean depthTest = GL11.glIsEnabled(GL_DEPTH_TEST);
        private final int drawBufferCount = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
        private final boolean[] blend = new boolean[drawBufferCount];
        private final boolean cull = GL11.glIsEnabled(GL_CULL_FACE);
        private final boolean scissor = GL11.glIsEnabled(GL_SCISSOR_TEST);
        private final boolean stencil = GL11.glIsEnabled(GL_STENCIL_TEST);
        private final boolean depthMask = GL11.glGetBoolean(GL_DEPTH_WRITEMASK);
        // Indexed blend functions/equations are optional on Minecraft's OpenGL 3.3 baseline.
        private final boolean coreIndependentBlend = org.lwjgl.opengl.GL.getCapabilities().OpenGL40;
        private final boolean independentBlend = coreIndependentBlend
                || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_draw_buffers_blend;
        private final int[] blendSrcRgb = new int[independentBlend ? drawBufferCount : 1];
        private final int[] blendDstRgb = new int[blendSrcRgb.length];
        private final int[] blendSrcAlpha = new int[blendSrcRgb.length];
        private final int[] blendDstAlpha = new int[blendSrcRgb.length];
        private final int[] blendEquationRgb = new int[blendSrcRgb.length];
        private final int[] blendEquationAlpha = new int[blendSrcRgb.length];
        private final int[] colorMask = new int[drawBufferCount];
        private final int[] viewport = new int[4];

        private GlStateSnapshot() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            texture0 = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            texture1 = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(activeTexture);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                // Global blend/color-mask writes affect all slots, including unused attachments.
                for (int buffer = 0; buffer < drawBufferCount; buffer++) {
                    blend[buffer] = GL30.glIsEnabledi(GL_BLEND, buffer);
                    GL30.glGetBooleani_v(GL_COLOR_WRITEMASK, buffer, mask);
                    for (int channel = 0; channel < 4; channel++) {
                        if (mask.get(channel) != 0) colorMask[buffer] |= 1 << channel;
                    }
                }

                for (int buffer = 0; buffer < blendSrcRgb.length; buffer++) {
                    blendSrcRgb[buffer] = getBlendParameter(GL14.GL_BLEND_SRC_RGB, buffer);
                    blendDstRgb[buffer] = getBlendParameter(GL14.GL_BLEND_DST_RGB, buffer);
                    blendSrcAlpha[buffer] = getBlendParameter(GL14.GL_BLEND_SRC_ALPHA, buffer);
                    blendDstAlpha[buffer] = getBlendParameter(GL14.GL_BLEND_DST_ALPHA, buffer);
                    blendEquationRgb[buffer] = getBlendParameter(GL20.GL_BLEND_EQUATION_RGB, buffer);
                    blendEquationAlpha[buffer] = getBlendParameter(GL20.GL_BLEND_EQUATION_ALPHA, buffer);
                }

                IntBuffer viewportBuffer = stack.mallocInt(4);
                GL11.glGetIntegerv(GL_VIEWPORT, viewportBuffer);
                for (int i = 0; i < viewport.length; i++) viewport[i] = viewportBuffer.get(i);
            }
        }

        private void restore() {
            GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vertexArray);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL_TEXTURE_2D, texture0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL_TEXTURE_2D, texture1);
            GL13.glActiveTexture(activeTexture);

            setEnabled(GL_DEPTH_TEST, depthTest);
            setEnabled(GL_CULL_FACE, cull);
            setEnabled(GL_SCISSOR_TEST, scissor);
            setEnabled(GL_STENCIL_TEST, stencil);
            GL11.glDepthMask(depthMask);
            for (int buffer = 0; buffer < drawBufferCount; buffer++) {
                if (blend[buffer]) GL30.glEnablei(GL_BLEND, buffer); else GL30.glDisablei(GL_BLEND, buffer);
                int mask = colorMask[buffer];
                GL30.glColorMaski(buffer, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
            }
            for (int buffer = 0; buffer < blendSrcRgb.length; buffer++) {
                if (coreIndependentBlend) {
                    org.lwjgl.opengl.GL40.glBlendEquationSeparatei(buffer,
                            blendEquationRgb[buffer], blendEquationAlpha[buffer]);
                    org.lwjgl.opengl.GL40.glBlendFuncSeparatei(buffer,
                            blendSrcRgb[buffer], blendDstRgb[buffer], blendSrcAlpha[buffer], blendDstAlpha[buffer]);
                } else if (independentBlend) {
                    org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendEquationSeparateiARB(buffer,
                            blendEquationRgb[buffer], blendEquationAlpha[buffer]);
                    org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendFuncSeparateiARB(buffer,
                            blendSrcRgb[buffer], blendDstRgb[buffer], blendSrcAlpha[buffer], blendDstAlpha[buffer]);
                } else {
                    GL20.glBlendEquationSeparate(blendEquationRgb[buffer], blendEquationAlpha[buffer]);
                    GL14.glBlendFuncSeparate(blendSrcRgb[buffer], blendDstRgb[buffer],
                            blendSrcAlpha[buffer], blendDstAlpha[buffer]);
                }
            }
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }

        private int getBlendParameter(int parameter, int buffer) {
            return independentBlend ? GL30.glGetIntegeri(parameter, buffer) : GL11.glGetInteger(parameter);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
        }
    }
}
