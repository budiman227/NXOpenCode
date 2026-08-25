import { NodeHttpServer } from "@effect/platform-node"
import { Credential } from "@opencode-ai/core/credential"
import { AppNodeBuilder } from "@opencode-ai/core/effect/app-node-builder"
import { LayerNode } from "@opencode-ai/core/effect/layer-node"
import { PermissionSaved } from "@opencode-ai/core/permission/saved"
import { createRoutes } from "@opencode-ai/server/routes"
import { Context, Layer } from "effect"
import * as Effect from "effect/Effect"
import { HttpRouter, HttpServer } from "effect/unstable/http"
import { createReadStream, existsSync, statSync } from "node:fs"
import { createServer, request as httpRequest, type IncomingMessage, type ServerResponse } from "node:http"
import path from "node:path"

const publicPort = Number(process.env.OPENCODE_PORT ?? "4096")
const hostname = process.env.OPENCODE_HOSTNAME ?? "127.0.0.1"
const password = process.env.OPENCODE_PASSWORD ?? ""
const webDir = process.env.OPENCODE_WEB_DIR ? path.resolve(process.env.OPENCODE_WEB_DIR) : ""

const mimeTypes: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".wasm": "application/wasm",
  ".txt": "text/plain; charset=utf-8",
}

function isApiPath(pathname: string) {
  return (
    pathname === "/openapi.json" ||
    pathname === "/api" ||
    pathname.startsWith("/api/") ||
    pathname.startsWith("/auth/") ||
    pathname.startsWith("/.well-known/")
  )
}

function resolveStatic(pathname: string) {
  if (!webDir) return undefined
  let decoded: string
  try {
    decoded = decodeURIComponent(pathname)
  } catch {
    return undefined
  }
  const candidate = path.resolve(webDir, "." + decoded)
  if (candidate !== webDir && !candidate.startsWith(webDir + path.sep)) return undefined
  if (!existsSync(candidate)) return undefined
  if (statSync(candidate).isDirectory()) {
    const index = path.join(candidate, "index.html")
    return existsSync(index) ? index : undefined
  }
  return candidate
}

function sendFile(res: ServerResponse, file: string, cache: boolean) {
  const extension = path.extname(file).toLowerCase()
  res.writeHead(200, {
    "Content-Type": mimeTypes[extension] ?? "application/octet-stream",
    "Cache-Control": cache ? "public, max-age=31536000, immutable" : "no-cache",
  })
  createReadStream(file).pipe(res)
}

function proxy(req: IncomingMessage, res: ServerResponse, apiPort: number) {
  const headers = { ...req.headers, host: `127.0.0.1:${apiPort}` }
  const upstream = httpRequest(
    { host: "127.0.0.1", port: apiPort, method: req.method, path: req.url, headers },
    (upstreamResponse) => {
      res.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers)
      upstreamResponse.pipe(res)
    },
  )
  upstream.on("error", (error) => {
    if (!res.headersSent) res.writeHead(502, { "Content-Type": "text/plain; charset=utf-8" })
    res.end(`upstream error: ${error.message}`)
  })
  req.pipe(upstream)
}

function startFrontend(apiPort: number) {
  return new Promise<void>((resolve, reject) => {
    const server = createServer((req, res) => {
      const pathname = (req.url ?? "/").split("?")[0]
      if (isApiPath(pathname)) return proxy(req, res, apiPort)

      const file = resolveStatic(pathname)
      if (file) return sendFile(res, file, pathname.startsWith("/assets/"))

      if (webDir && (req.headers.accept ?? "").includes("text/html")) {
        const index = path.join(webDir, "index.html")
        if (existsSync(index)) return sendFile(res, index, false)
      }
      return proxy(req, res, apiPort)
    })
    server.on("error", reject)
    server.listen(publicPort, hostname, () => resolve())
  })
}

const program = Effect.scoped(
  Effect.gen(function* () {
    const context = yield* Layer.build(
      HttpRouter.serve(createRoutes(password), { disableListenLog: true, disableLogger: true }).pipe(
        Layer.provideMerge(NodeHttpServer.layer(() => createServer(), { port: 0, host: "127.0.0.1" })),
        Layer.provide(AppNodeBuilder.build(LayerNode.group([Credential.node, PermissionSaved.node]))),
      ),
    )
    const address = Context.get(context, HttpServer.HttpServer).address
    const apiPort = address._tag === "TcpAddress" ? address.port : publicPort
    yield* Effect.promise(() => startFrontend(apiPort))
    console.log(`OPENCODE_READY http://${hostname}:${publicPort}`)
    return yield* Effect.never
  }),
)

Effect.runPromise(program).catch((error) => {
  console.error("OPENCODE_FAILED", error)
  process.exit(1)
})
