# Referencia: contrato del parche Forever (almacén de claves en memoria)

> Conocimiento operativo para Fase 3. Reproducido de `defexnicolas/wow-classic-launcher`.
> **Contrato vivo**: verifica la deriva con `scripts/contract-drift-check` (ver `docs/architecture.md` §6).
> Comportamiento normativo: `.agents/specs/US-001.md` y `docs/architecture.md`.

## Origen

- Repo: `https://github.com/defexnicolas/wow-classic-launcher` (MIT).
- Fuentes del contrato: `src/Patcher.cs` (Windows) y `linux/classic-forever-linux.py` (Linux/Wine).
- Commit/de tag validado al escribir esto: ver `forever/contract.json` (`validated_upstream_commit`).

## Qué se parchea

El cliente valida el certificado del servidor de login contra un almacén de **12 claves
públicas Ed25519** (`NetClient::CertStore`). Se sustituye la clave del **grupo 8** por
la clave pública del servidor Classic Forever. La **clave 1 es ancla de búsqueda y
nunca se modifica**.

## Estructura del almacén en memoria

- Entrada (40 B): `u32 id` little-endian (`1..12`) + **32 B** de clave + `flag` (1 B) + relleno (3 B).
- `ENTRY_SIZE = 40`, `ENTRY_COUNT = 12`, `BLOCK_SIZE = 480`, `TARGET_GROUP = 8`.
- Flags esperados por id 1..12: `[1,1,1,1,1,1,1,1,0,1,1,1]`.
- **Relleno tras el flag (depende de plataforma):** `7F 00 00` en Windows nativo,
  `00 00 00` bajo Wine/Proton (medido 2026-09-27 en CachyOS). El chequeo debe aceptar
  ambos. **Android corre bajo Wine ⇒ esperar `00 00 00`.**
- Búsqueda: heap del proceso = regiones **privadas rw, sin fichero detrás** (`MEM_PRIVATE`
  + `PAGE_READWRITE` en Windows; `rw-p` + `inode 0` + sin `[v*]`/`[stack]` en Linux).
  Nunca se escribe en código ni en disco. Alineación de la búsqueda: múltiplo de 4.

## Claves (hex)

| # | Rol | Valor |
|---|---|---|
| 1 | Ancla de búsqueda (NUNCA se toca) | `9B0671C815DFF513BFD4A2B26AE1F84EC9106841B2FB620DB65F6ADE7C21AD06` |
| 2 | | `112451B9843C2799E4750AA3D9B9AFABF536A645B863C4ADB2078B932B354E04` |
| 3 | | `A6B858485748BF38BE193517AB90F8DE169EFF0989EA9360DB346A378B0FFE15` |
| 4 | | `50FFDEB807F8FF73A299A16000AA6575C5945BE875ADFC8795374ED3415249AC` |
| 5 | | `9F842D078755647C008E5FE3E12B839A981DE02A1F520CB2545CC04CD2323E0E` |
| 6 | | `B8CC75864D8EF461D8DD6AA7425E2C63C24D3982066B8D773A1549BFE24E05EE` |
| 7 | | `76BB7BBDD9F34E124A4573C3AA227E3C87CC603506697D054F6FDEC342E56EBF` |
| 8 | **La que se sustituye** | `1FD6DD8FA0EC30D39E3F72E755B8A045BDE0F70449DC71008B767C2EAA89FB9F` |
| 9 | flag=0, no se toca | `15D618BD7DB577BD9A8D45769C59E4FC631633BF447398A4B489B4C26FBC03AD` |
| 10 | | `B34EC592D2AC4993F5FFC85B15C3DA9078694051CB224345592AF7136D796C99` |
| 11 | | `9E91586DD4B115AB0568B21959E181F6545910BC5E2F30B8975CA09D7BC3EFCE` |
| 12 | | `769C824A1DADD19AEEFA420414D1732DAF44537F98AA229FC5477BCA55F9D59A` |

**Clave pública del servidor (nueva, para el grupo 8):**
`02596F0D0C061A8B30745988FD72C59E29EC367FB0F341F28E0F08D037BAFC69`
(es pública: el cliente la usa para verificar al servidor; no es un secreto).

## Configuración (portal)

- Portal: `auth.gpon.com.co` — el certificado está emitido para el **nombre**, no la IP.
- Puertos: login `1119`, mundo `8085`.
- Fichero: `WTF/BetaSuspendedTest.wtf`, con `SET portal "auth.gpon.com.co"` y
  `SET textLocale "enUS"` (si falta). Si el `.wtf` no existe, se copia de `WTF/Config.wtf`.
- **`Config.wtf` nunca se toca**: abrir el juego desde Battle.net sigue yendo al beta oficial.
- Lanzamiento: `WowB.exe -config BetaSuspendedTest.wtf` con cwd = carpeta del juego.

## Validación y escritura

1. Buscar copias de la clave 1 (ancla) alineadas a 4.
2. Para cada copia, validar el bloque entero (ids `1..12`, las 12 claves, flags, relleno).
3. Escribir 32 B del grupo 8 solo si el bloque valida y los bytes actuales coinciden
   (reintentos si cambian justo antes).
4. Releer y confirmar; reaplicar si el cliente recrea el almacén; terminar al cerrar el juego.

## Builds y feed

- Builds soportadas en el launcher: `1.60.1.69913`, `1.60.1.69977`, `1.60.1.70009`.
- Build de medida de las claves: `1.60.1.70009`.
- El servidor puede anunciar builds admitidas extra por `status.json` (`clientBuilds`,
  patrón `^1.60\.\d+\.\d{5}$`), que se suman sin sacar launcher nuevo.
- Feed: `https://raw.githubusercontent.com/defexnicolas/wow-classic-launcher/status/status.json`
  (se ignora lo que diga del estado si tiene más de 15 min).

## Síntomas

- El login vuelve / "reason 24" ⇒ la clave no se aplicó (mira el log).
- Bajo Wine, si el chequeo de relleno exige `7F` y el bloque trae `00`, **nunca parchea**
  (bug corregido el 2026-09-27 en el script Linux). Nuestro port debe aceptar ambos.
- Wine como root ⇒ `BC_ASSERT`. **Nunca `sudo`.**
