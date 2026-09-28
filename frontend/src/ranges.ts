import type { Bucket } from './api/types'

/**
 * The chart ranges the README asks for, and the bucket each is fetched in.
 * Hourly for a day or a week (24 and 168 points); daily for a month, where
 * 720 hourly points would be more than a chart this size can show.
 */
export const RANGES = {
  '24h': { label: '24 hours', hours: 24, bucket: 'hour', ticks: 'time' },
  '7d': { label: '7 days', hours: 7 * 24, bucket: 'hour', ticks: 'date' },
  '30d': { label: '30 days', hours: 30 * 24, bucket: 'day', ticks: 'date' },
} as const satisfies Record<string, { label: string; hours: number; bucket: Bucket; ticks: 'time' | 'date' }>

// `keyof typeof RANGES` is the union '24h' | '7d' | '30d', derived from the
// object above so the two can never disagree.
export type RangeKey = keyof typeof RANGES
