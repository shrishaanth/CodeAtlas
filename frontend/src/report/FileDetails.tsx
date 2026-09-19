import type { Report } from './types'
import { sourceUrl } from './types'
import { ImportEdge } from './CouplingView'
import { Flags } from './OwnershipView'
import type { ReportIndex } from './reportIndex'
import { authorName, formatDate, formatPercent } from './reportIndex'

interface Props {
  report: Report
  index: ReportIndex
  path: string | null
  onSelect: (path: string) => void
}

export function FileDetails({ report, index, path, onSelect }: Props) {
  if (!path) {
    return <p className="muted">Select a file in the reading order or the graph to see its details.</p>
  }
  const file = index.files.get(path)
  if (!file) return <p className="muted">File not found in this report.</p>

  const reading = index.reading.get(path)
  // Reading-order fan-in counts non-test files only, so list tests separately to match it.
  const allImporters = index.importers.get(path) ?? []
  const importers = allImporters.filter((p) => !index.files.get(p)?.isTest)
  const testImporters = allImporters.filter((p) => index.files.get(p)?.isTest)
  const internal = [...new Set((file.imports ?? []).filter((i) => i.resolvedPath).map((i) => i.resolvedPath!))].sort()
  const external = [
    ...new Set((file.imports ?? []).filter((i) => i.external).map((i) => i.module.split('.')[0])),
  ].sort()
  const unresolved = (file.imports ?? []).filter((i) => !i.resolvedPath && !i.external)
  const url = sourceUrl(report.repo, path)
  const ownership = index.fileOwnership.get(path)
  const coupled = index.coupled.get(path) ?? []

  return (
    <div className="details">
      <h3 className="details-path">
        {url ? (
          <a href={url} target="_blank" rel="noreferrer">
            {path}
          </a>
        ) : (
          path
        )}
      </h3>
      <dl className="facts">
        <dt>Lines</dt>
        <dd>{file.lines}</dd>
        <dt>Commits</dt>
        <dd>{file.git?.commits ?? 0}</dd>
        <dt>Authors</dt>
        <dd>{file.git?.authorCount ?? 0}</dd>
        <dt>Last changed</dt>
        <dd>{formatDate(file.git?.lastChangedAt)}</dd>
        {reading && (
          <>
            <dt>Reading rank</dt>
            <dd>
              #{reading.rank} (score {reading.score.toFixed(2)})
            </dd>
          </>
        )}
        {file.isTest && (
          <>
            <dt>Kind</dt>
            <dd>Test file</dd>
          </>
        )}
      </dl>

      {ownership && (
        <section>
          <h4>
            Owners ({ownership.ownerCount}) · bus factor {ownership.busFactor}
          </h4>
          <ul className="plain">
            {ownership.owners.map((w) => (
              <li key={w.authorId}>
                {authorName(index, w.authorId)}{' '}
                <span className="muted">
                  {formatPercent(w.share)} ({w.lines} lines)
                </span>
              </li>
            ))}
            {ownership.otherLines > 0 && <li className="muted">others: {ownership.otherLines} lines</li>}
          </ul>
          {ownership.flags.length > 0 && (
            <p>
              <Flags o={ownership} />
            </p>
          )}
        </section>
      )}
      {coupled.length > 0 && (
        <section>
          <h4>Usually changes with ({coupled.length})</h4>
          <ul className="plain">
            {coupled.slice(0, 10).map((p) => {
              const other = p.a === path ? p.b : p.a
              return (
                <li key={other}>
                  <button type="button" className="link-button" onClick={() => onSelect(other)}>
                    {other}
                  </button>{' '}
                  <span className="muted">
                    {p.together} commits · <ImportEdge pair={p} />
                  </span>
                </li>
              )
            })}
          </ul>
        </section>
      )}

      <PathList title="Imported by" paths={importers} onSelect={onSelect} empty="No non-test file imports this one." />
      {testImporters.length > 0 && (
        <PathList title="Imported by tests" paths={testImporters} onSelect={onSelect} empty="" />
      )}
      <PathList title="Imports (in this repo)" paths={internal} onSelect={onSelect} empty="No imports of repository files." />
      {external.length > 0 && (
        <section>
          <h4>External packages ({external.length})</h4>
          <p className="chips">
            {external.map((m) => (
              <code key={m}>{m}</code>
            ))}
          </p>
        </section>
      )}
      {unresolved.length > 0 && (
        <section>
          <h4>Unresolved imports ({unresolved.length})</h4>
          <ul className="plain">
            {unresolved.map((i) => (
              <li key={`${i.line}-${i.module}`}>
                <code>{i.module}</code> <span className="muted">line {i.line}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {file.symbols && file.symbols.length > 0 && (
        <section>
          <h4>Definitions ({file.symbols.length})</h4>
          <ul className="plain symbols">
            {file.symbols.map((s) => {
              const link = sourceUrl(report.repo, path, s.startLine)
              const label = `${s.kind === 'class' ? 'class ' : ''}${s.name}`
              return (
                <li key={`${s.startLine}-${s.name}`} className={s.parent ? 'nested' : undefined}>
                  {link ? (
                    <a href={link} target="_blank" rel="noreferrer">
                      {label}
                    </a>
                  ) : (
                    label
                  )}{' '}
                  <span className="muted">
                    L{s.startLine}–{s.endLine}
                  </span>
                </li>
              )
            })}
          </ul>
        </section>
      )}
    </div>
  )
}

function PathList({
  title,
  paths,
  onSelect,
  empty,
}: {
  title: string
  paths: string[]
  onSelect: (path: string) => void
  empty: string
}) {
  return (
    <section>
      <h4>
        {title} ({paths.length})
      </h4>
      {paths.length === 0 ? (
        <p className="muted small">{empty}</p>
      ) : (
        <ul className="plain">
          {paths.map((p) => (
            <li key={p}>
              <button type="button" className="link-button" onClick={() => onSelect(p)}>
                {p}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
