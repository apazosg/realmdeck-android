---
id: integrated-runtime-prototype-2026-09-28
state: in-progress
date: 2026-09-28
---

## Summary

El usuario confirma objetivo de app única y propone como referencia
https://github.com/marcocastignoli/wow-mobile. Investigación estática realizada;
no se ha clonado, compilado ni ejecutado ese proyecto. Se propone un prototipo
aislado de runtime integrado para ejecutar sondas, no una migración del launcher
ni asumir superada Fase 2. Usuario aprobó avanzar con criterio de máximo desbloqueo.

## Content

- Referencia inspeccionada: commit
  `0c8068f617737d9c0fb6246f37b54f85b620b09d`, tag v0.1.2.
- Es un fork de Winlator con ejecución local, contenedor único y launcher WoW.
  WowContainerHelper monta carpeta cliente como F: y arranca una Activity interna
  con container_id/shortcut_path. No depende de controlar otro APK.
- Orientado a WoW 3.3.5a, Wow.exe/realmlist/ConsolePortLK, NO WowB Beta/Forever.
  Sus cambios de Config.wtf no deben trasladarse a nuestro patcher.
- Runtime identificado: Wine 10.10 y Box64 0.4.0; ARM64. No FEX/ARM64EC.
  No hay evidencia inspeccionada de compatibilidad con nuestro cliente ni GPU.
- LGPL-2.1 en raíz; inventario de componentes y fuentes correspondientes pendiente
  antes de redistribuir builds. No basta la licencia de raíz para todos los assets.
- GitHub CLI no disponible; investigación mediante fuentes web de GitHub.
- El kit manual de runtime-probe-kit-plan.md queda aparcado por elección del
  usuario de integración en una sola app.

### Decisión propuesta

Usar wow-mobile como base de un experimento separado, fijado a commit, no importar
su árbol y assets masivamente al repo ni reemplazar el APK diagnóstico funcional.
Esto reutiliza un runtime existente, no construye uno propio. Una única app para
el usuario; primero ejecuta sondas en lugar del juego. No empaquetar varios runtimes.

## Implementation / Action Items

- [ ] Auditar build/fuentes de componentes/licencias y procedencia de binarios,
  gitlinks reales de submódulos y herramientas requeridas. Limitar descarga inicial
  a fuentes necesarias; assets grandes fuera del repo.
- [ ] Reproducir compilación en workspace externo fijado al commit. Conservar
  APK actual y clave de firma; identificar prototipo con paquete distinto para
  no sobrescribir instalación/datos del usuario durante el experimento.
- [ ] Sustituir recorrido inicial WotLK por ejecución de ApiProbe y DynCodeProbe
  sin cliente, realm, addon ni cambios a configuración del juego.
- [ ] Conservar build/runtime/hashes/configuración y logs propios por ejecución;
  exportar informe desde la misma app sin ADB. Fallos/incompletos no son PASS.
- [ ] Validar host con Wine y tests de lógica. Emulador solo para componentes
  compatibles; no prometer instalación/ejecución ARM64 en emulador x86_64.
- [ ] Revisar límites de procesos/UID y viabilidad de integrar patcher existente;
  no darla por probada por compartir APK.
- [ ] Entregar prototipo firmado con hash vía SFTP y probar sondas en móvil.
- [ ] Go/no-go del prototipo: sondas Windows ejecutadas e informe exportado sin
  otra app/ADB. Si falla, conservar diagnóstico; no llamar a esto go de WoW.

## Open Issues

- Nueva evidencia externa: vídeo `1jQ3ApshCOg`, título verificable **“WoW
  forever running on Gamehub Android”**, canal Mage Front. Muestra WoW jugable
  con ~59 FPS, pero no muestra build, `WowB.exe`, servidor, contrato, FEX/Wine,
  dispositivo ni parche group8. El runtime indicado por el título es **GameHub**,
  no FrontMage; el nombre del canal no prueba qué app se utilizó. Antes de seguir
  invirtiendo en el fork Box64 de wow-mobile, obtener del autor cliente, runtime
  y configuración exactos o reproducir esa combinación con nuestro cliente.

- Gate de reproducibilidad bloqueado: parches aún no aplican a la base; checkout
  sparse incompleto (4/96 assets). No se ejecutó Gradle ni hay APK integrado.
  Estado y siguiente gate en `docs/integrated-runtime-prototype.md`.

- Aprobado wow-mobile como base experimental, no como elección definitiva.
- Box64 0.4.0 es anterior al 0.4.4 ya ensayado sin éxito con WowB: reutilizar UI/
  integración no implica resolver fallo loader ni preferencia técnica sobre FEX.
- Si build, licencias o extracción de dependencias exigen cambios estructurales,
  parar y comparar base Winlator/FrontMage antes de invertir más.
- GPU PowerVR: no adoptar Turnip por defecto ni asumir rendimiento D3D9/WotLK
  equivalente al cliente Beta. Probar render del cliente como fase posterior.
