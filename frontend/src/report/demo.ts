import type { Report } from './types'

/** Entry in public/demo/index.json: a pre-computed report served as a static file. */
export interface DemoEntry {
  name: string
  title: string
  description: string
}

// Demo files live next to the app, so they work even when the backend is asleep or absent.
export async function loadDemoIndex(signal?: AbortSignal): Promise<DemoEntry[]> {
  const res = await fetch('/demo/index.json', { signal })
  if (!res.ok) return []
  return (await res.json()) as DemoEntry[]
}

export async function loadDemoReport(name: string, signal?: AbortSignal): Promise<Report> {
  if (!/^[a-z0-9-]+$/.test(name)) throw new Error('Unknown demo report')
  const res = await fetch(`/demo/${name}.json`, { signal })
  if (!res.ok) throw new Error(`Demo report "${name}" not found`)
  return (await res.json()) as Report
}
