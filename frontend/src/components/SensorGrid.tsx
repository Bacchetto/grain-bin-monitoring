import type { SensorValue } from '../api/types'
import { formatAge, formatNumber, sensorName } from '../format'
import { HEAT_SPAN_C, HEAT_STEPS_DARK, HEAT_STEPS_LIGHT, heatStep, inkFor } from '../heat'
import { useColorScheme } from '../useColorScheme'

export interface Position {
  cable: number
  depth: number
}

interface Props {
  sensors: SensorValue[]
  maxTemperatureC: number
  selected: Position | null
  onSelect: (position: Position) => void
  now: number
}

/**
 * The latest temperature at every sensor position: cables across, depth down,
 * with the top of each cable (depth 0) as the first row -- the bin as it
 * stands.
 *
 * Every cell shows its value as text as well as colour, so the grid doubles
 * as the table view and nothing depends on reading a shade. A cell above the
 * bin's temperature threshold adds an icon and a label; a position with no
 * reading in the last seven days is shown as a gap, not a stale number.
 *
 * Cells are buttons: clicking one (or Enter/Space on it) picks the sensor the
 * charts below show.
 */
export function SensorGrid({ sensors, maxTemperatureC, selected, onSelect, now }: Props) {
  const scheme = useColorScheme()
  const steps = scheme === 'dark' ? HEAT_STEPS_DARK : HEAT_STEPS_LIGHT

  // Sized from the positions that have reported. A cable whose every sensor
  // has been silent for a week cannot be known about from here.
  const cables = Math.max(0, ...sensors.map((s) => s.cable + 1))
  const depths = Math.max(0, ...sensors.map((s) => s.depth + 1))
  const byPosition = new Map(sensors.map((s) => [`${s.cable}:${s.depth}`, s]))

  if (sensors.length === 0) {
    return <p>No readings in the last seven days.</p>
  }

  return (
    <div className="sensor-grid-wrap">
      <table className="sensor-grid" aria-label="Latest temperature by cable and depth">
        <thead>
          <tr>
            <th scope="col">
              <span className="visually-hidden">Depth</span>
            </th>
            {Array.from({ length: cables }, (_, cable) => (
              <th key={cable} scope="col">
                Cable {cable + 1}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: depths }, (_, depth) => (
            <tr key={depth}>
              <th scope="row">{depth === 0 ? 'Top' : `Depth ${depth + 1}`}</th>
              {Array.from({ length: cables }, (_, cable) => {
                const sensor = byPosition.get(`${cable}:${depth}`)
                const isSelected = selected?.cable === cable && selected?.depth === depth
                return (
                  <td key={cable}>
                    <Cell
                      sensor={sensor}
                      position={{ cable, depth }}
                      maxTemperatureC={maxTemperatureC}
                      fill={sensor ? steps[heatStep(sensor.temperatureC, maxTemperatureC)] : null}
                      selected={isSelected}
                      onSelect={onSelect}
                      now={now}
                    />
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <HeatLegend steps={steps} maxTemperatureC={maxTemperatureC} />
    </div>
  )
}

function Cell({
  sensor,
  position,
  maxTemperatureC,
  fill,
  selected,
  onSelect,
  now,
}: {
  sensor: SensorValue | undefined
  position: Position
  maxTemperatureC: number
  fill: string | null
  selected: boolean
  onSelect: (position: Position) => void
  now: number
}) {
  const name = sensorName(position.cable, position.depth)

  if (!sensor || fill === null) {
    return (
      <button
        type="button"
        className={`cell cell-empty${selected ? ' cell-selected' : ''}`}
        aria-pressed={selected}
        aria-label={`${name}: no reading in the last seven days`}
        title={`${name}: no reading in the last seven days`}
        onClick={() => onSelect(position)}
      >
        —
      </button>
    )
  }

  const tooHot = sensor.temperatureC > maxTemperatureC
  const moisture = sensor.moisturePct === null ? '' : `, moisture ${formatNumber(sensor.moisturePct)} %`
  const description = `${name}: ${formatNumber(sensor.temperatureC)} °C${moisture}${
    tooHot ? ', above the temperature limit' : ''
  }, ${formatAge(sensor.recordedAt, now)}`

  return (
    <button
      type="button"
      className={`cell${tooHot ? ' cell-hot' : ''}${selected ? ' cell-selected' : ''}`}
      style={{ background: fill, color: inkFor(fill) }}
      aria-pressed={selected}
      aria-label={description}
      title={description}
      onClick={() => onSelect(position)}
    >
      {tooHot && (
        <span className="cell-flag" aria-hidden="true">
          ▲{' '}
        </span>
      )}
      {formatNumber(sensor.temperatureC)}°
    </button>
  )
}

/** The scale, as swatches from the start of the span to the threshold. */
function HeatLegend({ steps, maxTemperatureC }: { steps: readonly string[]; maxTemperatureC: number }) {
  return (
    <div className="heat-legend">
      <span>{formatNumber(maxTemperatureC - HEAT_SPAN_C)} °C or less</span>
      <span className="heat-legend-swatches" aria-hidden="true">
        {steps.map((step) => (
          <span key={step} style={{ background: step }} />
        ))}
      </span>
      <span>{formatNumber(maxTemperatureC)} °C limit</span>
      <span className="heat-legend-flag">▲ above the limit</span>
    </div>
  )
}
