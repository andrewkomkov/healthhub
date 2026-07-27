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
