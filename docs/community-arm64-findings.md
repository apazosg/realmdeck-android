# Hallazgos del paquete comunitario Windows ARM64 / GameNative RP6

Investigación estática capturada el 2026-09-29. No se ejecutó ningún binario del
paquete, no se instaló APK y no se extrajo el cliente de WoW.

## Procedencia y verificación

- Origen: Google Drive, fichero público `WoW-Forever-GameNative-RP6-all-in-one-2026-09-25.zip`.
- URL canónica registrada (sin parámetros de descarga):
  `https://drive.google.com/file/d/1lssC4X4wT5hv9P1fMjrQYQdho68airSg/view`.
- Artefacto local: `/home/adrian/wowforever-artifacts/references/gamenative-rp6/`.
- Tamaño: **138.441.070 bytes**.
- SHA-256: **`70f1f9924d3649fed811634024eb5b40544485aa11b43d0f28959e95211fa3e2`**.
- El ZIP contiene 17 entradas, 138.438.476 bytes sin comprimir; `unzip -t` pasó.
- No hay rutas absolutas ni componentes `..` en el ZIP ni en los dos tar internos.
- Todos los hashes de `SHA256SUMS.txt` coinciden con los miembros del ZIP.

La información de la carpeta comunitaria adicional (manifest que afirma
GameNative 1.2.1, Bionic, Proton 11.0-90624-arm64ec-1, FEXCore 2609-0 y
DXVK 2.4.1) se considera **afirmación del distribuidor** hasta disponer de esos
ficheros y verificarlos independientemente.

## Inventario del ZIP exterior

| Entrada | Bytes |
|---|---:|
| `README.txt` | 4.179 |
| `SHA256SUMS.txt` | 1.698 |
| `SOURCES.txt` | 1.493 |
| `artwork-sources/Blizzard-icon.png` | 54.645 |
| `artwork-sources/Blizzard-logo.png` | 590.321 |
| `artwork-sources/Blizzard-masthead.jpg` | 394.164 |
| `components/dxvk-2.4.1-wow-aarch64-test.wcp` | 2.198.864 |
| `components/proton-11.0-90624-arm64ec.wcp` | 125.790.679 |
| `components/turnip-wow-scheduler-test.zip` | 3.307.824 |
| `shortcut/WoW Forever Beta/Launch-WoW-Direct.bat` | 105 |
| `shortcut/WoW Forever Beta/coverh.png` | 2.267.312 |
| `shortcut/WoW Forever Beta/coverv.png` | 3.231.877 |
| `shortcut/WoW Forever Beta/steamgriddb_logo.png` | 590.321 |
| `source-patches/NLS-fallback-allocation.patch` | 897 |
| `source-patches/diagnostic-logging-and-icd-exports.patch` | 1.443 |
| `source-patches/limit-barycentric-waits-to-current-block.patch` | 703 |
| `source-patches/wine-arm64-copied-syscall-proton11.patch` | 1.951 |

Hashes destacados, también comprobados contra `SHA256SUMS.txt`:

| Miembro | SHA-256 |
|---|---|
| Proton WCP | `137807f4145b1b440075f83b2b1b3501d19c4eb618784b1a28418652cae3db03` |
| DXVK WCP | `f660b5c6422b3f93cec65664f309718942e58581b5b96fa2902d55d63ce1db8a` |
| Turnip ZIP | `02384f692735515f73aad8a544bc640abe3f35fe18e8df2e667be712c442e54c` |
| BAT | `b9be192e5782c1b75ba1c64d0a4d80a9f7262357329ef50871415838daafce04` |

Los `.wcp` no son ZIP: son marcos Zstandard que contienen un tar POSIX. Solo se
leyeron sus índices y `profile.json`:

- Proton: 2.274 entradas. Incluye `aarch64-unix/`, `aarch64-windows/`,
  `bin/wine`, `bin/wineserver`, `prefixPack.txz` y el árbol Wine completo.
- DXVK: 13 entradas; solo instala `system32/dxgi.dll` y `system32/d3d11.dll`.
- Turnip: 8 entradas; el driver es `libvulkan_freedreno.so` (17.006.248 bytes)
  más `meta.json` y licencias.

Perfiles declarados:

