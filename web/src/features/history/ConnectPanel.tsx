import { useRef, type FormEvent } from 'react'
import type { ArchiveManifest } from '../../core/api/client'
import type { S3Credentials } from '../../core/analytics/session'
import { coverage, megabytes } from '../../core/analytics/parts'

/**
 * How the query engine gets at the archive.
 *
 * Three ways in, in descending order of how much the deployment has to have finished:
 * credentials minted by the Worker, credentials the athlete already holds, and a Parquet part
 * dragged in from disk. All three end in the same place — DuckDB running in this tab — and
 * none of them sends a byte of the athlete's history to anybody.
 */
export function ConnectPanel({
  manifest,
  busy,
  mintable,
  onMint,
  onCredentials,
  onFiles,
}: {
  manifest: ArchiveManifest | null
  busy: boolean
  /** False once `POST /api/archive/credentials` has answered that it does not exist. */
  mintable: boolean
  onMint: () => void
  onCredentials: (credentials: S3Credentials) => void
  onFiles: (files: File[]) => void
}) {
  const formRef = useRef<HTMLFormElement>(null)
  const summary = manifest ? coverage(manifest.months) : null

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const value = (name: string) => String(form.get(name) ?? '').trim()
    onCredentials({
      endpoint: value('endpoint'),
      bucket: value('bucket'),
      accessKeyId: value('accessKeyId'),
      secretAccessKey: value('secretAccessKey'),
      sessionToken: value('sessionToken') || null,
    })
    // The secret is read straight out of the form and handed to DuckDB. It is never React
    // state, never stored, and it leaves the page as soon as the field is cleared.
    formRef.current?.reset()
  }

  return (
    <div className="hh-connect">
      {summary && summary.months > 0 && (
        <p className="t-body-medium">
          {summary.months} month{summary.months === 1 ? '' : 's'} compacted
          {summary.earliest && summary.latest
            ? `, ${summary.earliest.year} to ${summary.latest.year}`
            : ''}
          {' · '}
          {summary.rows.toLocaleString()} activities in {summary.parts} Parquet part
          {summary.parts === 1 ? '' : 's'} · {megabytes(summary.bytes)}
        </p>
      )}

      <div className="hh-connect__actions">
        <button
          className="m3-button"
          onClick={onMint}
          disabled={busy || !mintable || !summary || summary.months === 0}
        >
          {busy ? 'Opening…' : 'Open my archive'}
        </button>
        <span className="t-body-small">
          Read-only keys for your own prefix, valid for an hour, held in this tab only.
        </span>
      </div>

      <details className="hh-connect__manual">
        <summary className="t-label-large">Use keys I already have</summary>
        <form ref={formRef} className="hh-connect__form" onSubmit={submit}>
          <p className="t-body-small">
            An R2 API token scoped to your bucket works here too — useful before the credential
            route is deployed, or from a bucket this deployment does not own.
          </p>
          <div className="m3-field">
            <label htmlFor="archive-endpoint">S3 endpoint</label>
            <input
              id="archive-endpoint"
              name="endpoint"
              required
              placeholder="<account>.r2.cloudflarestorage.com"
              defaultValue={manifest?.endpoint ?? ''}
              autoComplete="off"
            />
          </div>
          <div className="m3-field">
            <label htmlFor="archive-bucket">Bucket</label>
            <input
              id="archive-bucket"
              name="bucket"
              required
              defaultValue={manifest?.bucket ?? ''}
              autoComplete="off"
            />
          </div>
          <div className="m3-field">
            <label htmlFor="archive-key">Access key id</label>
            <input
              id="archive-key"
              name="accessKeyId"
              required
              autoComplete="off"
              spellCheck={false}
            />
          </div>
          <div className="m3-field">
            <label htmlFor="archive-secret">Secret access key</label>
            <input
              id="archive-secret"
              name="secretAccessKey"
              type="password"
              required
              autoComplete="off"
            />
          </div>
          <div className="m3-field">
            <label htmlFor="archive-token">Session token (temporary credentials only)</label>
            <input id="archive-token" name="sessionToken" autoComplete="off" spellCheck={false} />
          </div>
          {/* Filled rather than tonal: a tonal button on a secondary container is the same
              colour as the panel it sits on, and disappears. */}
          <button className="m3-button" type="submit" disabled={busy}>
            Connect
          </button>
        </form>
      </details>

      <details className="hh-connect__manual">
        <summary className="t-label-large">Query a Parquet file from this device</summary>
        <div className="hh-connect__form">
          <p className="t-body-small">
            Parts you have already downloaded are read by the browser itself — nothing is
            uploaded, and no credentials are involved.
          </p>
          <div className="m3-field">
            <label htmlFor="archive-files">Parquet parts</label>
            <input
              id="archive-files"
              type="file"
              name="parquet"
              accept=".parquet"
              multiple
              disabled={busy}
              onChange={(event) => {
                const files = Array.from(event.target.files ?? [])
                if (files.length > 0) onFiles(files)
              }}
            />
          </div>
        </div>
      </details>
    </div>
  )
}
