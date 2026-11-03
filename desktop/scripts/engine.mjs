// Builds the engine the app runs (../build/engine: jars plus a jlink runtime) with the repository's Gradle wrapper.
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const windows = process.platform === "win32";
const result = spawnSync(windows ? "gradlew.bat" : "./gradlew", ["engineBundle", "-q"], {
  cwd: root,
  stdio: "inherit",
  shell: windows,
});
process.exit(result.status ?? 1);