```json
{
  "type": "Proton",
  "versionName": "proton-11.0-90624-arm64ec",
  "description": "LOCAL TEST: Wine 11.0 with WoW ARM64 copied-syscall and NLS fixes..."
}
```

```json
{
  "type": "DXVK",
  "versionName": "2.4.1-wow-aarch64-test",
  "description": "LOCAL TEST: plain Windows ARM64 DXGI/D3D11 for WoW ARM64. Not an ARM64EC/x64 replacement."
}
```

El número `90624` está explícitamente descrito como etiqueta local, no como una
versión oficial de Proton.

## ¿Está incluido `WowB-ARM64.exe`?

No. El ZIP no contiene `WowB-ARM64.exe`, `WowB.exe`, `Data/`, CASC, `login.txt`,
GameNative ni Battle.net. El BAT solo referencia un fichero que el usuario debe
aportar:

```bat
cd /d "G:\WoW-Forever-beta\_classic_beta_" || exit /b 1
start "" /wait "WowB-ARM64.exe" -d3d11
```

Por tanto no es posible verificar el encabezado PE del cliente de este paquete.
El nombre del ejecutable en el BAT no es evidencia de arquitectura. Como control
de los componentes, el `lib/wine/aarch64-windows/ntdll.dll` incluido tiene
`PE.machine = 0xaa64` (ARM64), pero esto **no** demuestra la arquitectura de
`WowB-ARM64.exe`. La verificación necesaria sobre el cliente aportado sería
leer sus encabezados con `llvm-readobj --file-headers` u `objdump -f`, sin
ejecutarlo; el valor esperado para ARM64 es `AA64`/`0xaa64`, no `8664` (x86-64).

## Qué hace realmente el paquete

El README atribuye al distribuidor una prueba en un único Retroid Pocket 6
(Android 13, 12 GB, Adreno 740): cliente Windows ARM64 `1.60.1.70009`, llegada
al mundo y controles aceptados. También dice que una instalación limpia en otro
dispositivo no se ha probado. Es una **referencia provisional**, no una
reproducción ni una prueba de login contra Classic Forever.

La configuración descrita es:

- GameNative 1.2.1, contenedor Bionic.
- Wine/Proton local `proton-11.0-90624-arm64ec-1` y FEXCore 2609-0.
- DXVK ARM64 `2.4.1-wow-aarch64-test-1` y Vulkan.
- Driver Wrapper + Turnip experimental + Adrenotools.
- `WINEESYNC=0`.
- Lanzamiento directo del cliente con `-d3d11`.

El BAT no configura `BetaSuspendedTest.wtf`, no escribe `auth.gpon.com.co`, no
usa `-config BetaSuspendedTest.wtf` y no contiene una operación sobre la clave
del grupo 8. La búsqueda en los textos del paquete solo encontró `login.txt`
como advertencia de no distribuir credenciales. La marca “WoW Forever” y la
referencia a la página de Blizzard no prueban que el destino sea el servidor
Classic Forever del contrato de este repositorio.

Conclusión: es una distribución de runtime y driver para probar un cliente
Windows ARM64 aportado por separado; **no es una distribución del cliente ni un
port del parche Forever**. No se puede inferir que su ejecutable ARM64 conserve
las firmas, offsets o layout de `WowB.exe` x86-64 usados por nuestro patcher.

## Parches de fuente y racional

| Parche | Cambio observado | Racional / alcance |
|---|---|---|
| `wine-arm64-copied-syscall-proton11.patch` | Guarda `__wine_syscall_dispatcher` en el dato por-hilo ARM64 y hace que el stub copiado lo lea desde `x18 + 0x2f8`. | Corrige, según el comentario del parche, stubs de syscall reubicados que conservaban un dispatcher no inicializado. Es genérico para Wine ARM64/ARM64EC y es el aprendizaje más relevante para el puente. |
| `NLS-fallback-allocation.patch` | Usa `asprintf` para construir la ruta `XDG_DATA_DIRS/wine/nls/l_intl.nls` y falla explícitamente ante OOM. | Robustez del arranque de Wine/NLS; genérico, no específico de WoW ni de Forever. |
| `diagnostic-logging-and-icd-exports.patch` | Sustituye un `assert` del scheduler IR3 por diagnóstico Mesa; exporta tres símbolos ICD Vulkan Android. | La parte de scheduler es específica de Freedreno/Adreno. Las exportaciones ICD pueden ser útiles para integración Android, pero no prueban compatibilidad con PowerVR. |
| `limit-barycentric-waits-to-current-block.patch` | Evita que el scheduler IR3 espere instrucciones baryf de otro bloque. | Corrección específica del scheduler Freedreno/Adreno; no debe trasladarse al driver PowerVR. |

