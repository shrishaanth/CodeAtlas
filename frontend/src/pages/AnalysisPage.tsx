import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, getAnalysis, getReport, type AnalysisStatus } from '../api/client'
import { ReportView } from '../report/ReportView'
import type { Report } from '../report/types'

const POLL_MS = 1500

/** Polls an analysis until it finishes, then shows its report. */
export function AnalysisPage() {
  const { id = '' } = useParams()
  const [status, setStatus] = useState<AnalysisStatus | null>(null)
  const [report, setReport] = useState<Report | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined

    const poll = async () => {
      try {
        const s = await getAnalysis(id, controller.signal)
        setStatus(s)
        if (s.status === 'DONE') {
          setReport(await getReport(id, controller.signal))
        } else if (s.status !== 'FAILED') {
          timer = setTimeout(poll, POLL_MS)
        }
      } catch (err) {
        if (controller.signal.aborted) return
        setError(
          err instanceof ApiError && err.status === 404
            ? 'No analysis with this id.'
            : err instanceof Error
              ? err.message
              : String(err),
        )
      }
    }
    void poll()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [id])

  if (error) return <Message kind="error" text={error} />
  if (report) return <ReportView report={report} />
  if (!status) return <p className="muted">Loading…</p>
  if (status.status === 'FAILED') {
    return <Message kind="error" text={`The analysis failed: ${status.error ?? 'unknown error'}`} />
  }

  return (
    <section className="card progress-card" aria-live="polite">
      <h2>Analyzing {status.source.replace('https://github.com/', '')}</h2>
      <div className="progress" role="progressbar" aria-valuenow={status.percent} aria-valuemin={0} aria-valuemax={100}>
        <div className="progress-fill" style={{ width: `${status.percent}%` }} />
      </div>
      <p>
        {status.status === 'QUEUED' ? 'Waiting for other analyses to finish…' : (status.detail ?? 'Working…')}
      </p>
      <p className="muted small">
        Large repositories can take a few minutes. You can leave this page and come back with the same link.
      </p>
    </section>
  )
}

function Message({ kind, text }: { kind: 'error'; text: string }) {
  return (
    <section className="card">
      <p className={`status-${kind}`}>{text}</p>
      <Link to="/">Back to start</Link>
    </section>
  )
}
