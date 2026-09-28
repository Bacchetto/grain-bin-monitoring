import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError } from '../api/client'
import type { SensorValue } from '../api/types'
import { useApi } from '../auth/authContext'
import { AlertTable } from '../components/AlertTable'
import { SensorGrid, type Position } from '../components/SensorGrid'
import { SensorHistoryChart } from '../components/SensorHistoryChart'
import { formatNumber, sensorName } from '../format'
import { RANGES, type RangeKey } from '../ranges'
import { useNow } from '../useNow'

export const LATEST_REFRESH_MS = 30_000
const HISTORY_REFRESH_MS = 60_000

/** One bin: its grid of latest readings, one sensor's history, and its alerts. */
export function BinDetailPage() {
  const api = useApi()
  const now = useNow()
  // Read once, up here with the other hooks: hooks must run in the same
  // order on every render, so none may sit after an early return.
  const rawBinId = useParams().binId
  const binId = Number(rawBinId)

  const [range, setRange] = useState<RangeKey>('24h')
  const [chosen, setChosen] = useState<Position | null>(null)

  const bin = useQuery({ queryKey: ['bin', binId], queryFn: () => api.getBin(binId), enabled: Number.isInteger(binId) })
  const latest = useQuery({
    queryKey: ['latest', binId],
    queryFn: () => api.getLatest(binId),
    refetchInterval: LATEST_REFRESH_MS,
    enabled: bin.isSuccess,
  })
  const history = useQuery({
    // The range is part of the key: each range is cached separately, so
    // switching back to one already seen is instant.
    queryKey: ['history', binId, range],
    queryFn: () => {
      const to = new Date()
      const from = new Date(to.getTime() - RANGES[range].hours * 3_600_000)
      return api.getHistory(binId, from, to, RANGES[range].bucket)
    },
    refetchInterval: HISTORY_REFRESH_MS,
    enabled: bin.isSuccess,
  })
  const alerts = useQuery({
    queryKey: ['alerts', 'bin', binId],
    queryFn: () => api.listAlerts(['OPEN', 'ACKNOWLEDGED'], binId),
    refetchInterval: LATEST_REFRESH_MS,
    enabled: bin.isSuccess,
  })

  if (bin.isError) {
    const missing = bin.error instanceof ApiError && bin.error.status === 404
    return (
      <p role="alert" className="error">
        {missing ? `There is no bin ${rawBinId}.` : `Could not load the bin: ${bin.error.message}`}{' '}
        <Link to="/">Back to bins</Link>
      </p>
    )
  }
  if (!Number.isInteger(binId)) {
    return (
      <p role="alert" className="error">
        That is not a bin id. <Link to="/">Back to bins</Link>
      </p>
    )
  }
  if (bin.isPending) return <p>Loading…</p>

  const { thresholds } = bin.data
  const sensors = latest.data?.sensors ?? []
  // Until someone picks a sensor, show the hottest: on a bin with a problem,
  // that is almost always the one worth looking at.
  const selected = chosen ?? hottest(sensors)
  const series = selected && history.data?.series.find((s) => s.cable === selected.cable && s.depth === selected.depth)
  const hasMoisture = series?.points.some((p) => p.moisturePct !== null) ?? false
  const span = history.data && { from: new Date(history.data.from), to: new Date(history.data.to) }

  return (
    <>
      <p>
        <Link to="/">← All bins</Link>
      </p>
      <h1>{bin.data.name}</h1>
      <p className="hint">
        {bin.data.site} · {bin.data.grainType}
        {bin.data.capacityBushels !== null && ` · ${bin.data.capacityBushels.toLocaleString()} bu`}
        {' · '}limits: {formatNumber(thresholds.maxTemperatureC)} °C, {formatNumber(thresholds.maxMoisturePct)} % moisture, a
        rise of {formatNumber(thresholds.riseThresholdC)} °C over {thresholds.riseWindowHours} h
      </p>

      <section aria-labelledby="alerts-heading">
        <h2 id="alerts-heading">Open alerts</h2>
        {alerts.data ? <AlertTable alerts={alerts.data} now={now} showBin={false} /> : <p>Loading alerts…</p>}
      </section>

      <section aria-labelledby="grid-heading">
        <h2 id="grid-heading">Latest temperatures</h2>
        {latest.isPending ? (
          <p>Loading readings…</p>
        ) : (
          <SensorGrid
            sensors={sensors}
            maxTemperatureC={thresholds.maxTemperatureC}
            selected={selected}
            onSelect={setChosen}
            now={now}
          />
        )}
      </section>

      {selected && (
        <section aria-labelledby="history-heading">
          <div className="history-header">
            <h2 id="history-heading">{sensorName(selected.cable, selected.depth)}</h2>
            <div className="range-picker" role="group" aria-label="Time range">
              {(Object.keys(RANGES) as RangeKey[]).map((key) => (
                <button key={key} type="button" aria-pressed={range === key} onClick={() => setRange(key)}>
                  {RANGES[key].label}
                </button>
              ))}
            </div>
          </div>
          {history.isPending || !span ? (
            <p>Loading history…</p>
          ) : history.isError ? (
            <p role="alert" className="error">
              Could not load history: {history.error.message}
            </p>
          ) : (
            <>
              <SensorHistoryChart
                title={`Temperature (°C): ${RANGES[range].bucket === 'hour' ? 'hourly' : 'daily'} average with min–max range`}
                series={series ?? undefined}
                metric="temperatureC"
                threshold={thresholds.maxTemperatureC}
                from={span.from}
                to={span.to}
                tickFormat={RANGES[range].ticks}
              />
              {hasMoisture && (
                <SensorHistoryChart
                  title={`Moisture (%): ${RANGES[range].bucket === 'hour' ? 'hourly' : 'daily'} average with min–max range`}
                  series={series ?? undefined}
                  metric="moisturePct"
                  threshold={thresholds.maxMoisturePct}
                  from={span.from}
                  to={span.to}
                  tickFormat={RANGES[range].ticks}
                />
              )}
            </>
          )}
        </section>
      )}
    </>
  )
}

function hottest(sensors: SensorValue[]): Position | null {
  if (sensors.length === 0) return null
  const top = sensors.reduce((a, b) => (b.temperatureC > a.temperatureC ? b : a))
  return { cable: top.cable, depth: top.depth }
}
