/**
 * Where the DuckDB Wasm binary comes from.
 *
 * It is not bundled, and that is a platform limit rather than a preference: the two builds are
 * 39 MB and 34 MB uncompressed, and Cloudflare Workers static assets cap a single file at
 * 25 MiB. Emitting them through Vite would produce a `dist/` that cannot be deployed at all,
 * and would put a 70 MB pair of assets in a repository whose entire web build is 200 kB.
 *
 * So the URL is resolved at run time. `VITE_DUCKDB_BUNDLE_BASE` points at wherever a
 * deployment chose to host the four files — an R2 bucket with a public custom domain is the
 * obvious one, and costs nothing in egress like everything else here. With nothing configured
 * the library's own jsDelivr URLs are used, pinned by the package version so the binary always
 * matches the JS that drives it.
 *
 * The same shape as `VITE_MAP_TILES_URL`: a deployment detail the code refuses to invent.
 */

export interface DuckDbBundle {
  mainModule: string
  mainWorker: string
  pthreadWorker?: string | null
}

export interface DuckDbBundles {
  mvp: DuckDbBundle
  eh: DuckDbBundle
}

const FILES = {
  mvp: { module: 'duckdb-mvp.wasm', worker: 'duckdb-browser-mvp.worker.js' },
  eh: { module: 'duckdb-eh.wasm', worker: 'duckdb-browser-eh.worker.js' },
} as const

/** Self-hosted layout: the four files sitting next to each other, as they ship in `dist/`. */
export function selfHostedBundles(base: string): DuckDbBundles {
  const prefix = base.endsWith('/') ? base : `${base}/`
  return {
    mvp: { mainModule: prefix + FILES.mvp.module, mainWorker: prefix + FILES.mvp.worker },
    eh: { mainModule: prefix + FILES.eh.module, mainWorker: prefix + FILES.eh.worker },
  }
}

export function configuredBundleBase(): string | undefined {
  const base = import.meta.env['VITE_DUCKDB_BUNDLE_BASE'] as string | undefined
  return base && base.trim() ? base.trim() : undefined
}
