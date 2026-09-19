import { useState } from 'react'
import { KIND_LABELS, KIND_ORDER } from './findingKinds'
import type { Evidence, Finding, Report } from './types'
import { sourceUrl } from './types'

interface Props {
  report: Report
  onSelect: (path: string) => void
}

const METRICS_URL = 'https://github.com/shrishaanth/CodeAtlas/blob/main/docs/metrics.md#findings'

export function FindingsView({ report, onSelect }: Props) {
  const [warnOnly, setWarnOnly] = useState(false)
  const findings = report.findings
  if (!findings) {
    return <p className="muted">This report has no findings (it was produced before findings existed).</p>
  }
  const shown = warnOnly ? findings.filter((f) => f.severity === 'warn') : findings
  const omitted = report.limits.findingsOmitted ?? {}

  return (
    <div className="stack">
      <div className="section-head">
        <p className="muted small">
          Concrete things worth a look, each with the files and lines that support it. A finding is a prompt to check,
          not a verdict: <em>warn</em> usually needs action, <em>info</em> may be deliberate.{' '}
          <a href={METRICS_URL}>How each is detected</a>
        </p>
        <label className="small">
          <input type="checkbox" checked={warnOnly} onChange={(e) => setWarnOnly(e.target.checked)} /> Warnings only
        </label>
      </div>
      {shown.length === 0 && <p className="muted">No findings{warnOnly ? ' at warn level' : ''}.</p>}
      {KIND_ORDER.map((kind) => {
        const list = shown.filter((f) => f.kind === kind)
        if (list.length === 0) return null
        return (
          <section key={kind}>
            <h3>
              {KIND_LABELS[kind]} ({list.length}
              {omitted[kind] ? ` of ${list.length + omitted[kind]}` : ''})
            </h3>
            <ul className="findings">
              {list.map((f) => (
                <FindingCard key={f.id} finding={f} report={report} onSelect={onSelect} />
              ))}
            </ul>
          </section>
        )
      })}
    </div>
  )
}

function FindingCard({ finding, report, onSelect }: { finding: Finding; report: Report; onSelect: (p: string) => void }) {
  return (
    <li className="finding card">
      <div className="finding-head">
        <span className={finding.severity === 'warn' ? 'pill pill-warn' : 'pill'}>{finding.severity}</span>
        <strong>{finding.title}</strong>
      </div>
      <p className="small">{finding.detail}</p>
      <ul className="plain evidence">
        {finding.evidence.map((e, i) => (
          <EvidenceLine key={i} evidence={e} report={report} onSelect={onSelect} />
        ))}
      </ul>
    </li>
  )
}

function EvidenceLine({ evidence, report, onSelect }: { evidence: Evidence; report: Report; onSelect: (p: string) => void }) {
  const { path, startLine, endLine, note } = evidence
  if (!path) return <li className="muted small">{note}</li>
  const url = sourceUrl(report.repo, path, startLine, endLine)
  const lines = startLine ? (endLine && endLine !== startLine ? `L${startLine}–${endLine}` : `L${startLine}`) : null
  return (
    <li className="small">
      <button type="button" className="link-button mono" onClick={() => onSelect(path)}>
        {path}
      </button>
      {lines &&
        (url ? (
          <>
            {' '}
            <a href={url} target="_blank" rel="noreferrer" className="muted">
              {lines}
            </a>
          </>
        ) : (
          <span className="muted"> {lines}</span>
        ))}
      {note && <span className="muted"> · {note}</span>}
    </li>
  )
}
