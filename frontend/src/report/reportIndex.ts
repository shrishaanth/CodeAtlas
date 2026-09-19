import type { Author, FileEntry, FilePair, Ownership, ReadingItem, Report } from './types'

/** Lookups the views need repeatedly, built once per report. */
export interface ReportIndex {
  files: Map<string, FileEntry>
  importers: Map<string, string[]>
  reading: Map<string, ReadingItem>
  authors: Map<string, Author>
  fileOwnership: Map<string, Ownership>
  /** For each file, the coupled pairs it takes part in, strongest first. */
  coupled: Map<string, FilePair[]>
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
  const authors = new Map((report.people?.authors ?? []).map((a) => [a.id, a]))
  const fileOwnership = new Map((report.people?.fileOwnership ?? []).map((o) => [o.path, o]))
  const coupled = new Map<string, FilePair[]>()
  for (const p of report.coupling?.files ?? []) {
    for (const path of [p.a, p.b]) {
      const list = coupled.get(path)
      if (list) list.push(p)
      else coupled.set(path, [p])
    }
  }
  return { files, importers, reading, authors, fileOwnership, coupled }
}

export function authorName(index: ReportIndex, id: string): string {
  return index.authors.get(id)?.name ?? id
}

/** "Jane Doe 62%, Bob 20%": the top owners of a file or directory. */
export function ownersText(o: Ownership, index: ReportIndex, limit = 3): string {
  return o.owners
    .slice(0, limit)
    .map((w) => `${authorName(index, w.authorId)} ${formatPercent(w.share)}`)
    .join(', ')
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return 'n/a'
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

export function formatNumber(n: number): string {
  return n.toLocaleString()
}

export function formatPercent(share: number): string {
  return `${Math.round(share * 100)}%`
}
