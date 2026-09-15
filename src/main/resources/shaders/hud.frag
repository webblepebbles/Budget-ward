#version 450

layout(binding = 0) uniform sampler2D fontAtlas;

layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 outColor;

void main() {
    if (vUV.x >= 1.5) {

        outColor = vColor;
    } else {
        float a = texture(fontAtlas, vUV).r;
        outColor = vec4(vColor.rgb, vColor.a * a);
    }
}
