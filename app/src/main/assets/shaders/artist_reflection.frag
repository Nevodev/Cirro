precision highp float;
varying vec2 vTexCoord;
uniform sampler2D uTexSampler;
uniform vec4 uCrop;
uniform float uSide;
uniform float uHeight;
uniform float uRise;
uniform float uStripFraction;
uniform float uTopOffset;

void main() {
    float top = (1.0 - vTexCoord.y) * uHeight + uTopOffset;
    float sourceTop = top <= uSide
        ? top / uSide
        : 1.0 - (top - uSide) / uRise * uStripFraction;
    vec2 source = vec2(vTexCoord.x, 1.0 - sourceTop);
    gl_FragColor = texture2D(uTexSampler, uCrop.xy + source * uCrop.zw);
}
