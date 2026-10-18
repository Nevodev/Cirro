uniform vec2 uImageSize;
uniform float uRadius;
uniform float uMaxLevel;
uniform float uBlurStart;
uniform float uBlurHeight;

vec4 sampleLod(vec2 p, float lod) {
    float level = clamp(lod, 0.0, uMaxLevel);
    int lo = int(floor(level));
    int hi = int(min(float(lo + 1), uMaxLevel));
    vec4 a = atLevel(p, lo);
    if (hi == lo || fract(level) == 0.0) return a;
    return mix(a, atLevel(p, hi), fract(level));
}

void main() {
    float top = (1.0 - vTexCoord.y) * uImageSize.y;
    float intensity = clamp((top - uBlurStart) / uBlurHeight, 0.0, 1.0);
    float radius = 1.6 * uRadius * intensity;
    float lod = max(0.0, log2(radius < 2.0 ? radius * 0.5 + 1.0 : radius));
    vec2 d = vec2(lod * 0.25) / uImageSize;
    gl_FragColor = 0.25 * (sampleLod(vTexCoord + vec2( d.x, d.y), lod)
                        + sampleLod(vTexCoord + vec2( d.x,-d.y), lod)
                        + sampleLod(vTexCoord + vec2(-d.x, d.y), lod)
                        + sampleLod(vTexCoord + vec2(-d.x,-d.y), lod));
}
