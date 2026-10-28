const API = "https://api.curseforge.com/v1";
const GAME_ID = 1;
const VERSION = "1.60.1";
const PAGE_SIZE = 20;
const MAX_JSON = 2 * 1024 * 1024;
const MAX_ZIP = 64 * 1024 * 1024;
const TIMEOUT_MS = 12_000;
const API_KEY_ENV = "CURSEFORGE_API_KEY";

export interface AddonRequest {
  method?: string;
  query: Record<string, string | string[] | undefined>;
}

export interface AddonResponse {
  status(code: number): AddonResponse;
  setHeader(name: string, value: string): void;
  json(data: unknown): void;
  write?(chunk: Uint8Array): boolean;
  end?(): void;
  destroy?(error?: Error): void;
  once?(event: string, listener: (...args: never[]) => void): AddonResponse;
  off?(event: string, listener: (...args: never[]) => void): AddonResponse;
}

type Fetcher = typeof fetch;
interface ServiceOptions { apiKey?: string; fetcher?: Fetcher; }
interface Mod { id: number; name: string; summary?: string; authors?: { name?: string }[]; links?: { websiteUrl?: string }; }
interface CfFile {
  id: number; modId: number; displayName?: string; fileName?: string; fileLength?: number;
  isAvailable?: boolean; isAlternate?: boolean; releaseType?: number; gameVersions?: string[];
  hashes?: { algo: number; value: string }[];
  dependencies?: { modId: number; relationType: number }[];
}
let activeRoutes = 0;
const MAX_CONCURRENT = 4;

class AddonError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

function getKey(options: ServiceOptions): string {
  const key = options.apiKey ?? process.env[API_KEY_ENV];
  if (!key?.trim()) throw new AddonError(503, "service-not-configured");
  return key.trim();
}

function queryValue(request: AddonRequest, name: string): string | null {
  const value = request.query[name];
  return typeof value === "string" ? value : null;
}

function requiredInt(raw: string | null, name: string, max = Number.MAX_SAFE_INTEGER): number {
  if (!raw || !/^[1-9]\d*$/.test(raw)) throw new AddonError(400, `invalid-${name}`);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value > max) throw new AddonError(400, `invalid-${name}`);
  return value;
}

function sendJson(res: AddonResponse, status: number, body: unknown): void {
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.status(status).json(body);
}

function acquireRoute(res: AddonResponse): boolean {
  if (activeRoutes >= MAX_CONCURRENT) { sendJson(res, 429, { error: "rate-limited" }); return false; }
  activeRoutes++;
  return true;
}

function errorStatus(error: unknown): [number, string] {
  if (error instanceof AddonError) return [error.status, error.code];
  if (error instanceof DOMException && error.name === "AbortError") return [504, "upstream-timeout"];
  return [502, "upstream-unavailable"];
}

async function upstream(url: string, key: string, options: ServiceOptions, signal?: AbortSignal, apiKeyMode: "api" | "cdn" = "api"): Promise<Response> {
  if (signal?.aborted) throw new AddonError(504, "upstream-timeout");
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  try {
    if (signal?.aborted) throw new AddonError(504, "upstream-timeout");
    const headers = new Headers({ Accept: "application/octet-stream" });
    if (apiKeyMode === "api" || apiKeyMode === "cdn") headers.set("x-api-key", key);
    const response = await (options.fetcher ?? fetch)(url, {
      headers,
      redirect: "manual", signal: controller.signal,
    });
    if (response.status === 401 || response.status === 403) { await cancelBody(response); throw new AddonError(502, "access-unavailable"); }
    if (response.status === 429) { await cancelBody(response); throw new AddonError(429, "rate-limited"); }
    if (!response.ok) { await cancelBody(response); throw new AddonError(502, "upstream-unavailable"); }
    return response;
  } catch (error) {
    if (error instanceof AddonError) throw error;
    if (controller.signal.aborted) throw new AddonError(504, "upstream-timeout");
    throw new AddonError(502, "upstream-unavailable");
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}

async function cancelBody(response: Response): Promise<void> {
  try { await response.body?.cancel(); } catch { /* release best effort; never expose upstream details */ }
}

async function jsonResponse(response: Response, signal?: AbortSignal): Promise<unknown> {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > MAX_JSON) { await cancelBody(response); throw new AddonError(502, "upstream-invalid-response"); }
  const reader = response.body?.getReader();
  if (!reader) { await cancelBody(response); throw new AddonError(502, "upstream-invalid-response"); }
  const abort = () => { void reader.cancel(); };
  if (signal?.aborted) abort();
  else signal?.addEventListener("abort", abort, { once: true });
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (signal?.aborted) throw new AddonError(504, "upstream-timeout");
      if (done) break;
      size += value.byteLength;
      if (size > MAX_JSON) { await reader.cancel(); throw new AddonError(502, "upstream-invalid-response"); }
      chunks.push(value);
    }
  } finally { signal?.removeEventListener("abort", abort); reader.releaseLock(); }
  try { return JSON.parse(new TextDecoder().decode(Buffer.concat(chunks))); }
  catch { throw new AddonError(502, "upstream-invalid-response"); }
}

