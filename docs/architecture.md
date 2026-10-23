# Arquitectura y baseline (Fase 0)

> Estado: 2026-09-27. Baseline reproducible sobre host Linux x86-64.
> **Corte actual (2026-10-21):** C127 alcanza Login y entrada al juego con70450
> ARM64 en xaga/Mali; C128 adelanta el parche, compilada/auditada pero no instalada.
> ver [punto de reanudación](downloader-handoff.md) y
> [estado de producto](launcher-app-status.md).
> Winlator 11.2 / POCO M7 Pro 5G PowerVR abajo son **baseline histórico**, no
> dispositivo/runtime activos ni decisión nueva de runtime.
> Fuente de verdad del comportamiento: `.agents/specs/` (SYSTEM.md, US-001.md).
> Este documento fija las piezas y las decisiones de arquitectura de las Fases 0–1.

## 1. Objetivo

Ejecutar el cliente **WoW Classic Beta 1.60.1** contra **Classic Forever** en Android,
sin Battle.net ni Windows. Fase 0 fija las piezas; Fase 1 obtiene el cliente desde la
CDN. El proyecto solo sigue si Fase 2 (arrancar `WowB.exe` en Android) da go.

## 2. Cliente y protocolo NGDP/TACT

| Dato | Valor |
|---|---|
| Producto NGDP | `wow_classic_beta` (no existe `wow_forever` → 404) |
| Build activo (2026-09) | `1.60.1.70009` (BuildId `70009`) |
| Build probada en Android (2026-10-21) | `1.60.1.70450`, Windows ARM64/enUS; Login y entrada al juego en C127 |
| Builds aceptadas por el launcher | `1.60.1.69913`, `1.60.1.69977`, `1.60.1.70009` |
| Endpoint versiones | `http://us.patch.battle.net:1119/wow_classic_beta/versions` |
| Endpoint CDNs | `http://us.patch.battle.net:1119/wow_classic_beta/cdns` |
| CDN path | `tpr/wow` (compartido por toda la familia `wow`) |
| CDN config path | `tpr/configs/data` |
| Hosts | `level3.blizzard.com`, `us.cdn.blizzard.com` |
| Ejecutable Windows | `WowB.exe` (ARM64: `WowB-arm64.exe`) |
| Subcarpeta por defecto | `_classic_beta_` |
| Locale de texto | `enUS` |
| Requisito de disco (producto) | ~128 GB (todos los tags) |

### Layout de URLs de la CDN

- Configs de build/CDN → `http://<host>/tpr/wow/config/<a>/<b>/<hash>`
- Manifiestos de datos (encoding/install/download/size/vfs-root) → `http://<host>/tpr/wow/data/<a>/<b>/<hash>`
- Product config (JSON) → `http://<host>/tpr/configs/data/<a>/<b>/<hash>`
- Archivos de datos (buckets) → `http://<host>/tpr/wow/data/<a>/<b>/<cKey>`

### Regla de los pares de hashes (crítica)

Cada campo `<x> = h1 h2` del build config lleva **dos** hashes. Para el CDN elegido
funciona **el segundo**; el primero da 404. Verificado para `encoding`, `install`,
`download` y `vfs-root`. **No asumir el primero.**

El build config es texto plano (no BLTE) en `tpr/wow/config/<a>/<b>/<bcHash>`. El
manifiesto `root` legacy no se usa (404); el contenido se resuelve vía `vfs-root`
(TVFS) y el árbol `vfs-1..vfs-N`.

### Modo de almacenamiento

El build config **no** declara `build-file-db` ⇒ modo **contenedor** (archivos CASC),
no containerless.

## 3. Forever (servidor Classic Forever)

Referencia: `defexnicolas/wow-classic-launcher` (MIT).

- **Portal**: `auth.gpon.com.co` (certificado emitido para el nombre, no la IP).
  Login en `:1119`, mundo en `:8085`.
- **Configuración**: el launcher crea/actualiza `WTF/BetaSuspendedTest.wtf` con
  `SET portal "auth.gpon.com.co"` (copia `Config.wtf` la primera vez) y lanza
  `WowB.exe -config BetaSuspendedTest.wtf`. **No modifica `Config.wtf`.**
- **Parche de clave**: busca en la memoria privada del proceso (rw, no código, no
  disco) el almacén de 12 claves públicas Ed25519 de `NetClient::CertStore` y
  sustituye la clave del grupo 8 por la clave pública del servidor, reaplicando si el
  cliente la recrea. La clave 1 es ancla de búsqueda y nunca se toca.
- Detalle operativo (constantes, layout de entradas, flags): `.agents/references/classic-forever-patch.md`.
- No ejecutar con `sudo` (Wine como root → BC_ASSERT).

## 4. Runtime Android (candidatos, Fase 2)

El siguiente es el baseline histórico de septiembre, no el runtime/dispositivo
activo. La ruta posterior validada está en [estado de producto](launcher-app-status.md).

Dispositivo de aquel baseline: **POCO M7 Pro 5G** (Dimensity 7025, GPU **PowerVR B-Series
BXM-8-256**, Vulkan 1.4, Android 16). Inventario completo y capacidades Vulkan:
`docs/device-poco-m7-pro-5g.md`.

Sin decisión de runtime tomada. Candidatos a evaluar por combinación estable de:

| Capa | Candidatos |
|---|---|
| Contenedor | Winlator u equivalente |
| Wine | build ARM64/ARM64EC |
| x86-64→ARM64 | FEX-Emu, Box64 |
| D3D→Vulkan | DXVK (+ VKD3D si aplica) |
| Driver Vulkan | **propietario PowerVR** (no hay Turnip: es Adreno-only) |

