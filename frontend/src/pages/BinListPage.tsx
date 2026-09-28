import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useApi } from '../auth/authContext'
import { StatusBadge } from '../components/StatusBadge'
import { formatAge } from '../format'
import { useNow } from '../useNow'

/** How often the list refreshes itself. Alerts are evaluated every one to five minutes. */
export const BIN_LIST_REFRESH_MS = 30_000

/** Every bin, with its status. The dashboard's home page. */
export function BinListPage() {
  const api = useApi()
  const now = useNow()

  // TanStack Query fetches, caches, and refetches on the interval; `bins` is
  // undefined until the first answer arrives. The query key names the data
  // in the cache, so other pages -- and the acknowledge button, in the alerts
  // view -- can refresh it.
  const { data: bins, error, isPending } = useQuery({
    queryKey: ['bins'],
    queryFn: api.listBins,
    refetchInterval: BIN_LIST_REFRESH_MS,
  })

  if (isPending) return <p>Loading bins…</p>
  if (error) return <p role="alert" className="error">Could not load bins: {error.message}</p>
  if (bins.length === 0) return <p>No bins yet. Create one through the API, or run the simulator with --seed-bins.</p>

  return (
    <table className="bins">
      <thead>
        <tr>
          <th>Bin</th>
          <th>Site</th>
          <th>Grain</th>
          <th>Status</th>
          <th>Last reading</th>
        </tr>
      </thead>
      <tbody>
        {bins.map((bin) => (
          // `key` lets React match rows between renders, so a refresh
          // updates the rows in place instead of rebuilding the table.
          <tr key={bin.id}>
            <td>
              <Link to={`/bins/${bin.id}`}>{bin.name}</Link>
            </td>
            <td>{bin.site}</td>
            <td>{bin.grainType}</td>
            <td>
              <StatusBadge worst={bin.worstOpenAlert} count={bin.openAlertCount} />
            </td>
            <td title={bin.lastReadingAt ?? undefined}>{formatAge(bin.lastReadingAt, now)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