async function cfJson(url: string, key: string, options: ServiceOptions): Promise<unknown> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try { return await jsonResponse(await upstream(url, key, options, controller.signal), controller.signal); }
  catch (error) {
    if (controller.signal.aborted) throw new AddonError(504, "upstream-timeout");
    throw error;
  } finally { clearTimeout(timeout); }
}

function dataOf(value: unknown): unknown[] {
  if (typeof value !== "object" || value === null || !Array.isArray((value as { data?: unknown }).data)) {
    throw new AddonError(502, "upstream-invalid-response");
  }
  return (value as { data: unknown[] }).data;
}

async function foreverType(key: string, options: ServiceOptions): Promise<number> {
  const data = dataOf(await cfJson(`${API}/games/${GAME_ID}/version-types`, key, options));
  const matches = data.filter((item): item is { id: number; name: string } =>
    typeof item === "object" && item !== null &&
    (item as { name?: unknown }).name === "Forever" && Number.isSafeInteger((item as { id?: unknown }).id));
  if (matches.length !== 1) throw new AddonError(503, "compatibility-unverified");
  return matches[0]!.id;
}

function validMod(value: unknown): value is Mod {
  return typeof value === "object" && value !== null && Number.isSafeInteger((value as Mod).id) && (value as Mod).id > 0 &&
    typeof (value as Mod).name === "string";
}
function validFile(value: unknown): value is CfFile {
  return typeof value === "object" && value !== null && Number.isSafeInteger((value as CfFile).id) &&
    (value as CfFile).id > 0 && Number.isSafeInteger((value as CfFile).modId) && (value as CfFile).modId > 0;
}
function validDependencies(file: CfFile): boolean {
  return Array.isArray(file.dependencies) && file.dependencies.every((dependency) =>
    typeof dependency === "object" && dependency !== null && Number.isSafeInteger(dependency.modId) && dependency.modId > 0 &&
    Number.isSafeInteger(dependency.relationType) && dependency.relationType >= 1 && dependency.relationType <= 6);
}
function hasExactCompatibility(file: CfFile): boolean {
  return Array.isArray(file.gameVersions) && file.gameVersions.includes(VERSION) &&
    file.gameVersions.includes("Forever");
}
function sha1(file: CfFile): string | null {
  const hash = file.hashes?.find((item) => item.algo === 1 && /^[a-f\d]{40}$/i.test(item.value));
  return hash?.value.toLowerCase() ?? null;
}
function fileAccepted(file: CfFile, modId: number): boolean {
  return file.modId === modId && file.isAvailable === true && file.isAlternate === false && file.releaseType === 1 &&
    hasExactCompatibility(file) && validDependencies(file) && sha1(file) !== null && typeof file.fileName === "string" &&
    Number.isSafeInteger(file.fileLength) && file.fileLength! > 0 && file.fileLength! <= MAX_ZIP;
}

async function projectFiles(projectId: number, key: string, options: ServiceOptions): Promise<{ mod: Mod; files: CfFile[] }> {
  const versionTypeId = await foreverType(key, options);
  const result = await cfJson(`${API}/mods/${projectId}`, key, options);
  const mod = typeof result === "object" && result !== null ? (result as { data?: unknown }).data : null;
  if (!validMod(mod) || mod.id !== projectId) throw new AddonError(404, "project-not-found");
  if ((mod as Mod & { isAvailable?: boolean }).isAvailable === false ||
      (mod as Mod & { allowModDistribution?: boolean }).allowModDistribution !== true) throw new AddonError(409, "distribution-disabled");
  const data = dataOf(await cfJson(`${API}/mods/${projectId}/files?gameVersion=${encodeURIComponent(VERSION)}&gameVersionTypeId=${versionTypeId}&pageSize=50`, key, options));
  if (data.some((item) => typeof item === "object" && item !== null && (item as { modId?: unknown }).modId === projectId &&
      (!validFile(item) || !validDependencies(item)))) throw new AddonError(502, "upstream-invalid-response");
  const files = data.filter(validFile).filter((f) => fileAccepted(f, projectId)).sort((a, b) => b.id - a.id);
  return { mod, files };
}

