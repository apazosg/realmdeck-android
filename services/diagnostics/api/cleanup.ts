import { handleCleanup, type Deps } from "../src/handlers.js";
import { deps, header, methodNotAllowed, send, sendError } from "../src/http.js";
import type { ApiRequest, ApiResponse } from "../src/http.js";

/**
 * GET+POST /api/cleanup → borrado autenticado.
 * Vercel Cron invoca con GET (con `Authorization: Bearer ${CRON_SECRET}`
 * automático); el borrado operativo manual usa POST. Cualquier otro método
 * devuelve 405.
 */
export async function routeCleanup(req: ApiRequest, res: ApiResponse, context: Deps): Promise<void> {
  try {
    if (req.method !== "GET" && req.method !== "POST") {
      methodNotAllowed(res);
      return;
    }
    const response = await handleCleanup(context, {
      authHeader: header(req, "authorization"),
    });
    send(res, response.status, response.body);
  } catch (error) {
    sendError(res, error);
  }
}

export default async function handler(req: ApiRequest, res: ApiResponse): Promise<void> {
  try {
    return await routeCleanup(req, res, deps());
  } catch (error) {
    sendError(res, error);
  }
}
