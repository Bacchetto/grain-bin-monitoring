// The typed API client. Every request the dashboard makes goes through
// `request` below, so authentication and error handling live in one place.

import type { Alert, AlertStatus, BinDetail, BinSummary, Bucket, LatestReadings, ProblemDetail, ReadingHistory } from './types'

/**
 * Where the API is. Vite replaces `import.meta.env.VITE_*` at build time with
 * the value from the environment or a `.env` file, so a production build can
 * point at the deployed API. Only variables prefixed VITE_ are exposed to the
 * browser -- which is how Vite keeps other environment variables, such as
 * secrets, out of the bundle.
 */
export const API_URL: string = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

/** The server answered with an error status. Carries the Problem Details body. */
export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail | null

  constructor(status: number, problem: ProblemDetail | null) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/**
 * 401: the token is missing, wrong, or has been rotated. A class of its own
 * because the app reacts to it specially -- by signing out -- and
 * `instanceof UnauthorizedError` is a check TypeScript understands.
 */
export class UnauthorizedError extends ApiError {
  constructor(problem: ProblemDetail | null) {
    super(401, problem)
    this.name = 'UnauthorizedError'
  }
}

/** The request never got an answer: the API is down, or unreachable. */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super(`Cannot reach the API at ${API_URL}.`, { cause })
    this.name = 'NetworkError'
  }
}

async function request<T>(token: string, method: 'GET' | 'POST', path: string): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_URL}/api/v1${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch (cause) {
    // fetch rejects only when there is no HTTP response at all. An error
    // status such as 404 still resolves, and is handled below.
    throw new NetworkError(cause)
  }

  if (!response.ok) {
    const problem = await readProblem(response)
    throw response.status === 401 ? new UnauthorizedError(problem) : new ApiError(response.status, problem)
  }
  // `as T` is a promise to the compiler, not a check: see the note at the top
  // of types.ts.
  return (await response.json()) as T
}

async function readProblem(response: Response): Promise<ProblemDetail | null> {
  try {
    return (await response.json()) as ProblemDetail
  } catch {
    return null // not JSON: a proxy's HTML error page, say
  }
}

/**
 * Every endpoint the dashboard uses, bound to one admin token.
 *
 * Built by a function rather than a class with a mutable token, so a client
 * can never be halfway through a request with one token and finish with
 * another: signing in again creates a new client.
 */
export function createApiClient(token: string) {
  const get = <T>(path: string) => request<T>(token, 'GET', path)

  return {
    listBins: () => get<BinSummary[]>('/bins'),
    getBin: (binId: number) => get<BinDetail>(`/bins/${binId}`),
    getLatest: (binId: number) => get<LatestReadings>(`/bins/${binId}/latest`),
    getHistory: (binId: number, from: Date, to: Date, bucket: Bucket) =>
      get<ReadingHistory>(`/bins/${binId}/readings?${new URLSearchParams({
        from: from.toISOString(),
        to: to.toISOString(),
        bucket,
      })}`),
    listAlerts: (statuses: AlertStatus[], binId?: number) => {
      const params = new URLSearchParams({ status: statuses.join(',').toLowerCase() })
      if (binId !== undefined) params.set('binId', String(binId))
      return get<Alert[]>(`/alerts?${params}`)
    },
    acknowledgeAlert: (alertId: number) => request<Alert>(token, 'POST', `/alerts/${alertId}/acknowledge`),
  }
}

/** The type of the object `createApiClient` returns, inferred rather than written out twice. */
export type ApiClient = ReturnType<typeof createApiClient>
