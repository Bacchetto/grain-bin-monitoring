// Types that mirror the backend's response records, field for field.
//
// TypeScript types exist only at compile time: nothing here checks, at run
// time, that the server really sent this shape. They are a contract written
// down twice -- once in Java, once here -- and kept in step by hand. Each type
// names the Java record it mirrors, so a change on one side points to the
// other.
//
// Two conventions, from how the backend serialises JSON:
//   - Instants arrive as ISO-8601 strings ("2026-09-28T15:38:00Z"), not Date
//     objects. They are typed as `string` so nothing pretends otherwise; the
//     code converts with `new Date(...)` or `Date.parse(...)` where it needs to.
//   - BigDecimal values arrive as JSON numbers, so they are `number` here.
//
// `T | null` marks a field the backend may send as null. With TypeScript's
// strict mode on, the compiler then refuses code that uses such a field
// without checking for null first.

/** bins.BinSummaryResponse -- one row of GET /bins. */
export interface BinSummary {
  id: number
  name: string
  site: string
  grainType: string
  /** Null for a bin whose devices have never reported. */
  lastReadingAt: string | null
  /** The most serious open or acknowledged alert, or null if there is none. */
  worstOpenAlert: AlertType | null
  openAlertCount: number
}

/** bins.BinThresholds */
export interface BinThresholds {
  maxTemperatureC: number
  maxMoisturePct: number
  riseThresholdC: number
  riseWindowHours: number
}

/** bins.BinDetailResponse -- GET /bins/{id}. */
export interface BinDetail {
  id: number
  name: string
  site: string
  grainType: string
  capacityBushels: number | null
  thresholds: BinThresholds
  createdAt: string
}

/** readings.LatestReadingsResponse.SensorValue */
export interface SensorValue {
  cable: number
  depth: number
  temperatureC: number
  moisturePct: number | null
  recordedAt: string
}

/** readings.LatestReadingsResponse -- GET /bins/{id}/latest. */
export interface LatestReadings {
  binId: number
  since: string
  sensors: SensorValue[]
}

/** readings.ReadingHistoryResponse.Stats */
export interface Stats {
  avg: number
  min: number
  max: number
}

/** readings.ReadingHistoryResponse.Point -- one time bucket. */
export interface HistoryPoint {
  bucketStart: string
  readings: number
  temperatureC: Stats
  /** Null when no reading in the bucket had a moisture value. */
  moisturePct: Stats | null
}

/** readings.ReadingHistoryResponse.Series -- one sensor's buckets. */
export interface HistorySeries {
  cable: number
  depth: number
  points: HistoryPoint[]
}

/** readings.ReadingHistoryResponse -- GET /bins/{id}/readings. */
export interface ReadingHistory {
  binId: number
  from: string
  to: string
  bucket: Bucket
  series: HistorySeries[]
}

export type Bucket = 'hour' | 'day'

// A union of string literals: a value of this type can only be one of these
// four strings, and a typo is a compile error rather than a silent mismatch.
// alerts.AlertType
export type AlertType = 'HIGH_TEMPERATURE' | 'HIGH_MOISTURE' | 'RATE_OF_RISE' | 'DEVICE_OFFLINE'

/** alerts.AlertStatus */
export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'

/** alerts.AlertResponse -- GET /alerts, POST /alerts/{id}/acknowledge. */
export interface Alert {
  id: number
  binId: number
  binName: string
  type: AlertType
  status: AlertStatus
  /** Sensor alerts only. */
  cableIndex: number | null
  depthIndex: number | null
  /** DEVICE_OFFLINE only. */
  deviceId: number | null
  /** DEVICE_OFFLINE only; null if the device has never reported. */
  deviceLastSeenAt: string | null
  /** Sensor alerts only. */
  triggerValue: number | null
  thresholdValue: number | null
  firstDetectedAt: string
  lastDetectedAt: string
  acknowledgedAt: string | null
  resolvedAt: string | null
}

/**
 * An RFC 9457 Problem Details body, which every API error uses. `errors` is
 * this API's extension for validation failures (common.ApiExceptionHandler).
 */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  errors?: { field: string; message: string }[]
}