Los cuatro parches son cambios de Wine/Mesa/driver. Ninguno modifica el cliente,
el portal, `BetaSuspendedTest.wtf` ni las claves de autenticación de Classic
Forever.

## Qué es reutilizable y qué no

### Transferible a nuestro problema

1. **Cambiar la pregunta de Fase 2**: además de traducir x86-64 con Box64/FEX,
   existe una ruta distinta con cliente Windows ARM64 y Wine ARM64/ARM64EC. Si
   el PE aportado es realmente `AA64`, se evita la traducción x86-64 del proceso
   principal.
2. El arreglo de syscall copiado y el fallback NLS son candidatos a comparar con
   el runtime ARM64/ARM64EC que ya se está evaluando.
3. El lanzamiento directo mediante BAT, una unidad mapeada que contiene la
   carpeta del juego y una carpeta separada para runtime son un patrón sencillo
   de reproducir en una prueba controlada.
4. Mantener disponibles los componentes stock y cambiar un componente por vez
   es mejor que atribuir un resultado simultáneamente a Wine, FEX, DXVK y driver.

### No transferible al POCO/PowerVR sin otra evidencia

- Turnip `libvulkan_freedreno.so`, Adrenotools y los dos cambios IR3 son para
  Freedreno/Adreno; el POCO del proyecto tiene PowerVR BXM-8-256. No hay base
  para instalar o adaptar ese driver en el dispositivo objetivo.
- El éxito declarado en Adreno 740 no valida el camino Vulkan del PowerVR. El
  hecho de que el control D3D ya funcione en Winlator/PowerVR solo justifica una
  prueba con el driver del sistema, no que Turnip sea compatible.
- `FEXCore` en el nombre de la configuración no demuestra que el cliente ARM64
  use traducción x86-64; FEX puede seguir siendo necesario para binarios x86
  auxiliares del entorno, pero el PE ARM64 principal no debe clasificarse como
  x86 por estar dentro de Wine.

## Siguiente paso mínimo recomendado

Sin implementar otro runtime ni ejecutar el paquete:

1. Obtener por separado el cliente que el distribuidor usó y calcular su hash.
   Leer `WowB-ARM64.exe` con `llvm-readobj`/`objdump` y confirmar `0xaa64`.
   Si es `8664`, se descarta la hipótesis ARM64 nativa y vuelve a aplicar la
   matriz x86-64 existente.
2. Comparar el nombre/build (`1.60.1.70009`) y la presencia de `Data/` con el
   cliente del repositorio, sin asumir que el mismo build tiene el mismo layout
   de código entre x86-64 y ARM64.
3. Para PowerVR, el primer ensayo de puente debe ser **Wine ARM64/ARM64EC +
   cliente ARM64 + DXVK ARM64 usando el Vulkan del sistema**, sin Turnip. Solo
   después de un arranque estático/controlado tendría sentido investigar el
   renderer. Esto es un criterio de investigación, no una implementación aquí.
4. Tratar el parche Forever como una fase separada: primero confirmar que el
   cliente ARM64 llega al login; después verificar si el almacén de claves y la
   búsqueda de la clave del grupo 8 existen en ese binario. El contrato actual
   declara `arch=x86_64`, así que no debe prometerse compatibilidad ARM64 del
   patcher sin una nueva evidencia.

Resultado de la investigación: el paquete aporta una pista concreta para pivotar
de “traducir el juego x86-64” a “ejecutar un cliente ARM64 nativo”, además de un
parche Wine ARM64 potencialmente útil. No aporta el PE que demostraría la
arquitectura ni evidencia del portal/servidor Classic Forever; el driver Turnip
no es una opción para nuestro hardware PowerVR.

## Evidencia independiente del cliente oficial en CDN (2026-09-29)

Se verificó por separado el cliente publicado en la CDN oficial de Blizzard, sin
ejecutar ningún PE y sin modificar el cliente del repositorio. El script reproducible
es `scripts/diagnostics/inspect_arm64_client.py`; deja el único binario descargado y
su metadata fuera del repo:

