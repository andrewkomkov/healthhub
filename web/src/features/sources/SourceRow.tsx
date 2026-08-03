import type { KeyboardEvent } from 'react'
import { interpolate, useMessages, type Bundle } from '../../core/i18n'
import type { Source } from '../../core/api/client'
import { sourceLabel } from '../../core/format'

const MESSAGES = {
  en: {
    lifted: "%1$s lifted, %2$s. Arrow keys move it, space drops it, escape cancels.",
    reorder: "Reorder %1$s, %2$s. Press space to lift it.",
    consider: "Use %1$s when choosing which recording represents a workout",
  },
  ru: {
    lifted: "%1$s поднят, %2$s. Стрелки перемещают, пробел ставит, escape отменяет.",
    reorder: "Переставить %1$s, %2$s. Нажмите пробел, чтобы поднять.",
    consider: "Учитывать %1$s при выборе записи, представляющей тренировку",
  },
} satisfies Bundle<Record<string, string>>


function countLabel(count: number): string {
  return count === 1 ? '1 activity' : `${count} activities`
}

/**
 * One app in the trust order.
 *
 * The handle is a real button, not a decorated `div`, because it has to be reachable by Tab
 * and operable by Space and the arrow keys — an athlete on a trackpad and an athlete on a
 * screen reader are reordering the same list, and only one of them can drag.
 */
export function SourceRow({
  source,
  index,
  total,
  grabbed,
  handleRef,
  onHandleKeyDown,
  onLiftStart,
  onToggle,
  onDragStart,
  onDragEnter,
  onDragEnd,
  draggable,
}: {
  source: Source
  index: number
  total: number
  grabbed: boolean
  handleRef: (node: HTMLButtonElement | null) => void
  onHandleKeyDown: (event: KeyboardEvent<HTMLButtonElement>, index: number) => void
  onLiftStart: (index: number) => void
  onToggle: (index: number) => void
  onDragStart: (index: number) => void
  onDragEnter: (index: number) => void
  onDragEnd: () => void
  draggable: boolean
}) {
  const t = useMessages(MESSAGES)
  const name = sourceLabel(source.packageName, source.label)
  const position = `position ${index + 1} of ${total}`

  return (
    <li
      className={[
        'm3-card',
        'hh-source',
        grabbed ? 'hh-source--grabbed' : '',
        source.enabled ? '' : 'hh-source--disabled',
      ]
        .filter(Boolean)
        .join(' ')}
      draggable={draggable}
      onDragStart={() => onDragStart(index)}
      onDragEnter={() => onDragEnter(index)}
      onDragOver={(event) => event.preventDefault()}
      onDrop={(event) => event.preventDefault()}
      onDragEnd={onDragEnd}
    >
      <button
        ref={handleRef}
        type="button"
        className="hh-source__handle"
        aria-pressed={grabbed}
        aria-label={
          grabbed
            ? interpolate(t.lifted, name, position)
            : interpolate(t.reorder, name, position)
        }
        onKeyDown={(event) => onHandleKeyDown(event, index)}
        onPointerDown={() => onLiftStart(index)}
      >
        <span className="hh-source__grip" aria-hidden="true" />
      </button>

      <span className="hh-source__rank t-title-medium numeric" aria-hidden="true">
        {index + 1}
      </span>

      <span className="hh-source__text">
        <span className="hh-source__name t-title-medium">{name}</span>
        <span className="hh-source__package t-body-small">
          {source.packageName} · {countLabel(source.activityCount)}
        </span>
      </span>

      <span className="hh-source__controls">
        <span className="t-body-small">{source.enabled ? 'On' : 'Off'}</span>
        <button
          type="button"
          role="switch"
          className="hh-switch"
          aria-checked={source.enabled}
          aria-label={interpolate(t.consider, name)}
          onClick={() => onToggle(index)}
        />
      </span>
    </li>
  )
}
