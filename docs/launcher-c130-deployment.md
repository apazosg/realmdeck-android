# C130 — despliegue Wi-Fi ADB (2026-10-22)

Instalada a las16:31 UTC en xaga/22041216G mediante `adb install -r`, de C129 a
`130 / 0.2.106-guest-recovery`, conservando los datos y UID10330. Antes de instalar
no había procesos del UID de la app; no se forzaron cierres ni se reinició el móvil.

Se desplegó la APK C130 previamente compilada y auditada, no los cambios gráficos
posteriores en curso del repositorio. Android confirma versión y SHA256 del APK
instalado idéntico al auditado:

`36267225b90853d9fe95b266e3ce594a154365fe5b52a167c3f441cc592e823d`

No se abrió la app ni se inició Wine/WoW durante el despliegue. Queda pendiente
probar Jugar, cierre, reapertura y recuperación desde la APK sin depurador; la
instalación no demuestra que el flujo funcional esté corregido en el móvil.

Evidencia externa: `preflight.json`, `install.log` y `deployment-receipt.json` en
`/home/adrian/wowforever-artifacts/arm64-prototype/client130-guest-recovery-20261022/wifi-deployment/`.
