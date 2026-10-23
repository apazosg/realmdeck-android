# APK62: el prefijo se extrae, pero un guard rechaza modos de la plantilla

## Resultado actual

**NO-GO para SERVER_ISOLATION.** APK62 llegó más allá del fallo `filesDir` de61,
pero no publicó el prefijo ni acreditó el publisher, servidor o cliente Wine.
El nuevo RUN conserva `FAILED/EXITED`, resultado Wine `UNKNOWN`,
`terminationUnknown=true` y `quiescenceConfirmed=false`.

- APK instalado y retenido: code62 / `0.2.38-automated`, 418331853 bytes,
  SHA256 `d1431898c0cd7030419f82a7fc0e819c713d833ae0e98b978a4726827225d6ff`.
- Control: `RUN-1791752157102-84597710465` /
  `WS-5df69c2d17d326d0d9e07042119b9988`, nonce
  `ab9c93fab980150ce5000027efe9bf1e`.
- Boot: `0b550857-e3ee-4625-9ee8-875a70b85608`.
- Dispositivo: POCO X4 GT, `22041216G/xaga`, Android14/API34,
  serial `DEVICE_SERIAL`, package `com.wowforever.arm64probe`, UID10330.

## Preparación y control

La primera preparación se detuvo en la respuesta de instalación; una lectura
del package confirmó62. La siguiente preparación verificó el APK independiente
y reinició, sin repetir la instalación. El collector rechazó antes de UI una
incompatibilidad de provenance: esperaba un objeto reducido mientras el helper
guardaba la provenance completa autenticada del APK. Se corrigió comparando
contra el contenido completo del APK autenticado, no aceptando extras arbitrarios.
Cuatro tests dirigidos pasaron. El preflight anterior no se modificó.

Nuevo preflight sellado:
`wine62-preflight-57a5f36944644fc99a9e43345838f382`.
El control posterior tuvo una única secuencia de consentimiento/Start y quedó en
`wine62-server-isolation-1263031c38d24343b23a7a1fa7fedfc3`.
El mensaje de la app identifica el directorio firmado:

```
prefix-stage/.wine/drive_c/windows/winsxs/
arm64_microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef
```

La plantilla/manifest exige modo `0777`. Kotlin aplicaba ese modo y a continuación
invocaba el guard de directorios privados, que prohíbe world-write. C tenía la
misma comprobación incorrecta latente sobre directorios de plantilla.
El closeout read-only confirma UID/GID10330, modo777 en ese directorio y modo700
en la raíz de staging. Por tanto, el rechazo de modo tiene evidencia de dispositivo,
no solo una hipótesis de fuente. No es un diagnóstico de PanVK/SELinux/subreaper.

## Corrección posterior, todavía no instalada

- Raíces app/RUN/stage/prefix siguen con controles privados estrictos.
- Solo descendientes de plantilla autenticada se validan por tipo, owner/group
  y **modo exacto del manifest**, incluido0777. No se cambió ZIP ni manifest.
- Los regulares reciben `fchmod` del modo firmado sobre el FD, fsync y fstat;
  no se depende de la umask.
- C deja de aplicar el predicado de raíz privada a los directorios de plantilla;
  conserva los guards de raíz e imagen nativa.
- Host publisher: **5 tests pasan**. JVM `ForeverWinePrefixTest`: **11 pasan**
  en una ronda actual, con JDK17/offline/CPU2/Xmx4GiB/1worker.

No se recompiló el ELF publisher ni se construyó un APK posterior a62.
El publisher retenido62 y el APK instalado siguen siendo los anteriores.
El checkout generado ahora contiene la corrección para tests; su provenance nueva
no describe retroactivamente el APK62. No se afirma suite completa del repo/JVM.

## Evidencia y preservación

Base externa:
`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/`.
Closeout aditivo:
`wine62-server-isolation-1263031c38d24343b23a7a1fa7fedfc3/read-only-closeout-0aa91e41a1e7418a9f6e763439ed6455/`.

Dos snapshots estables, 315 comandos read-only y ningún control de app,
instalación, reboot, señal, UI o nuevo RUN durante el cierre. Se preservaron
result/seal/source originales. Tres closeouts parciales anteriores permanecen
aditivos; no se ocultaron ni reemplazaron.

| Evidencia | SHA256 |
|---|---|
| current/RUN, 940 bytes | `47822a424550974a7fdef333d5607695549f011edc724d0fc4d54c029ad5061a` |
| binding, 207 bytes | `54da738718c441133863183a8673555e826d72da6d65963a5e8c332366a4fe3a` |
| Wine result, 775 bytes | `c1983b2157cc8ff7494669a5d34a82df2f39baed34e4c7421dcf1137603896f3` |
| result original | `d3aaa155fb91888ba36e674c396090e517abf33a8af2d5ee712159f1e605223c` |
| seal original | `c8626fcdf8ba4c953e53fb68f78d7aa0433d976e5b594e3e9f6b28ae79a874f7` |

La continuación61 fue consumida con reserva/archivo/receipt/consumo ligados al
nuevo RUN. Los originales61 y60, sus transacciones y fixtures56/57/59 permanecen
byte-exactos. El stage parcialmente extraído62 se conserva; no se reutiliza ni
se borra para fabricar un control limpio.

Intents, receipt de publicación, prefijo publicado y logs Wine faltan en los
snapshots. Eso **no es prueba por sí solo de ausencia de procesos ni ECHILD**.
El collector hizo force-stop tras capturar el fallo; tampoco equivale a cierre.
No se ejecutó otro Start después de este UNKNOWN.

## Siguiente gate

El cambio de modos ya está corregido y probado en fuente. Para instalarlo hay que
recompilar el publisher, reconciliar pins/auditar un candidato nuevo y definir
la continuación de este intento concreto, sin generalizar recuperación de UNKNOWN.
Plan: [wine-template-mode-continuation-plan.md](../.agents/planning/wine-template-mode-continuation-plan.md).
No hay acreditación Wine, PE, API/present, visual, WoW, login ni parche de memoria.
