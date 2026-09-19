// Mirrors docs/report-schema.md (v0.1). The UI reads only what is in the report, so the
// static demo (a JSON file) and the live API render identically.

export interface Report {
  schemaVersion: string
  repo: RepoInfo
  limits: Limits
  overview: Overview
  files: FileEntry[]
  components: Component[]
  edges: Edge[]
  readingOrder: ReadingItem[]
  people?: People
}

export interface RepoInfo {
  source: string
  name: string
  commit: string
  branch: string | null
  analyzedAt: string
  toolVersion: string
}

export interface Limits {
  maxCommits: number
  historyTruncated: boolean
  skippedFiles: { path: string; reason: string }[]
  parseErrors: { path: string; startLine: number }[]
}

export interface Overview {
  commits: number
  contributors: number
  firstCommitAt: string | null
  lastCommitAt: string | null
  files: number
  linesOfCode: number
  languages: { language: string; files: number; lines: number }[]
  testFileRatio: number | null
}

export interface FileEntry {
  path: string
  language: string | null
  lines: number
  componentId: string
  isTest: boolean
  symbols?: Symbol[]
  imports?: Import[]
  git?: { commits: number; authorCount: number; firstChangedAt: string; lastChangedAt: string }
}

export interface Symbol {
  kind: 'class' | 'function'
  name: string
  startLine: number
  endLine: number
  parent: string | null
}

export interface Import {
  text: string
  line: number
  module: string
  resolvedPath: string | null
  external: boolean
}

export interface Component {
  id: string
  path: string
  files: number
  lines: number
}

export interface Edge {
  source: string
  target: string
  kind: 'import' | 'cochange'
  weight: number
}

export interface ReadingItem {
  rank: number
  path: string
  score: number
  parts: Record<string, number>
  reasons: string[]
}

export interface People {
  authors: { id: string; name: string; emails: string[]; commits: number }[]
}

/** Link to a file (and optionally a line) on GitHub at the analyzed commit, or null for local repos. */
export function sourceUrl(repo: RepoInfo, path: string, line?: number): string | null {
  if (!repo.source.startsWith('https://github.com/')) return null
  return `${repo.source}/blob/${repo.commit}/${path}${line ? `#L${line}` : ''}`
}
