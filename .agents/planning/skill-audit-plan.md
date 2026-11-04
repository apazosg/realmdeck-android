---
id: skill-audit-2026-11-04
state: draft
date: 2026-11-04
---

## Summary

Revisar la organización del repositorio y el desarrollo con agentes, manteniendo
especificaciones útiles pero reduciendo las aprobaciones y la documentación repetitiva.
Este plan propone cambios; no autoriza su ejecución hasta su aprobación.

## Content

### Hallazgos

- `AGENTS.md` pide consultar la historia relevante y `SYSTEM.md` para cambios
  transversales; la skill `spec-as-source-management` indica leer `SYSTEM.md`
  siempre y presupone que el host inyecta las historias. Falta aclarar un método
  de descubrimiento que funcione también sin inyección automática.
- La skill de especificaciones exige varias aprobaciones separadas para cambiar
  requisitos; el usuario quiere un flujo spec-driven menos rígido.
- `SYSTEM.md` está en `draft` y US-003, US-007, US-008 y US-009 están `dirty`.
  No está claro cómo aprovechar secciones ya aprobadas sin bloquearse ni tratar
  como aprobadas las partes pendientes. Se requiere revisar cada caso, no asumir
  que esos estados invaliden todo el contenido.
- `AGENTS.md` y la skill duplican reglas del ciclo de specs; conviene conservar
  en el primero un resumen autónomo y una ruta de carga, sin dos procedimientos
  completos que puedan divergir.
- La skill de mantenimiento identifica `/workspace/.agents/skills/` como fuente
  canónica, pero el script calcula la fuente desde la raíz del workspace. Debe
  comprobarse el alcance real antes de editar skills compartidas.
- `docs/README.md` presenta C142 como actual, mientras `README.md` y
  `docs/current-status.md` sitúan C146 como instantánea actual documentada. La
  documentación debe reflejar evidencia fechada, sin suponer que C146 sea la última
  versión real sin comprobación adicional.
- En `.agents/planning/` hay 64 archivos Markdown en la raíz, incluidos 49 con
  `state: in-progress`; esto incluye trabajo local no versionado. Su estado real no
  se ha verificado y no debe inferirse abandono.
- Hay tres `SKILL.md.orig` versionados junto a las skills; su finalidad y autoridad
  necesitan aclaración antes de retirarlos o cambiarlos.
- Los dos workflows de GitHub son de release y se activan por tags o manualmente;
  no hay workflow de `pull_request` en el repositorio. Esto no demuestra ausencia
  de validaciones externas, pero limita la señal automatizada visible aquí.
- La comprobación local `.agents/scripts/sync-shared-skills.sh --check` terminó
  correctamente. El árbol tenía cambios ajenos no versionados en
  `.agents/planning/voice-chat-plan.md` y `.tmp-uia/`; se preservarán.

### Propuesta de principios

1. Mantener especificaciones como fuente de comportamiento, no imponer todos los
   artefactos y puertas de aprobación a cambios rutinarios. Un encargo explícito
   puede autorizar cambios coherentes; consultar decisiones de producto ambiguas,
   riesgos importantes y acciones irreversibles. Preservar pruebas proporcionales.
2. Hacer que un agente pueda localizar contexto por sí mismo, tanto si su host
   inyecta documentos como si no, sin cargar toda la documentación en cada tarea.
3. Separar documentación vigente, histórica y planes realmente activos; no borrar
   evidencia ni reclasificar trabajo ajeno por su antigüedad sin verificarlo.
4. Preferir comprobaciones automáticas ligeras y existentes frente a nuevos
   mecanismos o procesos complejos.

## Implementation / Action Items

- [ ] 1. Completar inventario de instrucciones, skills, referencias, specs y
      puntos de entrada; verificar rutas, redundancias y capacidad de autocarga de
      subagentes. Determinar mecanismo real de sincronización de skills compartidas.
- [ ] 2. Diseñar y aplicar un flujo SDD proporcional: alcance de las specs,
      descubrimiento, autorización, reconciliación y trazabilidad adecuada al riesgo;
      contemplar secciones aprobadas en documentos `dirty` y el `SYSTEM.md` en
      borrador; alinear instrucciones locales y compartidas sin divergencias.
- [ ] 3. Corregir puntos de entrada documentales desactualizados contrastándolos con
      la evidencia vigente; distinguir estado actual y registros históricos.
- [ ] 4. Inventariar planes y respaldos `.orig`, proponer criterios claros de
      vigencia y tratar cada caso únicamente con evidencia y sin tocar cambios
      ajenos. No archivar ni eliminar masivamente por estado o fecha.
- [ ] 5. Evaluar la conveniencia de un CI de validación de PR mínimo con comandos
      existentes y sus dependencias; no publicar ni ejecutar workflows de release.
- [ ] 6. Validar enlaces y consistencia afectados, sincronización de mirrors,
      autocarga de agentes y pruebas concretas para lo modificado. Revisar diff y
      estado, y realizar commits atómicos según las reglas del repositorio.

## Open Issues

- Confirmar ubicación y alcance reales de la fuente canónica de las skills
  compartidas antes de proponer cambios que afecten a otros repositorios.
- Determinar qué planes siguen realmente activos y si los archivos `.orig` tienen
  una función antes de moverlos o eliminarlos.
- Precisar si un CI de PR es deseable y viable en este entorno; podría depender de
  recursos externos. Su inclusión no implica autorización para desplegar o publicar.
