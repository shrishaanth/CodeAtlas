import type { Report } from './types'
import { formatNumber } from './reportIndex'

interface Props {
  report: Report
  onSelect: (path: string) => void
}

const METRICS_URL = 'https://github.com/shrishaanth/CodeAtlas/blob/main/docs/metrics.md#hotspots'

export function HotspotsView({ report, onSelect }: Props) {
  const hotspots = report.hotspots
  if (!hotspots) {
    return <p className="muted">This report has no hotspot data (it was produced before hotspots existed).</p>
  }
  return (
    <div className="stack">
      <p className="muted small">
        Code that is both complicated and changed often, where bugs and slow reviews tend to concentrate. Complexity
        is indentation depth summed over lines, which tracks nesting in any language. The score multiplies both, so a
        file has to be busy <em>and</em> complex to rank high. <a href={METRICS_URL}>Definitions</a>
      </p>
      {hotspots.length === 0 ? (
        <p className="muted">No non-test code files with history.</p>
      ) : (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th className="num">#</th>
                <th>File</th>
                <th>Score</th>
                <th className="num">Commits</th>
                <th className="num">Lines</th>
                <th className="num">Complexity</th>
              </tr>
            </thead>
            <tbody>
              {hotspots.map((h) => (
                <tr key={h.path}>
                  <td className="num muted">{h.rank}</td>
                  <td>
                    <button type="button" className="link-button mono" onClick={() => onSelect(h.path)}>
                      {h.path}
                    </button>
                  </td>
                  <td>
                    <span className="bar" title={h.score.toFixed(3)}>
                      <span className="bar-fill" style={{ width: `${h.score * 100}%` }} />
                    </span>
                  </td>
                  <td className="num">{formatNumber(h.commits)}</td>
                  <td className="num">{formatNumber(h.lines)}</td>
                  <td className="num">{formatNumber(h.complexity)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
