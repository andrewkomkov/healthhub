import { useEffect, useRef, useState } from 'react'
import { interpolate, useMessages, type Bundle } from '../../core/i18n'
import { api, type Measurement, type SleepNight } from '../../core/api/client'
import {
  baseline,
  daily,
  deviationLabel,
  deviationPercent,
  hoursAndMinutes,
  HRV_KIND,
  latest,
  MIN_BASELINE_DAYS,
  readiness,
  RESTING_HR_KIND,
  type DayValue,
  type ReadinessScore,
} from './recovery'
const MESSAGES = {
  en: {
    loading: 'Loading your sleep and recovery…',
    errorTitle: 'Could not load your health data',
    nothingTitle: 'Nothing here yet',
    nothingBody: 'Sleep, heart-rate variability and resting heart rate are read from Health Connect by the HealthHub app on your phone, and each one is off until you turn it on. Open Health on the phone, switch on the domains you want, and they will appear here after the next sync.',
    readiness: 'Readiness',
    lastNight: 'Last night',
    noStages: 'The app that recorded this night stored its length but not its stages, so there is no breakdown to draw.',
    deep: 'Deep',
    rem: 'REM',
    light: 'Light',
    awake: 'Awake',
    hrv: 'Heart-rate variability',
    restingHr: 'Resting heart rate',
    unitMs: 'ms',
    unitBpm: 'bpm',
    baselineMissing: '%1$s of %2$s days needed before there is a normal to compare today against.',
    trendNoBaseline: '%1$s: %2$s days recorded, not yet enough for a baseline.',
    trendToday: '%1$s: %2$s %3$s today, against a normal of %4$s %3$s.',
  },
  ru: {
    loading: 'Загружаем сон и восстановление…',
    errorTitle: 'Не удалось загрузить данные о здоровье',
    nothingTitle: 'Здесь пока пусто',
    nothingBody: 'Сон, вариабельность пульса и пульс покоя читает из Health Connect приложение HealthHub на вашем телефоне, и каждый из них выключен, пока вы его не включите. Откройте «Здоровье» на телефоне, включите нужные разделы — и после следующей синхронизации они появятся здесь.',
    readiness: 'Готовность',
    lastNight: 'Прошлая ночь',
    noStages: 'Приложение, записавшее эту ночь, сохранило её длительность, но не стадии, поэтому разбивку строить не из чего.',
    deep: 'Глубокий',
    rem: 'БДГ',
    light: 'Лёгкий',
    awake: 'Бодрствование',
    hrv: 'Вариабельность пульса',
    restingHr: 'Пульс покоя',
    unitMs: 'мс',
    unitBpm: 'уд/мин',
    baselineMissing: 'Нужно %2$s дней, чтобы появилась норма для сравнения; сейчас %1$s.',
    trendNoBaseline: '%1$s: записано дней — %2$s, для нормы пока недостаточно.',
    trendToday: '%1$s: сегодня %2$s %3$s против нормы %4$s %3$s.',
  },
} satisfies Bundle<Record<string, string>>


/**
 * Sleep, recovery and readiness in the browser — the web half of roadmap session 4, step 4.
 *
 * Everything drawn here is either a figure the phone computed and the edge stored verbatim, or
 * a comparison derived in `recovery.ts` from those figures. Nothing is derived on the server,
 * and there is no readiness field anywhere on the API: that is Constitution Principle I, and it
 * is why `recovery.ts` is a deliberate twin of `feature/health/Readiness.kt` rather than a
 * second, browser-flavoured idea of what readiness means.
 *
 * The screen is honest about absence in three different ways, because three different things
 * can be missing and they are not the same sentence: no data at all (the phone has never synced
 * this domain), not enough history for a baseline (fewer than a week), and a night the source
 * recorded with no stage detail.
 */
export function RecoveryPanel() {
  const t = useMessages(MESSAGES)
  const [state, setState] = useState<{
    loading: boolean
    error: string | null
    hrv: DayValue[]
    restingHr: DayValue[]
    nights: SleepNight[]
  }>({ loading: true, error: null, hrv: [], restingHr: [], nights: [] })

  // A ref rather than the loading flag: StrictMode double-invokes mount effects in development
  // and both calls read the same pre-update state, which is how the archive once showed every
  // workout twice. Same guard, same reason — see AGENT-NOTES.
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true

    const from = Date.now() - WINDOW_DAYS * 86_400_000
    Promise.all([
      api.measurements({ kind: HRV_KIND, from }),
      api.measurements({ kind: RESTING_HR_KIND, from }),
      api.sleeps({ from }),
    ])
      .then(([hrv, restingHr, sleep]) =>
        setState({
          loading: false,
          error: null,
          hrv: daily(hrv.measurements as Measurement[]),
          restingHr: daily(restingHr.measurements as Measurement[]),
          nights: sleep.sleeps,
        }),
      )
      .catch((failure: Error) =>
        setState((previous) => ({ ...previous, loading: false, error: failure.message })),
      )
  }, [])

  if (state.loading) {
    return (
      <section className="m3-card hh-section" aria-busy="true">
        <p className="t-body-medium">{t.loading}</p>
      </section>
    )
  }

  if (state.error) {
    return (
      <section className="m3-card hh-section">
        <h2 className="t-title-medium">{t.errorTitle}</h2>
        <p className="t-body-medium">{state.error}</p>
      </section>
    )
  }

  const lastNight = state.nights[0] ?? null
  const hrvToday = latest(state.hrv)
  const restingToday = latest(state.restingHr)

  const score = readiness({
    hrvToday: hrvToday?.value ?? null,
    hrvBaseline: baseline(state.hrv, BASELINE_WINDOW_DAYS),
    restingHrToday: restingToday?.value ?? null,
    restingHrBaseline: baseline(state.restingHr, BASELINE_WINDOW_DAYS),
    sleptSeconds: lastNight?.totalSeconds ?? null,
  })

  const nothingAtAll =
    state.hrv.length === 0 && state.restingHr.length === 0 && state.nights.length === 0

  if (nothingAtAll) {
    return (
      <section className="m3-card hh-section">
        <h2 className="t-title-medium">{t.nothingTitle}</h2>
<p className="t-body-medium">{t.nothingBody}</p>
      </section>
    )
  }

  return (
    <>
      <ReadinessCard score={score} />
      {lastNight && <LastNightCard night={lastNight} />}
      <TrendCard
        title={t.hrv}
        unit={t.unitMs}
        days={state.hrv}
        today={hrvToday}
        base={baseline(state.hrv, BASELINE_WINDOW_DAYS)}
      />
      <TrendCard
        title={t.restingHr}
        unit={t.unitBpm}
        days={state.restingHr}
        today={restingToday}
        base={baseline(state.restingHr, BASELINE_WINDOW_DAYS)}
      />
    </>
  )
}

