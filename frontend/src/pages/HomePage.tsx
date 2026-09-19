import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, getRecentAnalyses, submitAnalysis, type AnalysisStatus } from '../api/client'
import { BackendStatus } from '../components/BackendStatus'
import { loadDemoIndex, type DemoEntry } from '../report/demo'
import { formatDate } from '../report/reportIndex'

export function HomePage() {
  const navigate = useNavigate()
  const [repo, setRepo] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [demos, setDemos] = useState<DemoEntry[]>([])
  const [recent, setRecent] = useState<AnalysisStatus[]>([])

  useEffect(() => {
    const controller = new AbortController()
    loadDemoIndex(controller.signal).then(setDemos).catch(() => setDemos([]))
    // The backend may be asleep or absent (static demo hosting); recent analyses are optional.
    getRecentAnalyses(controller.signal).then(setRecent).catch(() => setRecent([]))
    return () => controller.abort()
  }, [])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const status = await submitAnalysis(repo.trim())
      navigate(`/analyses/${status.id}`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reach the analysis server. Try a demo report below.')
      setSubmitting(false)
    }
  }

  return (
    <div className="home">
      <section className="card">
        <h2>Analyze a repository</h2>
        <form className="analyze-form" onSubmit={submit}>
          <label htmlFor="repo" className="visually-hidden">
            GitHub repository URL
          </label>
          <input
            id="repo"
            type="url"
            required
            placeholder="https://github.com/owner/repo"
            value={repo}
            onChange={(e) => setRepo(e.target.value)}
            disabled={submitting}
          />
          <button type="submit" disabled={submitting || repo.trim() === ''}>
            {submitting ? 'Queuing…' : 'Analyze'}
          </button>
        </form>
        {error && <p className="status-error">{error}</p>}
        <p className="muted small">
          Public GitHub repositories only. Python files get the full import analysis; git history works for any
          language.
        </p>
        <BackendStatus />
      </section>

      {demos.length > 0 && (
        <section>
          <h2>Example reports</h2>
          <p className="muted small">Pre-computed, so they open instantly even when the analysis server is asleep.</p>
          <ul className="cards">
            {demos.map((d) => (
              <li key={d.name}>
                <Link className="card card-link" to={`/demo/${d.name}`}>
                  <strong>{d.title}</strong>
                  <span className="muted small">{d.description}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {recent.length > 0 && (
        <section>
          <h2>Recently analyzed</h2>
          <ul className="plain recent">
            {recent.map((a) => (
              <li key={a.id}>
                <Link to={`/analyses/${a.id}`}>{a.repoName ?? a.source}</Link>{' '}
                <span className="muted small">
                  {a.source.replace('https://', '')} · {formatDate(a.finishedAt)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}
