# FrontMage/FEX runtime prototype — source and asset-pairing gate

Última actualización: 2026-09-29. Este documento registra únicamente el gate de
fuentes, toolchain, compilación e inventario. No contiene UI nueva, launcher
wiring, probes ni afirmaciones de ejecución móvil.

## Resultado del gate

**BUILD PASS.** La receta compiló el baseline upstream limpio con el commit fijo
`FrontMage/winlator-llm@4293b75b449d11fdf66da567791c9165fa6df6a0`.

- Comando: `scripts/frontmage-prototype/bootstrap.sh`
- APK: `/home/adrian/wowforever-artifacts/frontmage-prototype/build/app/build/outputs/apk/debug/app-debug.apk`
- Tamaño: `162445064` bytes
- SHA-256: `682aef19d127098fad7cfb8489d4f3a9ceed11ecade721813942f1d78b28cfcf`
- Paquete: `com.winlator.llm`
- Versión: `7.1` / `versionCode=16`
- ABI: `arm64-v8a`
- Firma: APK debug, esquema v2 válido; certificado SHA-256
  `2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b`
- No se instaló ni se sobrescribió ninguna app del dispositivo.

**RECOVERED-ASSET BUILD PASS.** Se reconstruyó el mismo checkout con los tres
assets que faltan en el checkout público, extraídos byte a byte del APK release
conocido que corresponde al mismo commit. El build resultante conserva
`com.winlator.llm`, pero es un APK debug externo y no se instaló.

- Comandos: `scripts/frontmage-prototype/recover-release-assets.sh` y después
  `scripts/frontmage-prototype/bootstrap.sh --with-recovered-assets`
- APK: `/home/adrian/wowforever-artifacts/frontmage-prototype/build/app/build/outputs/apk/debug/app-debug.apk`
- Tamaño: `610065969` bytes
- SHA-256: `aac70319edd5adf0638e634ba3e2cfbaa299ef4d2ea602f459e5087e81856007`
- Verificación: `asset_manifest=complete` en
  `/home/adrian/wowforever-artifacts/frontmage-prototype/recovered-asset-build.txt`
- La receta no ejecuta el runtime, no usa ADB y no modifica el APK release
  original ni el dispositivo.

El APK conocido del artefacto on-device sigue preservado como referencia,
`/home/adrian/wowforever-artifacts/frontmage-ab/app-debug.apk`, SHA-256
`01d364cafc77bb919a89668ac20618a108763c4e46ef3a81a104a4dc4bf98b2e`. La
diferencia de hash entre ambos APK debug no se interpreta como fallo: el gate
valida configuración, fuente y compilación, no reproducibilidad byte a byte.

## Fuentes y combinación fijada

| Componente | Valor |
|---|---|
| FrontMage | `4293b75b449d11fdf66da567791c9165fa6df6a0` |
| OpenXR gitlink | `288d3a7ebc1ad959f62d51da75baa3d27438c499` |
| Fuente externa | `/home/adrian/wowforever-artifacts/frontmage-prototype/source` |
| Build worktree | `/home/adrian/wowforever-artifacts/frontmage-prototype/build` |
| ImageFS (known release APK) | `imagefs.txz`, sha256 `f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780` |
| ImageFS patches (known release APK) | `imagefs_patches.tzst`, sha256 `398f863a69957da8be6ee9b73134cecead64b833f82afcb84b60d1a6725eef5a` |
| Wine (known release APK) | `preinstall/proton-10-arm64ec.wcp.xz`, sha256 `2501af75ffe4cffad0671e4e6613f0c8e6113fcf16f5a474a4dd3569135010d5` |
| FEX asset | `fexcore-2508.tzst`, sha256 `0afc4ded5830c2038237498ffe34c830533c6152ca7197349c06de4f44aee4a3` |
| FEX `libwow64fex.dll` | sha256 `aad28c9cdf85788e97ca355c2505433f12ba1a90931f2e27a52690690ee4d51f`; md5 `b985512ebfdb45d73384703d8584788f` |
| FEX `libarm64ecfex.dll` | sha256 `f1eb67578e0834ce1399b4356ea8832252849f6701a05e8439e1791466913556`; md5 `d7422128e90456a982293482028068b6` |

