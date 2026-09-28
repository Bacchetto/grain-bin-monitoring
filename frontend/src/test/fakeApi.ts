import { vi } from 'vitest'
import type { Alert, BinDetail, BinSummary, HistorySeries, SensorValue } from '../api/types'

export const GOOD_TOKEN = 'good-token'

export interface FakeData {
  bins?: BinSummary[]
  /** Bin details by id. A bin missing here is a 404. */
  binDetails?: Record<number, BinDetail>
  /** Latest readings by bin id. */
  latest?: Record<number, SensorValue[]>
  /** History series by bin id; every range returns the same series. */
  history?: Record<number, HistorySeries[]>
  alerts?: Alert[]
}

export interface RecordedRequest {
  method: string
  url: URL
  auth: string | null
}

/**
 * Stands in for the API by replacing the browser's `fetch`, answering like the
 * real backend on every path the dashboard uses: 401 as Problem Details for
 * any token but GOOD_TOKEN, 404 for an unknown bin, and 409 for acknowledging
 * a resolved alert. Alerts are held as state, so acknowledging one changes
 * what the next request returns -- as it would against the real server.
 *
 * `acceptToken` can be flipped mid-test to simulate the token being rotated on
 * the server while the dashboard is open. `requests` records every call, for
 * asserting what was asked for.
 */
export function fakeApi(input: BinSummary[] | FakeData) {
  const data: FakeData = Array.isArray(input) ? { bins: input } : input
  const alerts = [...(data.alerts ?? [])]
  const state = { acceptToken: true, requests: [] as RecordedRequest[], alerts }

  const fetchMock = vi.fn(async (resource: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(resource))
    const method = init?.method ?? 'GET'
    const auth = new Headers(init?.headers).get('Authorization')
    state.requests.push({ method, url, auth })

    if (!state.acceptToken || auth !== `Bearer ${GOOD_TOKEN}`) {
      return problem(401, 'Unauthorized', 'A valid admin token is required.')
    }

    const path = url.pathname.replace(/^\/api\/v1/, '')
    let match: RegExpMatchArray | null

    if (method === 'GET' && path === '/bins') {
      return json(200, data.bins ?? [])
    }
    if (method === 'GET' && (match = path.match(/^\/bins\/(\d+)$/))) {
      const detail = data.binDetails?.[Number(match[1])]
      return detail ? json(200, detail) : problem(404, 'Not Found', `Bin ${match[1]} does not exist`)
    }
    if (method === 'GET' && (match = path.match(/^\/bins\/(\d+)\/latest$/))) {
      const id = Number(match[1])
      return json(200, { binId: id, since: new Date(0).toISOString(), sensors: data.latest?.[id] ?? [] })
    }
    if (method === 'GET' && (match = path.match(/^\/bins\/(\d+)\/readings$/))) {
      const id = Number(match[1])
      return json(200, {
        binId: id,
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        bucket: url.searchParams.get('bucket'),
        series: data.history?.[id] ?? [],
      })
    }
    if (method === 'GET' && path === '/alerts') {
      const statuses = (url.searchParams.get('status') ?? '').toUpperCase().split(',')
      const binId = url.searchParams.get('binId')
      return json(200, alerts.filter((a) => statuses.includes(a.status) && (binId === null || a.binId === Number(binId))))
    }
    if (method === 'POST' && (match = path.match(/^\/alerts\/(\d+)\/acknowledge$/))) {
      const index = alerts.findIndex((a) => a.id === Number(match![1]))
      if (index < 0) return problem(404, 'Not Found', `Alert ${match[1]} does not exist`)
      if (alerts[index].status === 'RESOLVED') {
        return problem(409, 'Conflict', `Alert ${match[1]} is already resolved and cannot be acknowledged.`)
      }
      if (alerts[index].status === 'OPEN') {
        alerts[index] = { ...alerts[index], status: 'ACKNOWLEDGED', acknowledgedAt: new Date().toISOString() }
      }
      return json(200, alerts[index])
    }
    return problem(404, 'Not Found', `No fake for ${method} ${path}`)
  })

  vi.stubGlobal('fetch', fetchMock)
  return state
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

export function problem(status: number, title: string, detail: string, extra: object = {}): Response {
  return new Response(JSON.stringify({ status, title, detail, ...extra }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

// -- builders, with defaults matching the README's thresholds ----------------

export function bin(overrides: Partial<BinSummary> = {}): BinSummary {
  return {
    id: 1,
    name: 'Bin 1',
    site: 'North Yard',
    grainType: 'canola',
    lastReadingAt: null,
    worstOpenAlert: null,
    openAlertCount: 0,
    ...overrides,
  }
}

export function binDetail(overrides: Partial<BinDetail> = {}): BinDetail {
  return {
    id: 1,
    name: 'Bin 1',
    site: 'North Yard',
    grainType: 'canola',
    capacityBushels: 5000,
    thresholds: { maxTemperatureC: 20, maxMoisturePct: 14.5, riseThresholdC: 2, riseWindowHours: 72 },
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

export function sensor(cable: number, depth: number, temperatureC: number, moisturePct: number | null = null): SensorValue {
  return { cable, depth, temperatureC, moisturePct, recordedAt: new Date().toISOString() }
}

export function alert(overrides: Partial<Alert> = {}): Alert {
  return {
    id: 1,
    binId: 1,
    binName: 'Bin 1',
    type: 'HIGH_TEMPERATURE',
    status: 'OPEN',
    cableIndex: 1,
    depthIndex: 2,
    deviceId: null,
    deviceLastSeenAt: null,
    triggerValue: 21.5,
    thresholdValue: 20,
    firstDetectedAt: new Date(Date.now() - 3_600_000).toISOString(),
    lastDetectedAt: new Date(Date.now() - 60_000).toISOString(),
    acknowledgedAt: null,
    resolvedAt: null,
    ...overrides,
  }
}
