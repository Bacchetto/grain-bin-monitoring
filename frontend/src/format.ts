// Small display helpers, kept apart from components so they can be tested
// without rendering anything.

import type { AlertType } from './api/types'

/** How long ago an ISO instant was, relative to `now` (milliseconds since 1970). */
export function formatAge(isoInstant: string | null, now: number): string {
  if (isoInstant === null) return 'never'
  const seconds = Math.max(0, Math.round((now - Date.parse(isoInstant)) / 1000))
  if (seconds < 10) return 'just now'
  if (seconds < 60) return `${seconds} s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return `${hours} h ago`
  return `${Math.floor(hours / 24)} d ago`
}

/**
 * How each alert type is shown, most serious first -- the same order the
 * backend uses to pick a bin's "worst" alert (BinRepository.findAllWithStatus).
 *
 * `Record<AlertType, ...>` makes the compiler insist on an entry for every
 * alert type: adding a fifth type to AlertType without adding it here is a
 * compile error, not a blank badge.
 */
export const ALERT_LABELS: Record<AlertType, { label: string; severity: 'critical' | 'warning' | 'offline' }> = {
  HIGH_TEMPERATURE: { label: 'High temperature', severity: 'critical' },
  RATE_OF_RISE: { label: 'Temperature rising', severity: 'critical' },
  HIGH_MOISTURE: { label: 'High moisture', severity: 'warning' },
  DEVICE_OFFLINE: { label: 'Device offline', severity: 'offline' },
}

/**
 * A sensor's position, for people. The API numbers cables and depths from 0;
 * the dashboard counts from 1, the way a farmer would, and names depth 1 as
 * the top of the cable. Every screen uses this one function, so a position
 * reads the same in the grid, the chart title and the alerts list.
 */
export function sensorName(cable: number, depth: number): string {
  return `Cable ${cable + 1}, depth ${depth + 1}${depth === 0 ? ' (top)' : ''}`
}

/** One decimal place, as the backend stores readings. */
export function formatNumber(value: number): string {
  return value.toFixed(1)
}
