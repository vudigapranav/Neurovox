// npm run dev  ->  build the Java app (only if needed), start it, and open the browser.
import { spawnSync, spawn } from "node:child_process";
import { existsSync, statSync, readdirSync } from "node:fs";
import { join } from "node:path";

const win = process.platform === "win32";
const jar = join("target", "neurovox.jar");
const port = process.env.NEUROVOX_PORT || "8080";
const url = `http://localhost:${port}/`;

// Newest modification time under src/ and pom.xml, to rebuild only when code changed.
function newest(dir) {
  let t = 0;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    t = Math.max(t, e.isDirectory() ? newest(p) : statSync(p).mtimeMs);
  }
  return t;
}
const stale = !existsSync(jar) ||
  Math.max(newest("src"), statSync("pom.xml").mtimeMs) > statSync(jar).mtimeMs;

if (stale || process.argv.includes("--build-only")) {
  console.log("Building Neurovox (first time takes a few minutes)...");
  const mvnw = win ? "mvnw.cmd" : "./mvnw";
  const r = spawnSync(mvnw, ["-q", "clean", "package", "-DskipTests"], { stdio: "inherit", shell: win });
  if (r.status !== 0) {
    console.error("\nBuild failed. Check that `java -version` shows 17 or newer.");
    process.exit(r.status ?? 1);
  }
}
if (process.argv.includes("--build-only")) process.exit(0);

console.log(`Starting Neurovox at ${url}  (press Ctrl+C to stop)`);
const app = spawn("java", ["-jar", jar, `--port=${port}`], { stdio: "inherit" });

// Open the browser once the server answers.
const opener = win ? ["cmd", ["/c", "start", "", url]]
  : process.platform === "darwin" ? ["open", [url]] : ["xdg-open", [url]];
let tries = 0;
const timer = setInterval(async () => {
  try {
    await fetch(url);
    clearInterval(timer);
    spawn(opener[0], opener[1], { stdio: "ignore", detached: true }).unref();
  } catch {
    if (++tries > 60) clearInterval(timer);
  }
}, 500);

const stop = () => { clearInterval(timer); app.kill("SIGINT"); };
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
app.on("exit", (code) => process.exit(code ?? 0));
