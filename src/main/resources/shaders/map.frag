#version 450

layout(location = 0) in vec4 vCol;
layout(location = 1) in vec3 vWorldPos;
layout(location = 2) in flat uint vEmissive;

layout(push_constant) uniform Push {
    mat4 viewProj;
    vec4 sunDir;
    vec4 cameraPos;
    mat4 model;
} pc;

layout(std430, binding = 0) readonly buffer LightBuf {
    vec4 lightData[];
};

layout(location = 0) out vec4 outColor;

vec3 hemiAmbient(vec3 n) {
    float up = n.y * 0.5 + 0.5;
    vec3 ground = vec3(0.22, 0.18, 0.15);
    vec3 sky = vec3(0.58, 0.62, 0.70);
    return mix(ground, sky, up);
}

float specBlinn(vec3 n, vec3 l, vec3 v, float power) {
    vec3 h = normalize(l + v);
    return pow(max(dot(n, h), 0.0), power);
}

vec3 acesTonemap(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec3 n = normalize(cross(dFdx(vWorldPos), dFdy(vWorldPos)));
    if (dot(n, pc.sunDir.xyz) < 0.0) {
        n = -n;
    }

    vec3 albedo = vCol.rgb;
    vec3 viewDir = normalize(pc.cameraPos.xyz - vWorldPos);
    float ndv = abs(dot(n, viewDir));

    float luma = dot(albedo, vec3(0.299, 0.587, 0.114));
    float gloss = mix(0.55, 0.12, clamp(luma * 1.15, 0.0, 1.0));
    float specPow = mix(12.0, 48.0, gloss);

    vec3 ambient = hemiAmbient(n) * 0.92;

    float ndl = max(dot(n, pc.sunDir.xyz), 0.0);
    float wrap = clamp((dot(n, pc.sunDir.xyz) + 0.38) / 1.38, 0.0, 1.0);
    vec3 sunCol = vec3(1.00, 0.93, 0.82);
    vec3 sunDiff = sunCol * (0.38 * wrap + 0.52 * ndl);
    float sunSpec = specBlinn(n, pc.sunDir.xyz, viewDir, specPow) * gloss * 0.55;

    float rim = pow(1.0 - ndv, 3.0);
    vec3 rimL = vec3(0.42, 0.52, 0.68) * rim * 0.22;

    vec3 lit = albedo * (ambient + sunDiff) + sunCol * sunSpec + albedo * rimL;

    int lightCount = int(pc.sunDir.w);
    for (int i = 0; i < lightCount; i++) {
        vec4 lp = lightData[i * 2 + 0];
        vec4 lc = lightData[i * 2 + 1];
        vec3 toL = lp.xyz - vWorldPos;
        float d = length(toL);
        float t = clamp(1.0 - d / max(lp.w, 0.001), 0.0, 1.0);
        float att = t * t * (0.55 + 0.45 * t);
        vec3 L = toL / max(d, 0.001);
        float wrapL = clamp((dot(n, L) + 0.50) / 1.50, 0.0, 1.0);
        float ndlL = max(dot(n, L), 0.0);
        float lSpec = specBlinn(n, L, viewDir, specPow) * gloss * att;
        lit += albedo * lc.rgb * att * (0.42 + 0.70 * wrapL + 0.22 * ndlL);
        lit += lc.rgb * lSpec * 0.65;
    }

    if (vEmissive == 1u) {
        lit = albedo * 1.45 + vec3(0.10) + ambient * albedo * 0.12;
    } else {
        float crv = length(fwidth(n));
        float edgeAo = smoothstep(0.12, 1.35, crv) * 0.30;
        lit *= (1.0 - edgeAo);
    }

    float dist = length(vWorldPos - pc.cameraPos.xyz);
    float fog = clamp((dist - 720.0) / 1900.0, 0.0, 1.0);
    fog *= fog * (3.0 - 2.0 * fog);
    vec3 fogCol = vec3(0.11, 0.12, 0.15);
    lit = mix(lit, fogCol, fog);

    lit *= 1.12;
    lit = acesTonemap(lit);

    float gradedLuma = dot(lit, vec3(0.299, 0.587, 0.114));
    lit = mix(vec3(gradedLuma), lit, 1.10);
    lit = clamp(lit, 0.0, 1.0);
    lit = pow(lit, vec3(1.0 / 2.2));

    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    lit += (dither - 0.5) / 255.0;

    float alpha = vCol.a;
    if (alpha < 0.999) {
        float fres = pow(1.0 - ndv, 3.5);
        alpha = clamp(alpha * 0.88 + fres * 0.50, 0.0, 0.94);
        lit += vec3(0.50, 0.58, 0.66) * fres * 0.22;
        lit += sunCol * sunSpec * 0.8;
    }
    outColor = vec4(lit, alpha);
}