La variante Ludashi/CMOD FEX2508 (`84b3fd...`, DLL md5
`9c31c104...`/`8ee699...`) no se mezcló con este baseline. Box64 tampoco se
introdujo en esta receta.

### Gap de assets del source build

El APK compilado desde el checkout público fijo contiene FEX2508 y los assets
Proton 9 rastreados, pero **no** contiene `imagefs.txz`,
`imagefs_patches.tzst` ni `preinstall/proton-10-arm64ec.wcp.xz`. Esos tres
assets están presentes únicamente en el APK release conocido inspeccionado en
`frontmage-ab`. Por tanto el resultado de este gate es un APK de compilación
baseline, no un runtime autónomo entregable. No se copian esos binarios al
repositorio ni se oculta esta diferencia mediante overlays.

### Recuperación reproducible del APK release

El inventario completo de los 74 entries bajo `assets/` del APK release está en
`/home/adrian/wowforever-artifacts/frontmage-prototype/release-apk-asset-inventory.tsv`.
La recuperación solo acepta el APK local con SHA-256
`01d364cafc77bb919a89668ac20618a108763c4e46ef3a81a104a4dc4bf98b2e`, publicado
como `llm-apk-20260216-144633` para el commit `4293b75b`. La URL de procedencia
fijada es:

`https://github.com/FrontMage/winlator-llm/releases/download/llm-apk-20260216-144633/app-debug.apk`

Los bytes generados, fuera del repositorio, están en
`/home/adrian/wowforever-artifacts/frontmage-prototype/generated-assets/` y su
manifiesto es `generated-assets/asset-provenance.txt`:

| Asset seleccionado | Tamaño | SHA-256 |
|---|---:|---|
| `imagefs.txz` | 183231056 | `f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780` |
| `imagefs_patches.tzst` | 3714463 | `398f863a69957da8be6ee9b73134cecead64b833f82afcb84b60d1a6725eef5a` |
| `preinstall/proton-10-arm64ec.wcp.xz` | 260543968 | `2501af75ffe4cffad0671e4e6613f0c8e6113fcf16f5a474a4dd3569135010d5` |

No se extrajo ni se sustituyó `fexcore-2508.tzst`: el script comprueba que el
FEX2508 (`0afc4d...aee4a3`) y Proton 9 (`e9df3e...512e180`) del APK release
coinciden con los assets ya presentes en el build de `4293b75b`. Esto evita
recombinar DLL FEX de Ludashi/CMOD o de otra versión.

La lógica exacta del source no descarga esos bytes desde una URL:
`ImageFsInstaller` abre `imagefs.txz` mediante `AssetManager`, y el Proton por
defecto abre `preinstall/proton-10-arm64ec.wcp.xz` también como asset local. En
este commit `imagefs_patches.tzst` aparece en el comentario de versionado de
parches, pero no hay una ruta Java de descarga ni una invocación activa para ese
asset. Por eso la recuperación es una provisión reproducible del APK, no un
downloader móvil ni una afirmación de que los tres bytes sean consumidos
automáticamente por el source limpio.

## Toolchain y dependencias

- JDK `17.0.20.1` desde el artefacto externo, pasado mediante `JAVA_HOME`.
- Gradle wrapper `7.5.1`.
- Android Gradle Plugin `7.4.2`.
- SDK platform `android-30`.
- NDK `22.1.7171670`.
- CMake `3.22.1`.
- Descarga/install oficial realizada sin `sudo` ni cambios globales de
  configuración. Las licencias existentes permitieron la instalación sin
  aceptar acuerdos nuevos.
- Tamaños observados después de instalar: NDK `3.6G` expandido, platform 30
  `122M`, caché de distribuciones Gradle `1.4G`. Había espacio suficiente; no se
  descargaron componentes fuera de la combinación autorizada.

El proyecto sí compila C/C++: `app/src/main/cpp/CMakeLists.txt` añade
`patchelf`, `adrenotools`, `proot`, `virglrenderer`, OpenXR y la biblioteca
`winlator`. Por ello NDK 22.1 no era prescindible como paquete prebuilt.

## Inventario nativo y paths

El inventario completo está en:
`/home/adrian/wowforever-artifacts/frontmage-prototype/native-entrypoint-audit.txt`.