async function publicDependencies(file: CfFile, key: string, options: ServiceOptions): Promise<{ projectId: number; name: string }[]> {
  if (!validDependencies(file)) throw new AddonError(502, "upstream-invalid-response");
  const ids = [...new Set(file.dependencies!.filter((d) => d.relationType === 3).map((d) => d.modId))];
  if (ids.length > 10) throw new AddonError(502, "upstream-invalid-response");
  const dependencies = await Promise.all(ids.map(async (projectId) => {
    const result = await cfJson(`${API}/mods/${projectId}`, key, options);
    const mod = typeof result === "object" && result !== null ? (result as { data?: unknown }).data : null;
    if (!validMod(mod) || mod.id !== projectId) throw new AddonError(502, "upstream-invalid-response");
    return { projectId, name: mod.name };
  }));
  return dependencies;
}

function safeWebsite(value: unknown): string {
  if (typeof value !== "string") return "";
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.hostname === "www.curseforge.com" && !url.port && !url.username && !url.password &&
      !url.search && !url.hash && /^\/wow\/addons\/[a-z0-9][a-z0-9-]*$/i.test(url.pathname) ? url.href : "";
  } catch { return ""; }
}

function waitForDrain(res: AddonResponse, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.reject(new AddonError(504, "upstream-timeout"));
  if (!res.once) return Promise.reject(new AddonError(502, "stream-unavailable"));
  return new Promise((resolve, reject) => {
    const drain = () => finish();
    const close = () => finish(new AddonError(502, "client-disconnected"));
    const abort = () => finish(new AddonError(504, "upstream-timeout"));
    const finish = (error?: Error) => {
      res.off?.("drain", drain as (...args: never[]) => void);
      res.off?.("close", close as (...args: never[]) => void);
      signal.removeEventListener("abort", abort);
      if (error) reject(error); else resolve();
    };
    res.once!("drain", drain as (...args: never[]) => void);
    res.once!("close", close as (...args: never[]) => void);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  });
}

export async function routeAddonSearch(req: AddonRequest, res: AddonResponse, options: ServiceOptions = {}): Promise<void> {
  if (!acquireRoute(res)) return;
  try {
    if (req.method !== "GET") throw new AddonError(405, "method-not-allowed");
    const query = queryValue(req, "query")?.trim() ?? "";
    if (!query || query.length > 100) throw new AddonError(400, "invalid-query");
    const rawIndex = queryValue(req, "index") ?? "0";
    if (!/^(0|[1-9]\d*)$/.test(rawIndex) || !Number.isSafeInteger(Number(rawIndex)) || Number(rawIndex) > 10_000) throw new AddonError(400, "invalid-index");
    const key = getKey(options);
    const typeId = await foreverType(key, options);
    const url = `${API}/mods/search?gameId=${GAME_ID}&searchFilter=${encodeURIComponent(query)}&gameVersion=${encodeURIComponent(VERSION)}&gameVersionTypeId=${typeId}&index=${Number(rawIndex)}&pageSize=${PAGE_SIZE}`;
    const result = await cfJson(url, key, options);
    const root = result as { data?: unknown; pagination?: { index?: number; pageSize?: number; resultCount?: number; totalCount?: number } };
    const data = dataOf(root);
    const pagination = root.pagination;
    const index = Number(rawIndex);
    if (data.length > PAGE_SIZE || !pagination || pagination.index !== index || pagination.pageSize !== PAGE_SIZE ||
        !Number.isSafeInteger(pagination.resultCount) || pagination.resultCount! < 0 || pagination.resultCount! > PAGE_SIZE ||
        !Number.isSafeInteger(pagination.totalCount) || pagination.totalCount! < 0 || pagination.totalCount! < pagination.resultCount!) {
      throw new AddonError(502, "upstream-invalid-response");
    }
    const projects = data.filter((value): value is Mod => validMod(value) &&
      (value as Mod & { allowModDistribution?: boolean }).allowModDistribution === true).map((mod) => ({
      id: mod.id, name: mod.name.slice(0, 200), summary: typeof mod.summary === "string" ? mod.summary.slice(0, 1000) : "",
      authors: Array.isArray(mod.authors) ? mod.authors.flatMap((a) => typeof a?.name === "string" ? [a.name.slice(0, 100)] : []).slice(0, 10) : [],
      websiteUrl: safeWebsite(mod.links?.websiteUrl),
    }));
    const count = pagination.resultCount!;
    sendJson(res, 200, { projects, index, nextIndex: index + count < pagination.totalCount! && count > 0 ? index + count : null });
  } catch (error) { const [status, code] = errorStatus(error); sendJson(res, status, { error: code }); }
  finally { activeRoutes--; }
}

