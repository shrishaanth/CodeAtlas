import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, askQuestion, getQaStatus, type Answer, type AnswerSource } from '../api/client'
import type { Report } from './types'
import { sourceUrl } from './types'

interface Props {
  report: Report
  /** The analysis to question; demo reports have none, so the tab explains that instead. */
  analysisId: string | null
  onSelect: (path: string) => void
}

const EXAMPLES = [
  'Where is the command line entry point?',
  'How are configuration values loaded?',
  'What happens when a request comes in?',
]

export function AskView({ report, analysisId, onSelect }: Props) {
  const [question, setQuestion] = useState('')
  const [asking, setAsking] = useState(false)
  const [answer, setAnswer] = useState<Answer | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [model, setModel] = useState<string | null>(null)
  const [modelKnown, setModelKnown] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    getQaStatus(controller.signal)
      .then((s) => {
        setModel(s.modelConfigured ? s.model : null)
        setModelKnown(true)
      })
      .catch(() => setModelKnown(false))
    return () => controller.abort()
  }, [])

  if (!analysisId) {
    return (
      <p className="muted">
        This is a saved report, so there is no index to search. Analyze a repository from the start page to ask
        questions about it.
      </p>
    )
  }

  const ask = async (text: string) => {
    setAsking(true)
    setError(null)
    try {
      setAnswer(await askQuestion(analysisId, text))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reach the server.')
    } finally {
      setAsking(false)
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (question.trim()) void ask(question.trim())
  }

  return (
    <div className="stack">
      <form className="analyze-form" onSubmit={submit}>
        <label htmlFor="question" className="visually-hidden">
          Question about this repository
        </label>
        <input
          id="question"
          type="text"
          placeholder="Ask about this code, e.g. where is the CLI entry point?"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          disabled={asking}
          maxLength={500}
        />
        <button type="submit" disabled={asking || question.trim() === ''}>
          {asking ? 'Looking…' : 'Ask'}
        </button>
      </form>

      <p className="muted small">
        {modelKnown && model
          ? `Answers are written by ${model} from the code shown below, and every citation is checked against it. `
          : 'No language model is configured, so this finds the places in the code that match your question. '}
        Examples:{' '}
        {EXAMPLES.map((e, i) => (
          <span key={e}>
            {i > 0 && ' · '}
            <button
              type="button"
              className="link-button"
              onClick={() => {
                setQuestion(e)
                void ask(e)
              }}
            >
              {e}
            </button>
          </span>
        ))}
      </p>

      {error && <p className="status-error">{error}</p>}
      {answer && <AnswerPanel answer={answer} report={report} onSelect={onSelect} />}
    </div>
  )
}

function AnswerPanel({ answer, report, onSelect }: { answer: Answer; report: Report; onSelect: (p: string) => void }) {
  const exact = answer.citations.filter((c) => c.status === 'exact')
  const inside = answer.citations.filter((c) => c.status === 'inside')
  const unsupported = answer.citations.filter((c) => c.status === 'unsupported')
  return (
    <div className="stack">
      {answer.answer && (
        <section className="card">
          <p className="answer">{answer.answer}</p>
          <p className="muted small">
            Written by {answer.model} from the excerpts below. Citations checked:{' '}
            {exact.length > 0 && <span className="status-ok">{exact.length} match an excerpt exactly</span>}
            {exact.length > 0 && (inside.length > 0 || unsupported.length > 0) && ' · '}
            {inside.length > 0 && (
              <span className="status-waiting">
                {inside.length} fall inside an excerpt, so the excerpt is real but those exact line numbers are
                the model&apos;s own and may be off
              </span>
            )}
            {inside.length > 0 && unsupported.length > 0 && ' · '}
            {unsupported.length > 0 && (
              <span className="status-error">
                {unsupported.length} point outside the excerpts entirely; treat those claims as unsupported (
                {unsupported.map((c) => `${c.path}:${c.startLine}`).join(', ')})
              </span>
            )}
            {answer.citations.length === 0 && 'the answer cited nothing, so nothing could be checked'}
          </p>
        </section>
      )}
      {answer.note && <p className="muted">{answer.note}</p>}

      {answer.sources.length > 0 && (
        <section>
          <h3>Code found ({answer.sources.length})</h3>
          <ul className="plain sources">
            {answer.sources.map((s) => (
              <SourceBlock key={`${s.path}:${s.startLine}`} source={s} report={report} onSelect={onSelect} />
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function SourceBlock({
  source,
  report,
  onSelect,
}: {
  source: AnswerSource
  report: Report
  onSelect: (p: string) => void
}) {
  const url = sourceUrl(report.repo, source.path, source.startLine, source.endLine)
  return (
    <li className="source">
      <div className="source-head">
        <button type="button" className="link-button mono" onClick={() => onSelect(source.path)}>
          {source.path}
        </button>
        <span className="muted small">
          {url ? (
            <a href={url} target="_blank" rel="noreferrer">
              L{source.startLine}–{source.endLine}
            </a>
          ) : (
            `L${source.startLine}–${source.endLine}`
          )}
          {source.symbol && ` · ${source.kind} ${source.symbol}`}
        </span>
      </div>
      <pre className="code">
        <code>{source.text.length > 2000 ? `${source.text.slice(0, 2000)}\n…` : source.text}</code>
      </pre>
    </li>
  )
}
