package me.zziger.obsoverlay;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import me.zziger.obsoverlay.component.IOverlayComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlBackend;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.GlTextureView;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

public class OverlayRenderer implements Closeable {
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

    public boolean renderingHands;
    private int lastFramebuffer;
    private boolean framebufferOverridden;
    private int shaderProgram;
    private int vertexArray;
    private Framebuffer depthBackupFramebuffer;
    private final Map<OverlayFramebufferType, OverlayFramebuffer> framebuffers = new EnumMap<>(OverlayFramebufferType.class);

    OverlayRenderer() {
        OverlayHook.init();
        OverlayHook.subscribe(this::renderFrame);
        initializeFramebuffers();
        initializeShader();
    }

    @Override
    public void close() {
        OverlayHook.unsubscribe(this::renderFrame);
        framebuffers.values().forEach(framebuffer -> framebuffer.object.delete());
        if (depthBackupFramebuffer != null) depthBackupFramebuffer.delete();
        if (shaderProgram != 0) GL20.glDeleteProgram(shaderProgram);
        if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray);
    }

    private void initializeFramebuffers() {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getFramebufferWidth();
        int height = client.getWindow().getFramebufferHeight();
        depthBackupFramebuffer = new SimpleFramebuffer("OBS Overlay depth backup", width, height, true);
        framebuffers.put(OverlayFramebufferType.DEPTH,
                new OverlayFramebuffer(new SimpleFramebuffer("OBS Overlay depth", width, height, true)));
        framebuffers.put(OverlayFramebufferType.NORMAL,
                new OverlayFramebuffer(new SimpleFramebuffer("OBS Overlay GUI", width, height, true)));
        beginFrame();
    }

    private void initializeShader() {
        int vertex = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        shaderProgram = GL20.glCreateProgram();
        GL20.glAttachShader(shaderProgram, vertex);
        GL20.glAttachShader(shaderProgram, fragment);
        GL20.glLinkProgram(shaderProgram);
        if (GL20.glGetProgrami(shaderProgram, GL20.GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Failed to link overlay shader: " + GL20.glGetProgramInfoLog(shaderProgram));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        vertexArray = GL30.glGenVertexArrays();
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Failed to compile overlay shader: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public Framebuffer getFramebuffer(OverlayFramebufferType type) {
        OverlayFramebuffer framebuffer = framebuffers.get(type);
        return framebuffer == null ? null : framebuffer.object;
    }

    public boolean isFramebufferOverridden() {
        return framebufferOverridden;
    }

    private int getFramebufferId(Framebuffer framebuffer) {
        GlBackend backend = (GlBackend) RenderSystem.getDevice();
        return ((GlTextureView) framebuffer.getColorAttachmentView())
                .getOrCreateFramebuffer(backend.getBufferManager(), framebuffer.getDepthAttachment());
    }

    public void backupDepth(boolean overrideDepth) {
        MinecraftClient client = MinecraftClient.getInstance();
        GlStateSnapshot state = new GlStateSnapshot();
        try {
            GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, getFramebufferId(depthBackupFramebuffer));
            renderQuad(false, true, overrideDepth, client.getFramebuffer());
        } finally {
            state.restore();
        }
    }

    private void backupFramebuffer() {
        int bound = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
        if (bound != 0) lastFramebuffer = bound;
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

    public void beginDraw(IOverlayComponent component) {
        if (!component.isOverlayEnabled()) return;
        component.beforeBeginDraw();
        if (component.getFramebufferType() == OverlayFramebufferType.NORMAL) {
            GuiOverlayManager.begin(component.isHidden());
            return;
        }
        if (component.isHidden()) beginEmptyDraw();
        else beginDraw(component.getFramebufferType());
    }

    public void endDraw() {
        if (lastFramebuffer != 0) GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, lastFramebuffer);
        framebufferOverridden = false;
    }

    public void endDraw(IOverlayComponent component) {
        if (!component.isOverlayEnabled()) return;
        component.beforeEndDraw();
        if (component.getFramebufferType() == OverlayFramebufferType.NORMAL) {
            GuiOverlayManager.end();
            return;
        }
        endDraw();
    }

    public void onResolutionChanged(MinecraftClient client) {
        int width = client.getWindow().getFramebufferWidth();
        int height = client.getWindow().getFramebufferHeight();
        framebuffers.values().forEach(framebuffer -> framebuffer.object.resize(width, height));
        depthBackupFramebuffer.resize(width, height);
    }

    private void renderQuad(boolean writeDepth, boolean depthTest, boolean overrideDepth, Framebuffer framebuffer) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (writeDepth) {
            GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, getFramebufferId(depthBackupFramebuffer));
            GL30.glBlitFramebuffer(0, 0, depthBackupFramebuffer.textureWidth, depthBackupFramebuffer.textureHeight,
                    0, 0, client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight(),
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
        GL11.glViewport(0, 0, client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());

        GL20.glUseProgram(shaderProgram);
        GL20.glUniform1i(GL20.glGetUniformLocation(shaderProgram, "ColorSampler"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(shaderProgram, "DepthSampler"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(shaderProgram, "OverrideDepth"), overrideDepth ? 1 : 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL_TEXTURE_2D, ((GlTexture) framebuffer.getColorAttachment()).getGlId());
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL_TEXTURE_2D, ((GlTexture) framebuffer.getDepthAttachment()).getGlId());
        GL30.glBindVertexArray(vertexArray);
        GL11.glDrawArrays(GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void beginFrame() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        framebuffers.values().forEach(framebuffer -> {
            encoder.clearColorAndDepthTextures(framebuffer.object.getColorAttachment(), 0,
                    framebuffer.object.getDepthAttachment(), 1.0);
            framebuffer.dirty = false;
        });
        encoder.clearColorAndDepthTextures(depthBackupFramebuffer.getColorAttachment(), 0,
                depthBackupFramebuffer.getDepthAttachment(), 1.0);
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
        private final boolean blend = GL11.glIsEnabled(GL_BLEND);
        private final boolean cull = GL11.glIsEnabled(GL_CULL_FACE);
        private final boolean scissor = GL11.glIsEnabled(GL_SCISSOR_TEST);
        private final boolean stencil = GL11.glIsEnabled(GL_STENCIL_TEST);
        private final boolean depthMask = GL11.glGetBoolean(GL_DEPTH_WRITEMASK);
        private final int blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final boolean[] colorMask = new boolean[4];
        private final int[] viewport = new int[4];

        private GlStateSnapshot() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            texture0 = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            texture1 = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(activeTexture);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                GL11.glGetBooleanv(GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < colorMask.length; i++) colorMask[i] = mask.get(i) != 0;

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
            setEnabled(GL_BLEND, blend);
            setEnabled(GL_CULL_FACE, cull);
            setEnabled(GL_SCISSOR_TEST, scissor);
            setEnabled(GL_STENCIL_TEST, stencil);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
        }
    }
}
