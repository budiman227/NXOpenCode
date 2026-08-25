#!/usr/bin/env bun

import { rm, mkdir } from "node:fs/promises"
import path from "node:path"
import { createRequire } from "node:module"

const dir = path.resolve(import.meta.dirname)
const root = path.resolve(dir, "..")
const outdir = process.env.MOBILE_OUTDIR ?? path.join(dir, "dist")
const version = process.env.OPENCODE_VERSION ?? "0.0.0"


const esmOnlyPackages = ["jsonc-parser"]

const esmRedirect: Bun.BunPlugin = {
  name: "esm-redirect",
  setup(build) {
    for (const name of esmOnlyPackages) {
      const filter = new RegExp(`^${name}$`)
      build.onResolve({ filter }, (args) => {
        const from = args.importer && args.importer.length > 0 ? args.importer : path.join(root, "package.json")
        const main = createRequire(from).resolve(name)
        const esm = main.replace(`${path.sep}umd${path.sep}`, `${path.sep}esm${path.sep}`)
        return { path: esm }
      })
    }
  },
}

await rm(outdir, { recursive: true, force: true })
await mkdir(outdir, { recursive: true })

const result = await Bun.build({
  entrypoints: [path.join(dir, "server.ts")],
  target: "node",
  format: "esm",
  outdir,
  conditions: ["node"],
  minify: false,
  sourcemap: "none",
  plugins: [esmRedirect],
  define: {
    OPENCODE_VERSION: `'${version}'`,
    OPENCODE_CLI_NAME: "'opencode'",
    OPENCODE_CHANNEL: "'stable'",
    OPENCODE_LIBC: "undefined",
    FFF_LIBC: "undefined",
    OPENCODE_MODELS_DEV: process.env.OPENCODE_MODELS_DEV ?? "'{}'",
  },
})

if (!result.success) {
  for (const log of result.logs) console.error(log)
  process.exit(1)
}

for (const output of result.outputs) {
  console.log(`${path.relative(outdir, output.path)} ${(output.size / 1_000_000).toFixed(2)} MB`)
}
