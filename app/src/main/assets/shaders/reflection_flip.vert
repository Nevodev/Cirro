attribute vec4 aFramePosition;
varying vec2 vTexCoord;

void main() {
    gl_Position = aFramePosition;
    // Same as artist_header.vert except for the flipped y: this makes row 0 of the framebuffer the
    // top of the image, so glReadPixels lands straight into a Bitmap with no extra flip.
    vTexCoord = vec2((aFramePosition.x + 1.0) * 0.5, 1.0 - (aFramePosition.y + 1.0) * 0.5);
}
