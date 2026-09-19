import type { FindingKind } from './types'

export const KIND_LABELS: Record<FindingKind, string> = {
  'duplicate-module': 'Duplicate modules',
  'repeated-logic': 'Repeated logic',
  'import-cycle': 'Import cycles',
  'generated-file-committed': 'Generated or local files committed',
  'missing-tests': 'Areas without tests',
  'unreferenced-file': 'No static import found',
}

export const KIND_ORDER = Object.keys(KIND_LABELS) as FindingKind[]