Restricción derivada del dispositivo: el driver Vulkan es el del vendor (PowerVR), sin
alternativa libre; el riesgo se concentra en estabilidad y rendimiento de DXVK sobre él.

**Runtime elegido (baseline histórico de Fase 2)**: **Winlator oficial v11.2** (`com.winlator`,
versionCode 33, minSdk 26) — el APK más ligero de su familia (150 MB) y el único con vía
de driver para GPU no-Adreno (*System Driver*/Vortek/Gladio). Instalado y verificado
arrancando en el POCO M7 Pro 5G. Config inicial del contenedor: Graphics Driver =
*System Driver* (Vulkan PowerVR), DX Wrapper = DXVK, emulador = Box64.

Se probará sobre `WowB.exe` real (no `Wow.exe`), arquitectura objetivo ARM64. No se
escribe runtime propio hasta que Fase 2 dé go.

## 5. Licencias y redistribución

| Componente | Licencia | ¿Empaquetable en APK? |
|---|---|---|
| `wow-classic-launcher` | MIT | Sí (con atribución) |
| `cascette-py` | MIT / Apache-2.0 | Sí (con atribución) |
| Cliente WoW / CASC / `WowB.exe` | Propietario Blizzard | **No** — se descarga en el dispositivo |
| Wine / FEX / Box64 / DXVK | LGPL/MIT/etc. | Sí, respetando sus licencias |
| Runtime Android (Winlator) | GPL en su caso | Revisar al elegir |

El APK debe ser ligero: descarga runtime y cliente en el dispositivo en lugar de
empaquetarlos.

## 6. Decisiones de arquitectura

| ID | Decisión | Motivo |
|---|---|---|
| **D-001** | Motor TACT = `cascette-py` por URL git fijada a commit | No está en PyPI; implementa el flujo de `agent.exe` y resuelve `wow_classic_beta` correctamente |
| **D-002** | Producto autoritativo = `wow_classic_beta`; `cascette-py` se usa desde un **repo propio** (`apazosg/cascette-py`, fork con el enum añadido), dependencia por URL git fijada a commit | El `cdns` de la familia `wow` es idéntico (`tpr/wow`), pero nombrar el producto correcto evita deuda silenciosa. El fork se mantiene como repo propio; **no se contribuye a repos de terceros** |
| **D-003** | Python + `uv` para el provisionador | `uv` ya instalado; sin `pip3` en el host |
| **D-004** | Instalación autocontenida en un directorio (`WowB.exe` + `Data/` + metadatos en la raíz) | El launcher lanza `WowB.exe` desde el directorio de juego |
| **D-005** | Fase 1 se valida en Linux sin Android | Desacopla la incertidumbre de provisión de la de runtime |

## 7. Provisión: `forever-client`

Herramienta de Fase 1 (`US-001`). Subcomandos: `resolve`, `install`, `verify`,
`repair`. Genera el layout `WowB.exe` + `Data/` + metadatos y soporta instalación
mínima (`--loose-only`) para desbloquear Fase 2 con una descarga pequeña.

## 8. Organización de repositorios y compatibilidad upstream

Objetivo explícito del proyecto: **ser la versión Android del servidor Classic Forever**,
compatible con `defexnicolas/wow-classic-launcher` **y con sus versiones futuras**.

Reparto (principio: lo nuestro en un monorepo; lo ajeno que hay que modificar, repo propio):

| Repo | Rol | Relación |
|---|---|---|
| `apazosg/wowforever` | Monorepo de **todo lo nuestro** (Fase 1 `src/forever_client`, contrato `forever/`, Fase 5 `android/`, specs, docs) | — |
| `apazosg/cascette-py` | Repo propio (fork) del motor TACT, con `wow_classic_beta` añadido | Dependencia por URL git fijada a commit |
| `apazosg/wow-classic-launcher` | Repo propio (espejo de solo lectura) para **seguir el contrato** del launcher | No se compila ni se vendoriza |

**Política**: no se abren PRs ni se hace merge en repos de terceros. Todo el trabajo vive en
nuestros repos; el launcher y `cascette-py` upstream solo se **leen** (y el cambio de
`cascette-py` se mantiene en nuestro repo, no se envía río arriba).

Una copia vendorizada del launcher sería deuda: no reutilizamos su *código* (WPF + script
Python), reproducimos su *comportamiento*. **Seguir ≠ copiar.**

### Compatibilidad con futuras versiones: contrato versionado + chequeo de deriva

El contrato del parche **ya divergió** entre plataformas (relleno `7F 00 00` en Windows
frente a `00 00 00` bajo Wine/Proton, medido 2026-09-27). Por eso el contrato no se
hardcodea disperso: vive en un módulo versionado y se comprueba contra upstream.

- `forever/contract.json` — portal, puertos, 12 claves, `TARGET_GROUP`, `ENTRY_SIZE`,
  flags, **regla de relleno por plataforma**, builds soportadas, URL de `status.json`, y
  el commit upstream validado.
- `scripts/contract-drift-check` — extrae las constantes del launcher en un commit/tag
  dado y **falla si difieren** de nuestro contrato. Un build nuevo, una clave nueva o un
  cambio de portal no nos rompen en silencio.

## 9. Riesgos abiertos

- **Fase 2** (arranque en Android) es la puerta real del proyecto; ninguna decisión de
  Fase 1 debe acoplarse a ella.
- **Cambio de build** en la CDN: el launcher solo soporta `69913/69977/70009`; un build
  nuevo rompería el flujo hasta que el launcher lo soporte.
- **Peso real** del subconjunto `Windows`+`x86_64`+`enUS`: sin medir aún.
