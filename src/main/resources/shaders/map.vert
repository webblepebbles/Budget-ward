#version 450

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inCol;
layout(location = 2) in uint inEmissive;

layout(push_constant) uniform Push {
    mat4 viewProj;
    vec4 sunDir;
    vec4 cameraPos;
    mat4 model;
} pc;

layout(location = 0) out vec4 vCol;
layout(location = 1) out vec3 vWorldPos;
layout(location = 2) out flat uint vEmissive;

void main() {
    vec4 wp = pc.model * vec4(inPos, 1.0);
    gl_Position = pc.viewProj * wp;
    vCol = inCol;
    vWorldPos = wp.xyz;
    vEmissive = inEmissive;
}
