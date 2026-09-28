// The shape of the signed-in state, and the hook components use to read it.
//
// React "context" passes a value down the component tree without threading
// it through every component's props. Here it carries the admin token, an API
// client bound to it, and the functions to sign in and out.
//
// Kept apart from AuthProvider.tsx because the dev server's hot reload wants
// a file to export either components or other things, not both.

import { createContext, useContext } from 'react'
import type { ApiClient } from '../api/client'

export interface Auth {
  /** The API client for the current token, or null when signed out. */
  api: ApiClient | null
  /** Why the user was last signed out, if not by choice -- shown on the login page. */
  signedOutReason: 'rejected' | null
  signIn: (token: string) => void
  signOut: () => void
}

export const AuthContext = createContext<Auth | null>(null)

/** The signed-in state. Must be used inside <AuthProvider>. */
export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (auth === null) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return auth
}

/**
 * The API client, for components that only render when signed in (behind
 * RequireAuth). Throws rather than returning null, so those components need
 * no null checks of their own.
 */
export function useApi(): ApiClient {
  const { api } = useAuth()
  if (api === null) {
    throw new Error('useApi called while signed out; wrap the page in <RequireAuth>')
  }
  return api
}
