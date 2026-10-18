precision highp float;
varying vec2 vTexCoord;
uniform sampler2D uTexSampler;
uniform vec2 uParentSize;

void main() {
    vec2 a = vec2(1.9600850343704224) / uParentSize;
    vec2 b = vec2(3.9206759929656982) / uParentSize;
    vec4 center = texture2D(uTexSampler, vTexCoord);
    vec4 axes = texture2D(uTexSampler, vTexCoord + vec2(-a.x, 0.0))
              + texture2D(uTexSampler, vTexCoord + vec2( a.x, 0.0))
              + texture2D(uTexSampler, vTexCoord + vec2(0.0, -a.y))
              + texture2D(uTexSampler, vTexCoord + vec2(0.0,  a.y));
    vec4 diagonal = texture2D(uTexSampler, vTexCoord + vec2(-a.x, -a.y))
                  + texture2D(uTexSampler, vTexCoord + vec2(-a.x,  a.y))
                  + texture2D(uTexSampler, vTexCoord + vec2( a.x, -a.y))
                  + texture2D(uTexSampler, vTexCoord + vec2( a.x,  a.y));
    vec4 farAxes = texture2D(uTexSampler, vTexCoord + vec2(-b.x, 0.0))
                 + texture2D(uTexSampler, vTexCoord + vec2( b.x, 0.0))
                 + texture2D(uTexSampler, vTexCoord + vec2(0.0, -b.y))
                 + texture2D(uTexSampler, vTexCoord + vec2(0.0,  b.y));
    gl_FragColor = center * 0.10549700260162354
                + axes * 0.09018599987030029
                + diagonal * 0.07709799706935883
                + farAxes * 0.05634099990129471;
}
