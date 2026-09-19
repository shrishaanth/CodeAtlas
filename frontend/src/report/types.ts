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
  coupling?: Coupling
  hotspots?: Hotspot[]
  findings?: Finding[]
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
  ignoredRevisions?: string[]
  findingsOmitted?: Record<string, number>
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
  isGenerated?: boolean
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
  /** 0 = imports no other directory; null without Python files */
  layer?: number | null
}

export type FindingKind =
  | 'duplicate-module'
  | 'repeated-logic'
  | 'import-cycle'
  | 'generated-file-committed'
  | 'missing-tests'
  | 'unreferenced-file'

export interface Finding {
  id: string
  kind: FindingKind
  severity: 'warn' | 'info'
  title: string
  detail: string
  evidence: Evidence[]
}

export interface Evidence {
  path?: string
  startLine?: number
  endLine?: number
  note?: string
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
  authors: Author[]
  fileOwnership?: Ownership[]
  directoryOwnership?: Ownership[]
}

export interface Author {
  id: string
  name: string
  emails: string[]
  commits: number
  isBot?: boolean
  lastCommitAt?: string
}

export type OwnershipFlag = 'single-owner' | 'orphaned'

export interface Ownership {
  path: string
  totalLines: number
  owners: { authorId: string; lines: number; share: number }[]
  otherLines: number
  ownerCount: number
  busFactor: number
  topOwnerActive: boolean
  flags: OwnershipFlag[]
}

export interface Coupling {
  files: FilePair[]
  components: AreaPair[]
  skippedLargeCommits: number
}

export interface FilePair {
  a: string
  b: string
  together: number
  aCommits: number
  bCommits: number
  degree: number
  /** null: unknown, because at least one file's imports are not parsed */
  hasImportEdge: boolean | null
}

export interface AreaPair {
  a: string
  b: string
  together: number
  aCommits: number
  bCommits: number
  degree: number
}

export interface Hotspot {
  rank: number
  path: string
  score: number
  commits: number
  lines: number
  complexity: number
}

// Mirrors FileClassifier.isCode in the backend.
const NON_CODE = new Set(['markdown', 'restructuredtext', 'json', 'yaml', 'toml', 'xml', 'jupyter'])

export function isCodeLanguage(language: string | null | undefined): boolean {
  return !!language && !NON_CODE.has(language)
}

/** Link to a file (optionally a line or line range) on GitHub at the analyzed commit, or null for local repos. */
export function sourceUrl(repo: RepoInfo, path: string, line?: number, endLine?: number): string | null {
  if (!repo.source.startsWith('https://github.com/')) return null
  const anchor = line ? (endLine && endLine !== line ? `#L${line}-L${endLine}` : `#L${line}`) : ''
  return `${repo.source}/blob/${repo.commit}/${path}${anchor}`
}
