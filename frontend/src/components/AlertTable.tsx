import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../api/client'
import type { Alert } from '../api/types'
import { useApi } from '../auth/authContext'
import { ALERT_LABELS, formatAge, formatNumber, sensorName } from '../format'
import { StatusBadge } from './StatusBadge'

interface Props {
  alerts: Alert[]
  now: number
  /** Whether to show which bin each alert is on; not needed within one bin's page. */
  showBin: boolean
}

/**
 * A list of alerts, each with an Acknowledge button while it is open.
 *
 * Acknowledging tells the dashboard -- and anyone else looking -- that a
 * person has seen the alert. It does not resolve it: the alert stays until
 * the engine sees the condition clear three times.
 */
export function AlertTable({ alerts, now, showBin }: Props) {
  const acknowledge = useAcknowledge()

  // The error is shown whether or not any alerts remain: a 409 means the
  // alert resolved, and the refreshed list may well be empty -- which is
  // exactly when the user most needs to be told why their click did nothing.
  const error = acknowledge.error && (
    <p role="alert" className="error">
      {acknowledge.error}
    </p>
  )

  if (alerts.length === 0) {
    return (
      <>
        {error}
        <p>No open alerts.</p>
      </>
    )
  }

  return (
    <>
      {error}
      <table className="alerts">
        <thead>
          <tr>
            <th>Alert</th>
            {showBin && <th>Bin</th>}
            <th>Where</th>
            <th>Reading</th>
            <th>First seen</th>
            <th>Last seen</th>
            <th>
              <span className="visually-hidden">Action</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {alerts.map((alert) => (
            <tr key={alert.id}>
              <td>
                <StatusBadge worst={alert.type} count={1} />
              </td>
              {showBin && (
                <td>
                  <Link to={`/bins/${alert.binId}`}>{alert.binName}</Link>
                </td>
              )}
              <td>{where(alert)}</td>
              <td>{reading(alert, now)}</td>
              <td title={alert.firstDetectedAt}>{formatAge(alert.firstDetectedAt, now)}</td>
              <td title={alert.lastDetectedAt}>{formatAge(alert.lastDetectedAt, now)}</td>
              <td>
                {alert.status === 'OPEN' ? (
                  <button
                    type="button"
                    onClick={() => acknowledge.run(alert.id)}
                    disabled={acknowledge.pendingId === alert.id}
                    aria-label={`Acknowledge ${ALERT_LABELS[alert.type].label.toLowerCase()} on ${alert.binName}, ${where(alert)}`}
                  >
                    {acknowledge.pendingId === alert.id ? 'Acknowledging…' : 'Acknowledge'}
                  </button>
                ) : (
                  <span className="hint" title={alert.acknowledgedAt ?? undefined}>
                    Acknowledged {formatAge(alert.acknowledgedAt, now)}
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}

function where(alert: Alert): string {
  if (alert.cableIndex !== null && alert.depthIndex !== null) {
    return sensorName(alert.cableIndex, alert.depthIndex)
  }
  return alert.deviceId === null ? '—' : `Device ${alert.deviceId}`
}

/** What tripped the alert, against its limit, in words. */
function reading(alert: Alert, now: number): string {
  const { triggerValue: value, thresholdValue: limit } = alert
  switch (alert.type) {
    case 'DEVICE_OFFLINE':
      return alert.deviceLastSeenAt === null
        ? 'Never reported'
        : `Last heard from ${formatAge(alert.deviceLastSeenAt, now)}`
    case 'HIGH_TEMPERATURE':
      return value === null || limit === null ? '—' : `${formatNumber(value)} °C (limit ${formatNumber(limit)})`
    case 'HIGH_MOISTURE':
      return value === null || limit === null ? '—' : `${formatNumber(value)} % (limit ${formatNumber(limit)})`
    case 'RATE_OF_RISE':
      return value === null || limit === null ? '—' : `Up ${formatNumber(value)} °C (limit ${formatNumber(limit)})`
  }
}

/**
 * The acknowledge action, shared by every alert list.
 *
 * On success or failure alike, it tells the query cache that alerts and the
 * bin list are out of date. Any list of alerts on screen refetches at once,
 * so the button turns into "Acknowledged"; a cached bin list is marked stale
 * and refetched when next shown -- without this component knowing which
 * screens exist.
 */
function useAcknowledge() {
  const api = useApi()
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (alertId: number) => api.acknowledgeAlert(alertId),
    onMutate: () => setError(null),
    onError: (e) =>
      setError(
        // 409: the condition cleared between this list loading and the click.
        e instanceof ApiError && e.status === 409
          ? 'That alert resolved before it could be acknowledged. The list has been refreshed.'
          : `Could not acknowledge the alert: ${e.message}`,
      ),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['alerts'] }),
        queryClient.invalidateQueries({ queryKey: ['bins'] }),
      ]),
  })

  return {
    run: (alertId: number) => mutation.mutate(alertId),
    pendingId: mutation.isPending ? mutation.variables : null,
    error,
  }
}