`/home/adrian/wowforever-artifacts/references/official-arm64-beta/`

### Fuente NGDP y selección real del manifiesto

- Producto NGDP: `wow_classic_beta`; región `us`; CDN path observado: `tpr/wow`.
- Build config: `05215079e3905ef5922ae0b03ffefb73`,
  [URL oficial](https://us.cdn.blizzard.com/tpr/wow/config/05/21/05215079e3905ef5922ae0b03ffefb73).
- CDN config: `9b3c456dbb837d133a026d380c7c13e9`,
  [URL oficial](https://us.cdn.blizzard.com/tpr/wow/config/9b/3c/9b3c456dbb837d133a026d380c7c13e9).
- Nombre de build: `WOW-70009patch1.60.1_ForeverBeta` (`1.60.1.70009`).
- El install manifest tiene 219 entradas y declara explícitamente los tags
  `Windows`, `arm64`, `x86_32` y `x86_64`.
- El filtro real `Windows,arm64,enUS` selecciona 35 entradas; el equivalente
  `Windows,x86_64,enUS` selecciona 42. No se infirió ARM64 por el nombre.
- La entrada seleccionada es `WowB-ARM64.exe`, 94.286.544 bytes, content key
  `f8d91fd1b3751e77973035dccbde40e7`, encoding key
  `23fa684e4800baea5cb06689ee6713f4`.
- El `WowB.exe` x86-64 del mismo manifest es distinto: 102.916.816 bytes y
  content key `c333cd87ca146aa83bc97e33a8e73489`.

### Verificación binaria

Artefacto: `/home/adrian/wowforever-artifacts/references/official-arm64-beta/WowB-ARM64.exe`

| Comprobación | Resultado |
|---|---|
| Tamaño del fichero | `94.286.544` bytes; coincide con manifest |
| MD5 del contenido | `f8d91fd1b3751e77973035dccbde40e7`; coincide con manifest |
| SHA-256 | `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59` |
| PE machine | `0xaa64` (`ARM64`) |
| Optional header | `0x020b` (PE32+) |
| `DllCharacteristics` | `0x8160`; `NX_COMPAT=True` |
| Versión encontrada estáticamente | `1.60.1.70009` |
| Timestamp PE | `2026-09-24T01:36:00Z` (`1790213760`) |
| Importaciones PE estáticas | `USER32.dll` (el binario puede cargar otras DLL dinámicamente) |

Esto es evidencia binaria positiva de un cliente Windows ARM64 nativo y de un PE
compatible con NX/DEP. No demuestra todavía que sea ARM64EC, que arranque bajo
Wine, que llegue al login, ni que sea compatible con el patcher Forever.

### Dependencias observadas, sin descargarlas

El filtro ARM64 del manifest también nombra estos ejecutables/DLLs asociados:

`BlizzardError.exe`, `Utils\\WowVoiceProxyT.exe`, `Utils\\BlizzardBrowser.exe`,
`UTILS\\LIBCEF.DLL`, `Utils\\vivoxsdk.dll`, `Utils\\chrome_elf.dll`,
`Utils\\swiftshader\\libEGL.dll`, `Utils\\swiftshader\\libGLESv2.dll`,
`Utils\\libEGL.dll`, `Utils\\libGLESv2.dll`, `Utils\\discord_partner_sdk.dll`,
`Utils\\WindowsExceptionHandlerB-arm64.dll`,
`Utils\\WowWindowsExceptionHandlerB-arm64.dll`, `D3D12_ARM64\\D3D12Core.dll` y
`D3D12_ARM64\\d3d12SDKLayers.dll`.

Solo se descargó el ejecutable principal (límite del script: 100.000.000 bytes).
`LIBCEF.DLL`, por ejemplo, figura con 118.525.952 bytes, y no se bulk-descargó.
La metadata completa, incluyendo tags, pares ckey/ekey, URLs, timestamp y hashes,
está en `evidence.json` junto al binario. Esta procedencia es del cliente oficial
en CDN; no convierte el build `ForeverBeta` en una prueba del servidor privado
Classic Forever ni cambia el contrato actual, que sigue declarando `arch=x86_64`.
