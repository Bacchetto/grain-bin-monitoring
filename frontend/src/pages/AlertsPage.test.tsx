import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { alert, bin, fakeApi } from '../test/fakeApi'
import { renderSignedIn } from '../test/renderApp'

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString()

function rows() {
  return screen.getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell').map((c) => c.textContent))
}

describe('the alerts view', () => {
  it('lists open and acknowledged alerts across bins, in words', async () => {
    fakeApi({
      bins: [bin()],
      alerts: [
        alert({ id: 1, binId: 1, binName: 'North 1', type: 'HIGH_TEMPERATURE', cableIndex: 1, depthIndex: 2, triggerValue: 21.5, thresholdValue: 20 }),
        alert({ id: 2, binId: 2, binName: 'North 2', type: 'DEVICE_OFFLINE', status: 'ACKNOWLEDGED', cableIndex: null, depthIndex: null, deviceId: 7, deviceLastSeenAt: minutesAgo(42), triggerValue: null, thresholdValue: null, acknowledgedAt: minutesAgo(5) }),
        alert({ id: 3, binId: 1, binName: 'North 1', type: 'HIGH_MOISTURE', status: 'RESOLVED' }),
      ],
    })
    await renderSignedIn('/alerts')
    await screen.findByRole('heading', { name: 'Alerts' })

    await waitFor(() => expect(rows()).toHaveLength(2)) // the resolved one is not asked for
    const [hot, offline] = rows()
    expect(hot.slice(0, 4)).toEqual(['High temperature', 'North 1', 'Cable 2, depth 3', '21.5 °C (limit 20.0)'])
    expect(hot[6]).toBe('Acknowledge')
    expect(offline.slice(0, 4)).toEqual(['Device offline', 'North 2', 'Device 7', 'Last heard from 42 min ago'])
    expect(offline[6]).toBe('Acknowledged 5 min ago')
    expect(screen.getByRole('link', { name: 'North 2' }).getAttribute('href')).toBe('/bins/2')
  })

  it('acknowledges an alert, and the list refreshes to show it', async () => {
    const api = fakeApi({ bins: [bin()], alerts: [alert({ id: 1, binName: 'North 1' })] })
    await renderSignedIn('/alerts')
    const button = await screen.findByRole('button', { name: /Acknowledge high temperature on North 1/ })
    const alertFetches = () => api.requests.filter((r) => r.url.pathname.endsWith('/alerts')).length
    const before = alertFetches()

    await userEvent.setup().click(button)

    // "Acknowledged" comes from the server's refreshed list, not from the page
    // guessing: the POST, then a fresh GET of the alerts.
    expect(await screen.findByText(/Acknowledged just now/)).toBeTruthy()
    expect(api.requests.some((r) => r.method === 'POST' && r.url.pathname === '/api/v1/alerts/1/acknowledge')).toBe(true)
    expect(alertFetches()).toBeGreaterThan(before)
  })

  it('explains a 409 when the alert resolved before it could be acknowledged', async () => {
    const api = fakeApi({ bins: [bin()], alerts: [alert({ id: 1, binName: 'North 1' })] })
    await renderSignedIn('/alerts')
    const button = await screen.findByRole('button', { name: /Acknowledge/ })
    // The engine resolves it between the page loading and the click.
    api.alerts[0] = { ...api.alerts[0], status: 'RESOLVED', resolvedAt: new Date().toISOString() }

    await userEvent.setup().click(button)

    expect((await screen.findByRole('alert')).textContent).toContain('resolved before it could be acknowledged')
    await waitFor(() => expect(screen.getByText('No open alerts.')).toBeTruthy())
  })

  it('says so when there is nothing to show', async () => {
    fakeApi({ bins: [bin()], alerts: [] })
    await renderSignedIn('/alerts')

    expect(await screen.findByText('No open alerts.')).toBeTruthy()
  })
})
