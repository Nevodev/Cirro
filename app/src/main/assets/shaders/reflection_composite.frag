// artist_lpf_composite.frag, two deliberate differences.
//
// 1. Orientation: our pyramid levels have t = 0 at the top, so the distance from the top edge is
//    `vTexCoord.y * height` instead of `(1 - vTexCoord.y) * height`.
//
// 2. The sampling kernel lives in the **sampled level's own texel space** instead of output pixels.
//    The video moves 4 diagonal taps by `lod * 0.25` output pixels; at lod 6 that is 1.6 px while one
//    texel of that level spans 64 px, so all four taps land inside the same texel and the pass does
//    no filtering at all. What you end up looking at is a 8x10 texture magnified 128x with bilinear
//    only - which reads as a grid of soft diamonds. A 3x3, 1:2:1 kernel spaced 0.75 texel apart at
//    every lod actually filters once, and it is isotropic (the 4 diagonal taps used to leave a
//    cross-shaped footprint).
//
// `atLevel` and one sampler per level are prepended by GlReflection before compiling: GLSL ES 1.00
// cannot index a sampler array, which is why the video builds the same if-chain at runtime.

uniform vec2 uImageSize;
uniform float uRadius;
uniform float uMaxLevel;
// Radius 0 row and the length of the ramp, in px from the top of the image. The ramp starts ABOVE the
// seam (bleed = rise / 4), so the blur already has some width where the reflection begins and the
// cover melts into it instead of hitting a hard edge. uBlurStart + uBlurHeight == image height.
uniform float uBlurStart;
uniform float uBlurHeight;

// Tap spacing in the sampled level's texels. The kernel therefore spans ~1.5 texels of that level;
// together with the pyramid trimming one level off in GlReflection, the overall blur radius stays
// where it was while the content being blurred is twice as detailed.
const float TapSpread = 0.75;

// Curve of the radius ramp. 1.0 = the video's straight line, 2.0 = x squared (slower start, all the
// blur near the bottom). With max radius 96 px and the band running from the bleed top to the bottom
// edge: 1.0 gives 19 / 48 / 96 px at the seam, half and bottom; 2.0 gives 4 / 24 / 96 px.
const float RampPower = 1.5;

vec4 sampleLod(vec2 p, float lod) {
    float level = clamp(lod, 0.0, uMaxLevel);
    int lo = int(floor(level));
    int hi = int(min(float(lo + 1), uMaxLevel));
    vec4 a = atLevel(p, lo);
    if (hi == lo || fract(level) == 0.0) return a;
    return mix(a, atLevel(p, hi), fract(level));
}

void main() {
    #ifdef REFLECTION_VIDEO_ORIENTATION
    float top = (1.0 - vTexCoord.y) * uImageSize.y;
    #else
    float top = vTexCoord.y * uImageSize.y;
    #endif
    float intensity = clamp((top - uBlurStart) / uBlurHeight, 0.0, 1.0);
    // Radius grows like intensity^RampPower; the sub-2 px softening is the video's, so the clear end
    // does not pop into blur on the first few rows.
    float radius = 1.6 * uRadius * pow(intensity, RampPower);
    float lod = max(0.0, log2(radius < 2.0 ? radius * 0.5 + 1.0 : radius));

    // One texel of level L in uv space: level L is uImageSize / 2^L pixels wide and tall.
    vec2 spacing = TapSpread * exp2(clamp(lod, 0.0, uMaxLevel)) / uImageSize;

    vec4 center = sampleLod(vTexCoord, lod);
    vec4 axis = sampleLod(vTexCoord + vec2(spacing.x, 0.0), lod)
              + sampleLod(vTexCoord + vec2(-spacing.x, 0.0), lod)
              + sampleLod(vTexCoord + vec2(0.0, spacing.y), lod)
              + sampleLod(vTexCoord + vec2(0.0, -spacing.y), lod);
    vec4 corner = sampleLod(vTexCoord + spacing, lod)
                + sampleLod(vTexCoord + vec2(-spacing.x, spacing.y), lod)
                + sampleLod(vTexCoord + vec2(spacing.x, -spacing.y), lod)
                + sampleLod(vTexCoord - spacing, lod);
    vec4 color = center * 0.25 + axis * 0.125 + corner * 0.0625;

    // 8-bit output plus a wide smooth gradient is a banding recipe: the blur has eaten every bit of
    // texture that used to hide the steps. Half an LSB of noise breaks them up and stays invisible.
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    color.rgb += (noise - 0.5) / 255.0;
    color.a = 1.0;
    gl_FragColor = color;
}
