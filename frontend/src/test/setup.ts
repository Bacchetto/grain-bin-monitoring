import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// jsdom does not lay anything out, so it has no ResizeObserver. Recharts'
// ResponsiveContainer needs one to exist; it then falls back to the
// container's initialDimension, which is enough to render a chart in a test.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

// Unmount whatever each test rendered, and undo any stubbed globals such as
// fetch, so no test can see another's leftovers.
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})
