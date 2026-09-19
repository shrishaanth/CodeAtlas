import type { Report } from './types'
import { formatDate, formatNumber } from './reportIndex'

const MAX_LANGUAGES = 6

export function OverviewPanel({ report }: { report: Report }) {
  const o = report.overview
  const totalLines = o.languages.reduce((sum, l) => sum + l.lines, 0)
  const shown = o.languages.slice(0, MAX_LANGUAGES)
  const otherLines = totalLines - shown.reduce((sum, l) => sum + l.lines, 0)

  return (
    <section aria-label="Overview">
      <div className="stats">
        <Stat label="Commits" value={formatNumber(o.commits)} note={report.limits.historyTruncated ? 'history capped' : undefined} />
        <Stat label="Contributors" value={formatNumber(o.contributors)} note="distinct emails" />
        <Stat label="Files" value={formatNumber(o.files)} />
        <Stat label="Lines of code" value={formatNumber(o.linesOfCode)} />
        <Stat
          label="Test files per source file"
          value={o.testFileRatio === null ? 'n/a' : o.testFileRatio.toFixed(2)}
          note="Python"
        />
        <Stat label="History" value={`${formatDate(o.firstCommitAt)} – ${formatDate(o.lastCommitAt)}`} small />
        {report.findings && (
          <Stat
            label="Findings"
            value={String(report.findings.length)}
            note={`${report.findings.filter((f) => f.severity === 'warn').length} warn`}
          />
        )}
      </div>

      {totalLines > 0 && (
        <div className="languages">
          <div className="language-bar" role="img" aria-label="Lines by language">
            {shown.map((l, i) => (
              <span
                key={l.language}
                className={`lang-seg lang-${i}`}
                style={{ width: `${(100 * l.lines) / totalLines}%` }}
                title={`${l.language}: ${formatNumber(l.lines)} lines`}
              />
            ))}
            {otherLines > 0 && (
              <span className="lang-seg lang-other" style={{ width: `${(100 * otherLines) / totalLines}%` }} />
            )}
          </div>
          <ul className="language-legend">
            {shown.map((l, i) => (
              <li key={l.language}>
                <span className={`swatch lang-${i}`} />
                {l.language} <span className="muted">{formatNumber(l.lines)} lines, {l.files} files</span>
              </li>
            ))}
            {otherLines > 0 && (
              <li>
                <span className="swatch lang-other" />
                other <span className="muted">{formatNumber(otherLines)} lines</span>
              </li>
            )}
          </ul>
        </div>
      )}
    </section>
  )
}

function Stat({ label, value, note, small }: { label: string; value: string; note?: string; small?: boolean }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className={small ? 'stat-value stat-value-small' : 'stat-value'}>{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  )
}
