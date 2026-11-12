// Runs the AIR Chat backend without the window (for tests and development).
import path from "node:path";
import { Vault, account } from "./air/account.js";
import { LocalServer } from "./air/localserver.js";

Vault.dir = process.env.AIR_DATA || path.resolve("air-data");
if (process.env.AIR_TEST_DIRECTORY) account.useTestDirectory();
account.install();
const server = new LocalServer({ port: Number(process.env.AIR_PORT || 47821), webDir: process.env.AIR_WEB ? path.resolve(process.env.AIR_WEB) : null });
await server.listen();
console.log(`AIR Chat backend on ${server.base}`);
