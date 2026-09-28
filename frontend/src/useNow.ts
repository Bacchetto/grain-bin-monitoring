import { useEffect, useState } from 'react'

/**
 * The current time, refreshed every `intervalMs`, so text like "3 min ago"
 * keeps counting.
 *
 * Refetching the data is not enough on its own. When the server's answer is
 * unchanged, TanStack Query keeps the previous object -- "structural sharing"
 * -- and nothing re-renders, so an age computed at render time would freeze.
 */
export function useNow(intervalMs = 15_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs)
    // The cleanup function: runs when the component goes away, so the timer
    // does not keep firing for a page no longer on screen.
    return () => clearInterval(id)
  }, [intervalMs])
  return now
}
