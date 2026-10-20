attribute vec4 aFramePosition;
varying vec2 vTexCoord;

// Plain pass-through, same as artist_header.vert. Used by the downsample and composite passes,
// which all keep the "row 0 is the top" convention established by reflection_flip.vert.
void main() {
    gl_Position = aFramePosition;
    vTexCoord = (aFramePosition.xy + 1.0) * 0.5;
}
