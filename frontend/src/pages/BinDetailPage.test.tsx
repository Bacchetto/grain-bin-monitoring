import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, describe, expect, it } from 'vitest'
import type { HistorySeries } from '../api/types'
import { alert, binDetail, fakeApi, sensor } from '../test/fakeApi'
import { renderSignedIn } from '../test/renderApp'

// The page is loaded lazily in the app (see App.tsx). Its first import makes
// Vitest transform the chart library, which takes longer than a findBy* query
// waits; importing it once up front keeps that cost out of the first test.
beforeAll(async () => {
  await import('./BinDetailPage')
}, 60_000)

const HOUR = 3_600_000

function series(cable: number, depth: number): HistorySeries {
  const start = Date.now() - 3 * HOUR
  return {
    cable,
    depth,
    points: [0, 1, 2].map((i) => ({
      bucketStart: new Date(start + i * HOUR).toISOString(),
      readings: 12,
      temperatureC: { avg: 12 + i, min: 11 + i, max: 13 + i },
      moisturePct: { avg: 13.9, min: 13.8, max: 14.0 },
    })),
  }
}

function setUp() {
  return fakeApi({
    bins: [],
    binDetails: { 3: binDetail({ id: 3, name: 'West 3' }) },
    latest: { 3: [sensor(0, 0, 10.0), sensor(1, 2, 17.5), sensor(2, 1, 12.0)] },
    history: { 3: [series(0, 0), series(1, 2), series(2, 1)] },
    alerts: [alert({ id: 9, binId: 3, binName: 'West 3', type: 'RATE_OF_RISE', triggerValue: 2.3, thresholdValue: 2 })],
  })
}

/** The history requests made so far: their bucket and span in hours. */
function historyRequests(api: ReturnType<typeof setUp>) {
  return api.requests
    .filter((r) => r.url.pathname.endsWith('/readings'))
    .map((r) => ({
      bucket: r.url.searchParams.get('bucket'),
      hours: Math.round((Date.parse(r.url.searchParams.get('to')!) - Date.parse(r.url.searchParams.get('from')!)) / HOUR),
    }))
}

describe('the bin detail page', () => {
  it('shows the bin, its limits and its open alerts', async () => {
    setUp()
    await renderSignedIn('/bins/3')

    expect(await screen.findByRole('heading', { name: 'West 3' })).toBeTruthy()
    expect(screen.getByText(/limits: 20.0 °C, 14.5 % moisture, a rise of 2.0 °C over 72 h/)).toBeTruthy()
    expect(await screen.findByText('Up 2.3 °C (limit 2.0)')).toBeTruthy()
  })

  it('starts on the hottest sensor, over the last 24 hours in hourly buckets', async () => {
    const api = setUp()
    await renderSignedIn('/bins/3')

    expect(await screen.findByRole('heading', { name: 'Cable 2, depth 3' })).toBeTruthy()
    expect(await screen.findByText(/Temperature \(°C\): hourly average/)).toBeTruthy()
    expect(historyRequests(api)).toEqual([{ bucket: 'hour', hours: 24 }])
  })

  it('asks for the right bucket for each range', async () => {
    const api = setUp()
    await renderSignedIn('/bins/3')
    await screen.findByRole('heading', { name: 'Cable 2, depth 3' })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '7 days' }))
    await screen.findByText(/Temperature \(°C\): hourly average/)
    await user.click(screen.getByRole('button', { name: '30 days' }))
    await screen.findByText(/Temperature \(°C\): daily average/)

    expect(historyRequests(api)).toEqual([
      { bucket: 'hour', hours: 24 },
      { bucket: 'hour', hours: 7 * 24 },
      { bucket: 'day', hours: 30 * 24 },
    ])
    expect(screen.getByRole('button', { name: '30 days' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('switches the charts to a sensor picked on the grid', async () => {
    setUp()
    await renderSignedIn('/bins/3')
    await screen.findByRole('heading', { name: 'Cable 2, depth 3' })

    await userEvent.setup().click(screen.getByRole('button', { name: /Cable 1, depth 1 \(top\): 10.0/ }))

    expect(await screen.findByRole('heading', { name: 'Cable 1, depth 1 (top)' })).toBeTruthy()
  })

  it('draws a moisture chart as well, when the sensor measures moisture', async () => {
    setUp()
    await renderSignedIn('/bins/3')

    expect(await screen.findByText(/Moisture \(%\): hourly average/)).toBeTruthy()
  })

  it('keeps every charted value in a table as well', async () => {
    setUp()
    await renderSignedIn('/bins/3')
    await screen.findByText(/Temperature \(°C\)/)

    // The hottest sensor's three hourly averages, 12-14 degrees.
    const cells = screen.getAllByRole('cell').map((c) => c.textContent)
    expect(cells).toEqual(expect.arrayContaining(['12.0 °C', '13.0 °C', '14.0 °C']))
  })

  it('says so for a bin that does not exist', async () => {
    fakeApi({ bins: [] })
    await renderSignedIn('/bins/404')

    expect((await screen.findByRole('alert')).textContent).toContain('There is no bin 404.')
  })
})
