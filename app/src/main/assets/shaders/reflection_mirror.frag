// Byte-for-byte the geometry of artist_reflection.frag. Both edits are about texture orientation,
// not maths: our source texture comes from GLUtils.texImage2D, where t = 0 is the first Bitmap row
// (the top), whereas the video pipeline receives media3 textures with t = 0 at the bottom. So the
// lookup uses `sourceTop` directly instead of `1.0 - sourceTop`, and the caller feeds this shader
// through reflection_flip.vert so that the result also ends up top-row-first.
precision highp float;
varying vec2 vTexCoord;
uniform sampler2D uTexSampler;
uniform vec4 uCrop;
uniform float uSide;
uniform float uHeight;
uniform float uRise;
uniform float uStripFraction;

void main() {
    float top = (1.0 - vTexCoord.y) * uHeight;
    float sourceTop = top <= uSide
        ? top / uSide
        : 1.0 - (top - uSide) / uRise * uStripFraction;
    vec2 source = vec2(vTexCoord.x, sourceTop);
    gl_FragColor = texture2D(uTexSampler, uCrop.xy + source * uCrop.zw);
}
