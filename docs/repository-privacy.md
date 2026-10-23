# Privacidad del repositorio

El código es publicable; los identificadores físicos, credenciales, claves de firma
e informes privados no lo son. Mantener artefactos y capturas fuera del repositorio.
Si hace falta un directorio local dentro del checkout, `private/` está ignorado.

## Selección del teléfono

`DEVICE_SERIAL` en documentos y contratos es un marcador, no un teléfono real.
Los runners USB históricos reciben la selección por `WOWFOREVER_ADB_SERIAL`;
no guardar el valor en scripts ni sustituir el marcador en documentación versionada.
La variable no sustituye la autorización de la prueba ni sus comprobaciones de
modelo, paquete, versión y exclusión.

Se puede exportar en la terminal sin escribir el valor en su historial:

```bash
read -r -s -p 'Serial USB: ' WOWFOREVER_ADB_SERIAL
printf '\n'
export WOWFOREVER_ADB_SERIAL
```

Un fichero `.env.local` también queda ignorado, pero no se carga automáticamente:
exportar explícitamente las variables necesarias. Los informes generados pueden
contener el serial seleccionado; revisar/sanear cualquier extracto antes de añadirlo
a documentación pública. No publicar capturas de cuentas o pantallas de login.

## Qué protege `.gitignore`

Se excluyen `.env`/`.env.*` (salvo `.env.example`), configuración local de Vercel,
claves y almacenes de firma, capturas HAR/PCAP y `private/`. El ejemplo de entorno
del backend se conserva **sin credenciales**. Conservar también fuera de Git los
datos de diagnóstico, tokens de administración, binarios y cliente/CASC.

**No es un filtro de contenido:** no protege un serial pegado en un Markdown,
un secreto en un archivo ya versionado ni datos del historial. `git add -f` puede
saltarse las exclusiones. Revisar los archivos concretos y el diff preparado antes
de cada commit; no añadir indiscriminadamente todo el checkout. Las exclusiones
no sustituyen una revisión de secretos antes de publicar.

## Acceso a GitHub

La referencia de acceso a GitHub se conserva solo en el checkout local, ignorada y
fuera del seguimiento de Git, igual que la credencial. Nunca versionar el valor
del PAT ni copiarlo a documentación pública.

## Después del saneado del historial

Los hashes cambian. No mezclar ni volver a subir ramas basadas en el historial
antiguo: podrían reintroducir los datos retirados. Coordinar otros clones y usar
un clon nuevo del remoto saneado una vez confirmada su actualización. Los respaldos
anteriores son privados y nunca deben publicarse como ramas, tags o releases.

Reescribir el historial local no borra automáticamente copias, forks, cachés de
GitHub, referencias de PR ni adjuntos/Actions. Verificar el remoto y esos recursos
antes de abrirlo. No se promete una eliminación global ni un escaneo exhaustivo.

Comprobación del 2026-10-23: historial saneado de 140 commits revisado, adoptado y
subido únicamente a `main` mientras GitHub sigue privado. El servidor todavía
devuelve un blob antiguo con el identificador retirado: no hacerlo público hasta
resolverlo mediante una purga comprobada o una ruta de repositorio nuevo aprobada.
GitHub documenta esta retención y la gestión de cachés/GC por Support en
[Removing sensitive data](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).
No publicar respaldos ni mezclar el historial antiguo con el saneado.
