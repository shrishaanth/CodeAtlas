import { lazy, Suspense, useMemo, useState } from 'react'
import { FileDetails } from './FileDetails'
import { OverviewPanel } from './OverviewPanel'
import { ReadingOrderList } from './ReadingOrderList'
import { formatDate } from './reportIndex'
import { indexReport } from './reportIndex'
import type { Report } from './types'

// Cytoscape is most of the JavaScript; load it only when the graph tab is opened.
const ImportGraphView = lazy(() => import('./ImportGraphView').then((m) => ({ default: m.ImportGraphView })))

type Tab = 'reading' | 'graph'

/** Renders a report. Knows nothing about where it came from (static demo file or live API). */
export function ReportView({ report }: { report: Report }) {
  const index = useMemo(() => indexReport(report), [report])
  const [tab, setTab] = useState<Tab>('reading')
  const [selected, setSelected] = useState<string | null>(report.readingOrder[0]?.path ?? null)
  const isGitHub = report.repo.source.startsWith('https://github.com/')

  const download = () => {
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `codeatlas-${report.repo.name}-${report.repo.commit.slice(0, 7)}.json`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  return (
    <div className="report">
      <header className="report-header">
        <div>
          <h2>{report.repo.name}</h2>
          <p className="muted small">
            {isGitHub ? (
              <a href={report.repo.source} target="_blank" rel="noreferrer">
                {report.repo.source.replace('https://', '')}
              </a>
            ) : (
              'local repository'
            )}{' '}
            · commit <code>{report.repo.commit.slice(0, 7)}</code>
            {report.repo.branch ? ` on ${report.repo.branch}` : ''} · analyzed {formatDate(report.repo.analyzedAt)}
          </p>
        </div>
        <button type="button" className="secondary" onClick={download}>
          Download JSON
        </button>
      </header>

      <OverviewPanel report={report} />

      {(report.limits.parseErrors.length > 0 || report.limits.skippedFiles.length > 0) && (
        <p className="muted small">
          {report.limits.parseErrors.length > 0 &&
            `${report.limits.parseErrors.length} file(s) had syntax errors and were parsed partially. `}
          {report.limits.skippedFiles.length > 0 &&
            `${report.limits.skippedFiles.length} file(s) over 1 MB were not parsed.`}
        </p>
      )}

      <div className="report-body">
        <div className="report-main">
          <div className="tabs" role="tablist">
            <button type="button" role="tab" aria-selected={tab === 'reading'} onClick={() => setTab('reading')}>
              Reading order
            </button>
            <button type="button" role="tab" aria-selected={tab === 'graph'} onClick={() => setTab('graph')}>
              Import graph
            </button>
          </div>
          {tab === 'reading' ? (
            <ReadingOrderList items={report.readingOrder} selected={selected} onSelect={setSelected} />
          ) : (
            <Suspense fallback={<p className="muted">Loading graph…</p>}>
              <ImportGraphView report={report} index={index} selected={selected} onSelect={setSelected} />
            </Suspense>
          )}
        </div>
        <aside className="report-side card">
          <FileDetails report={report} index={index} path={selected} onSelect={setSelected} />
        </aside>
      </div>
    </div>
  )
}
