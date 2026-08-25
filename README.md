# NX OpenCode

[opencode](https://github.com/anomalyco/opencode) running entirely on Android, as a normal APK.

No Termux, no root, no proot, no glibc shim. A Node.js runtime cross-compiled for
Android bionic runs the opencode server locally, and the official web interface is
displayed in a WebView on the same origin.

## Architecture

```
┌─────────────────────────────────────────────┐
│ MainActivity (WebView)                      │
│   loads http://127.0.0.1:<random port>      │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│ NodeService (foreground service)            │
│   libnode.so  <- nativeLibraryDir           │
│   server.js   <- filesDir/runtime           │
│   web/        <- filesDir/web               │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│ server.js                                   │
│   public port  : static SPA + /api proxy    │
│   internal port: opencode Effect HTTP API   │
└─────────────────────────────────────────────┘
```

### Why this shape

The published opencode binaries are produced with `bun build --compile` and link
against glibc, which Android does not provide. The musl target is not statically
linked either, and the terminal interface depends on `@opentui/core`, a Zig native
library with no Android build.

The repository does contain a complete Node.js branch, however:

| Component | Node implementation |
| --- | --- |
| Database | `node:sqlite`, built into Node 22.5+ |
| File search | `fff.node.ts`, plain TypeScript |
| HTTP server | `@effect/platform-node` and `node:http` |

None of these need a native module, so bundling `packages/server` for the Node
target produces a single JavaScript file that runs on a stock Node binary.

The web interface resolves its API base URL from `location.origin` in production
builds, so the bundled server serves the static files and proxies `/api` to the
internal Effect server. One origin means no CORS handling and no mixed content.

## Building

Node.js has to be cross-compiled once. Run the `node-android` workflow; it publishes
`libnode.so` and `libc++_shared.so` to a release tagged `node-arm64-v22.x`. This takes
roughly an hour and only needs repeating when the Node version changes.

Afterwards the `build` workflow produces the APKs. It clones opencode, bundles the
server for Node, builds the web interface, downloads the Node release assets into
`jniLibs`, and runs Gradle.

## Notes

- `arm64-v8a` only. 32-bit devices are not supported.
- `useLegacyPackaging` is enabled so `libnode.so` is extracted to `nativeLibraryDir`
  as a real file. Executing it from there works on any target SDK, unlike the app's
  own data directory.
- Android 12 and above may kill the Node child process through the phantom process
  killer. The foreground service reduces this but does not eliminate it.
- The server binds to `127.0.0.1` and is not reachable from outside the device.

## Credits

opencode is developed by [anomalyco](https://github.com/anomalyco/opencode) and
licensed under MIT. This repository only packages it for Android.
