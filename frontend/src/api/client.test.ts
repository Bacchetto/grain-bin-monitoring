import { describe, expect, it, vi } from 'vitest'
import { json, problem } from '../test/fakeApi'
import { API_URL, ApiError, createApiClient, NetworkError, UnauthorizedError } from './client'

function stubFetch(response: Response | Error) {
  const fetchMock = vi.fn(async () => {
    if (response instanceof Error) throw response
    return response
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** The URL and headers of the one request made. */
function sent(fetchMock: ReturnType<typeof stubFetch>) {
  const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
  return { url, headers: new Headers(init.headers), method: init.method }
}

describe('the API client', () => {
  it('sends the token as a bearer header to the versioned path', async () => {
    const fetchMock = stubFetch(json(200, []))

    await createApiClient('t0ken').listBins()

    const request = sent(fetchMock)
    expect(request.url).toBe(`${API_URL}/api/v1/bins`)
    expect(request.headers.get('Authorization')).toBe('Bearer t0ken')
  })

  it('returns the parsed body', async () => {
    stubFetch(json(200, [{ id: 7 }]))

    await expect(createApiClient('t').listBins()).resolves.toEqual([{ id: 7 }])
  })

  it('turns a 401 into an UnauthorizedError', async () => {
    stubFetch(problem(401, 'Unauthorized', 'A valid admin token is required.'))

    const error = await createApiClient('t').listBins().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(UnauthorizedError)
    expect(error).toBeInstanceOf(ApiError)
  })

  it('carries the Problem Details body of any other error', async () => {
    stubFetch(problem(409, 'Conflict', 'Alert 3 is already resolved and cannot be acknowledged.'))

    const error = await createApiClient('t').acknowledgeAlert(3).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).not.toBeInstanceOf(UnauthorizedError)
    expect((error as ApiError).status).toBe(409)
    expect((error as ApiError).message).toBe('Alert 3 is already resolved and cannot be acknowledged.')
  })

  it('keeps the field errors of a validation failure', async () => {
    stubFetch(problem(400, 'Bad Request', 'The request failed validation.', {
      errors: [{ field: 'status', message: 'unknown value [pending]' }],
    }))

    const error = (await createApiClient('t').listAlerts(['OPEN']).catch((e: unknown) => e)) as ApiError

    expect(error.problem?.errors).toEqual([{ field: 'status', message: 'unknown value [pending]' }])
  })

  it('copes with an error body that is not JSON', async () => {
    stubFetch(new Response('<html>Bad Gateway</html>', { status: 502 }))

    const error = (await createApiClient('t').listBins().catch((e: unknown) => e)) as ApiError

    expect(error.status).toBe(502)
    expect(error.problem).toBeNull()
  })

  it('turns no response at all into a NetworkError naming the API', async () => {
    stubFetch(new TypeError('Failed to fetch'))

    const error = await createApiClient('t').listBins().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(NetworkError)
    expect((error as Error).message).toContain(API_URL)
  })

  it('asks for several alert statuses in one comma-separated parameter', async () => {
    const fetchMock = stubFetch(json(200, []))

    await createApiClient('t').listAlerts(['OPEN', 'ACKNOWLEDGED'], 4)

    const url = new URL(sent(fetchMock).url)
    expect(url.pathname).toBe('/api/v1/alerts')
    expect(url.searchParams.get('status')).toBe('open,acknowledged')
    expect(url.searchParams.get('binId')).toBe('4')
  })

  it('acknowledges with a POST', async () => {
    const fetchMock = stubFetch(json(200, {}))

    await createApiClient('t').acknowledgeAlert(12)

    const request = sent(fetchMock)
    expect(request.method).toBe('POST')
    expect(request.url).toBe(`${API_URL}/api/v1/alerts/12/acknowledge`)
  })
})
