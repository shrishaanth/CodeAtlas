import { lazy, Suspense, useMemo, useState } from 'react'
import { ArchitectureView } from './ArchitectureView'
import { AskView } from './AskView'
import { CouplingView } from './CouplingView'
import { FileDetails } from './FileDetails'
import { FindingsView } from './FindingsView'
import { HotspotsView } from './HotspotsView'
import { OverviewPanel } from './OverviewPanel'
import { OwnershipView } from './OwnershipView'
import { ReadingOrderList } from './ReadingOrderList'
import { formatDate } from './reportIndex'
import { indexReport } from './reportIndex'
import type { Report } from './types'

// Cytoscape is most of the JavaScript; load it only when the graph tab is opened.
const ImportGraphView = lazy(() => import('./ImportGraphView').then((m) => ({ default: m.ImportGraphView })))

const TABS = [
  { id: 'ask', label: 'Ask' },
  { id: 'reading', label: 'Reading order' },
  { id: 'findings', label: 'Findings' },
  { id: 'architecture', label: 'Architecture' },
  { id: 'graph', label: 'Graph' },
  { id: 'ownership', label: 'Ownership' },
  { id: 'coupling', label: 'Change coupling' },
  { id: 'hotspots', label: 'Hotspots' },
] as const

type Tab = (typeof TABS)[number]['id']

/** Renders a report. Knows nothing about where it came from (static demo file or live API). */
export function ReportView({ report, analysisId = null }: { report: Report; analysisId?: string | null }) {
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

      <Limitations report={report} />

      <div className="report-body">
        <div className="report-main">
          <div className="tabs" role="tablist">
            {TABS.map((t) => (
              <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
                {t.label}
              </button>
            ))}
          </div>
          {tab === 'ask' && <AskView report={report} analysisId={analysisId} onSelect={setSelected} />}
          {tab === 'reading' && (
            <ReadingOrderList items={report.readingOrder} selected={selected} onSelect={setSelected} />
          )}
          {tab === 'findings' && <FindingsView report={report} onSelect={setSelected} />}
          {tab === 'architecture' && <ArchitectureView report={report} onSelect={setSelected} />}
          {tab === 'graph' && (
            <Suspense fallback={<p className="muted">Loading graph…</p>}>
              <ImportGraphView report={report} index={index} selected={selected} onSelect={setSelected} />
            </Suspense>
          )}
          {tab === 'ownership' && <OwnershipView report={report} index={index} onSelect={setSelected} />}
          {tab === 'coupling' && <CouplingView report={report} index={index} onSelect={setSelected} />}
          {tab === 'hotspots' && <HotspotsView report={report} onSelect={setSelected} />}
        </div>
        <aside className="report-side card">
          <FileDetails report={report} index={index} path={selected} onSelect={setSelected} />
        </aside>
      </div>
    </div>
  )
}

function Limitations({ report }: { report: Report }) {
  const { parseErrors, skippedFiles, ignoredRevisions, historyTruncated, maxCommits } = report.limits
  const tooLarge = skippedFiles.filter((s) => s.reason === 'larger than 1 MB').length
  const overBlameCap = skippedFiles.filter((s) => s.reason === 'blame cap').length
  const blameFailed = skippedFiles.filter((s) => s.reason.startsWith('blame failed')).length
  const notes = [
    historyTruncated && `History was capped at the newest ${maxCommits.toLocaleString()} commits.`,
    parseErrors.length > 0 && `${parseErrors.length} file(s) had syntax errors and were parsed partially.`,
    tooLarge > 0 && `${tooLarge} file(s) over 1 MB were not parsed.`,
    overBlameCap > 0 && `${overBlameCap} file(s) were not blamed (cap reached; the most-changed files were blamed first).`,
    blameFailed > 0 && `Blame failed for ${blameFailed} file(s).`,
    (ignoredRevisions?.length ?? 0) > 0 &&
      `Blame skipped ${ignoredRevisions!.length} bulk-formatting commit(s) listed in .git-blame-ignore-revs.`,
  ].filter(Boolean)
  if (notes.length === 0) return null
  return <p className="muted small">{notes.join(' ')}</p>
}
