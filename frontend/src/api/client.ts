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

export async function fetchInfo(signal?: AbortSignal): Promise<BackendInfo> {
  const res = await fetch(`${API_BASE_URL}/api/info`, { signal })
  if (!res.ok) {
    throw new Error(`Backend responded with HTTP ${res.status}`)
  }
  return (await res.json()) as BackendInfo
}
