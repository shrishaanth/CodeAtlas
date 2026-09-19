import { useMemo, useState } from 'react'
import type { FilePair, Report } from './types'
import { isCodeLanguage } from './types'
import type { ReportIndex } from './reportIndex'
import { formatNumber } from './reportIndex'

interface Props {
  report: Report
  index: ReportIndex
  onSelect: (path: string) => void
}

const METRICS_URL = 'https://github.com/shrishaanth/CodeAtlas/blob/main/docs/metrics.md#change-coupling'

export function CouplingView({ report, index, onSelect }: Props) {
  // Docs and config files that get bumped together dominate the raw list, so start with code only.
  const [includeNonCode, setIncludeNonCode] = useState(false)
  const [includeTests, setIncludeTests] = useState(true)
  const [hiddenOnly, setHiddenOnly] = useState(false)
  const coupling = report.coupling

  const pairs = useMemo(() => {
    const isCode = (p: string) => isCodeLanguage(index.files.get(p)?.language)
    const isTest = (p: string) => index.files.get(p)?.isTest ?? false
    return (coupling?.files ?? []).filter(
      (p) =>
        (includeNonCode || (isCode(p.a) && isCode(p.b))) &&
        (includeTests || (!isTest(p.a) && !isTest(p.b))) &&
        (!hiddenOnly || p.hasImportEdge === false),
    )
  }, [coupling, index, includeNonCode, includeTests, hiddenOnly])

  if (!coupling) {
    return <p className="muted">This report has no change-coupling data (it was produced before coupling existed).</p>
  }

  return (
    <div className="stack">
      <p className="muted small">
        Files and areas that tend to change in the same commits. Degree is shared commits divided by the average of
        both files&apos; commits (1 = always together). Commits touching more than 30 files are ignored (
        {formatNumber(coupling.skippedLargeCommits)} here). <a href={METRICS_URL}>Definitions</a>
      </p>

      <section>
        <h3>Areas</h3>
        {coupling.components.length === 0 ? (
          <p className="muted">No areas changed together in 3 or more commits.</p>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Area</th>
                  <th>Area</th>
                  <th className="num">Together</th>
                  <th className="num">Degree</th>
                </tr>
              </thead>
              <tbody>
                {coupling.components.slice(0, 20).map((p) => (
                  <tr key={`${p.a}|${p.b}`}>
                    <td className="mono">{p.a}</td>
                    <td className="mono">{p.b}</td>
                    <td className="num">{formatNumber(p.together)}</td>
                    <td className="num">{p.degree.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section>
        <div className="section-head">
          <h3>Files ({pairs.length})</h3>
          <div className="filters small">
            <label>
              <input type="checkbox" checked={includeTests} onChange={(e) => setIncludeTests(e.target.checked)} />{' '}
              Tests
            </label>
            <label>
              <input
                type="checkbox"
                checked={includeNonCode}
                onChange={(e) => setIncludeNonCode(e.target.checked)}
              />{' '}
              Docs and config
            </label>
            <label>
              <input type="checkbox" checked={hiddenOnly} onChange={(e) => setHiddenOnly(e.target.checked)} /> Only
              without an import
            </label>
          </div>
        </div>
        <p className="muted small">
          <em>No import</em>: neither file imports the other, yet they change together, a dependency the code does not
          show. Pairs of test files often land here after broad test refactors, so judge those with care.{' '}
          <em>Unknown</em>: one file is not Python, so its imports are not analyzed.
        </p>
        {pairs.length === 0 ? (
          <p className="muted">No pairs match these filters.</p>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>File</th>
                  <th>File</th>
                  <th className="num">Together</th>
                  <th className="num">Degree</th>
                  <th>Import</th>
                </tr>
              </thead>
              <tbody>
                {pairs.slice(0, 100).map((p) => (
                  <tr key={`${p.a}|${p.b}`}>
                    <td>
                      <PathButton path={p.a} onSelect={onSelect} />
                    </td>
                    <td>
                      <PathButton path={p.b} onSelect={onSelect} />
                    </td>
                    <td className="num">{p.together}</td>
                    <td className="num">{p.degree.toFixed(2)}</td>
                    <td>
                      <ImportEdge pair={p} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}

function PathButton({ path, onSelect }: { path: string; onSelect: (path: string) => void }) {
  return (
    <button type="button" className="link-button mono" onClick={() => onSelect(path)}>
      {path}
    </button>
  )
}

export function ImportEdge({ pair }: { pair: FilePair }) {
  if (pair.hasImportEdge === null) return <span className="muted">unknown</span>
  return pair.hasImportEdge ? <span>imports</span> : <span className="pill pill-warn">no import</span>
}