export async function routeAddonFile(req: AddonRequest, res: AddonResponse, options: ServiceOptions = {}): Promise<void> {
  if (!acquireRoute(res)) return;
  try {
    if (req.method !== "GET") throw new AddonError(405, "method-not-allowed");
    const projectId = requiredInt(queryValue(req, "projectId"), "project-id");
    const key = getKey(options);
    const { mod, files } = await projectFiles(projectId, key, options);
    if (files.length === 0) throw new AddonError(409, "no-compatible-file");
    const file = files[0]!;
    sendJson(res, 200, { projectId, fileId: file.id, name: file.displayName ?? file.fileName, fileName: file.fileName,
      size: file.fileLength, sha1: sha1(file), requiredDependencies: await publicDependencies(file, key, options), compatible: true,
      projectName: mod.name });
  } catch (error) { const [status, code] = errorStatus(error); sendJson(res, status, { error: code }); }
  finally { activeRoutes--; }
}

export async function routeAddonDownload(req: AddonRequest, res: AddonResponse, options: ServiceOptions = {}): Promise<void> {
  if (!acquireRoute(res)) return;
  const requestAbort = new AbortController();
  let streaming = false;
  const streamTimeout = setTimeout(() => requestAbort.abort(), TIMEOUT_MS);
  const onClose = () => requestAbort.abort();
  res.once?.("close", onClose as (...args: never[]) => void);
  try {
    if (req.method !== "GET") throw new AddonError(405, "method-not-allowed");
    const projectId = requiredInt(queryValue(req, "projectId"), "project-id");
    const fileId = requiredInt(queryValue(req, "fileId"), "file-id");
    const key = getKey(options);
    const { files } = await projectFiles(projectId, key, options);
    const file = files.find((item) => item.id === fileId);
    if (!file) throw new AddonError(409, "no-compatible-file");
    const response = await cfJson(`${API}/mods/${projectId}/files/${fileId}/download-url`, key, options);
    const downloadUrl = typeof response === "object" && response !== null ? (response as { data?: unknown }).data : null;
    if (typeof downloadUrl !== "string") throw new AddonError(502, "upstream-invalid-response");
    let parsed: URL;
    try { parsed = new URL(downloadUrl); } catch { throw new AddonError(502, "upstream-invalid-response"); }
    const authority = /^https:\/\/([^/?#]+)/i.exec(downloadUrl)?.[1] ?? "";
    if (parsed.protocol !== "https:" || !["edge.forgecdn.net", "media.forgecdn.net"].includes(parsed.hostname.toLowerCase()) ||
        authority.includes(":") || authority.includes("@") || parsed.port || parsed.username || parsed.password || parsed.hash) {
      throw new AddonError(502, "upstream-invalid-response");
    }
    const downloadResponse = await upstream(downloadUrl, key, options, requestAbort.signal, "cdn");
    // Redirects are deliberately not followed: the API key must never reach a CDN redirect.
    const declaredHeader = downloadResponse.headers.get("content-length");
    const declared = declaredHeader === null ? null : Number(declaredHeader);
    if (declared !== null && (!Number.isSafeInteger(declared) || declared < 0)) { await cancelBody(downloadResponse); throw new AddonError(502, "upstream-invalid-response"); }
    if (declared !== null && declared > MAX_ZIP) { await cancelBody(downloadResponse); throw new AddonError(502, "download-too-large"); }
    if (declared !== null && declared !== file.fileLength) { await cancelBody(downloadResponse); throw new AddonError(502, "download-length-mismatch"); }
    if (!downloadResponse.body || !res.write || !res.end) { await cancelBody(downloadResponse); throw new AddonError(502, "stream-unavailable"); }
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Content-Type", "application/zip");
    res.setHeader("Content-Disposition", `attachment; filename="${file.fileName!.replace(/["\\\r\n]/g, "_")}"`);
    res.setHeader("Content-Length", String(file.fileLength));
    streaming = true;
    const reader = downloadResponse.body.getReader();
    const abortDownload = () => { void reader.cancel(); };
    if (requestAbort.signal.aborted) abortDownload();
    else requestAbort.signal.addEventListener("abort", abortDownload, { once: true });
    let total = 0;
    let completed = false;
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (requestAbort.signal.aborted) throw new AddonError(504, "upstream-timeout");
        if (done) break;
        total += value.byteLength;
        if (total > MAX_ZIP || total > file.fileLength!) throw new AddonError(502, "download-too-large");
        if (!res.write(value)) await waitForDrain(res, requestAbort.signal);
      }
      completed = true;
    } finally {
      requestAbort.signal.removeEventListener("abort", abortDownload);
      if (!completed) { try { await reader.cancel(); } catch { /* response is already failing */ } }
      reader.releaseLock();
    }
    if (total !== file.fileLength) throw new AddonError(502, "download-length-mismatch");
    res.end();
  } catch (error) {
    if (streaming && res.destroy) res.destroy();
    else { const [status, code] = errorStatus(error); sendJson(res, status, { error: code }); }
  } finally { clearTimeout(streamTimeout); res.off?.("close", onClose as (...args: never[]) => void); activeRoutes--; }
}
