import { describe, expect, it } from 'vitest'
import { contrastRatio, HEAT_SPAN_C, HEAT_STEPS_DARK, HEAT_STEPS_LIGHT, heatStep, inkFor } from './heat'

describe('heatStep', () => {
  const LIMIT = 20

  it('spans the twenty degrees below the limit', () => {
    expect(heatStep(LIMIT - HEAT_SPAN_C, LIMIT)).toBe(0)
    expect(heatStep(LIMIT - 0.1, LIMIT)).toBe(HEAT_STEPS_LIGHT.length - 1)
  })

  it('clamps: colder than the span is the first step, at or over the limit the last', () => {
    expect(heatStep(-40, LIMIT)).toBe(0)
    expect(heatStep(LIMIT, LIMIT)).toBe(HEAT_STEPS_LIGHT.length - 1)
    expect(heatStep(85, LIMIT)).toBe(HEAT_STEPS_LIGHT.length - 1)
  })

  it('never goes down as the temperature goes up', () => {
    let previous = -1
    for (let t = -10; t <= 30; t += 0.1) {
      const step = heatStep(t, LIMIT)
      expect(step).toBeGreaterThanOrEqual(previous)
      previous = step
    }
  })

  it('follows the bin: the same reading is darker in a bin with a lower limit', () => {
    expect(heatStep(15, 16)).toBeGreaterThan(heatStep(15, 25))
  })
})

describe('inkFor', () => {
  it.each([...HEAT_STEPS_LIGHT, ...HEAT_STEPS_DARK])('text on %s clears 4.5:1', (fill) => {
    // WCAG AA for normal text. The cell values are the grid's table view, so
    // they have to be readable on every step.
    expect(contrastRatio(inkFor(fill), fill)).toBeGreaterThanOrEqual(4.5)
  })

  it('puts dark ink on the pale end and white on the dark end', () => {
    expect(inkFor(HEAT_STEPS_LIGHT[0])).toBe('#0b0b0b')
    expect(inkFor(HEAT_STEPS_LIGHT[HEAT_STEPS_LIGHT.length - 1])).toBe('#ffffff')
  })
})
