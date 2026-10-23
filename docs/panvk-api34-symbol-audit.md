# PanVK beta16: símbolos presentes en API34, loader todavía no cualificado

2026-10-06. Solo POCO xaga conectado. Auditoría estática posterior a metadata
CSF1.18/SET_FLAGS0/GPU-ID0xa8670000; no carga del ICD, constructor, entrypoint,
Vulkan instance/device/queue ni workload. APK41/runtime/HAL sin cambios.

## Política publicada y fuente fijada

PanVK package beta16 conserva `minApi=35` y la nota «until API29 rebuild is proven».
En `zenithblue-oss/panvk-kbase-android@de577411e8f27da9de50aec26a0d8cc43890745e`:

- `scripts/build-android.sh` usa `ANDROID_API` default35 y admite `--api`.
- `scripts/package-android-adpkg.sh` declara minApi35 como política de paquete.
- `scripts/validate-package.sh` exige prueba explícita para un mínimo inferior.

No se ha demostrado API35-only en el código ni se conoce con certeza el API
efectivo del binario publicado. Package patch-series y sources.lock de ese commit
declaran hashes distintos: tampoco se afirma reproducción exacta de ese artefacto.
Cambiar un número no cualifica API34 y no se modificó metadata ni el binario.

## Auditoría real de proveedores del POCO

ICD host 30.972.472 bytes, SHA
`c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c`.
`llvm-readelf --dyn-syms --version-info` identifica **269 imports dinámicos**,
incluyendo versiones LIBC/LIBC_N/LIBC_O/LIBC_P/LIBC_R.

Se leyeron solo siete archivos sistema/API34 por ADB, con paths canónicos, tamaño
<=8MiB/file y SHA antes/después/copia host: libc, libdl, liblog, libm,
libnativewindow, libsync y libz. Total1.876.128 bytes. No ejecutar los ELF o los
resolvers IFUNC; no modificar sistema ni namespace. Artefactos externos:

```text
controls/panvk-audit-beta16/system-api34-audit-v1/
  symbol-audit.json
  symbol-audit-interpretation.json
  <siete proveedores ELF capturados>
```

El primer informe conservado registró doce aparentes faltantes por exigir
FUNC→FUNC: memcpy/strcmp/strrchr/strlen/strncmp/memmove/memcmp/memchr/strchr/
strnlen/strcpy/stpcpy. La revisión de libc confirma exports **IFUNC** con versión
LIBC, compatibles como candidatos de resolución de funciones. La interpretación
offline preserva el informe original y su hash, admite FUNC→IFUNC y vuelve a
verificar cada proveedor contra el hash capturado: **ningún nombre/tipo/versión
de esos 269 imports queda sin un proveedor estático**.

Esto no ejecuta siquiera IFUNC ni prueba resolución dinámica, namespaces,
comportamiento de API, constructores, seguridad kernel, funcionalidades Vulkan o
compatibilidad completa con el teléfono. El mínimo publicado sigue35. No poner
`ready=true` ni afirmar que hace falta/no hace falta un rebuild por este dato solo.

## Siguiente decisión

Un control separado de **dlopen privado en API34** puede comprobar el límite de
loader sin falsear metadata, siempre registrando que es una cualificación
experimental de un paquete minApi35. No llamar funciones Vulkan, abrir el GPU
desde el probe ni enviar workload. Los constructores son código del driver y
su ejecución no puede tratarse como otra lectura estática.

Ese cambio requirió aprobación separada y se completó posteriormente en
`.agents/planning/done/panvk-api34-loader-plan.md`; ver
`docs/panvk-private-loader-poco-results.md`. No reescribir los resultados
estáticos como prueba completa de API/Vulkan. No shims de símbolos, aliases
arbitrarios, features fingidas, root o HAL.
