import cytoscape, { type Core, type ElementDefinition } from 'cytoscape'
import fcose from 'cytoscape-fcose'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { Report } from './types'
import type { ReportIndex } from './reportIndex'

// Force-directed layout of thousands of nodes is slow and unreadable, so large repos show
// the highest-ranked files. The UI says how many are hidden.
const MAX_NODES = 150

// The built-in "cose" layout spread graphs with directory groups and disconnected parts over
// tens of thousands of pixels (seen on pallets/click); fcose handles both.
cytoscape.use(fcose)

interface Props {
  report: Report
  index: ReportIndex
  selected: string | null
  onSelect: (path: string) => void
}

export function ImportGraphView({ report, index, selected, onSelect }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const cy = useRef<Core | null>(null)
  // The graph is built once per element set; read the latest callback through a ref
  // instead of rebuilding (and re-laying-out) the graph whenever the parent re-renders.
  const onSelectRef = useRef(onSelect)
  const [includeTests, setIncludeTests] = useState(false)
  const [showCochange, setShowCochange] = useState(false)
  const hasCochange = report.edges.some((e) => e.kind === 'cochange')

  useEffect(() => {
    onSelectRef.current = onSelect
  }, [onSelect])

  const { elements, shown, total } = useMemo(
    () => buildElements(report, index, includeTests, showCochange),
    [report, index, includeTests, showCochange],
  )

  useEffect(() => {
    if (!container.current) return
    const css = getComputedStyle(document.documentElement)
    const color = (name: string) => css.getPropertyValue(name).trim()
    const instance = cytoscape({
      container: container.current,
      elements,
      minZoom: 0.05,
      maxZoom: 3,
      style: [
        {
          selector: 'node[?isFile]',
          style: {
            width: 'data(size)',
            height: 'data(size)',
            'background-color': color('--node'),
            label: 'data(label)',
            color: color('--text'),
            'font-size': 10,
            'text-valign': 'bottom',
            'text-margin-y': 3,
            'min-zoomed-font-size': 8,
          },
        },
        { selector: 'node[?isTest]', style: { 'background-color': color('--node-test') } },
        {
          selector: 'node[!isFile]',
          style: {
            'background-color': color('--group'),
            'background-opacity': 0.5,
            'border-color': color('--border'),
            'border-width': 1,
            label: 'data(label)',
            color: color('--muted'),
            'font-size': 11,
            'text-valign': 'top',
            'text-halign': 'center',
            shape: 'round-rectangle',
          },
        },
        {
          selector: 'edge',
          style: {
            width: 1,
            'line-color': color('--edge'),
            'target-arrow-color': color('--edge'),
            'target-arrow-shape': 'triangle',
            'arrow-scale': 0.7,
            'curve-style': 'bezier',
          },
        },
        {
          selector: 'edge[kind = "cochange"]',
          style: {
            'line-style': 'dashed',
            'line-color': color('--cochange'),
            'target-arrow-shape': 'none',
            width: 'data(width)',
          },
        },
        {
          selector: 'node.selected',
          style: { 'background-color': color('--accent'), 'border-width': 3, 'border-color': color('--accent') },
        },
        { selector: 'edge.out', style: { 'line-color': color('--accent'), 'target-arrow-color': color('--accent'), width: 2 } },
        { selector: 'edge.in', style: { 'line-color': color('--warn'), 'target-arrow-color': color('--warn'), width: 2 } },
        { selector: 'edge.co', style: { 'line-color': color('--cochange'), width: 3 } },
      ],
      layout: {
        name: 'fcose',
        animate: false,
        randomize: true,
        quality: 'default',
        nodeRepulsion: () => 6000,
        idealEdgeLength: () => 70,
        nestingFactor: 0.5,
        packComponents: true,
        padding: 20,
      } as cytoscape.LayoutOptions,
    })
    instance.on('tap', 'node[?isFile]', (evt) => onSelectRef.current(evt.target.id()))
    cy.current = instance
    return () => {
      instance.destroy()
      cy.current = null
    }
  }, [elements])

  useEffect(() => {
    const instance = cy.current
    if (!instance) return
    instance.elements().removeClass('selected out in co')
    if (!selected) return
    const node = instance.getElementById(selected)
    if (node.empty()) return
    node.addClass('selected')
    node.outgoers('edge[kind = "import"]').addClass('out')
    node.incomers('edge[kind = "import"]').addClass('in')
    node.connectedEdges('edge[kind = "cochange"]').addClass('co')
  }, [selected, elements])

  return (
    <div>
      <div className="graph-toolbar">
        <span className="muted small">
          Showing {shown} of {total} Python files{shown < total ? ', highest ranked first' : ''}. Arrows point from
          importer to imported. <span className="legend-out">Blue</span>: what the selected file imports.{' '}
          <span className="legend-in">Amber</span>: files that import it.
        </span>
        <span className="filters small">
          <label>
            <input type="checkbox" checked={includeTests} onChange={(e) => setIncludeTests(e.target.checked)} /> Tests
          </label>
          {hasCochange && (
            <label title="Dashed lines join files that often change in the same commits">
              <input type="checkbox" checked={showCochange} onChange={(e) => setShowCochange(e.target.checked)} />{' '}
              <span className="legend-co">Co-change</span>
            </label>
          )}
        </span>
      </div>
      <div ref={container} className="graph" role="img" aria-label="Import graph of the repository" />
    </div>
  )
}

function buildElements(report: Report, index: ReportIndex, includeTests: boolean, showCochange: boolean) {
  const python = report.files.filter((f) => f.language === 'python' && (includeTests || !f.isTest))
  // Ranked files first (by score), then unranked ones (tests, tiny files) by how many files import them.
  const score = (path: string) => {
    const ranked = index.reading.get(path)
    if (ranked) return ranked.score
    return -1 + (index.importers.get(path)?.length ?? 0) / 1e6
  }
  const chosen = [...python].sort((a, b) => score(b.path) - score(a.path)).slice(0, MAX_NODES)
  const keep = new Set(chosen.map((f) => f.path))

  const elements: ElementDefinition[] = []
  const groups = new Set<string>()
  for (const f of chosen) {
    groups.add(f.componentId)
    const s = index.reading.get(f.path)?.score ?? 0
    elements.push({
      data: {
        id: f.path,
        parent: `dir:${f.componentId}`,
        label: f.path.slice(f.path.lastIndexOf('/') + 1),
        isFile: true,
        isTest: f.isTest,
        size: 14 + 30 * s,
      },
    })
  }
  for (const g of groups) {
    elements.push({ data: { id: `dir:${g}`, label: g === '.' ? '(root)' : g, isFile: false } })
  }
  for (const e of report.edges) {
    if (!keep.has(e.source) || !keep.has(e.target)) continue
    if (e.kind === 'import') {
      elements.push({ data: { id: `${e.source}->${e.target}`, source: e.source, target: e.target, kind: 'import' } })
    } else if (e.kind === 'cochange' && showCochange) {
      const width = Math.min(4, 1 + Math.log2(e.weight) / 2)
      elements.push({
        data: { id: `${e.source}~${e.target}`, source: e.source, target: e.target, kind: 'cochange', width },
      })
    }
  }
  return { elements, shown: chosen.length, total: python.length }
}
