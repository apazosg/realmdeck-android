import { routeAddonFile } from "../../src/addons.js";
import type { AddonRequest, AddonResponse } from "../../src/addons.js";
export default async function handler(req: AddonRequest, res: AddonResponse): Promise<void> {
  await routeAddonFile(req, res);
}
