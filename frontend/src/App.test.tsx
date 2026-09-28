import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { BIN_LIST_REFRESH_MS } from './pages/BinListPage'
import { bin, binDetail, fakeApi, GOOD_TOKEN } from './test/fakeApi'
import { renderApp, signIn } from './test/renderApp'

// The page is loaded lazily in the app (see App.tsx). Its first import makes
// Vitest transform the chart library, which takes longer than a findBy* query
// waits; importing it once up front keeps that cost out of the first test.
beforeAll(async () => {
  await import('./pages/BinDetailPage')
}, 60_000)

afterEach(() => {
  vi.useRealTimers()
})

describe('signing in', () => {
  it('asks for the token before showing anything', () => {
    fakeApi([bin()])

    renderApp('/')

    expect(screen.getByLabelText('Admin token')).toBeTruthy()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('rejects a wrong token with a clear message and stays on the login page', async () => {
    fakeApi([bin()])
    renderApp()

    await signIn('wrong')

    expect((await screen.findByRole('alert')).textContent).toBe('That token was not accepted.')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('shows the bin list once the token is accepted', async () => {
    fakeApi([bin({ name: 'North 1' })])
    renderApp()

    await signIn()

    expect(await screen.findByRole('link', { name: 'North 1' })).toBeTruthy()
  })

  it('returns to the page that was asked for', async () => {
    fakeApi({ bins: [bin()], binDetails: { 7: binDetail({ id: 7, name: 'Deep link' }) } })
    renderApp('/bins/7')

    await signIn()

    expect(await screen.findByRole('heading', { name: 'Deep link' })).toBeTruthy()
  })

  it('never writes the token to browser storage', async () => {
    fakeApi([bin()])
    renderApp()

    await signIn()
    await screen.findByRole('table')

    // The README's rule: memory only. Anything in storage outlives the tab
    // and is readable by any script on the page.
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(document.cookie).toBe('')
  })

  it('signs out when the API stops accepting the token, and says why', async () => {
    // Fake timers from the start: the list's refresh timer is created when the
    // list first renders, and one created on the real clock would never fire
    // when fake time is advanced. user-event is told to use the fake clock
    // too, for its own small delays between keystrokes.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const api = fakeApi([bin()])
    renderApp()
    await signIn(GOOD_TOKEN, userEvent.setup({ advanceTimers: vi.advanceTimersByTime }))
    await screen.findByRole('table')

    // The token is rotated on the server while the dashboard is open; the
    // next background refresh gets a 401.
    api.acceptToken = false
    await vi.advanceTimersByTimeAsync(BIN_LIST_REFRESH_MS + 1000)

    expect(await screen.findByLabelText('Admin token')).toBeTruthy()
    expect(screen.getByText(/stopped accepting your token/)).toBeTruthy()
  })

  it('signs out on request, and the next sign-in starts from a fresh fetch', async () => {
    const api = fakeApi([bin()])
    renderApp()
    await signIn()
    await screen.findByRole('table')

    await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByLabelText('Admin token')).toBeTruthy()
    const before = api.requests.length
    await signIn()
    await screen.findByRole('table')
    // Fetched again rather than served from the previous session's cache.
    expect(api.requests.length).toBeGreaterThan(before + 1)
  })
})

describe('the bin list', () => {
  it('shows each bin with its status and last reading age', async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60_000).toISOString()
    fakeApi([
      bin({ id: 1, name: 'Healthy', lastReadingAt: tenMinutesAgo }),
      bin({ id: 2, name: 'Hot', worstOpenAlert: 'HIGH_TEMPERATURE', openAlertCount: 3, lastReadingAt: tenMinutesAgo }),
      bin({ id: 3, name: 'Silent', worstOpenAlert: 'DEVICE_OFFLINE', openAlertCount: 1 }),
    ])
    renderApp()
    await signIn()

    const rows = (await screen.findAllByRole('row')).slice(1) // skip the header row
    const cells = rows.map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))

    expect(cells).toEqual([
      ['Healthy', 'North Yard', 'canola', 'OK', '10 min ago'],
      ['Hot', 'North Yard', 'canola', 'High temperature +2 more', '10 min ago'],
      ['Silent', 'North Yard', 'canola', 'Device offline', 'never'],
    ])
  })

  it('links each bin to its detail page', async () => {
    fakeApi([bin({ id: 42, name: 'Linked' })])
    renderApp()
    await signIn()

    expect((await screen.findByRole('link', { name: 'Linked' })).getAttribute('href')).toBe('/bins/42')
  })

  it('says so when there are no bins yet', async () => {
    fakeApi([])
    renderApp()
    await signIn()

    await waitFor(() => expect(screen.getByText(/No bins yet/)).toBeTruthy())
  })
})
