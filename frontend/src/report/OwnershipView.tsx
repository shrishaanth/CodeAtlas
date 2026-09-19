import { useMemo, useState } from 'react'
import type { Ownership, Report } from './types'
import type { ReportIndex } from './reportIndex'
import { formatDate, formatNumber, ownersText } from './reportIndex'

interface Props {
  report: Report
  index: ReportIndex
  onSelect: (path: string) => void
}

const METRICS_URL = 'https://github.com/shrishaanth/CodeAtlas/blob/main/docs/metrics.md#ownership'

export function OwnershipView({ report, index, onSelect }: Props) {
  const [depth, setDepth] = useState(2)
  const dirs = report.people?.directoryOwnership
  const files = report.people?.fileOwnership

  const shownDirs = useMemo(
    () =>
      (dirs ?? [])
        .filter((d) => d.path !== '.' && d.path.split('/').length <= depth)
        .sort((a, b) => a.path.localeCompare(b.path)),
    [dirs, depth],
  )
  const flagged = useMemo(
    () => (files ?? []).filter((f) => f.flags.length > 0).sort((a, b) => b.totalLines - a.totalLines),
    [files],
  )
  const root = dirs?.find((d) => d.path === '.')
  const people = (report.people?.authors ?? []).filter((a) => !a.isBot)

  if (!dirs || dirs.length === 0) {
    return <p className="muted">This report has no ownership data (it was produced before ownership existed).</p>
  }

  return (
    <div className="stack">
      <p className="muted small">
        Who last changed each current line (git blame, whitespace changes ignored). Bus factor is how many people
        together own half the lines: 1 means one person holds most of the knowledge.{' '}
        <a href={METRICS_URL}>Definitions</a>
      </p>

      {root && (
        <p>
          Whole repository: bus factor <strong>{root.busFactor}</strong> across {formatNumber(root.totalLines)} blamed
          lines; top owners {ownersText(root, index)}.
        </p>
      )}

      <section>
        <div className="section-head">
          <h3>Directories</h3>
          <label className="small">
            Depth{' '}
            <select value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
              {[1, 2, 3, 4].map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Directory</th>
                <th className="num">Lines</th>
                <th className="num">Bus factor</th>
                <th>Top owners</th>
                <th>Flags</th>
              </tr>
            </thead>
            <tbody>
              {shownDirs.map((d) => (
                <tr key={d.path}>
                  <td className="mono">{d.path}</td>
                  <td className="num">{formatNumber(d.totalLines)}</td>
                  <td className="num">
                    <span className={d.busFactor === 1 ? 'pill pill-warn' : 'pill'}>{d.busFactor}</span>
                  </td>
                  <td>{ownersText(d, index)}</td>
                  <td>
                    <Flags o={d} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h3>Files that depend on one person ({flagged.length})</h3>
        <p className="muted small">
          At least 100 lines, bus factor 1, and one person owns 80% or more. <em>Orphaned</em>: that person has no
          commits in the repository&apos;s last year.
        </p>
        {flagged.length === 0 ? (
          <p className="muted">None.</p>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>File</th>
                  <th className="num">Lines</th>
                  <th>Owner</th>
                  <th>Flags</th>
                </tr>
              </thead>
              <tbody>
                {flagged.slice(0, 100).map((f) => (
                  <tr key={f.path}>
                    <td>
                      <button type="button" className="link-button mono" onClick={() => onSelect(f.path)}>
                        {f.path}
                      </button>
                    </td>
                    <td className="num">{formatNumber(f.totalLines)}</td>
                    <td>{ownersText(f, index, 1)}</td>
                    <td>
                      <Flags o={f} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section>
        <h3>People ({formatNumber(people.length)})</h3>
        <p className="muted small">
          After merging identities that belong to one person (same email, same full name, or .mailmap). Bots are
          excluded.
        </p>
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Name</th>
                <th className="num">Commits</th>
                <th>Last commit</th>
                <th className="num">Emails</th>
              </tr>
            </thead>
            <tbody>
              {people.slice(0, 25).map((a) => (
                <tr key={a.id}>
                  <td>{a.name}</td>
                  <td className="num">{formatNumber(a.commits)}</td>
                  <td>{formatDate(a.lastCommitAt)}</td>
                  <td className="num" title={a.emails.join('\n')}>
                    {a.emails.length}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}

export function Flags({ o }: { o: Ownership }) {
  return (
    <>
      {o.flags.map((f) => (
        <span key={f} className={f === 'orphaned' ? 'pill pill-err' : 'pill pill-warn'}>
          {f}
        </span>
      ))}
    </>
  )
}
