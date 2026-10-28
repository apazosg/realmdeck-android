import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { routeAddonDownload, routeAddonFile, routeAddonSearch } from "../src/addons.js";
import type { AddonRequest, AddonResponse } from "../src/addons.js";

function capture() {
  const headers: Record<string, string> = {};
  const chunks: Uint8Array[] = [];
  const result = {
    statusCode: 0, payload: null as unknown, ended: false, destroyed: false, headers, chunks,
    status(code: number): AddonResponse { result.statusCode = code; return result; },
    setHeader(name: string, value: string) { headers[name.toLowerCase()] = value; },
    json(value: unknown) { result.payload = value; },
    write(chunk: Uint8Array) { chunks.push(chunk); return true; },
    end() { result.ended = true; },
    destroy() { result.destroyed = true; },
    once(_event: string, _listener: (...args: never[]) => void) { return result; },
  };
  return result;
}

const versions = { data: [{ id: 77, name: "Forever" }] };
const mod = { data: { id: 123, name: "Addon", summary: "brief", allowModDistribution: true, authors: [{ name: "Author" }], links: { websiteUrl: "https://www.curseforge.com/wow/addons/addon" } } };
const goodFile = (overrides: Record<string, unknown> = {}) => ({
  id: 900, modId: 123, displayName: "release", fileName: "addon.zip", fileLength: 4,
  isAvailable: true, isAlternate: false, releaseType: 1, gameVersions: ["1.60.1", "Forever"],
  hashes: [{ algo: 1, value: "a".repeat(40) }], dependencies: [], ...overrides,
});
const fileList = (file = goodFile()) => ({ data: [file] });
function req(method: string, query: Record<string, string>): AddonRequest { return { method, query }; }
function json(body: unknown): Response { return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } }); }

