/** Bindings declared in wrangler.jsonc. */
export interface Bindings {
  /** Small, queryable, relational data. */
  DB: D1Database
  /** Large objects: telemetry blobs, previews, future exports. */
  BLOBS: R2Bucket
  /** The web SPA, served by this same Worker. */
  ASSETS: Fetcher
  /** Extra secret mixed into token hashing. Set with `wrangler secret put SESSION_PEPPER`. */
  SESSION_PEPPER?: string

  /**
   * Auth0 is an optional identity provider. When these three are absent the Auth0 routes
   * return 404 and local password accounts are the only way in, so a fork can be deployed
   * without registering a tenant.
   */
  AUTH0_DOMAIN?: string
  AUTH0_CLIENT_ID?: string
  AUTH0_CLIENT_SECRET?: string

  /**
   * Scoped R2 credentials for the analytical tier are also optional. Without them compaction
   * still runs and the archive is still written — only the "give me a key for my own prefix"
   * endpoint disappears, so a fork that has not created an R2 API token loses nothing else.
   *
   * `R2_API_TOKEN` needs exactly one permission: Workers R2 Storage → Edit, on this account.
   * `R2_PARENT_ACCESS_KEY_ID` is the access key id the temporary credentials descend from;
   * its secret never has to reach the Worker.
   */
  CF_ACCOUNT_ID?: string
  R2_API_TOKEN?: string
  R2_PARENT_ACCESS_KEY_ID?: string
  /** The bucket behind `BLOBS`. A Worker cannot read a binding's bucket name at runtime. */
  R2_BUCKET_NAME?: string
}

/** Who the current request belongs to, established by the auth middleware. */
export interface Principal {
  userId: string
  /** Present when the caller authenticated with a device token rather than a web session. */
  deviceId?: string
  kind: 'session' | 'device'
}

export interface AppEnv {
  Bindings: Bindings
  Variables: {
    principal: Principal
    requestId: string
  }
}
