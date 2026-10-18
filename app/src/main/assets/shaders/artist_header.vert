attribute vec4 aFramePosition;
varying vec2 vTexCoord;

void main() {
    gl_Position = aFramePosition;
    vTexCoord = (aFramePosition.xy + 1.0) * 0.5;
}