describe("CurseForge addon backend", () => {
  it("fails closed without a server key and does not call upstream", async () => {
    let calls = 0;
    const res = capture();
    await routeAddonSearch(req("GET", { query: "foo" }), res, { apiKey: "", fetcher: (async () => { calls++; return json({}); }) as typeof fetch });
    assert.equal(res.statusCode, 503);
    assert.deepEqual(res.payload, { error: "service-not-configured" });
    assert.equal(calls, 0);
  });

  it("searches with exact Forever metadata and returns only explicitly distributable projects", async () => {
    const calls: { url: string; key: string | null }[] = [];
    const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
      calls.push({ url: String(input), key: new Headers(init?.headers).get("x-api-key") });
      if (String(input).endsWith("version-types")) return json(versions);
      return json({ data: [mod.data, { ...mod.data, id: 124, allowModDistribution: false }], pagination: { index: 0, pageSize: 20, resultCount: 2, totalCount: 2 } });
    }) as typeof fetch;
    const res = capture();
    await routeAddonSearch(req("GET", { query: "  alpha ", index: "0" }), res, { apiKey: "server-secret", fetcher });
    assert.equal(res.statusCode, 200);
    assert.deepEqual(res.payload, { projects: [{ id: 123, name: "Addon", summary: "brief", authors: ["Author"], websiteUrl: "https://www.curseforge.com/wow/addons/addon" }], index: 0, nextIndex: null });
    assert.ok(calls[1]!.url.includes("gameVersion=1.60.1"));
    assert.ok(calls[1]!.url.includes("gameVersionTypeId=77"));
    assert.ok(calls.every((call) => call.key === "server-secret"));
  });

  it("does not claim compatibility for a non-exact file or unknown Forever type", async () => {
    const fetcher = (async (input: string | URL | Request) => {
      const url = String(input);
      if (url.endsWith("version-types")) return json(versions);
      if (url.endsWith("/mods/123")) return json(mod);
      return json(fileList(goodFile({ gameVersions: ["1.60.1", "Classic"] })));
    }) as typeof fetch;
    const res = capture();
    await routeAddonFile(req("GET", { projectId: "123" }), res, { apiKey: "key", fetcher });
    assert.equal(res.statusCode, 409);
    assert.deepEqual(res.payload, { error: "no-compatible-file" });
    const unknownRes = capture();
    await routeAddonFile(req("GET", { projectId: "123" }), unknownRes, { apiKey: "key", fetcher: (async () => json({ data: [{ id: 2, name: "Classic" }] })) as typeof fetch });
    assert.equal(unknownRes.statusCode, 503);
    assert.deepEqual(unknownRes.payload, { error: "compatibility-unverified" });
  });

  it("rejects restricted distribution before offering a file", async () => {
    const fetcher = (async (input: string | URL | Request) => String(input).endsWith("version-types") ? json(versions) : json({ data: { ...mod.data, allowModDistribution: false } })) as typeof fetch;
    const res = capture();
    await routeAddonFile(req("GET", { projectId: "123" }), res, { apiKey: "key", fetcher });
    assert.equal(res.statusCode, 409);
    assert.deepEqual(res.payload, { error: "distribution-disabled" });
  });

  it("streams a valid official download with the required key only to an exact allowed CDN host", async () => {
    const calls: { url: string; key: string | null; redirect: RequestRedirect | undefined }[] = [];
    const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, key: new Headers(init?.headers).get("x-api-key"), redirect: init?.redirect });
      if (url.endsWith("version-types")) return json(versions);
      if (url.endsWith("/mods/123")) return json(mod);
      if (url.includes("/files?") ) return json(fileList());
      if (url.endsWith("/download-url")) return json({ data: "https://edge.forgecdn.net/addon.zip" });
      return new Response(new Uint8Array([1, 2, 3, 4]), { status: 200, headers: { "content-length": "4" } });
    }) as typeof fetch;
    const res = capture();
    await routeAddonDownload(req("GET", { projectId: "123", fileId: "900" }), res, { apiKey: "secret", fetcher });
    assert.equal(res.ended, true);
    assert.equal(res.destroyed, false);
    assert.equal(res.headers["content-length"], "4");
    assert.deepEqual([...Buffer.concat(res.chunks)], [1, 2, 3, 4]);
    const cdn = calls.find((call) => call.url.includes("forgecdn.net"))!;
    assert.equal(cdn.key, "secret");
    assert.equal(cdn.redirect, "manual");
  });

  it("bounds search offsets and rejects malformed pagination and oversized result pages", async () => {
    const invalid = [
      { data: [], pagination: { index: 0, pageSize: 20, resultCount: 0 } },
      { data: Array.from({ length: 21 }, () => mod.data), pagination: { index: 0, pageSize: 20, resultCount: 21, totalCount: 21 } },
      { data: [], pagination: { index: 1, pageSize: 20, resultCount: 0, totalCount: 0 } },
    ];
    for (const result of invalid) {
      const fetcher = (async (input: string | URL | Request) => String(input).endsWith("version-types") ? json(versions) : json(result)) as typeof fetch;
      const res = capture();
      await routeAddonSearch(req("GET", { query: "x", index: "0" }), res, { apiKey: "key", fetcher });
      assert.equal(res.statusCode, 502);
      assert.deepEqual(res.payload, { error: "upstream-invalid-response" });
    }
    const offset = capture();
    await routeAddonSearch(req("GET", { query: "x", index: "10001" }), offset, { apiKey: "key" });
    assert.equal(offset.statusCode, 400);
  });

  it("rejects malformed dependency schemas rather than silently dropping them", async () => {
    const fetcher = (async (input: string | URL | Request) => {
      const url = String(input);
      if (url.endsWith("version-types")) return json(versions);
      if (url.endsWith("/mods/123")) return json(mod);
      return json(fileList(goodFile({ dependencies: [{ modId: 42, relationType: 99 }] })));
    }) as typeof fetch;
    const res = capture();
    await routeAddonFile(req("GET", { projectId: "123" }), res, { apiKey: "key", fetcher });
    assert.equal(res.statusCode, 502);
    assert.deepEqual(res.payload, { error: "upstream-invalid-response" });
  });

  it("rejects CDN suffix hosts, URL credentials, ports, and fragments without sending the key", async () => {
    for (const candidate of [
      "https://evil.forgecdn.net/addon.zip", "https://edge.forgecdn.net.evil.invalid/addon.zip",
      "https://user@edge.forgecdn.net/addon.zip", "https://edge.forgecdn.net:443/addon.zip",
      "https://edge.forgecdn.net/addon.zip#fragment",
    ]) {
      const calls: { url: string; key: string | null }[] = [];
      const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
        const url = String(input);
        calls.push({ url, key: new Headers(init?.headers).get("x-api-key") });
        if (url.endsWith("version-types")) return json(versions);
        if (url.endsWith("/mods/123")) return json(mod);
        if (url.includes("/files?")) return json(fileList());
        return json({ data: candidate });
      }) as typeof fetch;
      const res = capture();
      await routeAddonDownload(req("GET", { projectId: "123", fileId: "900" }), res, { apiKey: "secret", fetcher });
      assert.equal(res.statusCode, 502);
      assert.deepEqual(res.payload, { error: "upstream-invalid-response" });
      assert.ok(calls.every((call) => !call.url.includes("forgecdn")));
      assert.ok(!JSON.stringify(res.payload).includes("secret"));
    }
  });

  it("waits for response drain before writing the next chunk", async () => {
    const fetcher = (async (input: string | URL | Request) => {
      const url = String(input);
      if (url.endsWith("version-types")) return json(versions);
      if (url.endsWith("/mods/123")) return json(mod);
      if (url.includes("/files?")) return json(fileList(goodFile({ fileLength: 2 })));
      if (url.endsWith("/download-url")) return json({ data: "https://edge.forgecdn.net/addon.zip" });
      return new Response(new Uint8Array([1, 2]), { status: 200, headers: { "content-length": "2" } });
    }) as typeof fetch;
    const res = capture();
    let drain: ((...args: never[]) => void) | undefined;
    let writes = 0;
    res.once = ((event: string, listener: (...args: never[]) => void) => {
      if (event === "drain") drain = listener;
      return res;
    }) as typeof res.once;
    res.write = (chunk: Uint8Array) => {
      writes++;
      res.chunks.push(chunk);
      if (writes === 1) { queueMicrotask(() => drain?.()); return false; }
      return true;
    };
    await routeAddonDownload(req("GET", { projectId: "123", fileId: "900" }), res, { apiKey: "secret", fetcher });
    assert.equal(writes, 1); // one chunk: no second write is attempted while this stream is backpressured
    assert.equal(res.ended, true);
  });

  it("rejects arbitrary download hosts and sanitizes upstream failures", async () => {
    const fetcher = (async (input: string | URL | Request) => {
      const url = String(input);
      if (url.endsWith("version-types")) return json(versions);
      if (url.endsWith("/mods/123")) return json(mod);
      if (url.includes("/files?")) return json(fileList());
      return json({ data: "https://attacker.invalid/file.zip?key=secret" });
    }) as typeof fetch;
    const res = capture();
    await routeAddonDownload(req("GET", { projectId: "123", fileId: "900" }), res, { apiKey: "secret", fetcher });
    assert.equal(res.destroyed, false);
    assert.deepEqual(res.payload, { error: "upstream-invalid-response" });
    assert.equal(res.statusCode, 502);
  });

  it("rejects a streamed body that exceeds its verified file length", async () => {
    const fetcher = (async (input: string | URL | Request) => {
      const url = String(input);
      if (url.endsWith("version-types")) return json(versions);
      if (url.endsWith("/mods/123")) return json(mod);
      if (url.includes("/files?")) return json(fileList());
      if (url.endsWith("/download-url")) return json({ data: "https://edge.forgecdn.net/addon.zip" });
      return new Response(new Uint8Array(5), { status: 200, headers: { "content-length": "4" } });
    }) as typeof fetch;
    const res = capture();
    await routeAddonDownload(req("GET", { projectId: "123", fileId: "900" }), res, { apiKey: "secret", fetcher });
    assert.equal(res.destroyed, true);
    assert.equal(res.ended, false);
  });
});
