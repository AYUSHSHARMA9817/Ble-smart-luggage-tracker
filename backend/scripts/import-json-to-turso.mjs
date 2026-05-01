import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { overwriteDb } from "../src/store.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const sourcePath = process.argv[2]
  ? path.resolve(process.cwd(), process.argv[2])
  : path.resolve(__dirname, "../data/app.json");

async function main() {
  const raw = await fs.readFile(sourcePath, "utf8");
  const parsed = JSON.parse(raw);
  await overwriteDb(parsed);
  console.log(`Imported JSON state from ${sourcePath} into Turso.`);
}

main().catch((error) => {
  console.error("Import failed", error);
  process.exitCode = 1;
});
