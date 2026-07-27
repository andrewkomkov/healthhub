import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiFailure, type Providers, type User } from '../../core/api/client'
import { passwordProof } from './prehash'

/**
 * The Worker cannot check this any more: it is handed a fixed-width digest, not a password.
 * The rule still exists — it is stated in contracts/api.md — and this is now the only place
 * on the web side that keeps it.
 */
const MIN_PASSWORD = 10

export function AuthScreen({ onSignedIn }: { onSignedIn: (user: User) => void }) {
  const [providers, setProviders] = useState<Providers>({ password: true, auth0: false })
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api
      .providers()
      .then(setProviders)
      .catch(() => undefined)
  }, [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (mode === 'register' && password.length < MIN_PASSWORD) {
      setError(`Choose a password of at least ${MIN_PASSWORD} characters.`)
      return
    }
    setBusy(true)
    setError(null)
    try {
      // Several hundred milliseconds of PBKDF2 before the request is even built — the busy
      // state above covers it, and it is the point rather than an overhead.
      const proofs = [await passwordProof(email, password)]
      const result =
        mode === 'login'
          ? await api.login(email, password, proofs)
          : await api.register(email, displayName, proofs)
      onSignedIn(result.user)
    } catch (failure) {
      setError(
        failure instanceof ApiFailure ? failure.error.message : 'Something went wrong.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="m3-page" style={{ maxWidth: 420, paddingTop: 'var(--hh-space-xxxl)' }}>
      <header style={{ display: 'flex', flexDirection: 'column', gap: 'var(--hh-space-sm)' }}>
        <h1 className="t-headline-large">HealthHub</h1>
        <p className="t-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>
          Your Health Connect data, properly.
        </p>
      </header>

      {providers.auth0 && (
        <>
          {/* A full page navigation, not fetch: the provider redirect has to happen at the
              top level for the state cookie and the return trip to work. */}
          <button
            className="m3-button"
            type="button"
            onClick={() => {
              window.location.href = '/api/auth/auth0/login'
            }}
          >
            Continue with Auth0
          </button>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 'var(--hh-space-md)',
              color: 'var(--md-sys-color-on-surface-variant)',
            }}
          >
            <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--md-sys-color-outline-variant)' }} />
            <span className="t-label-medium">or use a password</span>
            <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--md-sys-color-outline-variant)' }} />
          </div>
        </>
      )}

      <form
        onSubmit={submit}
        style={{ display: 'flex', flexDirection: 'column', gap: 'var(--hh-space-lg)' }}
      >
        {mode === 'register' && (
          <div className="m3-field">
            <label htmlFor="displayName">Name</label>
            <input
              id="displayName"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              autoComplete="name"
              required
            />
          </div>
        )}

        <div className="m3-field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            required
          />
        </div>

        <div className="m3-field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            minLength={mode === 'register' ? MIN_PASSWORD : undefined}
            required
          />
          {mode === 'register' && (
            <span className="t-body-small">At least {MIN_PASSWORD} characters.</span>
          )}
        </div>

        {error && (
          <p className="m3-error t-body-medium" role="alert">
            {error}
          </p>
        )}

        <button className="m3-button" type="submit" disabled={busy}>
          {busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>

        <button
          className="m3-button m3-button--text"
          type="button"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login')
            setError(null)
          }}
        >
          {mode === 'login' ? 'Create an account' : 'I already have an account'}
        </button>
      </form>
    </div>
  )
}
