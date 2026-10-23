package com.wowforever.launcher

/** Puente al crate `forever-patcher-jni` (cdylib Rust).
 * Los nombres JNI correspondientes viven en
 * `crates/forever-patcher-jni/src/lib.rs`. */
object NativeBridge {
    init {
        System.loadLibrary("forever_patcher_jni")
    }

    /** Demuestra que la biblioteca carga: versión del crate JNI + testigo del núcleo. */
    @JvmStatic
    external fun nativeVersion(): String

    /** Valida el JSON del contrato con el cargador real del núcleo y lo resume. */
    @JvmStatic
    external fun contractSummaryJson(json: String): String

    /** Autotest cross-proceso (punto 4, D-L3): hijo `fork()` con almacén
     * sintético, parcheo vía `/proc/&lt;pid&gt;/mem` con el núcleo y
     * confirmación del hijo. Devuelve `result=OK ...` o `result=FAIL ...`
     * con la causa exacta. Puede tardar ~10 s: llamar fuera del hilo UI. */
    @JvmStatic
    external fun crossProcessSelfTest(contractJson: String): String
}
