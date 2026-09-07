import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { CanonicalCompilerClient } from "../src/canonical-compiler.js";

test("portable streaming-compiler session owns scoped iterative repair", async () => {
  const compiler = new CanonicalCompilerClient();
  await compiler.setup();
  const registry = await compiler.repairDiagnostics();
  for (const root of [join(compiler.dealRepo, "deal", "compiler"), join(compiler.dealUiRepo, "src", "main", "java", "deal", "ui")]) {
    for (const file of await javaFiles(root)) {
      for (const match of (await readFile(file, "utf8")).matchAll(/\b(?:UI|CP|CC)\d{4}\b/g)) {
        assert.ok(Object.hasOwn(registry, match[0]), `unregistered repair diagnostic ${match[0]} in ${file}`);
      }
    }
  }
  const result = await runJava(compiler.classes, "streaming.compiler.CanonicalRefinementSessionTest");
  assert.match(result, /all tests passed/);
});

async function javaFiles(root) {
  const files = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) files.push(...await javaFiles(path));
    else if (entry.name.endsWith(".java")) files.push(path);
  }
  return files;
}

function runJava(classpath, main) {
  return new Promise((resolve, reject) => {
    const child = spawn("java", ["-ea", "-cp", classpath, main], { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", reject);
    child.on("close", (code) => code === 0 ? resolve(stdout) : reject(new Error(stderr || `java exited ${code}`)));
  });
}
