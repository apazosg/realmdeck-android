# WoW ARM64 y servidores privados: referencias públicas

Investigación del 2026-10-12 solicitada por el usuario. Se revisaron documentación,
código fuente público y metadatos. No se ejecutó ningún cliente/launcher ajeno, no
se descargó un ejecutable completo ni se hizo Login, lectura nueva de memoria o
escritura en el móvil. El parche Forever ARM64 sigue pendiente de validación.

## Hallazgo principal: jhinzuo2

Repositorio: <https://github.com/jhinzuo2/wow-classic-arm64-customservers>.
Revisión fijada: `7907fdc35852bee3341dafa64040049c236133d2`.

Su README anuncia ARM64, Wine, servidores privados/HermesProxy y dos launchers:
Classic Forever Beta `1.60.1.70009` y Classic Era `1.14.2.42597`. Es una pista
concreta, pero los dos caminos tienen mecanismos y límites diferentes.

### Forever Beta: no aporta todavía un formato ARM64 demostrado

[PlayBeta.c](https://github.com/jhinzuo2/wow-classic-arm64-customservers/blob/7907fdc35852bee3341dafa64040049c236133d2/source/ClassicForeverLauncher-x64-arm64/PlayBeta.c)
selecciona `WowB-ARM64.exe`/`WowB_arm64.exe` con `--arm64`, pudiendo volver a
`WowB.exe`. No verifica Machine del PE ni el build; el código declara el build
70009 y desactiva la comprobación de versión. El flag no constituye una ruta
criptográfica ARM64 distinta.

El algoritmo sigue siendo dinámico: busca la clave ancla en memoria privada
`PAGE_READWRITE`, valida IDs1..12/40 bytes/clave en +4/flag en +36 y escribe
únicamente los 32 bytes del grupo8, con lectura posterior. No modifica el PE en
disco ni inicializa el CertStore del juego. El diagnóstico más amplio tampoco
prueba por sí mismo la disposición ARM64.

El [build.bat](https://github.com/jhinzuo2/wow-classic-arm64-customservers/blob/7907fdc35852bee3341dafa64040049c236133d2/source/ClassicForeverLauncher-x64-arm64/build.bat)
exige compilador x86_64. Que el patcher sea x64 no impide conceptualmente trabajar
sobre un proceso ARM64, pero requiere su propia compatibilidad de ejecución y
acceso; no aporta un patcher Android nativo listo para nuestro APK.

### Classic Era: sí contiene lógica de instrucciones ARM64

[ArctiumClassicEra.c](https://github.com/jhinzuo2/wow-classic-arm64-customservers/blob/7907fdc35852bee3341dafa64040049c236133d2/source/ClassicEra-Arctium-Launcher-x64-arm64/ArctiumClassicEra.c)
comprueba el Machine del PE y separa rutas x64/ARM64. Las funciones
`a64_scan_certbundle`, `a64_scan_commonname` y `arm64_stage` buscan secuencias A64
y derivan parches de ramas/NOP o de resultados de comparación. La ruta espera
texto desempaquetado/descifrado antes de buscar; no reutiliza ciegamente los
patrones de instrucciones x64.

Esto **sí es una referencia de parcheo específico ARM64**, pero se dirige a
Classic Era `1.14.2.42597`: trabaja con RSA/certificate bundle y parches de código,
no demuestra el contrato Ed25519/grupo8 de Beta `1.60.1.70009`. Su método no se
puede aplicar directamente a nuestro build. Tampoco se ha reproducido aquí su
autenticación. La espera de desempaquetado es una hipótesis investigable para
otras versiones, no prueba de que nuestro Beta esté empaquetado de la misma forma.

El proyecto no declara una licencia que permita trasplantar su código al repo.
Se puede investigar el método; no se incorpora código literal ni material RSA.

### Arquitectura comprobada de los archivos publicados

Se leyeron solo cabeceras por HTTP Range `bytes=0-4095`, en RAM, a URLs de la
revisión fijada. Las tres respuestas fueron HTTP206, 4096 bytes cada una
(12288 bytes totales), sin guardar/ejecutar los archivos.

| Archivo | Tamaño publicado | Machine comprobado |
|---|---:|---|
| `WowClassic-arm64.exe` | 47836048 | `0xAA64`, ARM64, PE32+ |
| `prebuilt/PlayBeta.exe` | 451712 | `0x8664`, x64, PE32+ |
| `prebuilt/ArctiumClassicEra.exe` | 350313 | `0x8664`, x64, PE32+ |

Por tanto, existe un **cliente realmente ARM64** publicado en este proyecto, no
solo un nombre ARM64. La cabecera no prueba su versión, firma, parche estático ni
login correcto. No se debe sustituir nuestro cliente por él a partir de esto.

## Otras referencias y límites

- [AeroNico/thor-forever](https://github.com/AeroNico/thor-forever), revisión
  `4d332477b489863c7d5da686403ac92acb1ebad1`: ejecución ARM64 Beta70009 reportada
  en Adreno. Su configuración pública actual sigue con `SET portal "test"`, no
  el portal Classic Forever, y no aporta el parche del grupo8. Revisión previa:
  `docs/thor-forever-findings.md`. El nombre del proyecto no demuestra auth privada.
- [DealsBeam/wow-classic-launcher](https://github.com/DealsBeam/wow-classic-launcher),
  revisión `5c25a4c8dd28358407c4c669ad8925425621e5b8`: árbol público relacionado con
  el upstream de defexnicolas, cuya URL original devuelve404. Build C# x64; el
  patcher publica el mismo método de memoria y versiones69913/69977. El
  [script Linux público](https://cdn.gpon.com.co/linux/classic-forever-linux.py)
  declara claves medidas en69977/70009, sin evidencia específica ARM64.
- [Burralis/Game-Launcher](https://github.com/Burralis/Game-Launcher), destino
  actual de la referencia Arctium: launcher x64 con escaneo/parches en memoria de
  portal y RSA/Ed25519. Es otra referencia de método, no prueba de Beta70009 ARM64.

Las búsquedas públicas no son exhaustivas. No se encontró una validación
independiente de login Classic Forever con **nuestro** Beta ARM64; sí se encontró
código de parcheo ARM64 para otro cliente/versionado.

## Herramientas de esta revisión pública y continuación posterior

Hasta ahora: scripts Python de búsqueda/estructura, parsers PE/RVA/`.pdata`,
lecturas JNI de clases privadas y desensamblado acotado con el NDK
`llvm-objdump`. No se hizo una decompilación completa ni un análisis de flujo
criptográfico comparativo en Ghidra/radare2/Rizin.

Comprobación local: `ghidraRun`, `analyzeHeadless`, `radare2`, `r2`, `rizin`,
`rz-bin`, `rabin2` no están en PATH; tampoco se encontró `analyzeHeadless` bajo
los artefactos. El NDK24 sí proporciona `llvm-objdump` en su directorio de tools,
aunque no está en PATH. No se instalaron herramientas nuevas en esta revisión.

El usuario pide priorizar comparación del **código de verificación criptográfica**
x64/ARM64 y no limitarse a las claves Ed25519 conocidas. El siguiente análisis
útil debe reconstruir referencias y funciones: construcción/carga de claves,
decodificación o indirecciones y consumo de la clave al verificar el servidor.
Un desensamblador/decompilador con CFG y xrefs es más adecuado que repetir
búsquedas literales. Si el texto relevante está empaquetado o se descifra durante
la ejecución, hay que demostrarlo para nuestro build antes de interpretar una
decompilación estática; la referencia Classic Era no basta.

No se amplía la escritura por una semejanza de IDs ni se repite Login sin parche.
Las claves públicas no cambian por la CPU; representación/build/implementación
del cliente pueden diferir. Estado físico y límites actuales:
`docs/arm64-certstore-patch-exploration.md`.

La continuación «perfecto adelante!» sí instaló Ghidra oficial12.1.4 fuera del
repo y ejecutó importación, recuperación `.pdata`, CFG y decompilación acotados
x64/ARM64. Bootstrap e indirecciones observados, ningún verificador o lugar de
parche demostrado. `docs/arm64-certstore-ghidra-results.md` recoge la ejecución.
La ausencia de Ghidra descrita arriba corresponde a la revisión pública anterior.

## Revisión específica de parches en disco — 2026-10-13

El usuario descarta ejecutar el cliente x64 mediante FEX: mantener WoW nativo
ARM64, usando x64 únicamente como referencia. Solicita comprobar cómo otros
proyectos permiten servidores privados y si modifican el ejecutable en disco.
Esta revisión consultó fuentes públicas; no descargó/ejecutó clientes completos,
no modificó el PE local ni hizo pruebas en el móvil.

### Forever70009: el proyecto publicado sigue parcheando memoria

Se revisaron issues, forks, releases, pull requests e historial de
`jhinzuo2/wow-classic-arm64-customservers`. Las cuatro primeras listas devolvieron
cero entradas; el historial de `PlayBeta.c` contiene un commit, y el repositorio
tres commits. HEAD sigue siendo `7907fdc35852bee3341dafa64040049c236133d2`;
la implementación procede de `50d5577cbeca3bbaeeba1b3445a01fec7ee9c9ea`.
No aparecieron testimonios de autenticación, vídeos o pruebas reproducibles
adicionales en ese ámbito. No implica ausencia absoluta en Internet.

`scan_heap()`/`write32()`/`patch()` usan `VirtualQueryEx`,
`ReadProcessMemory` y `WriteProcessMemory`: no abren el PE del juego para
reescribirlo. Las escrituras en disco son configuración WTF, logs y self-test,
no un cliente ARM64 estáticamente parcheado. El submódulo HermesProxy fijado
`f6528e4010b25ddb83b4e4c8ed1d1307e5a97efd` documenta versiones1.14.0/2.5.2,
no Beta70009; tampoco aporta esa demostración.

### Otra rama Arctium con instrucciones ARM64 reales

[Longee-G/Arctium-WoW-Launcher-No-Certificate-Requirements](https://github.com/Longee-G/Arctium-WoW-Launcher-No-Certificate-Requirements/tree/ed00b27b6f4ffaca9e7e2434206dae705915765d),
revisión `ed00b27b6f4ffaca9e7e2434206dae705915765d`, contiene:

- [Launcher.cs](https://raw.githubusercontent.com/Longee-G/Arctium-WoW-Launcher-No-Certificate-Requirements/ed00b27b6f4ffaca9e7e2434206dae705915765d/src/Launcher.cs):
  rama `ARM64` que selecciona el cliente y encola cambios `CertBundle` y
  `CertCommonName` después de `WaitForUnpack`. El trabajo se aplica mediante
  `PatchMemory`, `QueuePatch` y `RemapAndPatch`, no guardando el PE modificado.
- [Patterns/Windows.cs](https://raw.githubusercontent.com/Longee-G/Arctium-WoW-Launcher-No-Certificate-Requirements/ed00b27b6f4ffaca9e7e2434206dae705915765d/src/Patterns/Windows.cs)
  y [Patches/Windows.cs](https://raw.githubusercontent.com/Longee-G/Arctium-WoW-Launcher-No-Certificate-Requirements/ed00b27b6f4ffaca9e7e2434206dae705915765d/src/Patches/Windows.cs):
  patrones y modificaciones distintos para instrucciones ARM64, no reutiliza
  las secuencias x64. Son pistas para localizar funciones de certificados,
  no offsets ni autoridad aplicables a nuestro build.
- `GenerateAuthSeedFunctionPatch()` solo implementa `x64`; el resto lanza
  `NotImplementedException`. Es una función opcional de auth-seed: esto no
  demuestra que toda autenticación ARM64 sea imposible en ese proyecto.
- La comprobación TLS con `SslStream` que omite validación ocurre en el propio
  launcher; no demuestra que cambie la verificación interna del juego.

No hay soporte específico Beta1.60.1.70009 demostrado en los archivos revisados.
La espera de desempaquetado de otros clientes tampoco prueba que nuestro código
necesite ese procedimiento; seis ventanas de nuestro ARM64 ya coinciden con disco.

Se contrastaron **solo sus dos patrones ARM64 de instrucciones** con `.text`
del PE local, sin repetir búsquedas de claves ni tablas: `CertBundle` tiene12
bytes y `CertCommonName`42, con sus wildcards originales y búsqueda a cualquier
alineamiento. Ambos dieron **cero coincidencias**. El archivo94286544 bytes
mantuvo SHAd0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59
antes/después. Informe externo exclusivo:
`/home/adrian/wowforever-artifacts/work/arm64-static-locator-research-20261013T071800Z-95cf4a9d6d.txt`.
Esto descarta reutilizar directamente esos patrones en el archivo70009 examinado;
no demuestra ausencia de las funciones ni imposibilidad de parche estático.

### Sí existen patchers de disco, pero no un parche ARM6470009 reutilizable

| Proyecto y revisión | Evidencia concreta | Límite |
|---|---|---|
| [St0nys-AIO-WoW-EXE-Patcher](https://github.com/Raz0r1337/St0nys-AIO-WoW-EXE-Patcher/tree/e5113cc76de01b1b96bf2c032d3b1469963a618d) | [docs/README.md](https://raw.githubusercontent.com/Raz0r1337/St0nys-AIO-WoW-EXE-Patcher/e5113cc76de01b1b96bf2c032d3b1469963a618d/docs/README.md) describe leer el archivo, modificar su buffer y escribir `Wow.exe` una vez, con originales/backups | WoW3.3.5a/build12340, código de instrucciones legado x86; los DLL loaders no son prueba de autenticación Forever |
| [Smerdokryl/WoW-Patcher](https://github.com/Smerdokryl/WoW-Patcher/tree/59d4c1ab1ca6b9f7ee767d41268d6fdf37493859) | [Patcher.c](https://raw.githubusercontent.com/Smerdokryl/WoW-Patcher/59d4c1ab1ca6b9f7ee767d41268d6fdf37493859/Patcher.c) abre entrada `rb`/salida `wb`; [Patch.c](https://raw.githubusercontent.com/Smerdokryl/WoW-Patcher/59d4c1ab1ca6b9f7ee767d41268d6fdf37493859/Patch.c) sustituye patrones con `memcmp`/`fwrite` | Su README.md devuelve404; versión/arquitectura no verificadas. No clasificar como ARM64/Beta compatible |

Detalle útil: [Patcher.h](https://raw.githubusercontent.com/Smerdokryl/WoW-Patcher/59d4c1ab1ca6b9f7ee767d41268d6fdf37493859/Patcher.h)
declara cambios RSA y Ed25519. El patrón Ed25519 tiene ocho bytes que coinciden
con el prefijo de la clave **del grupo9**, no grupo8, de nuestro contrato; la
clave sustituta coincide con la publicada para Forever. Esto verifica un método
de sustitución criptográfica en archivo, pero no identifica la misma fase de
autenticación ni permite trasladarlo al CertStore de Beta. Las claves literales
no aparecen en nuestro PE ARM64 analizado; aplicar este patrón a ciegas no sirve.
No se incorpora código o material de claves de esos patchers.

Thor Forever sigue siendo un informe del propietario de gameplay ARM6470009
en Adreno con cambios Wine/DXVK/Mesa. Su README no proporciona una modificación
del cliente de autenticación; playability no equivale a prueba de login Classic
Forever. `aanze/wow-arm64-armada`, revisión
`2bcdf25b7b81b6ff946e03adf7f1b06fda2fc29d`, aporta una corrección Wine/ntdll de
syscall ARM64, no un parche de confianza del cliente.

### Conclusión práctica

Hay evidencia de **parcheo de código ARM64 en memoria** y de **parcheo
criptográfico en disco en otros clientes**. No se encontró en este alcance un
parche estático nativo ARM64 de Forever70009 listo para reutilizar. Algunas
consultas GitHub API quedaron limitadas por HTTP403; las fuentes raw fijadas
citadas arriba sí fueron comprobadas, y los404 se mantienen como incertidumbre.

El siguiente trabajo es localizar en nuestro build el código/dato de confianza
correcto y comprobar si el cambio sobre una copia del PE sobreviviría a su
inicialización y posibles comprobaciones de integridad. No convertir otro
build, rama de certificados, prefijo de ocho bytes o README en autoridad para
parchear. No se ejecuta el A/B x64 rechazado ni se repite Login sin parche.
