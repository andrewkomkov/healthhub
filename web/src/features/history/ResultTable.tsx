import { barShares, formatCell, numericColumns, type ResultSet } from '../../core/analytics/rows'

/**
 * Whatever DuckDB answered, as a table.
 *
 * It renders columns it has never heard of, because the free-text box can ask for any of them.
 * The only interpretation it makes is a comparison bar on one named column — and that bar is
 * scaled within the result, which is the only comparison a bar can make honestly.
 */
export function ResultTable({
  result,
  measure,
  caption,
}: {
  result: ResultSet
  measure?: string | undefined
  caption: string
}) {
  if (result.columns.length === 0) return null

  const measureIndex = measure
    ? result.columns.findIndex((name) => name.startsWith(measure))
    : -1
  const shares = measureIndex >= 0 ? barShares(result.rows, measureIndex) : []
  const numeric = numericColumns(result)

  return (
    <div className="hh-result">
      <table className="hh-table">
        <caption className="hh-visually-hidden">{caption}</caption>
        <thead>
          <tr>
            {result.columns.map((name, index) => (
              <th key={name} scope="col" style={{ textAlign: numeric[index] ? 'right' : 'left' }}>
                {name}
              </th>
            ))}
            {measureIndex >= 0 && (
              <th scope="col" className="hh-table__bar-column">
                <span className="hh-visually-hidden">Relative {measure}</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((value, index) =>
                index === 0 ? (
                  <th key={index} scope="row" style={{ textAlign: numeric[0] ? 'right' : 'left' }}>
                    {formatCell(value)}
                  </th>
                ) : (
                  <td
                    key={index}
                    className={numeric[index] ? 'numeric' : undefined}
                    style={{ textAlign: numeric[index] ? 'right' : 'left' }}
                  >
                    {formatCell(value)}
                  </td>
                ),
              )}
              {measureIndex >= 0 && (
                <td className="hh-table__bar-column">
                  <span
                    className="hh-bar"
                    style={{ width: `${shares[rowIndex] ?? 0}%` }}
                    aria-hidden="true"
                  />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      {result.rows.length === 0 && (
        <p className="t-body-medium">
          That query ran and matched nothing. The months you have compacted may not cover it.
        </p>
      )}
    </div>
  )
}
