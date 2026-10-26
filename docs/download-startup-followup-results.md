# Preparación de descarga: seguimiento C151 → C152

Evidencia del 26 de octubre de 2026. Goal
`786f802d-773f-4396-95f0-7126786de75e`; [plan archivado](../.agents/planning/done/download-startup-followup-plan.md).
La [referencia C151](download-resume-baseline-review.md) corresponde al objetivo
anterior, ya cerrado; no se repitieron su build, pruebas ni ensayo completo.

## Resultado completo en Android

| Recorrido | C151 | C152 | Reducción observada |
| --- | ---: | ---: | ---: |
| Confirmar → transferencia visible | 494,323 s | **422,589 s** | 71,734 s / **14,5 %** |
| Resume → transferencia visible | 772,468 s | **667,502 s** | 104,966 s / **13,6 %** |
| Continue → resumen, sin payload | 370,247 s | 300,074 s | 70,173 s; no sustituye las métricas anteriores |

Son aproximadamente **7:03 y 11:08** hasta transferencia visible. Sigue siendo
una preparación larga, no un inicio inmediato. Mismo dispositivo/runtime,
cliente 1.60.1.70532, destino USB y catálogo objetivo de 1144978 objetos; cambian
el avance del checkpoint, datos pendientes y caché/temperatura no controlados.
Es una comparación observacional, no un A/B causal ni una promesa universal.

El reloj parte del Enter real enviado por UHID; UI observada cada 15 s más el
coste del dump. Primer feedback observado a +2,881 s / +2,673 s, no una prueba de
latencia subsegundo. Primera muestra positiva: 26,7 MiB / 28,6 MiB. Ni esa muestra
ni la primera petición de fuente prueban el instante exacto del primer byte de red.

## Cambio seleccionado por medición

Un muestreo ART acotado de C151 durante inspección atribuyó 12,495 s de 54,520 s
de CPU del hilo a `PatternNative.compileImpl` (22,92 %). El perfil instrumentado
es diagnóstico: los tiempos inclusivos se solapan y no se suman ni predicen el
ahorro global.

`36cb879` construye una vez los mismos patrones hexadecimales constantes de CASC
y catálogo. Conserva cada comparación, sensibilidad a mayúsculas, normalización,
rechazo y orden de validación. Solo comparte patrones inmutables, nunca matchers
o resultados. No cambia consultas, slots, decodificador, autoridad de metadatos,
cadencia, formatos, recuperación ni barreras. No hubo profiler ART en el ensayo
completo C152 usado en la tabla.

Fases observadas, no aditivas ni controladas:

| Fase | C151 | C152 |
| --- | ---: | ---: |
| Inspección tras confirmar | 134380 ms | 100953 ms |
| Dos inspecciones al reanudar | 167615 / 133416 ms | 131514 / 98175 ms |
| Carga de checkpoint, confirmar / Resume | 60640 / 60669 ms | 50981 / 50672 ms |

No hubo rehash dentro de las inspecciones; persisten cinco crecimientos en cada
carga de checkpoint, incluidos en su duración. El writer conserva reutilización
de la fila admitida y lectores bulk; diagnósticos C152: `fallbacks=0`,
`checkpointBatchCalls=3063/3127`, `checkpointBatchKeys=777762/793117`.
Son lecturas acumuladas de varias pasadas, no objetos únicos ni verificación
completa del prefijo.

## Consentimiento, UX y progreso

El usuario confirmó expresamente el plan real: descarga 62,5 GiB, espacio adicional
64,7 GiB, app hasta 1,7 GiB y volumen cliente hasta 62,9 GiB. No se aceptaron otros
costes ni se dejó descargando todo el cliente.

- Flujo de tres acciones: abrir gestión, Continue, Download. Un único Download
  a las 11:47:28,325518 UTC; pausa a las 11:54:59,400407.
- Un Resume a las 11:55:31,719697, sin reconfirmación. Volver/Ver descarga
  conservan el mismo proceso y servicio; una instancia y progreso visible.
- Pausa final 12:07:34,708369. Exposición desde la primera muestra de transferencia
  hasta el Enter de pausa: 28,486 s / 55,487 s; no son ventanas de throughput.
- Checkpoint recargado: **363961 + 1648 = 365609** objetos. Últimos commits de
  cada fase: 1648 / 4185 objetos y 49074527 / 108594802 bytes escritos. Los contadores
  intermedios son acumulativos: no se suman; bytes escritos no son bytes de red.
- Comprobación fresca posterior: C152, En pausa/Reanudar y **cero servicios updater**.
  Los reinicios del servidor no relanzaron el controlador ni repitieron acciones.

No se demuestra publicación/consistencia completa del cliente ni verificación
completa del prefijo en esta ventana breve. No se ejecutó el juego ni se midió
estabilidad prolongada, rendimiento gráfico o throughput sostenido.

## Validación y procedencia

- Seis focales Kotlin únicos verdes: claves/normalización, catálogo/batching,
  fallo y cancelación antes de reparar cola, metadatos antes de ACK y solapamiento.
  El único fallo inicial era una expectativa nueva equivocada: la API conserva
  `originalSpec`; solo se corrigió y reejecutó ese test. Cinco focales Python verdes
  para identidad y autenticación; no se afirma una suite completa.
- Revisión semántica independiente del snapshot: sin hallazgos accionables.
  El revisor no accedió al filesystem ni ejecutó pruebas; cotejos de fuente,
  hashes, XML y staged diff fueron comprobados por el agente principal.
- C152 `0.2.128-regex-startup-test`, retag `269f3b3`. Reconstrucción autenticada
  fresca: seis diferencias admitidas respecto a C151 y seis fuentes críticas
  cotejadas. Build Modern/JDK17 offline exitoso (5m57s).
- APK exacto: 484925891 bytes, SHA-256
  `252c87023016f0438588e1d6bb84a2aa9168b744d671878f49fb2933e2b348b6`.
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`: 2226 fuentes sin cambios, 178 payloads.
  Actualización `install -r` preservando datos; sin reset, desinstalación,
  publicación ni excepciones nuevas del auditor. No es un chequeo íntegro del cliente.

Artefactos externos, no versionados:

- `download-startup-followup-20261026/inspection-profile/`: traza y agregado ART.
- `download-startup-followup-20261026/focused-tests/`: XML iniciales y selectivo final.
- `download-regex-c152-20261026/`: recibo, build, auditoría, instalación y
  `physical-trial/{prepare-summary,download-resume-summary}.json`.

## Auditoría del objetivo

| Criterio | Evidencia y límite |
| --- | --- |
| Reducción adicional global | 422,589 / 667,502 s frente a 494,323 / 772,468 s; método y diferencias declarados, no solo resumen o perfil. |
| Primera acción, flujo 3/1 y reapertura | Log de acciones, feedback, ausencia de reconfirmación y mismo proceso/servicio único; no se prueba doble tap sintético ni muerte de proceso. |
| Garantías y regresiones | Patrones exactos, seis focales Kotlin, cinco Python y revisión; consentimiento, EKey/CKey/CRC, ACK/cpByteEnd, cotas, Wi-Fi, identidad/espacio y exclusión sin cambios. Trazabilidad: [US-008](../.agents/specs/US-008.md), AC-07/08/10/12 y FR-002/003/004/005/008/009/011. |
| Candidata autenticada y progreso | Recibo, fuente cotejada, APK auditado exacto instalado; consentimiento real, checkpoint recargado y pausa final. |
| Entrega acotada | Commits propios separados de código/identidad/documentación, plan archivado, enlaces comprobados y revisión del staged diff/estado final. |
