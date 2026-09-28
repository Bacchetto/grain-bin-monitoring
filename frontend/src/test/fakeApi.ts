import { vi } from 'vitest'
import type { BinSummary } from '../api/types'

export const GOOD_TOKEN = 'good-token'

/**
 * Stands in for the API by replacing the browser's `fetch`. Answers like the
 * real backend on the paths the tests use: 401 as a Problem Details body for
 * any token but GOOD_TOKEN, and the given bins for GET /bins.
 *
 * `acceptToken` can be flipped mid-test to simulate the token being rotated
 * on the server while the dashboard is open.
 */
export function fakeApi(bins: BinSummary[]) {
  const state = { acceptToken: true, requests: [] as { url: string; auth: string | null }[] }

  const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input)
    const auth = new Headers(init?.headers).get('Authorization')
    state.requests.push({ url, auth })

    if (!state.acceptToken || auth !== `Bearer ${GOOD_TOKEN}`) {
      return problem(401, 'Unauthorized', 'A valid admin token is required.')
    }
    if (url.endsWith('/api/v1/bins')) {
      return json(200, bins)
    }
    return problem(404, 'Not Found', `No fake for ${url}`)
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
