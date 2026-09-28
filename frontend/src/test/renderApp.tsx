import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { App } from '../App'
import { AuthProvider } from '../auth/AuthProvider'
import { GOOD_TOKEN } from './fakeApi'

/**
 * The whole app -- router, auth, query cache, pages -- with only `fetch`
 * replaced. MemoryRouter is the test stand-in for BrowserRouter: it keeps the
 * current URL in memory instead of the browser's address bar.
 */
export function renderApp(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

export async function signIn(token = GOOD_TOKEN, user = userEvent.setup()) {
  await user.type(screen.getByLabelText('Admin token'), token)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

/** Render at `path`, sign in, and land on that page. */
export async function renderSignedIn(path: string) {
  renderApp(path)
  await signIn()
}
