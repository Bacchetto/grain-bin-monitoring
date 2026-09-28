import { describe, expect, it } from 'vitest'
import { formatAge } from './format'

const NOW = Date.parse('2026-09-28T12:00:00Z')
const ago = (seconds: number) => new Date(NOW - seconds * 1000).toISOString()

describe('formatAge', () => {
  it.each([
    [null, 'never'],
    [ago(3), 'just now'],
    [ago(45), '45 s ago'],
    [ago(60), '1 min ago'],
    [ago(59 * 60), '59 min ago'],
    [ago(2 * 3600), '2 h ago'],
    [ago(47 * 3600), '47 h ago'],
    [ago(3 * 86400), '3 d ago'],
  ])('%s -> %s', (instant, expected) => {
    expect(formatAge(instant, NOW)).toBe(expected)
  })

  it('treats a reading slightly in the future (clock skew) as just now', () => {
    expect(formatAge(ago(-30), NOW)).toBe('just now')
  })
})
