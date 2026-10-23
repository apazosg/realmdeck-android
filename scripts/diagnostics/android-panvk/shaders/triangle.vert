#version 450

// Offscreen triangle vertex shader: no vertex buffers. Positions are derived
// from gl_VertexIndex with an explicit branch (no dynamic indexing of a local
// array) so the SPIR-V is trivially valid under Vulkan 1.0 rules.
void main() {
    vec2 pos = vec2(0.0, 0.0);
    if (gl_VertexIndex == 0) {
        pos = vec2(-0.5, -0.5);
    } else if (gl_VertexIndex == 1) {
        pos = vec2(0.5, -0.5);
    } else {
        pos = vec2(0.0, 0.5);
    }
    gl_Position = vec4(pos, 0.0, 1.0);
}
