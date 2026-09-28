import { lazy, Suspense, type ReactNode } from 'react'
import { Link, Navigate, Route, Routes } from 'react-router'
import { useAuth } from './auth/authContext'
import { RequireAuth } from './auth/RequireAuth'
import { AlertsPage } from './pages/AlertsPage'
import { BinListPage } from './pages/BinListPage'
import { LoginPage } from './pages/LoginPage'

// The bin detail page is the only one with charts, and the chart library is
// most of the app's weight. Loading the page lazily splits it -- and the
// library -- into a separate file the browser fetches only when a bin is
// opened, so the login screen and the bin list load without it. `lazy` wants
// a default export; the page exports a named one, so it is picked out here.
const BinDetailPage = lazy(() => import('./pages/BinDetailPage').then((m) => ({ default: m.BinDetailPage })))

/**
 * The page layout and the routes. Every page except login sits behind
 * RequireAuth.
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<Page><BinListPage /></Page>} />
      <Route path="/bins/:binId" element={<Page><BinDetailPage /></Page>} />
      <Route path="/alerts" element={<Page><AlertsPage /></Page>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

/** A signed-in page: the auth check, then the shared header. */
function Page({ children }: { children: ReactNode }) {
  return (
    <RequireAuth>
      <Layout>{children}</Layout>
    </RequireAuth>
  )
}

function Layout({ children }: { children: ReactNode }) {
  const { signOut } = useAuth()
  return (
    <>
      <header className="app-header">
        <Link to="/" className="brand">
          Grain Bin Telemetry
        </Link>
        <nav>
          <Link to="/">Bins</Link>
          <Link to="/alerts">Alerts</Link>
        </nav>
        <button type="button" onClick={signOut}>
          Sign out
        </button>
      </header>
      {/* Shown while a lazily loaded page is still downloading. */}
      <main>
        <Suspense fallback={<p>Loading…</p>}>{children}</Suspense>
      </main>
    </>
  )
}
