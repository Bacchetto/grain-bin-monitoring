// The heatmap's colour scale: temperature to a step of one sequential ramp.
//
// Sequential, one hue, because temperature is a magnitude: darker means more.
// Not red, even though "hot" suggests it -- red is reserved for the critical
// status (above the bin's threshold), and a heatmap already half red would
// drown the one cell that matters. That cell is marked separately, with an
// icon and a label, so colour is never the only signal.
//
// The steps are the reference palette's blue ramp (dataviz skill,
// palette.md), 100 to 700. In dark mode the order flips so that "low" fades
// toward the dark background rather than glowing: same ramp, its own steps.

export const HEAT_STEPS_LIGHT = ['#cde2fb', '#9ec5f4', '#6da7ec', '#3987e5', '#256abf', '#184f95', '#0d366b']
export const HEAT_STEPS_DARK = [...HEAT_STEPS_LIGHT].reverse()

/**
 * How many degrees below the bin's threshold the scale starts. A fixed span,
 * not the coldest-to-hottest range of the current readings: with a range that
 * followed the data, an evenly cool bin would be painted dark in its
 * warmest corner, and colours would not mean the same thing from one bin, or
 * one minute, to the next.
 */
export const HEAT_SPAN_C = 20

/**
 * Which step of the ramp a temperature falls in: 0 (coldest) to 6. Readings
 * at or above the threshold take the last step; anything colder than the
 * span takes the first.
 */
export function heatStep(temperatureC: number, thresholdC: number): number {
  const steps = HEAT_STEPS_LIGHT.length
  const fraction = (temperatureC - (thresholdC - HEAT_SPAN_C)) / HEAT_SPAN_C
  return Math.min(steps - 1, Math.max(0, Math.floor(fraction * steps)))
}

/** The ink that reads best on a fill: near-black or white, by WCAG contrast. */
export function inkFor(fillHex: string): '#0b0b0b' | '#ffffff' {
  const fill = luminance(fillHex)
  const onDark = contrast(fill, luminance('#ffffff'))
  const onLight = contrast(fill, luminance('#0b0b0b'))
  return onDark >= onLight ? '#ffffff' : '#0b0b0b'
}

// WCAG 2 relative luminance and contrast ratio.
function luminance(hex: string): number {
  const channel = (i: number) => {
    const c = parseInt(hex.slice(1 + 2 * i, 3 + 2 * i), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2)
}

function contrast(a: number, b: number): number {
  const [light, dark] = a > b ? [a, b] : [b, a]
  return (light + 0.05) / (dark + 0.05)
}

export function contrastRatio(foreground: string, background: string): number {
  return contrast(luminance(foreground), luminance(background))
}
