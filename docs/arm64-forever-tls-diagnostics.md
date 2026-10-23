# ARM64 + Forever: TLS y confianza interna

Actualizado2026-10-14. Objetivo: distinguir transporte TLS/X509 y verificación
propietaria del cliente antes de elegir una adaptación. Estado general:
[ARM64 + Forever](arm64-forever-status.md).

## Permiso y fronteras

El usuario confirma que sirve cualquier solución viable, incluyendo desactivar
validación TLS. Eso permite evaluar una variante aislada y reversible; no
demuestra que TLS sea la causa ni autoriza cambiar verificaciones de otras
aplicaciones o modificar Btrfs. No se ha implementado ningún bypass TLS.
Los originales se conservan. Los controles de captura siguen sin Login ni
credenciales; no reutilizar cuentas temporales históricas automáticamente.

## T1 — Handshake estricto desde el host

**Pregunta:** ¿El endpoint de auth ofrece TLS con un certificado aceptado por
las CA del host Linux y válido para su hostname?

- Endpoint fijo del contrato: `auth.gpon.com.co:1119`, sin otros hosts/puertos.
- Python `ssl.create_default_context()`, hostname/SNI verificables, mínimoTLS1.2,
  timeout5s por conexión/handshake. Una conexión, sin datos de aplicación ni
  credenciales. `validationDisabled=false`.
- Resultado: `STRICT_TLS_ACCEPTED`, TLS1.3,
  `TLS_AES_256_GCM_SHA384`,432ms. SAN `DNS:auth.gpon.com.co`, vencimiento
  `Dec 17 06:31:02 2026 GMT` observado. No se retuvo certificado privado ni tráfico.

## T2 — Compatibilidad TLS1.2 del mismo endpoint

**Pregunta:** ¿El servidor también acepta TLS1.2, sin depender de TLS1.3?

- Mismo contexto estricto y endpoint, mínimo=máximoTLS1.2. Una conexión adicional,
  sin Login, credenciales o datos de aplicación; timeout5s.
- Resultado: `STRICT_TLS_ACCEPTED`, TLS1.2,
  `ECDHE-ECDSA-AES256-GCM-SHA384`,311ms. Validación no desactivada.

Estos dos resultados descartan que, desde este host y en este momento, el endpoint
sea inalcanzable o necesariamente presente una cadena/hostname inválidos, o
exija exclusivamente TLS1.3. **No prueban** el trust store de Wine/Android, su
selección de cipher, el transporte usado por WoW, el challenge de autenticación,
aceptación de cuenta o causa de WOW51900317. No modifican Wine ni el juego.

## Métodos candidatos, sin elegir por semejanza de nombres

| Método | Evidencia | Límite / siguiente pregunta |
|---|---|---|
| Configuración o confianza X509 de Wine | Hay actividad histórica Wine TLS/certificados después de Enter; T1/T2 prueban el endpoint desde Linux. | Falta una llamada TLS concreta del cliente y evidencia del trust store/certificado visto por su runtime; los `fixme` no son diagnóstico. |
| Flags WinHTTP para ignorar validación X509 | API Windows documenta `WINHTTP_OPTION_SECURITY_FLAGS`. | No hay uso WinHTTP confirmado en este build ARM64; imports previos incompletos. No inventar una clave de registro Wine o flag CLI `ignoreTLS`. |
| Adaptación o bypass del verificador nativo | Textos CertStore y método PC grupo8; referencias públicas de parches para otras versiones. | No se identificó caller/retorno/cuerpo del verificador70009. Las cadenas y métodos42597 no identifican un offset aplicable aquí. |
| Sustitución de clave propietaria | Método x64 validado sobre doce entradas12×40. | No demuestra layout/materialización ARM64 ni relación directa con certificados X509 del transporte. |

Desactivar una comprobación X509 no implica desactivar el challenge/firma que
consume `NetClient::CertStore`. Tampoco un `SslStream` inseguro de un launcher
externo demuestra que cambie la verificación del juego. `allowDevPublicKey` es
texto descriptivo observado, no un mecanismo de configuración validado.

Referencias de esta evaluación:

- [Resultados funcionales](functional-forever-profiles-results.md).
- [Investigación PC: imports incompletos](arm64-certstore-pc-investigation.md).
- [Launchers públicos y límites de build](arm64-private-server-public-references.md).
- [WinHTTP option flags](https://learn.microsoft.com/en-us/windows/win32/winhttp/option-flags).

No repetir T1/T2 idénticos como prueba de autenticación. El siguiente diagnóstico
de transporte debe usar el runtime real o un callsite demostrado y registrar
solo estado/errores públicos, sin buffers de cuenta. La investigación de código
runtime continúa por separado con dos ventanas fijas32KiB/max64KiB autorizadas.
