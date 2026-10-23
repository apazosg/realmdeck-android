import { handleCompleteReport } from "../../../src/handlers.js";
import { deps, header, methodNotAllowed, send, sendError } from "../../../src/http.js";
import type { ApiRequest, ApiResponse } from "../../../src/http.js";

/** POST /api/reports/{id}/complete → confirma subidas con la capacidad. */
export default async function handler(req: ApiRequest, res: ApiResponse): Promise<void> {
  try {
    const context = deps();
    if (req.method !== "POST") {
      methodNotAllowed(res);
      return;
    }
    const response = await handleCompleteReport(context, {
      authHeader: header(req, "authorization"),
      reportId: first(req.query["id"]),
    });
    send(res, response.status, response.body);
  } catch (error) {
    sendError(res, error);
  }
}

function first(value: string | string[] | undefined): string | null {
  if (typeof value === "string") return value;
  if (Array.isArray(value) && typeof value[0] === "string") return value[0] as string;
  return null;
}
