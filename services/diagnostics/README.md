# Diagnóstico remoto — backend mínimo

Backend privado de ingestión anónima y consulta de mantenedor según
`docs/diagnostics-api.md`. Sin Next.js, sin frontend, sin SDK de observabilidad.
Se despliega como proyecto Vercel independiente con raíz en `services/diagnostics`.

## Rutas

| Método | Ruta | Auth | Efecto |
|---|---|---|---|
| `POST` | `/api/reports` | Capacidad de escritura (`Idempotency-Key` + `Bearer` base64url 32B) | Reserva atómica de cuota e emite PUT firmados |
| `POST` | `/api/reports/{id}/complete` | Misma capacidad | Verifica objetos exactos y marca completo (idempotente) |
| `GET` | `/api/reports` | `DIAGNOSTICS_READ_TOKEN` | Listado paginado con filtros |
| `GET` | `/api/reports/{id}` | `DIAGNOSTICS_READ_TOKEN` | Detalle sin bytes |
| `GET` | `/api/reports/{id}/attachments/{name}` | `DIAGNOSTICS_READ_TOKEN` | GET firmado de 60 s |
| `GET` (cron) + `POST` (manual) | `/api/cleanup` | `CRON_SECRET` | Borrado de vencidos y huérfanos |

La capacidad de escritura jamás autoriza lectura; un id incorrecto o una
capacidad ajena devuelven el mismo `404` sin distinguir. Toda respuesta privada
lleva `Cache-Control: no-store` y los errores son códigos genéricos.

## Variables de entorno (fijar en Vercel, nunca en el repo)

| Clave | Origen |
|---|---|
| `DATABASE_URL` | Neon (cadena pooled, TLS verificado) |
| `BLOB_READ_WRITE_TOKEN` | Vercel Blob store privado |
| `DIAGNOSTICS_READ_TOKEN` | Token externo del mantenedor (solo entorno local + Vercel) |
| `CRON_SECRET` | Secreto del cron de Vercel (solo Vercel) |
| `DIAGNOSTICS_IP_SALT` | Salt para HMAC de IP (solo Vercel) |
| `RETENTION_DAYS`, `PENDING_TTL_SECONDS`, `UPLOAD_URL_TTL_SECONDS`, `READ_URL_TTL_SECONDS`, `MAX_REPORTS`, `MAX_RESERVED_BYTES`, `META_MAX_BYTES`, `MAX_UPLOAD_ISSUES` | Opcionales con valores del contrato |

Sin cualquiera de los cinco primeros el servicio falla cerrado (`config-unavailable`).

## Despliegue

1. Crear proyecto Vercel con *Root Directory* `services/diagnostics`.
2. Crear base Neon Free y store Blob **privado**; pegar las cinco claves.
3. Aplicar `sql/schema.sql` en Neon.
4. Desplegar; el cron `0 3 * * *` invoca con GET `/api/cleanup` a diario
   (con `CRON_SECRET` automático); el borrado manual usa POST.
5. Compilar el endpoint HTTPS público de ingestión en la app; ningún secreto en el APK.

## Desarrollo

```bash
npm ci
npm run typecheck   # tsc --noEmit
npm test            # tsc + node --test (sin frameworks)
npm run build       # emite a dist/ (ignorado por git)
```

Los tests usan `MemoryStore` + `FakeBlobIssuer` (sin red ni credenciales);
`PostgresStore` solo se ejercita con un pool falso que verifica el orden
transaccional `BEGIN → advisory lock → cuotas → INSERT → COMMIT`.
