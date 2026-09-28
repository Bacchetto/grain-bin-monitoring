import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { createApiClient, NetworkError, UnauthorizedError } from '../api/client'
import { useAuth } from '../auth/authContext'

/**
 * Asks for the admin token, and checks it against the API before accepting
 * it: a mistyped token fails here, with a clear message, instead of on the
 * first page that loads data.
 */
export function LoginPage() {
  const { signIn, signedOutReason } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [token, setToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    // A form submission would otherwise reload the page -- and with it, lose
    // everything held in memory.
    event.preventDefault()
    setError(null)
    setChecking(true)
    const candidate = token.trim()
    try {
      await createApiClient(candidate).listBins()
      signIn(candidate)
      // `location.state` is whatever RequireAuth stored on the way here. Its
      // type is `unknown`, so it is checked before being used as a path.
      const from = (location.state as { from?: unknown } | null)?.from
      navigate(typeof from === 'string' ? from : '/', { replace: true })
    } catch (e) {
      setError(
        e instanceof UnauthorizedError
          ? 'That token was not accepted.'
          : e instanceof NetworkError
            ? e.message
            : 'Something went wrong checking the token. Try again.',
      )
    } finally {
      setChecking(false)
    }
  }

  return (
    <main className="login">
      <h1>Grain Bin Telemetry</h1>
      {signedOutReason === 'rejected' && (
        <p className="notice">The API stopped accepting your token. Sign in again.</p>
      )}
      <form onSubmit={handleSubmit}>
        <label htmlFor="token">Admin token</label>
        {/* type="password" hides it on screen; autoComplete="off" asks the
            browser not to offer to save it, which would put it on disk. */}
        <input
          id="token"
          type="password"
          autoComplete="off"
          value={token}
          onChange={(e) => setToken(e.target.value)}
          required
        />
        <button type="submit" disabled={checking || token.trim() === ''}>
          {checking ? 'Checking…' : 'Sign in'}
        </button>
      </form>
      {error !== null && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <p className="hint">The token is kept in this tab's memory only. Reloading the page asks for it again.</p>
    </main>
  )
}
