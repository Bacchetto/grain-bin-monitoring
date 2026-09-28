import type { AlertType } from '../api/types'
import { ALERT_LABELS } from '../format'

/**
 * A bin's worst open alert, or "OK". Colour signals severity, but the text
 * always says what is wrong too: a badge that relied on colour alone would be
 * unreadable to a colour-blind user.
 */
export function StatusBadge({ worst, count }: { worst: AlertType | null; count: number }) {
  if (worst === null) {
    return <span className="badge badge-ok">OK</span>
  }
  const { label, severity } = ALERT_LABELS[worst]
  return (
    <span className={`badge badge-${severity}`}>
      {label}
      {count > 1 && <span className="badge-more"> +{count - 1} more</span>}
    </span>
  )
}
