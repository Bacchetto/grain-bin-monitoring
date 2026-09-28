import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
} from 'recharts'
import type { HistorySeries } from '../api/types'
import { formatNumber } from '../format'
import { CHART_COLORS, useColorScheme } from '../useColorScheme'

export type Metric = 'temperatureC' | 'moisturePct'

const UNITS: Record<Metric, string> = { temperatureC: '°C', moisturePct: '%' }

interface Props {
  title: string
  series: HistorySeries | undefined
  metric: Metric
  /** The bin's limit for this metric, drawn as a reference line. */
  threshold: number
  from: Date
  to: Date
  /** Whether the ticks show a time of day or a date. */
  tickFormat: 'time' | 'date'
}

/** One chart point: a bucket's average, and its min-max range for the band. */
interface Point {
  t: number
  avg: number
  range: [number, number]
  readings: number
}

/**
 * One sensor, one measure, over time: the average per bucket as a line, the
 * range from minimum to maximum as a faint band behind it, and the bin's
 * limit as a labelled reference line.
 *
 * Temperature and moisture are separate charts, never one chart with two
 * y-axes: two scales on one plot invite reading a crossing of the lines as
 * meaningful when it is only an artefact of how each axis was scaled.
 *
 * A single series needs no legend; the title says what is plotted. The table
 * under the chart holds every value, so nothing depends on hovering.
 */
export function SensorHistoryChart({ title, series, metric, threshold, from, to, tickFormat }: Props) {
  const colors = CHART_COLORS[useColorScheme()]
  const unit = UNITS[metric]

  // flatMap drops buckets without this metric (moisture is optional).
  const points: Point[] = (series?.points ?? []).flatMap((p) => {
    const stats = p[metric]
    return stats === null ? [] : [{ t: Date.parse(p.bucketStart), avg: stats.avg, range: [stats.min, stats.max], readings: p.readings }]
  })

  const formatTick = (t: number) =>
    new Date(t).toLocaleString(undefined, tickFormat === 'time' ? { hour: 'numeric', minute: '2-digit' } : { month: 'short', day: 'numeric' })
  const formatWhen = (t: number) =>
    new Date(t).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

  return (
    <figure className="chart">
      <figcaption>{title}</figcaption>
      {points.length === 0 ? (
        <p className="hint">No readings in this range.</p>
      ) : (
        <>
          {/* ResponsiveContainer fills the parent's width; the height is fixed
              and includes the x-axis labels, so they are never cut off. */}
          <ResponsiveContainer width="100%" height={240} initialDimension={{ width: 640, height: 240 }}>
            <ComposedChart data={points} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
              <CartesianGrid stroke={colors.grid} vertical={false} />
              <XAxis
                dataKey="t"
                type="number"
                scale="time"
                domain={[from.getTime(), to.getTime()]}
                tickFormatter={formatTick}
                stroke={colors.axis}
                tick={{ fill: colors.tick, fontSize: 12 }}
                minTickGap={32}
              />
              <YAxis
                // Include the limit, so the reference line is always on the plot.
                domain={[(min: number) => Math.floor(Math.min(min, threshold) - 1), (max: number) => Math.ceil(Math.max(max, threshold) + 1)]}
                tickFormatter={(v: number) => `${v}`}
                stroke={colors.axis}
                tick={{ fill: colors.tick, fontSize: 12 }}
                width={40}
                unit={unit === '%' ? '%' : '°'}
              />
              {/* The band: an Area whose value is a [low, high] pair. A wash
                  of the series colour at about 10%, never a solid block. */}
              <Area dataKey="range" stroke="none" fill={colors.series} fillOpacity={0.12} isAnimationActive={false} />
              <Line
                dataKey="avg"
                stroke={colors.series}
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, stroke: 'var(--bg)', strokeWidth: 2 }}
                isAnimationActive={false}
              />
              <ReferenceLine
                y={threshold}
                stroke={colors.threshold}
                strokeDasharray="4 4"
                label={{ value: `Limit ${formatNumber(threshold)} ${unit}`, position: 'insideTopRight', fill: colors.tick, fontSize: 12 }}
              />
              <Tooltip
                content={(props: TooltipContentProps) => <Readout {...props} unit={unit} formatWhen={formatWhen} />}
                cursor={{ stroke: colors.axis, strokeWidth: 1 }}
              />
            </ComposedChart>
          </ResponsiveContainer>
          <details className="chart-table">
            <summary>Show the data as a table</summary>
            <table>
              <thead>
                <tr>
                  <th>From</th>
                  <th>Average</th>
                  <th>Min</th>
                  <th>Max</th>
                  <th>Readings</th>
                </tr>
              </thead>
              <tbody>
                {points.map((p) => (
                  <tr key={p.t}>
                    <td>{formatWhen(p.t)}</td>
                    <td>{formatNumber(p.avg)} {unit}</td>
                    <td>{formatNumber(p.range[0])}</td>
                    <td>{formatNumber(p.range[1])}</td>
                    <td>{p.readings}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        </>
      )}
    </figure>
  )
}

/** The crosshair readout: the value leads, the range and time follow. */
function Readout({
  active,
  payload,
  unit,
  formatWhen,
}: TooltipContentProps & { unit: string; formatWhen: (t: number) => string }) {
  const point = payload?.[0]?.payload as Point | undefined
  if (!active || !point) return null
  return (
    <div className="chart-readout">
      <strong>
        {formatNumber(point.avg)} {unit}
      </strong>{' '}
      average
      <div className="hint">
        {formatNumber(point.range[0])} – {formatNumber(point.range[1])} {unit} · {point.readings} readings
      </div>
      <div className="hint">{formatWhen(point.t)}</div>
    </div>
  )
}
