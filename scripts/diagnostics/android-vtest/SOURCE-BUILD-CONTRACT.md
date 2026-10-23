# Servidor vtest de fuente oficial: contrato del control

Plan aprobado y gate caps completado:
`.agents/planning/done/vtest-android-egl-build-plan.md`.

- Fuente: virglrenderer 1.3.0, tar
  `56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce`.
- Primero `diagnostic`: mismas decisiones EGL/GBM que la fuente configurada,
  añadiendo solo logs identificables `WFVTEST_DIAG` antes/después de winsys,
  display, config, contexto, make-current y errores. No fuente funcional de
  Android-default hasta obtener la traza de ese baseline.
- Native run-as child; paquete `com.wowforever.arm64probe`, UID 10330, solo serial
  `DEVICE_SERIAL`. APK41/Wine/DXVK/driver/datos permanecen intactos.
- Build diagnóstico cualificado en
  `/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-android-build-v6-final/`;
  manifest `candidate_diagnostic_verified/candidate-manifest.json`, SHA
  `3947b472b1aeeba6a9e16e882c2a503e9b05d831006da3ab60c47927bd21e031`.
  Fuente oficial en `controls/virgl-source-audit-1.3.0/official-source.tar.gz` y
  proveedores retenidos en `controls/virgl-audit/server_candidate-v2/candidate-manifest.json`
  bajo el mismo root `/home/adrian/wowforever-artifacts/arm64-prototype/`.
  Máximo 512 MiB y ≤10 MiB de herramientas descargadas; NDK24/Ninja SDK existentes.
- Variante Android-default posterior, solo después del rechazo temprano observado:
  `controls/vtest-android-default-v2/candidate_android_default_v2_verified/candidate-manifest.json`,
  SHA `b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28`.
  Solo define `WFVTEST_ANDROID_DEFAULT=1`; perfil diagnóstico conserva únicamente
  `WFVTEST_DIAG=1`. Ambos candidatos y sus manifests se mantienen separados.
  `--source-profile` permite únicamente esas dos selecciones fijadas, no paths/hash
  de caller. Revisión ELF/tamaño/mode/SHA y los ocho proveedores se mantienen.
- Salida de cada build en un candidato independiente (`candidate-diagnostic`,
  después `candidate-android-default` solo si procede), nunca overwrite del
  servidor Termux o de un candidato publicado previamente.
- Manifest `candidate-manifest.json` compatible con el loader existente:
  schema `wowforever.virgl-server-candidate`, schemaVersion 1, executedPayloads false,
  ready false, protocol.status UNVERIFIED, deviceAliases exactamente
  `libEGL.so.1 -> /system/lib64/libEGL.so` y
  `libGLESv2.so.2 -> /system/lib64/libGLESv2.so`.
  Lista `materializedFiles`: destination, bytes, sha256, mode. Exactamente los diez
  bin/lib destinos del candidato original; reutilizar los ocho proveedores
  auditados, reemplazar solo `bin/virgl_test_server` y `lib/libvirglrenderer.so`
  por sus builds. Registrar y comprobar el cierre ELF real antes de publicar.
- La ABI pública del build oficial es `libvirglrenderer.so.1`: el servidor la
  declara en DT_NEEDED. El archivo copiado mantiene el destino fijo anterior
  `lib/libvirglrenderer.so`; el runner de fuente añade únicamente el alias relativo
  privado `libvirglrenderer.so.1 -> libvirglrenderer.so`, comprobando target/hash.
  No interpretar un SONAME coincidente como prueba de que exista su ruta de carga.
  Las otras dos aliases EGL/GLES del contrato no cambian.
- Manifest incluye buildProfile, source SHA, parches/opciones/config.h/toolchain,
  `.pc` y headers efectivos, logs/build hashes. Identidad/hash final fijados en el
  runner antes de ejecutar. Un archivo/hash/arco nuevo requiere revisión, no se
  autoriza un CLI con librerías o comandos arbitrarios.
- Mismos comandos que el control anterior:
  `--no-loop-or-fork --use-gles [--use-egl-surfaceless] --socket-path vtest.sock`.
  Cliente fijo VtestHandshake, peer PID/startTicks/UID y framing/EOF existentes.
- `VTEST_CAPS_RECEIVED` significa caps opacas y protocolo de esa instancia, no
  hardware, triángulo, presentación, D3D o WoW. Los logs de EGL no sustituyen un
  workload. No considerar exit 0 del servidor como aceptación.
