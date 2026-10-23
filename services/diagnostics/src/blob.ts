// Acceso a Blob privado mediante Signed URLs oficiales
// (issueSignedToken + presignUrl). El servidor nunca entrega su token
// de lectura/escritura; solo URLs acotadas a objeto/MIME/tamaño/tiempo.
import { issueSignedToken, list, presignUrl } from "@vercel/blob";
import { isUuid } from "./crypto.js";
import { ATTACHMENT_NAMES } from "./validate.js";

export interface BlobBytes {
  bytes: Buffer;
  contentType: string | null;
}

export interface BlobListItem {
  pathname: string;
  uploadedAtMs: number;
}

export interface BlobIssuer {
  issueUploadUrl(pathname: string, contentType: string, maxBytes: number, validUntilMs: number): Promise<string>;
  issueReadUrl(pathname: string, validUntilMs: number): Promise<string>;
  fetchBytes(pathname: string, maxBytes: number): Promise<BlobBytes>;
  deletePathname(pathname: string): Promise<void>;
  /** Página acotada del listado oficial, solo bajo el prefijo dado. */
  listObjects(prefix: string, limit: number, cursor?: string | null): Promise<{ items: BlobListItem[]; cursor: string | null }>;
}

export function blobPathname(reportId: string, name: string): string {
  return `reports/${reportId}/${name}.txt`;
}

const DERIVED_PATHNAME = /^reports\/([0-9a-fA-F-]{36})\/([a-z]+)\.txt$/;

/**
 * Acepta SOLO pathnames derivados exactos del servidor (UUID + nombre
 * allowlistado). Cualquier otra forma —incluidas URLs— se rechaza: el
 * barrido de huérfanos jamás toca objetos arbitrarios.
 */
export function parseDerivedPathname(pathname: string): { reportId: string; name: string } | null {
  const match = DERIVED_PATHNAME.exec(pathname);
  if (match === null) return null;
  const reportId = match[1] as string;
  const name = match[2] as string;
  if (!isUuid(reportId)) return null;
  if (!(ATTACHMENT_NAMES as readonly string[]).includes(name)) return null;
  return { reportId, name };
}

/**
 * Allowlist para firmas de subida: Blob normaliza `text/plain; charset=utf-8`
 * a `text/plain` al comprobar allowedContentTypes, así que la firma debe usar
 * el tipo base. El manifiesto y el wire de Android conservan el exacto.
 */
export function uploadAllowlist(contentType: string): string[] {
  const base = contentType.split(";")[0]?.trim().toLowerCase() ?? "";
  return [base];
}

export class VercelBlobIssuer implements BlobIssuer {
  constructor(private readonly readWriteToken: string) {}

  async issueUploadUrl(pathname: string, contentType: string, maxBytes: number, validUntilMs: number): Promise<string> {
    const allowlist = uploadAllowlist(contentType);
    const token = await issueSignedToken({
      pathname,
      operations: ["put"],
      allowedContentTypes: allowlist,
      maximumSizeInBytes: maxBytes,
      validUntil: validUntilMs,
      token: this.readWriteToken,
    });
    const { presignedUrl } = await presignUrl(token, {
      operation: "put",
      pathname,
      access: "private",
      allowedContentTypes: allowlist,
      maximumSizeInBytes: maxBytes,
      validUntil: validUntilMs,
      addRandomSuffix: false,
      allowOverwrite: false,
    });
    return presignedUrl;
  }

  async issueReadUrl(pathname: string, validUntilMs: number): Promise<string> {
    const token = await issueSignedToken({
      pathname,
      operations: ["get"],
      validUntil: validUntilMs,
      token: this.readWriteToken,
    });
    const { presignedUrl } = await presignUrl(token, {
      operation: "get",
      pathname,
      access: "private",
      validUntil: validUntilMs,
      useCache: false,
    });
    return presignedUrl;
  }

  async fetchBytes(pathname: string, maxBytes: number): Promise<BlobBytes> {
    const url = await this.issueReadUrl(pathname, Date.now() + 60_000);
    const response = await fetch(url);
    if (response.status === 404) {
      throw new BlobMissingError(pathname);
    }
    if (!response.ok) throw new Error(`blob fetch failed (${response.status})`);
    const contentType = response.headers.get("content-type");
    const buffer = Buffer.from(await response.arrayBuffer());
    if (buffer.length > maxBytes) throw new Error("blob exceeds declared size");
    return { bytes: buffer, contentType };
  }

  async deletePathname(pathname: string): Promise<void> {
    const token = await issueSignedToken({
      pathname,
      operations: ["delete"],
      validUntil: Date.now() + 60_000,
      token: this.readWriteToken,
    });
    const { presignedUrl } = await presignUrl(token, {
      operation: "delete",
      pathname,
      access: "private",
    });
    const response = await fetch(presignedUrl, { method: "DELETE" });
    // Borrado idempotente: ausente cuenta como borrado.
    if (response.status !== 404 && !response.ok) throw new Error(`blob delete failed (${response.status})`);
  }

  async listObjects(prefix: string, limit: number, cursor?: string | null): Promise<{ items: BlobListItem[]; cursor: string | null }> {
    const options: { prefix: string; limit: number; token: string; cursor?: string } = { prefix, limit, token: this.readWriteToken };
    if (cursor !== null && cursor !== undefined) options.cursor = cursor;
    const result = await list(options);
    return {
      items: result.blobs.map((blob) => ({
        pathname: blob.pathname,
        uploadedAtMs: blob.uploadedAt instanceof Date ? blob.uploadedAt.getTime() : Date.parse(String(blob.uploadedAt)),
      })),
      cursor: result.hasMore && typeof result.cursor === "string" ? result.cursor : null,
    };
  }
}

export class BlobMissingError extends Error {
  constructor(readonly pathname: string) {
    super("blob not found");
  }
}

/** Doble en memoria para tests: sin red, bytes exactos. */
export class FakeBlobIssuer implements BlobIssuer {
  readonly objects = new Map<string, { bytes: Buffer; contentType: string; uploadedAtMs?: number }>();
  issuedUploads: Array<{ pathname: string; contentType: string; maxBytes: number }> = [];
  issuedReads = 0;

  async issueUploadUrl(pathname: string, contentType: string, maxBytes: number): Promise<string> {
    this.issuedUploads.push({ pathname, contentType, maxBytes });
    return `https://blob.test/${pathname}?upload=1`;
  }

  async issueReadUrl(pathname: string): Promise<string> {
    this.issuedReads += 1;
    return `https://blob.test/${pathname}?read=1`;
  }

  async fetchBytes(pathname: string, maxBytes: number): Promise<BlobBytes> {
    const object = this.objects.get(pathname);
    if (object === undefined) throw new BlobMissingError(pathname);
    if (object.bytes.length > maxBytes) throw new Error("blob exceeds declared size");
    return { bytes: object.bytes, contentType: object.contentType };
  }

  async deletePathname(pathname: string): Promise<void> {
    this.objects.delete(pathname);
  }

  async listObjects(prefix: string, limit: number, cursor?: string | null): Promise<{ items: BlobListItem[]; cursor: string | null }> {
    const names = [...this.objects.keys()].filter((name) => name.startsWith(prefix)).sort();
    const start = cursor === null || cursor === undefined ? 0 : names.indexOf(cursor) + 1;
    const page = names.slice(start, start + limit);
    return {
      items: page.map((pathname) => ({
        pathname,
        uploadedAtMs: this.objects.get(pathname)?.uploadedAtMs ?? 0,
      })),
      cursor: start + limit < names.length ? (page[page.length - 1] as string) : null,
    };
  }
}
