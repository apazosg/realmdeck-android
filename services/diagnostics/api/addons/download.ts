import { routeAddonDownload } from "../../src/addons.js";
import type { AddonRequest, AddonResponse } from "../../src/addons.js";
export default async function handler(req: AddonRequest, res: AddonResponse): Promise<void> {
  await routeAddonDownload(req, res);
}
