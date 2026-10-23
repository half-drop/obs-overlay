// SPDX-License-Identifier: MIT
#version 450
layout(location = 0) out vec2 uv;
void main() {
    vec2 position = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    // RenderPearl's normal Vulkan present blit reverses its texture Y axis.
    uv = vec2(position.x, 1.0 - position.y);
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
