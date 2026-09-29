# Results

> Numbers land here as the milestones that produce them are completed. Every
> figure carries the date and the environment it was measured in, because a
> number without those is not quotable. Still to come: the load tests
> (Milestone 4).

## Container image size

Target: under 250 MB (README).

| Date | Base image | Uncompressed | Compressed | Notes |
|---|---|---|---|---|
| 2026-09-29 | `eclipse-temurin:21.0.12_8-jre-alpine` | **239.6 MB** | 100.7 MB | First production build, `backend/Dockerfile` |

**How it was measured.** *Uncompressed* is the sum of every layer after
decompression, from `docker save` -- the size the image occupies once
unpacked, and the figure the target refers to. *Compressed* is what a
registry stores and what each ECS task downloads.

Beware the number `docker image ls` prints under Rancher Desktop: it showed
**344 MB**, because its image store reports the compressed content and the
unpacked copy added together.

**Where the size goes.** The Temurin JRE is 165 MB and Alpine's packages and
base another 47 MB. The application adds 31 MB: 29.6 MB of dependency jars,
0.7 MB of Spring Boot's loader, and **0.6 MB of the project's own code** -- the
only layer a normal code change rebuilds, so a deploy ships under a megabyte of
new image.

The margin under 250 MB is small and almost all of it is the JRE. A runtime
trimmed with `jlink` to the modules the application uses would roughly halve
the image; see enhancement E16.

## Load test: local

*(Milestone 4.)* From `load-tests/k6/ingest.js`.

| Metric | Value |
|---|---|
| Requests/sec | — |
| Readings/sec | — |
| p95 latency | — |
| p99 latency | — |
| Error rate | — |
| Duplicate detection correct | — |

Environment: —
Date: —

## Load test: AWS

*(Milestone 4.)* Same script against the deployed ALB.

| Metric | Value |
|---|---|
| Requests/sec | — |
| Readings/sec | — |
| p95 latency | — |
| p99 latency | — |
| Error rate | — |
| Duplicate detection correct | — |

Environment (instance sizes, task count): —
Date: —

## Methodology

*(Milestone 4.)* How each run was performed, so that anyone -- including a
author, months later -- can reproduce it exactly.

## Query plans

Measured 2026-09-24 on PostgreSQL 16 with 6.2 million readings across four
monthly partitions. Single warm runs, for plan shape and order of magnitude --
not load-test figures. Full method, plans and discussion:
[ADR 0007](decisions/0007-read-path-indexes-verified-with-explain-analyze.md).

| Query | Index used | Rows read | Time |
|---|---|---|---|
| `latest`, 7-day lookback | `readings_bin_recorded_idx` | 48,384 | 29 ms |
| `readings`, hourly, 24 h | `readings_bin_recorded_idx` | 6,912 | 6 ms |
| `readings`, daily, 30 days | `readings_bin_recorded_idx` | 207,360 | 93 ms |
| `RATE_OF_RISE` daily averages, one bin, 72 h window *(Milestone 2)* | `readings_bin_recorded_idx`, twice (`BitmapOr`) | 13,824 | 12 ms |

The index originally built for `latest` was never used by it and added about 70%
to insert time (1,655 ms vs 971 ms for 345,600 rows), so `V5` dropped it.

### Rate-of-rise evaluation

Measured 2026-09-28 with the same method and data shape as above: 6.2 million
readings, 10 bins with one device each, 24 sensors every 5 minutes for 90 days.
The query is `RateOfRiseEvaluator`'s own, for one bin, with the default 72-hour
window.

- **Two index ranges, as designed.** A `BitmapOr` combines two bitmap index
  scans of `readings_bin_recorded_idx`, one per 24-hour window, 6,912 rows
  each. The rows read depend on the two days being averaged, not on the window
  length, so a one-year window would cost the same.
- **Partition pruning.** 7 of the 8 partitions were removed. Both windows fell
  in the current month.
- **11.6 ms** to execute, 6.5 ms to plan, all from shared buffers. A run judges
  every bin, so at this size a full run costs about ten times this: well
  under the five-minute interval.
- **The daily cycle cancels.** The generated data carries the same ±1.5 °C daily
  swing near the top of the bin as the simulator's `normal` scenario. Every one
  of the 24 sensors came out with a rise of exactly 0.000, which is the reason
  for using daily averages.
