import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError, createApiClient, NetworkError, UnauthorizedError } from '../api/client'
import { AuthContext, type Auth } from './authContext'

/**
 * Holds the admin token, and the data fetched with it.
 *
 * THE TOKEN LIVES IN MEMORY ONLY -- React state, never localStorage or a
 * cookie (README). Anything in localStorage can be read by any script that
 * ever runs on the page, including an injected one, and it outlives the tab.
 * The cost is that reloading the page asks for the token again. For a
 * token that grants every admin action, that is the right trade.
 *
 * It also owns the TanStack Query cache, for one reason: data fetched with a
 * token must not outlive it. Signing out, or being signed out by a 401,
 * empties the cache, so the next person to sign in never briefly sees the
 * last person's data.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null)
  const [signedOutReason, setSignedOutReason] = useState<Auth['signedOutReason']>(null)

  // useState with an initialiser function creates the QueryClient once, on
  // first render, and keeps the same one across re-renders.
  const [queryClient] = useState(
    () =>
      new QueryClient({
        // Any query failing with 401 means the token no longer works: sign
        // out, wherever in the app it happened.
        queryCache: new QueryCache({
          onError: (error) => {
            if (error instanceof UnauthorizedError) {
              setToken(null)
              setSignedOutReason('rejected')
            }
          },
        }),
        defaultOptions: {
          queries: {
            // Retry only what a retry might fix: no answer at all, or a
            // server error. A 4xx -- a bad token, a bin that does not exist --
            // will get the same answer every time, and retrying it only delays
            // the error by the retry backoff.
            retry: (failureCount, error) =>
              failureCount < 2 &&
              (error instanceof NetworkError || (error instanceof ApiError && error.status >= 500)),
          },
        },
      }),
  )

  // Runs after any render in which the token changed. Signing out -- by
  // choice or by a 401 -- drops every cached response.
  useEffect(() => {
    if (token === null) {
      queryClient.clear()
    }
  }, [token, queryClient])

  // useMemo keeps the same object between renders unless the token changes,
  // so components reading it do not re-render for nothing.
  const auth = useMemo<Auth>(
    () => ({
      api: token === null ? null : createApiClient(token),
      signedOutReason,
      signIn: (newToken) => {
        setToken(newToken)
        setSignedOutReason(null)
      },
      signOut: () => setToken(null),
    }),
    [token, signedOutReason],
  )

  return (
    <AuthContext value={auth}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </AuthContext>
  )
}
