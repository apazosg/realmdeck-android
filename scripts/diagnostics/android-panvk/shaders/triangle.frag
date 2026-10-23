#version 450

// Offscreen triangle fragment shader: constant red output. The render pass
// clears to green, so red pixels are unambiguously "not clear" on readback.
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(1.0, 0.0, 0.0, 1.0);
}
