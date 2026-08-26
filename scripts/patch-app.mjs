import { readFileSync, writeFileSync } from "node:fs"
import path from "node:path"

const root = process.argv[2]
if (!root) {
  console.error("usage: patch-app.mjs <opencode-checkout>")
  process.exit(1)
}

const target = path.join(root, "packages/app/src/components/dialog-select-directory.tsx")

const before = `  const home = createMemo(() => sync.data.path.home || fallbackPath()?.home || "")
  const start = createMemo(
    () => sync.data.path.home || sync.data.path.directory || fallbackPath()?.home || fallbackPath()?.directory,
  )`

const after = `  const embeddedHome = () => (globalThis as { __NXOPENCODE_HOME__?: string }).__NXOPENCODE_HOME__ ?? ""
  const home = createMemo(() => sync.data.path.home || fallbackPath()?.home || embeddedHome())
  const start = createMemo(
    () =>
      sync.data.path.home ||
      sync.data.path.directory ||
      fallbackPath()?.home ||
      fallbackPath()?.directory ||
      embeddedHome(),
  )`

const source = readFileSync(target, "utf8")

if (source.includes("__NXOPENCODE_HOME__")) {
  console.log("dialog-select-directory.tsx already patched")
  process.exit(0)
}

if (!source.includes(before)) {
  console.error("dialog-select-directory.tsx does not match the expected source")
  console.error("upstream changed the home/start memos; the patch must be updated")
  process.exit(1)
}

writeFileSync(target, source.replace(before, after))
console.log("patched dialog-select-directory.tsx")