- 19 bibliotecas `lib/arm64-v8a/*.so` fueron enumeradas y hasheadas.
- Solo `libproot.so` y `libpulseaudio.so` contienen `PT_INTERP`:
  `/system/bin/linker64`.
- No apareció `/data/data/it.wowmobile` en ninguna biblioteca nativa.
- No se detectaron paths de paquete en los bytes nativos del APK.
- No existen argumentos CMake de compilación que incrusten un `PACKAGE_NAME`;
  `applicationId` es `com.winlator.llm` y el manifest conserva `package="com.winlator"`.
- Sí existen paths Java que deben auditarse antes de cualquier namespace nuevo:
  `Container.DEFAULT_DRIVES` usa `/data/data/com.winlator.llm/storage`;
  `WineUtils` migra desde `com.winlator`/`com.winlator.cmod`; y
  `GuestProgramLauncherComponent` conserva el path legacy
  `/data/data/com.winlator/files/rootfs`.

Recomendación segura para futuro namespace: no hacer un reemplazo textual del
package. Mantener una build upstream intacta y crear una variante explícita que
derive `filesDir`, `nativeLibraryDir` y almacenamiento desde `Context`/`BuildConfig`,
manteniendo solo migraciones legacy deliberadas y un paquete nuevo. Repetir el
inventario ELF/APK antes de compilar esa variante.

## Licencia, seguridad y distribución

- Fuente FrontMage: MIT (hash de `LICENSE` registrado en
  `source-provenance.txt`).
- OpenXR-SDK: Apache-2.0; otros subárboles incluyen sus propios avisos/licencias.
- El APK es debug, `application-debuggable` y no es una entrega pública.
- Los assets runtime (Wine/Proton, FEX, imagefs, gráficos y componentes
  empaquetados) requieren una auditoría individual de copyright y
  redistribución; no se declara autorización para distribuirlos.
- El APK no incorpora el cliente WoW ni credenciales.
- La procedencia de los tres payloads recuperados queda registrada, pero su
  redistribución no está autorizada por este proyecto. `imagefs`, Proton/Wine,
  drivers gráficos y componentes empaquetados requieren revisión individual de
  copyright/licencia antes de incorporarse a cualquier producto.

## Evidencia previa y límite

La corrida real de `frontmage-ab` fijó la combinación `imagefs=21`,
`proton-10-arm64ec`, FEX2508, VirGL 23.1.9 y WineD3D: Hello/ApiProbe pasaron y
`WowB.exe` terminó con `0xC0000005` en `WowB_loader.dll + 0x203E6AB`. Esa
evidencia pertenece al APK/dispositivo existente; este gate de compilación no
repite la prueba móvil y no convierte el build local en un PASS de runtime.

## Siguientes etapas, todavía no implementadas

Después de revisar este gate, las etapas previstas son Hello → ApiProbe →
DynCode → una única ejecución de WowB, con export persistente y estados
`PASS`, `FAIL`, `UNKNOWN` o `SNAPSHOT`. No se debe interpretar `UNKNOWN` como
PASS ni cambiar simultáneamente Wine, FEX, Box64 o gráficos.

El siguiente trabajo mínimo, una vez resuelta la decisión de pairing en móvil,
es preparar esos probes como exportaciones nativas/guest acotadas y repetir la
combinación conocida `imagefs=21 + proton-10-arm64ec + FEX2508 + VirGL 23.1.9 +
WineD3D`. No se implementa UI nueva todavía.

Para una futura build standalone con namespace propio, no se debe renombrar el
APK actual ni sobreescribir `com.winlator.llm`: el baseline y el APK release
usan esa identidad y una firma debug. Primero habrá que crear una variante
explícita con `applicationId` nuevo, derivar `filesDir`/`nativeLibraryDir` y
almacenamiento desde `Context`/`BuildConfig`, conservar solo migraciones legacy
deliberadas, y repetir la auditoría de manifest, paths Java, JNI/ELF y assets.

Ficheros upstream relevantes para una futura revisión de compatibilidad, sin
modificarlos en este gate: `GuestProgramLauncherComponent.java`, `ImageFs.java`,
`ImageFsInstaller.java`, `WineUtils.java`, `XServerDisplayActivity.java` y
`MainActivity.java`.
