import { Link, Navigate, Route, Routes, useParams } from 'react-router'
import { useAuth } from './auth/authContext'
import { RequireAuth } from './auth/RequireAuth'
import { BinListPage } from './pages/BinListPage'
import { LoginPage } from './pages/LoginPage'

/**
 * The page layout and the routes. Every page except login sits behind
 * RequireAuth.
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Layout>
              <BinListPage />
            </Layout>
          </RequireAuth>
        }
      />
      <Route
        path="/bins/:binId"
        element={
          <RequireAuth>
            <Layout>
              <BinDetailPlaceholder />
            </Layout>
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

function Layout({ children }: { children: React.ReactNode }) {
  const { signOut } = useAuth()
  return (
    <>
      <header className="app-header">
        <Link to="/" className="brand">
          Grain Bin Telemetry
        </Link>
        <nav>
          <Link to="/">Bins</Link>
        </nav>
        <button type="button" onClick={signOut}>
          Sign out
        </button>
      </header>
      <main>{children}</main>
    </>
  )
}

// Replaced by the real bin detail page -- grid and charts -- in the next phase.
function BinDetailPlaceholder() {
  const { binId } = useParams()
  return (
    <p>
      Bin {binId}: detail view not built yet. <Link to="/">Back to bins</Link>
    </p>
  )
}
