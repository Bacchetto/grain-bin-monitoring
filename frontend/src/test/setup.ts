import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// Unmount whatever each test rendered, and undo any stubbed globals such as
// fetch, so no test can see another's leftovers.
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})
