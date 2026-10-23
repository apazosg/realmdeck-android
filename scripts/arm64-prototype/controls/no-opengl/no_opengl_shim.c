/*
 * Scoped diagnostic asset.  This intentionally exports no OpenGL or GLX
 * symbols.  Loading it can make an optional Wine OpenGL lookup fail without
 * providing a fake renderer or changing the Vulkan driver.
 */
#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
const char wow_diagnostic_no_opengl[] = "Scoped diagnostic: OpenGL unavailable";
