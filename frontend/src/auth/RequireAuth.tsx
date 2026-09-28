import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from './authContext'

/**
 * Renders its children only when signed in; otherwise sends the user to the
 * login page, remembering where they were going so login can return them
 * there.
 *
 * This is a convenience, not security. Anyone can edit the page's JavaScript;
 * what actually protects the data is the API refusing requests without a
 * valid token.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { api } = useAuth()
  const location = useLocation()

  if (api === null) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return children
}
