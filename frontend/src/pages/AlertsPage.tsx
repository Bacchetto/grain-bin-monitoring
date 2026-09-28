import { useQuery } from '@tanstack/react-query'
import { useApi } from '../auth/authContext'
import { AlertTable } from '../components/AlertTable'
import { useNow } from '../useNow'

export const ALERTS_REFRESH_MS = 30_000

/** Every open and acknowledged alert, across all bins, newest first. */
export function AlertsPage() {
  const api = useApi()
  const now = useNow()

  const { data: alerts, error, isPending } = useQuery({
    // Under the ['alerts'] prefix, so acknowledging refreshes it.
    queryKey: ['alerts', 'all'],
    queryFn: () => api.listAlerts(['OPEN', 'ACKNOWLEDGED']),
    refetchInterval: ALERTS_REFRESH_MS,
  })

  return (
    <>
      <h1>Alerts</h1>
      {isPending ? (
        <p>Loading alerts…</p>
      ) : error ? (
        <p role="alert" className="error">
          Could not load alerts: {error.message}
        </p>
      ) : (
        <AlertTable alerts={alerts} now={now} showBin />
      )}
    </>
  )
}
