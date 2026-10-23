---
id: windows-arm64-runtime-2026-09-29
state: in-progress
date: 2026-09-29
---

## Summary

El usuario pidió verificar el cliente Windows ARM y aplicar el aprendizaje del
paquete GameNative/RP6. Investigación completada: existe PE ARM64 oficial del
mismo build 1.60.1.70009. Cambio de prioridad aprobado por el usuario:
«hagamoslo. vamos a ver si conseguimos avanzar». No se cambian aún contratos.

## Content

- CDN oficial wow_classic_beta, build config 05215079e3905ef5922ae0b03ffefb73.
- WowB-ARM64.exe: 94286544 bytes, máquina PE 0xaa64, MD5
  f8d91fd1b3751e77973035dccbde40e7, SHA256
  d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59.
- Manifiesto asocia Windows+arm64; nuestro filtro Windows+x86_64 excluye ese
  ejecutable. No se modificó ni reemplazó el cliente existente.
- Se verificó estáticamente; no se ejecutó ningún PE descargado. La disponibilidad
  del juego ARM64 no demuestra arquitectura del launcher Battle.net.
- Paquete RP6: Proton11 local con parche syscall ARM64 copiado y NLS; DXVK
  dxgi/d3d11 AA64, distinto de ARM64EC/x64. Turnip/ir3 específicos de Adreno no
  aplican a PowerVR. Fuentes y hashes en docs/community-arm64-findings.md.
- No hay demostración de autenticación Classic Forever/clave grupo8 en el paquete.
  No convertir llegada a mundo oficial en validación del servidor privado.

## Implementation / Action Items

- [ ] Auditar compatibilidad del runtime comunitario con GameNative y su mecanismo
  de instalación WCP; elegir integración mínima reproducible SIN trasplantar
  Proton/DXVK AA64 sobre prefijo FEX x64 existente a ciegas.
- [ ] Preparar sondas Windows ARM64 propias, incluida reproducción de stub syscall
  copiado si técnicamente viable, y casos de control con runtime stock/parcheado.
  No seguir usando solamente las sondas x86_64 como criterio de cliente nativo.
- [ ] Incorporar ensayo nativo en app única con runtime/hash, logs por etapa y
  exportación sin ADB. No prometer ejecución ARM64 en emulador x86_64.
- [ ] Provisión ARM64 fuera de la instalación existente: inventariar todas las
  entradas Windows+arm64 requeridas, no solo EXE. Definir opción arch explícita
  sin cambiar default x86_64 ni contrato existente sin especificación/tests.
- [ ] Validar Wine+sonda ARM64; después D3D11+DXVK AA64 con Vulkan PowerVR del
  sistema. Probar cliente real solo con datos suficientes y logs; no Turnip.
- [ ] Tras arranque/login real, investigar CertStore/layout ARM64 y autenticación
  Forever como puerta independiente; no reutilizar offsets/firmas x64 por nombre.

## Open Issues

- Aprobada la ruta nativa ARM64 como siguiente experimento principal. Preservar
  prototipo FrontMage x86 como control, sin entregar la build pendiente como si
  demostrara el nuevo recorrido.
- Ejecutable ARM64 oficial verificado, pero capacidad de ejecutar stubs propios,
  render PowerVR, datos CASC mínimos y autenticación privada no demostradas.
- Cambios Wine personalizados requieren fuente/procedencia/licencias y comparación
  causal. No asumir que el arreglo syscall resuelve el crash x86/FEX anterior.
- Especificaciones/global stack deben reconciliarse si el experimento da go;
  no declarar eliminado FEX de todos los auxiliares por ser ARM64 el proceso principal.
