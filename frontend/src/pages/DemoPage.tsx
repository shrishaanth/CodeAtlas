import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { loadDemoReport } from '../report/demo'
import { ReportView } from '../report/ReportView'
import type { Report } from '../report/types'

/** A pre-computed report loaded from a static file: no backend involved. */
export function DemoPage() {
  const { name = '' } = useParams()
  // Keyed by name so switching demos starts from a clean state.
  return <DemoReport key={name} name={name} />
}

function DemoReport({ name }: { name: string }) {
  const [report, setReport] = useState<Report | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    loadDemoReport(name, controller.signal)
      .then(setReport)
      .catch((err: unknown) => {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : String(err))
      })
    return () => controller.abort()
  }, [name])

  if (error) {
    return (
      <section className="card">
        <p className="status-error">{error}</p>
        <Link to="/">Back to start</Link>
      </section>
    )
  }
  if (!report) return <p className="muted">Loading report…</p>
  return <ReportView report={report} />
}
