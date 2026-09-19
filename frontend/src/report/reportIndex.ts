import type { FileEntry, ReadingItem, Report } from './types'

/** Lookups the views need repeatedly, built once per report. */
export interface ReportIndex {
  files: Map<string, FileEntry>
  importers: Map<string, string[]>
  reading: Map<string, ReadingItem>
}

export function indexReport(report: Report): ReportIndex {
  const files = new Map(report.files.map((f) => [f.path, f]))
  const importers = new Map<string, string[]>()
  for (const e of report.edges) {
    if (e.kind !== 'import') continue
    const list = importers.get(e.target)
    if (list) list.push(e.source)
    else importers.set(e.target, [e.source])
  }
  for (const list of importers.values()) list.sort()
  const reading = new Map(report.readingOrder.map((r) => [r.path, r]))
  return { files, importers, reading }
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return 'n/a'
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

export function formatNumber(n: number): string {
  return n.toLocaleString()
}
