# Referencias Android: compatibilidad y empaquetado

Investigación de repositorios públicos, 2026-09-27. No se instalaron candidatos
adicionales durante esta comparación. Ninguno de los proyectos examinados aporta
evidencia verificada de login con nuestro cliente `1.60.1.70009` en PowerVR.

| Proyecto / referencia | Evidencia útil | Límite para nuestro objetivo |
|---|---|---|
| [FrontMage/winlator-llm](https://github.com/FrontMage/winlator-llm) | Wine ARM64EC/FEX y logs persistentes; documentación de correcciones para Turtle WoW | En nuestro móvil el cliente beta falla con 0xC0000005. No equivale a la versión de Turtle documentada |
| [marcocastignoli/wow-mobile](https://github.com/marcocastignoli/wow-mobile) | Automatización de contenedor/launcher para WoW 3.3.5a build 12340 | No valida WowB.exe ni su loader |
| [Stredohori/Winlator-CMOD](https://github.com/Stredohori/Winlator-CMOD/tree/26f65e9) | Árbol con FEX2508, Proton ARM64EC y Box64 0.3.7 | Repositorio archivado; no se verificó soporte de nuestra beta. Prioridad inferior a un candidato mantenido |
| [StevenMXZ/Winlator-Ludashi v4.1](https://github.com/StevenMXZ/Winlator-Ludashi/tree/v4.1) | Árbol con FEX2601, Proton9 ARM64EC y Box64 0.4.2: combinación diferente a FrontMage observado | Pendiente verificar APK real, puentes, firma, ID y ruta PowerVR. Las etiquetas de assets no identifican por sí solas los binarios efectivos |
| [puzzled-pancake/pocket-realm v0.103.0-alpha](https://github.com/puzzled-pancake/pocket-realm/tree/v0.103.0-alpha) | Launcher Android gestionado, supervisor, logs, importación y cualificación gráfica; ruta sistema/Vortek para no-Adreno | Valida exclusivamente WoW 1.12.1 build 5875, PE32 I386; no es un sustituto instalable para nuestra beta x64 |

## Pocket Realm: evidencia y reutilización posible

Referencia de release: `ec2783f175f71e5df239b0bc48bb93c09639518e`.
En `android/app/src/main/java/com/pocketrealm/client/`:

- `SafClientScanner.kt`, `ClientPeIdentity.kt`, `ManagedClientStore.kt` fijan
  `WoW.exe`, build 5875 y arquitectura I386. Cambiar el nombre del ejecutable
  o el filtro no demuestra compatibilidad del motor con otro cliente.
- `ClientRuntimeService.kt` y `ArmTranslationBackend.kt` implementan el
  lanzamiento Box64/Wine; no hay backend FEX demostrado en esta revisión.
- `ArmRendererAuto.kt` y `ArmClientRendererCatalog.kt` separan Turnip/Adreno
  de sistema/Vortek y de alternativas experimentales. No prueban PowerVR.
- Los tests Android de login de build 5875 no equivalen a una prueba ejecutada
  en nuestro hardware, ni la CI JVM demuestra éxito del juego.

Lo transferible es el diseño: lanzamiento sin navegación por Wine, supervisión,
captura persistente de errores y pruebas que distinguen proceso vivo de frames
del juego realmente presentados. No trasladar realmlist/auto-login del servidor
local al contrato Forever (portal y BetaSuspendedTest.wtf).

Antes de copiar código, revisar licencia GPL-3.0 declarada por Pocket Realm y
obligaciones de los componentes por separado. No es automáticamente compatible
con distribuir todo nuestro proyecto bajo MIT. Podemos estudiar los patrones
sin incorporar código todavía.

## Correcciones importantes

- Wine ARM64EC con FEX sí es una ruta relevante para ejecutables Windows x64.
- El Box64 oficial observado es 0.4.4, no 4.4.4. Una versión menor distinta
  no es por sí sola peor ni mejor para compatibilidad.
- «Bionic» en un nombre de rama no demuestra Ubuntu 18.04 ni identifica la libc.
- Un asset `wowbox64` no significa «World of Warcraft probado».
- No se puede negar ni afirmar un fallback Vulkan del sistema solo por el
  selector gráfico: falta revisar la selección efectiva del driver y el APK.
- No hay un backend de producto elegido aún: FEX y Box64 siguen en evaluación.

## Orden recomendado

1. Recoger la lectura directa del slot con `SlotProbe.exe`, ya desplegado en
   FrontMage, para no sustituir evidencia con nuevas instalaciones.
2. Evaluar Ludashi v4.1 como ensayo alternativo controlado: verificar APK,
   puente FEX real y coexistencia antes de instalar. Comparar combinaciones
   completas, sin atribuir automáticamente un resultado a FEX solamente.
3. Aprovechar Pocket Realm y wow-mobile como referencias de automatización
   cuando se cierre la compatibilidad; no construir un launcher completo para
   ocultar un runtime que todavía no ejecuta nuestro cliente.
