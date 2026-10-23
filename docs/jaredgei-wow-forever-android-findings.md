# Referencia jaredgei/wow-forever-android — 2026-10-02

Inspección estática, sin descargar APKs ni ejecutar Wine/PE. Fuente fijada:
[`2619b9f7be80d5549be96e8e04135228651b57f1`](https://github.com/jaredgei/wow-forever-android/tree/2619b9f7be80d5549be96e8e04135228651b57f1).
Release `v1.2.0` en `9aa445606562bd54547590a04e0d91e3b0445428`.

## Hallazgo útil

Es una APK específica de WoW sobre GameNative 1.2.1, no solo una guía GameHub.
El [README](https://github.com/jaredgei/wow-forever-android/blob/2619b9f7be80d5549be96e8e04135228651b57f1/README.md)
informa mundo jugable en Thor/Adreno 740 y explica el bypass de `winhandler.exe`
para Windows ARM64. No lo hemos reproducido.

En [XServerScreen.kt, getWineStartCommand](https://github.com/jaredgei/wow-forever-android/blob/2619b9f7be80d5549be96e8e04135228651b57f1/app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt),
la rama absoluta resuelve cwd por unidad y devuelve directamente el executablePath
si contiene ARM64; el retorno final devuelve args en el mismo caso. El resto usa
`winhandler.exe`. Nuestro GameNative fijado a `205cbf8...` conserva ese wrapper
para el custom game que aparece en los ZIPs de code 27/28.

Los [hashes de componentes](https://github.com/jaredgei/wow-forever-android/blob/2619b9f7be80d5549be96e8e04135228651b57f1/tools/components.sha256)
son **idénticos a los de nuestro bundle**:

| Componente | SHA-256 |
|---|---|
| Proton 11.0-90624-arm64ec | `137807f4145b1b440075f83b2b1b3501d19c4eb618784b1a28418652cae3db03` |
| DXVK 2.4.1 aarch64 | `f660b5c6422b3f93cec65664f309718942e58581b5b96fa2902d55d63ce1db8a` |
| Turnip scheduler test | `02384f692735515f73aad8a544bc640abe3f35fe18e8df2e667be712c442e54c` — no usar en PowerVR |

Por tanto, el experimento más pequeño es cambiar **el launch del PE verificado**,
no reemplazar la app/runtime. No copiar la detección por nombre: debe depender de
cabecera AA64, path/cwd y RUN gestionados. No se ha establecido relación causal
entre el wrapper y el SIGINT del host observado en nuestro teléfono.

## No es un reemplazo completo

- [WoWForeverScreen.kt](https://github.com/jaredgei/wow-forever-android/blob/2619b9f7be80d5549be96e8e04135228651b57f1/app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt)
  instala el preset Turnip/Adreno 740, Proton y DXVK y configura `G:`. El README
  exige copiar `.build.info` y todo `Data/` (~67–80 GB) de Battle.net/Mac/PC.
- El descargador automático obtiene binarios ARM64 correspondientes a esa
  `.build.info`; no instala ni actualiza el `Data/` completo ni valida nuestro
  bootstrap pequeño como suficiente.
- `ensureGameConfig` escribe `WTF/Config.wtf` con portal `test`; el sidebar ofrece
  credenciales Battle.net. No implementa `auth.gpon.com.co` ni el parche del grupo 8.
- Su ruta gráfica WoW es Turnip. La aceptación de `System` para otras GPUs en el
  código común no prueba render PowerVR ni que su preset esté preparado para ello.
- `BionicProgramLauncherComponent` mantiene Wine/explorer, SHM, evshim y redirect;
  configura los aliases del package como nuestra corrección code 28. No demuestra
  exclusividad de señales ni solución a nuestro SIGINT. Conserva limpieza global
  de procesos que no debemos importar.
- GPL-3.0 derivado de GameNative y licencias propias de componentes; no sustituye
  nuestro inventario pendiente para distribución pública. La revisión no auditó
  los bytes del APK público ni su firma/contenido.

## Decisión pendiente

Se propone `direct-arm64-launch-plan.md`: conservar nuestro descargador, portal,
patcher, PowerVR y firma, pero lanzar el PE AA64 sin el helper Windows. Se mantiene
Wine y la infraestructura de input/X11, y se conservan las trazas finales nuevas.
No se ha aplicado esta ruta ni entregado todavía un APK posterior a code 28.
