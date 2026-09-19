import type { Report } from '../report/types'

/**
 * Base URL of the backend API.
 * - Local dev and Docker Compose: empty, so requests go to the same origin and are proxied to the backend.
 * - Vercel: set VITE_API_BASE_URL to the Render backend URL, e.g. https://codeatlas-api.onrender.com
 */
export const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export interface BackendInfo {
  name: string
  version: string
  parsedLanguages: string[]
}

export type AnalysisState = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

export interface AnalysisStatus {
  id: string
  source: string
  status: AnalysisState
  stage: string | null
  percent: number
  detail: string | null
  error: string | null
  repoName: string | null
  commit: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

/** An error the backend explained (RFC 9457 problem detail), safe to show to the user as is. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, init)
  if (!res.ok) {
    let message = `The server responded with HTTP ${res.status}`
    try {
      const body = (await res.json()) as { detail?: string }
      if (body.detail) message = body.detail
    } catch {
      // Not a problem-detail body; keep the generic message.
    }
    throw new ApiError(res.status, message)
  }
  return (await res.json()) as T
}

export function fetchInfo(signal?: AbortSignal): Promise<BackendInfo> {
  return request('/api/info', { signal })
}

export function submitAnalysis(repo: string): Promise<AnalysisStatus> {
  return request('/api/analyses', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repo }),
  })
}

export function getAnalysis(id: string, signal?: AbortSignal): Promise<AnalysisStatus> {
  return request(`/api/analyses/${encodeURIComponent(id)}`, { signal })
}

export function getRecentAnalyses(signal?: AbortSignal): Promise<AnalysisStatus[]> {
  return request('/api/analyses?limit=10', { signal })
}

export function getReport(id: string, signal?: AbortSignal): Promise<Report> {
  return request(`/api/analyses/${encodeURIComponent(id)}/report`, { signal })
}
