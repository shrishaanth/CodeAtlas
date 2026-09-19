import { useEffect, useState } from 'react'
import { fetchInfo, type BackendInfo } from '../api/client'

type Status =
  | { kind: 'connecting'; slow: boolean }
  | { kind: 'ok'; info: BackendInfo }
  | { kind: 'error'; message: string }

// A free-tier backend that has gone to sleep can take close to a minute to answer.
const SLOW_AFTER_MS = 5_000
const GIVE_UP_AFTER_MS = 90_000

export function BackendStatus() {
  const [status, setStatus] = useState<Status>({ kind: 'connecting', slow: false })

  useEffect(() => {
    const controller = new AbortController()
    let unmounted = false
    let timedOut = false
    const slowTimer = setTimeout(() => setStatus({ kind: 'connecting', slow: true }), SLOW_AFTER_MS)
    const giveUpTimer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, GIVE_UP_AFTER_MS)

    fetchInfo(controller.signal)
      .then((info) => setStatus({ kind: 'ok', info }))
      .catch((err: unknown) => {
        if (unmounted) return
        const message = timedOut
          ? `no response after ${GIVE_UP_AFTER_MS / 1000} seconds`
          : err instanceof Error
            ? err.message
            : String(err)
        setStatus({ kind: 'error', message })
      })
      .finally(() => {
        clearTimeout(slowTimer)
        clearTimeout(giveUpTimer)
      })

    return () => {
      unmounted = true
      controller.abort()
      clearTimeout(slowTimer)
      clearTimeout(giveUpTimer)
    }
  }, [])

  switch (status.kind) {
    case 'connecting':
      return (
        <p className="status-waiting">
          {status.slow
            ? 'Waking up the analysis server. On the free hosting tier this can take up to a minute…'
            : 'Connecting to the analysis server…'}
        </p>
      )
    case 'ok':
      return (
        <p className="status-ok">
          Connected to {status.info.name} backend <code>{status.info.version}</code>. Parses:{' '}
          {status.info.parsedLanguages.join(', ')}.
        </p>
      )
    case 'error':
      return <p className="status-error">Could not reach the analysis server: {status.message}</p>
  }
}
