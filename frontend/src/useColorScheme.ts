import { useEffect, useState } from 'react'

export type ColorScheme = 'light' | 'dark'

const QUERY = '(prefers-color-scheme: dark)'

/**
 * Whether the operating system is set to light or dark mode, updated live if
 * the user switches.
 *
 * The page's own colours switch in CSS (index.css). Charts and the heatmap
 * need the colours as values in JavaScript -- Recharts writes them into SVG
 * attributes, where CSS variables cannot be relied on -- so they read the
 * scheme from here and pick their own steps for it.
 */
export function useColorScheme(): ColorScheme {
  const [scheme, setScheme] = useState<ColorScheme>(() => current())

  useEffect(() => {
    // matchMedia is absent in some test environments; light is the default.
    const media = window.matchMedia?.(QUERY)
    if (!media) return
    const onChange = () => setScheme(current())
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])

  return scheme
}

function current(): ColorScheme {
  return window.matchMedia?.(QUERY).matches ? 'dark' : 'light'
}

/**
 * The chart colours, per scheme. The series blue and the critical red were
 * checked as a pair with the dataviz skill's validator against this app's own
 * surfaces (#ffffff light, #14171b dark): both clear every check, including
 * colour-blind separation, in both modes. Chrome greys are from the same
 * reference palette.
 */
export const CHART_COLORS = {
  light: { series: '#2a78d6', threshold: '#d03b3b', grid: '#e1e0d9', axis: '#c3c2b7', tick: '#5c6773' },
  dark: { series: '#3987e5', threshold: '#d03b3b', grid: '#2c2c2a', axis: '#383835', tick: '#9aa5b1' },
} as const
