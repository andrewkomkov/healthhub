import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, ApiFailure, type ArchiveManifest, type User } from '../../core/api/client'
import { archiveUrls, coverage } from '../../core/analytics/parts'
import { availability, looksReadOnly, starterSql, type Question } from '../../core/analytics/queries'
import type { FieldMap } from '../../core/analytics/fields'
import type { ResultSet } from '../../core/analytics/rows'
import { ArchiveSession, type S3Credentials } from '../../core/analytics/session'
import { ConnectPanel } from './ConnectPanel'
import { ResultTable } from './ResultTable'
import './history.css'

/**
 * Whole-history questions, answered in this tab.
 *
 * The feed and the detail screen read one activity at a time; this is the other half of R-013 —
 * years of closed months compacted into Parquet, queried where the athlete is standing. DuckDB
 * arrives only when this screen asks for it (it is larger than everything else on the page put
 * together), reads from the athlete's own R2 prefix with scoped read-only keys, and aggregates
 * nothing anywhere but here. R2 charges no egress, so the query is free in the literal sense.
 */

interface Outcome {
  result: ResultSet
  elapsedMs: number
  measure?: string | undefined
  caption: string
}

type Attachment = { kind: 'archive'; parts: number } | { kind: 'files'; parts: number }

export function HistoryLab({ user }: { user: User }) {
  const [manifest, setManifest] = useState<ArchiveManifest | null>(null)
  const [manifestState, setManifestState] = useState<'loading' | 'ready' | 'absent' | 'failed'>(
    'loading',
  )
  const [mintable, setMintable] = useState(true)
  const [busy, setBusy] = useState(false)
  const [engine, setEngine] = useState<{ version: string; loadMs: number } | null>(null)
  const [attachment, setAttachment] = useState<Attachment | null>(null)
  const [fields, setFields] = useState<FieldMap | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [year, setYear] = useState<number | null>(null)
  const [sql, setSql] = useState('')

  const sessionRef = useRef<ArchiveSession | null>(null)
  // StrictMode invokes a mount effect twice and both calls read the same state, so a second
  // manifest request would be in flight before the first has landed.
  const loadedRef = useRef(false)

  useEffect(() => {
    if (loadedRef.current) return
    loadedRef.current = true
    api
      .archive()
      .then((result) => {
        setManifest(result)
        setManifestState('ready')
        // The Worker knows whether it can mint at all; asking and being refused is worse.
        if (result.credentialsAvailable === false) setMintable(false)
      })
      .catch((failure: unknown) => {
        // 404 is "no archive tier here yet", which is a state, not a fault.
        const absent = failure instanceof ApiFailure && failure.status === 404
        setManifestState(absent ? 'absent' : 'failed')
        if (absent) setMintable(false)
      })
  }, [])

  useEffect(
    () => () => {
      void sessionRef.current?.close()
      sessionRef.current = null
    },
    [],
  )

  const summary = useMemo(() => (manifest ? coverage(manifest.months) : null), [manifest])
  const questions = useMemo(() => (fields ? availability(fields) : []), [fields])

  const describe = useCallback((failure: unknown): string => {
    const message = failure instanceof Error ? failure.message : String(failure)
    // A cross-origin read that R2 has no CORS rule for fails as an opaque network error, and
    // the browser says nothing useful about why. Naming the likely cause saves the hour.
    if (/failed to fetch|networkerror|cors|load failed/i.test(message)) {
      return `${message} — two deployment facts fail exactly like this: a bucket with no CORS rule for this origin, and an endpoint that does not answer to the bucket as a subdomain, which is how this engine addresses S3.`
    }
    if (/403|signature|access denied/i.test(message)) {
      return `${message} — the keys did not sign the way R2 expected, or they do not cover this prefix.`
    }
    return message
  }, [])

  const runQuestion = useCallback(
    async (question: Question, map: FieldMap, forYear: number | null) => {
      const session = sessionRef.current
      if (!session) return
      const built = question.build(map, {
        units: user.unitSystem,
        year: forYear,
        limit: 20,
      })
      setSql(built)
      setSelected(question.id)
      const run = await session.run(built)
      setOutcome({
        result: run.result,
        elapsedMs: run.elapsedMs,
        measure: question.measure,
        caption: question.title,
      })
    },
    [user.unitSystem],
  )

  /**
   * Opens the engine once and keeps it. Instantiating DuckDB costs a second or two of Wasm
   * compilation; the athlete pays that once for the session, never per query.
   */
  const ensureSession = useCallback(async () => {
    if (sessionRef.current) return sessionRef.current
    const session = await ArchiveSession.open()
    sessionRef.current = session
    setEngine({ version: session.version, loadMs: session.loadMs })
    return session
  }, [])

  const attach = useCallback(
    async (urls: string[], kind: Attachment['kind']) => {
      const session = await ensureSession()
      const map = await session.attach(urls)
      setFields(map)
      setAttachment({ kind, parts: urls.length })

      const first = availability(map).find((entry) => entry.missing.length === 0)
      if (first) {
        const latest = manifest ? (coverage(manifest.months).latest?.year ?? null) : null
        setYear(latest)
        await runQuestion(first.question, map, first.question.yearly ? latest : null)
      } else {
        setSql(starterSql(map))
        setOutcome(null)
      }
    },
    [ensureSession, manifest, runQuestion],
  )

  const connect = useCallback(
    async (credentials: S3Credentials) => {
      setBusy(true)
      setError(null)
      try {
        const session = await ensureSession()
        await session.useCredentials(credentials)
        const bucket = credentials.bucket || manifest?.bucket || ''
        const urls = archiveUrls(user.id, bucket, manifest?.months ?? [])
        if (urls.length === 0) {
          throw new Error('No months have been compacted into the archive yet.')
        }
        await attach(urls, 'archive')
      } catch (failure) {
        setError(describe(failure))
      } finally {
        setBusy(false)
      }
    },
    [attach, describe, ensureSession, manifest, user.id],
  )

  const mint = useCallback(async () => {
    setBusy(true)
    setError(null)
    try {
      const { credentials } = await api.archiveCredentials()
      await connect({
        endpoint: credentials.endpoint || manifest?.endpoint || '',
        bucket: credentials.bucket || manifest?.bucket || '',
        accessKeyId: credentials.accessKeyId,
        secretAccessKey: credentials.secretAccessKey,
        sessionToken: credentials.sessionToken,
      })
    } catch (failure) {
      if (failure instanceof ApiFailure && failure.status === 404) {
        setMintable(false)
        setError(
          'This deployment cannot issue archive keys yet. Paste keys of your own below, or query a Parquet file from this device.',
        )
      } else {
        setError(describe(failure))
      }
    } finally {
      setBusy(false)
    }
  }, [connect, describe, manifest])

  const openFiles = useCallback(
    async (files: File[]) => {
      setBusy(true)
      setError(null)
      try {
        const session = await ensureSession()
        const names = await session.registerFiles(files)
        await attach(names, 'files')
      } catch (failure) {
        setError(describe(failure))
      } finally {
        setBusy(false)
      }
    },
    [attach, describe, ensureSession],
  )

  const ask = useCallback(
    async (question: Question, forYear: number | null) => {
      if (!fields) return
      setBusy(true)
      setError(null)
      try {
        await runQuestion(question, fields, question.yearly ? forYear : null)
      } catch (failure) {
        setError(describe(failure))
      } finally {
        setBusy(false)
      }
    },
    [describe, fields, runQuestion],
  )

  const runSql = useCallback(async () => {
    const session = sessionRef.current
    if (!session) return
    setBusy(true)
    setError(null)
    try {
      const run = await session.run(sql)
      setSelected(null)
      setOutcome({ result: run.result, elapsedMs: run.elapsedMs, caption: 'Your own query' })
    } catch (failure) {
      setError(describe(failure))
    } finally {
      setBusy(false)
    }
  }, [describe, sql])

  const readOnly = looksReadOnly(sql)

  return (
    <section className="m3-card hh-section hh-history" aria-labelledby="history-heading">
      <div className="hh-detail-header">
        <h2 id="history-heading" className="t-title-medium">
          Your whole history
        </h2>
        <p className="t-body-medium">
          Years of closed months, compacted into Parquet in your own bucket. The query engine
          runs here, in this tab, over your own storage — nothing is computed on a server and
          nothing is charged for the transfer. The duplicate recordings in your archive are in
          these files too, and every total below leaves them out.
        </p>
      </div>

      {/* Everything about reaching the archive disappears once it has been reached: an athlete
          reading their own totals does not need to be told again how the manifest went. */}
      {!attachment && (
        <>
          {manifestState === 'loading' && (
            <p className="t-body-medium">Looking for your archive…</p>
          )}

          {manifestState === 'failed' && (
            <p className="m3-error t-body-medium" role="alert">
              Could not read your archive manifest. The months are still there; this page just
              could not list them.
            </p>
          )}

          {manifestState === 'absent' && (
            <p className="t-body-medium">
              This deployment has not compacted anything into the analytical tier yet. When the
              nightly job has run, the months appear here — and a Parquet file you already have
              can be queried below in the meantime.
            </p>
          )}

          {manifestState === 'ready' && summary?.months === 0 && (
            <p className="t-body-medium">
              Nothing has been compacted yet. Only closed months are rolled into the archive, so
              a brand-new account has an empty one — every activity is still in your feed.
            </p>
          )}
        </>
      )}

      {!attachment && (
        <ConnectPanel
          manifest={manifest}
          busy={busy}
          mintable={mintable}
          onMint={() => void mint()}
          onCredentials={(credentials) => void connect(credentials)}
          onFiles={(files) => void openFiles(files)}
        />
      )}

      {error && (
        <p className="m3-error t-body-medium" role="alert">
          {error}
        </p>
      )}

      {fields && (
        <>
          <div className="hh-chips hh-history__questions" role="group" aria-label="Questions">
            {questions.map(({ question, missing }) => (
              <button
                key={question.id}
                className={`hh-chip${selected === question.id ? ' hh-chip--selected' : ''}`}
                disabled={busy || missing.length > 0}
                title={
                  missing.length > 0
                    ? `The archive has no ${missing.join(', ')} column.`
                    : question.blurb
                }
                onClick={() => void ask(question, year)}
              >
                {question.title}
              </button>
            ))}
          </div>

          {summary && summary.years.length > 0 && (
            <div className="hh-chips" role="group" aria-label="Year">
              {summary.years
                .slice()
                .reverse()
                .map((candidate) => (
                  <button
                    key={candidate}
                    className={`hh-chip${year === candidate ? ' hh-chip--selected' : ''}`}
                    disabled={busy}
                    onClick={() => {
                      setYear(candidate)
                      const current = questions.find((entry) => entry.question.id === selected)
                      if (current?.question.yearly) void ask(current.question, candidate)
                    }}
                  >
                    {candidate}
                  </button>
                ))}
            </div>
          )}
        </>
      )}

      {outcome && (
        <ResultTable
          result={outcome.result}
          measure={outcome.measure}
          caption={outcome.caption}
        />
      )}

      {fields && (
        <details className="hh-history__sql">
          <summary className="t-label-large">The SQL behind this</summary>
          <div className="hh-history__panel">
            <p className="t-body-small">
              The same statement runs in the DuckDB CLI against your bucket; `activities` is a
              view over the Parquet parts this page listed for you.
            </p>
            <textarea
              id="archive-sql"
              name="sql"
              className="hh-history__editor"
              value={sql}
              spellCheck={false}
              rows={Math.min(14, sql.split('\n').length + 1)}
              onChange={(event) => setSql(event.target.value)}
              aria-label="SQL"
            />
            <div className="hh-section__bar">
              <button
                className="m3-button m3-button--tonal"
                disabled={busy || !readOnly}
                onClick={() => void runSql()}
              >
                Run
              </button>
              {!readOnly && (
                <span className="t-body-small">
                  These keys are read-only, so that statement would be refused at the bucket.
                </span>
              )}
            </div>
          </div>
        </details>
      )}

      <p className="t-body-small hh-history__status" role="status">
        {engine
          ? `DuckDB ${engine.version} started in ${(engine.loadMs / 1000).toFixed(1)} s`
          : 'DuckDB loads only when you open the archive — it never touches the feed.'}
        {attachment &&
          ` · ${attachment.parts} Parquet part${attachment.parts === 1 ? '' : 's'} ${
            attachment.kind === 'files' ? 'from this device' : 'in your bucket'
          }`}
        {outcome && ` · ${outcome.result.rows.length} rows in ${Math.round(outcome.elapsedMs)} ms`}
      </p>
    </section>
  )
}