function ReadinessCard({ score }: { score: ReadinessScore }) {
  const t = useMessages(MESSAGES)
  return (
    <section className="m3-card hh-section" aria-labelledby="readiness-heading">
      <div className="hh-detail-header">
        <h2 id="readiness-heading" className="t-title-medium">
          {t.readiness}
        </h2>
      </div>

      {score.value === null ? (
        <p className="t-body-medium">{score.note}</p>
      ) : (
        <>
          <p className="t-display-large numeric hh-readiness__value">{score.value}</p>
          <ul className="hh-readiness__components">
            {score.components.map((component) => (
              <li key={component.label}>
                <span className="t-label-medium">{component.label}</span>
                <span className="t-body-medium numeric">{component.detail}</span>
              </li>
            ))}
          </ul>
          {/* Never a colour alone, and never without the sentence: a number out of a hundred
              with a green ring around it is very easy to over-trust, and this one is a
              comparison with the athlete's own median rather than a measurement of anything. */}
          <p className="t-body-small">{score.note}</p>
        </>
      )}
    </section>
  )
}

function LastNightCard({ night }: { night: SleepNight }) {
  const t = useMessages(MESSAGES)
  const stages = [
    [t.deep, night.stages.deep],
    [t.rem, night.stages.rem],
    [t.light, night.stages.light],
    [t.awake, night.stages.awake],
  ] as const
  const detailed = stages.some(([, seconds]) => seconds != null && seconds > 0)

  return (
    <section className="m3-card hh-section" aria-labelledby="last-night-heading">
      <div className="hh-detail-header">
        <h2 id="last-night-heading" className="t-title-medium">
          {t.lastNight}
        </h2>
        <p className="t-label-medium">{night.localDate}</p>
      </div>

      <p className="t-headline-medium numeric">{hoursAndMinutes(night.totalSeconds)}</p>

      {detailed ? (
        <ul className="hh-stages">
          {stages.map(([label, seconds]) => (
            <li key={label} className="hh-stages__row">
              <span className="t-label-medium">{label}</span>
              <span
                className="hh-stages__bar"
                style={{ width: `${((seconds ?? 0) / night.totalSeconds) * 100}%` }}
                aria-hidden="true"
              />
              <span className="t-body-medium numeric">
                {seconds == null ? '—' : hoursAndMinutes(seconds)}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        /* A source that wrote a duration and no stages is normal — plenty of phone apps do
           exactly that. That night slept for its whole duration, not for zero seconds, and an
           empty bar chart would say the opposite. */
<p className="t-body-medium">{t.noStages}</p>
      )}
    </section>
  )
}

function TrendCard({
  title,
  unit,
  days,
  today,
  base,
}: {
  title: string
  unit: string
  days: DayValue[]
  today: DayValue | null
  base: number | null
}) {
  const t = useMessages(MESSAGES)
  if (days.length === 0) return null

  const deviation = deviationLabel(deviationPercent(today?.value ?? null, base))
  const max = Math.max(...days.map((d) => d.value))
  const min = Math.min(...days.map((d) => d.value))
  const span = max - min || 1

  return (
    <section className="m3-card hh-section" aria-labelledby={`trend-${unit}`}>
      <div className="hh-detail-header">
        <h2 id={`trend-${unit}`} className="t-title-medium">
          {title}
        </h2>
        <p className="t-body-medium numeric">
          {today ? `${Math.round(today.value)} ${unit}` : '—'}
          {deviation ? ` · ${deviation}` : ''}
        </p>
      </div>

      {/* Bars rather than a line, and no axis: the question this answers is "is today unlike my
          recent days", which is a shape rather than a reading. The figures that are meant to be
          read are the two above, in words. */}
      <div
        className="hh-trend"
        role="img"
        aria-label={
          base === null
            ? interpolate(t.trendNoBaseline, title, days.length)
            : interpolate(
                t.trendToday,
                title,
                Math.round(today?.value ?? 0),
                unit,
                Math.round(base),
              )
        }
      >
        {days.map((day) => (
          <span
            key={day.date}
            className="hh-trend__bar"
            style={{ height: `${20 + ((day.value - min) / span) * 80}%` }}
          />
        ))}
      </div>

      {base === null && (
        <p className="t-body-small">
          {interpolate(t.baselineMissing, days.length, MIN_BASELINE_DAYS)}
        </p>
      )}
    </section>
  )
}

/** Three weeks of mornings: enough for a baseline and a shape, not enough to be a scroll. */
const WINDOW_DAYS = 21
const BASELINE_WINDOW_DAYS = 21
