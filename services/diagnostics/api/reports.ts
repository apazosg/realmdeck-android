import { handleCreateReport, handleListReports } from "../src/handlers.js";
import { bodyText, deps, header, methodNotAllowed, send, sendError, trustedClientIp } from "../src/http.js";
import type { ApiRequest, ApiResponse } from "../src/http.js";

/**
 * POST /api/reports → ingestión anónima.
 * GET  /api/reports → listado de mantenedor (token de lectura).
 */
export default async function handler(req: ApiRequest, res: ApiResponse): Promise<void> {
  try {
    const context = deps();
    if (req.method === "POST") {
      const response = await handleCreateReport(context, {
        authHeader: header(req, "authorization"),
        idempotencyKey: header(req, "idempotency-key"),
        bodyText: bodyText(req, context.config.metaMaxBytes),
        clientIp: trustedClientIp(req),
      });
      send(res, response.status, response.body);
      return;
    }
    if (req.method === "GET") {
      const response = await handleListReports(context, {
        authHeader: header(req, "authorization"),
        limitRaw: req.query["limit"] ?? null,
        cursorRaw: req.query["cursor"] ?? null,
        filtersRaw: req.query,
      });
      send(res, response.status, response.body);
      return;
    }
    methodNotAllowed(res);
  } catch (error) {
    sendError(res, error);
  }
}
