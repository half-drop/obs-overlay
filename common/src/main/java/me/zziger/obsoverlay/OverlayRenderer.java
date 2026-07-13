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
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.Closeable;
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
        int previous = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, getFramebufferId(depthBackupFramebuffer));
        renderQuad(false, true, overrideDepth, client.getFramebuffer());
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previous);
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
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, getFramebufferId(depthBackupFramebuffer));
            GlStateManager._glBlitFrameBuffer(0, 0, depthBackupFramebuffer.textureWidth, depthBackupFramebuffer.textureHeight,
                    0, 0, client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight(),
                    GL_DEPTH_BUFFER_BIT, GL_NEAREST);
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        }

        if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
        GlStateManager._depthMask(true);
        GlStateManager._enableBlend();
        GlStateManager._disableCull();
        GlStateManager._blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager._viewport(0, 0, client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());

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
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        renderQuad(type == OverlayFramebufferType.DEPTH, type == OverlayFramebufferType.DEPTH, false, framebuffer.object);
    }

    public void markDirty(OverlayFramebufferType type) {
        OverlayFramebuffer framebuffer = framebuffers.get(type);
        if (framebuffer != null) framebuffer.dirty = true;
    }

    public void renderFrame() {
        renderFramebuffer(OverlayFramebufferType.DEPTH);
        renderFramebuffer(OverlayFramebufferType.NORMAL);
    }
}
