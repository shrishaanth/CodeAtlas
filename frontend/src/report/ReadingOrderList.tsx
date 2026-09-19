import { useState } from 'react'
import type { ReadingItem } from './types'

const PAGE = 25

const PART_LABELS: Record<string, string> = {
  centrality: 'Centrality',
  fanIn: 'Fan-in',
  churn: 'Churn',
}

interface Props {
  items: ReadingItem[]
  selected: string | null
  onSelect: (path: string) => void
}

export function ReadingOrderList({ items, selected, onSelect }: Props) {
  const [limit, setLimit] = useState(PAGE)

  if (items.length === 0) {
    return <p className="muted">No Python source files to rank.</p>
  }

  return (
    <div>
      <p className="muted small">
        Files ranked by how much other code depends on them and how often they change. Each part is scaled
        to 0–1; the score weights them 0.5 / 0.3 / 0.2. <a href="https://github.com/shrishaanth/CodeAtlas/blob/main/docs/metrics.md#reading-order-v1">How this is computed</a>
      </p>
      <ol className="reading-list">
        {items.slice(0, limit).map((item) => (
          <li key={item.path}>
            <button
              type="button"
              className={item.path === selected ? 'reading-item selected' : 'reading-item'}
              onClick={() => onSelect(item.path)}
            >
              <span className="rank">{item.rank}</span>
              <span className="reading-main">
                <span className="path">{item.path}</span>
                <span className="reasons">{item.reasons.join(' · ')}</span>
              </span>
              <span className="parts" aria-label="Score components">
                {Object.entries(item.parts).map(([name, value]) => (
                  <span key={name} className="part" title={`${PART_LABELS[name] ?? name}: ${value.toFixed(2)}`}>
                    <span className="part-label">{PART_LABELS[name] ?? name}</span>
                    <span className="part-track">
                      <span className="part-fill" style={{ width: `${value * 100}%` }} />
                    </span>
                  </span>
                ))}
              </span>
              <span className="score">{item.score.toFixed(2)}</span>
            </button>
          </li>
        ))}
      </ol>
      {limit < items.length && (
        <button type="button" className="link-button" onClick={() => setLimit(limit + PAGE)}>
          Show {Math.min(PAGE, items.length - limit)} more of {items.length - limit} remaining
        </button>
      )}
    </div>
  )
}
