import { useMemo } from 'react'
import type { Report } from './types'
import { formatNumber } from './reportIndex'

interface Props {
  report: Report
  onSelect: (path: string) => void
}

const METRICS_URL = 'https://github.com/shrishaanth/CodeAtlas/blob/main/docs/metrics.md#layers-and-import-cycles'

/** Directories grouped by layer: foundations at the bottom, code built on them above. */
export function ArchitectureView({ report, onSelect }: Props) {
  const { layers, inCycle, readingTop } = useMemo(() => {
    const byLayer = new Map<number, Report['components']>()
    for (const c of report.components) {
      if (c.layer === null || c.layer === undefined) continue
      const list = byLayer.get(c.layer)
      if (list) list.push(c)
      else byLayer.set(c.layer, [c])
    }
    const dirOf = new Map(report.files.map((f) => [f.path, f.componentId]))
    const cycleDirs = new Set<string>()
    for (const f of report.findings ?? []) {
      if (f.kind !== 'import-cycle') continue
      f.evidence.forEach((e) => {
        const dir = e.path ? dirOf.get(e.path) : undefined
        if (dir) cycleDirs.add(dir)
      })
    }
    // The highest-ranked file in each directory, as a place to start reading it.
    const top = new Map<string, string>()
    for (const item of report.readingOrder) {
      const dir = dirOf.get(item.path)
      if (dir && !top.has(dir)) top.set(dir, item.path)
    }
    return {
      layers: [...byLayer.entries()].sort((a, b) => b[0] - a[0]),
      inCycle: cycleDirs,
      readingTop: top,
    }
  }, [report])

  if (layers.length === 0) {
    return <p className="muted">No layer data: this report has no Python directories, or predates layers.</p>
  }

  return (
    <div className="stack">
      <p className="muted small">
        Directories stacked by import direction. Layer 0 imports no other directory: the foundations. Each layer
        above builds on the ones below. Directories in an import cycle depend on each other and share a layer.
        Imports made by <code>__init__.py</code> files are ignored here. <a href={METRICS_URL}>Definitions</a>
      </p>
      {layers.map(([layer, comps]) => (
        <section key={layer} className="layer">
          <h3>
            Layer {layer}
            {layer === 0 ? ' · foundations' : ''}
          </h3>
          <div className="layer-row">
            {comps
              .sort((a, b) => b.lines - a.lines)
              .map((c) => {
                const start = readingTop.get(c.id)
                return (
                  <div key={c.id} className={inCycle.has(c.id) ? 'layer-box in-cycle' : 'layer-box'}>
                    <div className="mono">{c.id === '.' ? '(root)' : c.id}</div>
                    <div className="muted small">
                      {c.files} files · {formatNumber(c.lines)} lines
                      {inCycle.has(c.id) && <span className="pill pill-warn">cycle</span>}
                    </div>
                    {start && (
                      <button type="button" className="link-button small" onClick={() => onSelect(start)}>
                        start with {start.slice(start.lastIndexOf('/') + 1)}
                      </button>
                    )}
                  </div>
                )
              })}
          </div>
        </section>
      ))}
    </div>
  )
}
