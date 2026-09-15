#version 450

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUV;
layout(location = 2) in vec4 inColor;

layout(push_constant) uniform Push {
    vec2 viewport;
} pc;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec4 vColor;

void main() {
    float x = (inPos.x / pc.viewport.x) * 2.0 - 1.0;
    float y = 1.0 - (inPos.y / pc.viewport.y) * 2.0;
    gl_Position = vec4(x, y, 0.0, 1.0);
    vUV = inUV;
    vColor = inColor;
}
