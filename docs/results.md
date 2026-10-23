# Results

> Numbers land here as the milestones that produce them are completed. Every
> figure carries the date and the environment it was measured in, because a
> number without those is not quotable. Still to come: the AWS load test
> (Milestone 4).

## Container image size

Target: under 250 MB (spec).

| Date | Base image | Uncompressed | Compressed | Notes |
|---|---|---|---|---|
| 2026-09-29 | `eclipse-temurin:21.0.12_8-jre-alpine` | **239.6 MB** | 100.7 MB | First production build, `backend/Dockerfile` |
| 2026-10-23 | `eclipse-temurin:21.0.12.1_1-jre-alpine` | **239.6 MB** | 100.7 MB | Base image bumped for the `libexpat` and `zlib` fixes (#20). No measurable change in size |

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

Measured 2026-10-23 with `load-tests/k6/ingest.js`. Each device posts one batch
of 24 readings (4 cables x 6 depths) every *interval* seconds, and 5% of
batches resend the device's previous batch. Each run ramps up over 2 minutes,
then holds for 5 minutes; the figures are for the hold. The full summaries,
every metric included, are in
[`load-tests/k6/results/`](../load-tests/k6/results/).

| Run | API container | Offered | Achieved (hold) | Readings/s | p50 | p95 | p99 | Errors | Fell behind |
|---|---|---|---|---|---|---|---|---|---|
| A | unlimited | 2,000 devices / 2 s = 1,000 req/s | **999.8 req/s** | 22,794 | 5.2 ms | 37.6 ms | 97.5 ms | 0 | 0 |
| A2 | unlimited, rate-of-rise check hourly | the same | 945.5 req/s | 21,548 | 14.4 ms | 2,190 ms | 5,711 ms | 0 | 14,424 |
| B | **0.5 vCPU, 1 GB** (the Fargate task) | 1,000 devices / 5 s = 200 req/s | **200.0 req/s** | 4,560 | 3.5 ms | 4.7 ms | 5.6 ms | 0 | 0 |
| C | **0.5 vCPU, 1 GB** | 2,000 devices / 2 s = 1,000 req/s | **485.7 req/s** (the ceiling) | 11,062 | 4,002 ms | 5,046 ms | 6,125 ms | 0 | 186,406 |

**Correctness held in every run, including past saturation.**

| Run | Resent batches | Detected as duplicates | Readings accepted | Readings stored (counted in the database afterwards) |
|---|---|---|---|---|
| A | 18,673 | 100% | 8,549,448 | 8,549,448 |
| A2 | 17,890 | 100% | 8,181,072 | 8,181,072 |
| B | 3,648 | 100% | 1,712,136 | 1,712,136 |
| C | 9,948 | 100% | 4,558,056 | 4,558,056 |

No reading was rejected, and none was stored twice: every response accounted
for every reading it was sent, and every run's stored count equals its
accepted count exactly.

**What the runs show:**

- **The Fargate-sized task tops out at about 490 requests a second (11,000
  readings a second), and it is CPU-bound.** The container sat at its 0.5-CPU
  limit while Postgres had cores to spare. Past the ceiling, requests queue,
  mostly waiting for a database connection: about 190 ms each, on average, in
  run C. Latency climbs to seconds and devices fall behind their cadence. But
  nothing failed, and nothing was miscounted. A pilot run gave the same ceiling
  (490 req/s), so the figure is stable.
- **At 200 requests a second the same task is idle:** p99 5.6 ms, against 5.3 ms
  in a pilot run. That is the steady state the AWS deployment should match
  before the network is added.
- **At 1,000 requests a second on this desktop, the limit is the local
  Postgres's disk housekeeping, not the API.** Run A2's tail latency comes from
  a single 60-second stall. Postgres's log shows it overlapping a 67-second
  checkpoint. Ingesting 22,000 readings a second fills the stock image's 1 GB
  `max_wal_size` in about a minute. So checkpoints run back to back instead of
  every 5 minutes, and Postgres logs "checkpoints are occurring too
  frequently". A2 also started right after 8.5 million rows were deleted from
  the previous run, which added a burst of WAL. Two runs of the same load gave
  p99s of 97.5 ms and 5.7 s. Run A's figures are therefore not a stable ceiling,
  and both runs are shown. A2 was meant to test whether the scheduled
  rate-of-rise check caused the tail. It was the run *without* that check that
  stalled, so the check didn't.
- **The rate-of-rise check's cost grows with the bins and with the readings in
  its windows.** It took 5.4 seconds over 2,003 bins in run A, about 2.7 ms a
  bin. In an earlier pass, whose bins had not been cleared between runs, it took
  up to 127 seconds. That pass had 8,500 to 11,500 bins and up to 19.5 million
  readings, so 11 to 15 ms a bin. At that rate, somewhere between 20,000 and
  27,000 busy bins, the check would no longer fit in its 5-minute interval. The
  advisory lock (ADR 0015) means overruns can't overlap, but checks would run
  back to back (enhancement E17).

**Environment:**

- **Machine:** AMD Ryzen 5 7600 (6 cores, 12 threads) with 31 GB of RAM, on
  Windows 11 Pro.
- **Containers:** Rancher Desktop with dockerd, in a WSL 2 VM with 12 CPUs and
  16 GB.
- **API:** the production image from commit `fa21520` (Temurin 21.0.12.1, JRE
  on Alpine), run through `docker compose --profile api`. Tomcat's and Hikari's
  defaults: 200 threads, and a pool of 10 connections.
- **Database:** PostgreSQL 16.15 (`postgres:16-alpine`), with the stock
  configuration: `shared_buffers` 128 MB, `max_wal_size` 1 GB.
- **Load generator:** k6 v2.2.0, on the same machine. It competes with the
  containers for CPU, and 2,000 virtual users take about 4.5 GB of memory.
- **Starting data:** before each run, the previous run's bins were deleted,
  followed by `VACUUM ANALYZE`, leaving 3 bins and 69,168 readings.

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

Enough to reproduce any run above. How to run the script, and what each
setting means, is in [`load-tests/k6/README.md`](../load-tests/k6/README.md).

**The load model.**
- **One virtual user is one device.** `setup()` registers one bin and one
  device per virtual user through the admin API, so every run has its own bins
  and devices, and existing data is untouched.
- **A device posts a batch, waits out the rest of its interval, and repeats.**
  That is a closed model: a slow response delays the device's next batch.
  Requests that took longer than the interval are counted ("fell behind"), so
  saturation shows up in the results rather than quietly lowering the load.
- **A run ramps from 0 to the peak over 2 minutes, holds for 5, then ramps down
  over 30 seconds.** The reported throughput and latency are for the hold only.
  Each device's first batch is delayed by a random part of one interval, so the
  devices don't post in lockstep.
- **A batch is one sample of 24 readings.** Temperatures and moisture stay below
  the default alert limits, so the load is ingest and alert evaluation rather
  than alert transitions.
- **Each device's `seq` is the send time in milliseconds,** and 5% of batches
  resend the device's previous batch, byte for byte.

**What counts as correct.** Each run checks three things. It fails if any
response or resend is miscounted, or if more than 1% of requests fail:
1. every response's `accepted + duplicates + rejected` equals the readings
   sent, and a fresh batch is accepted whole;
2. every resent batch comes back as all duplicates, with none accepted;
3. after the run, `teardown()` sums the run's readings per bin from
   `GET /bins/{id}/readings?bucket=day`, which counts the rows in the
   database, and that total must equal the accepted total.

**Locally, at the Fargate task's size.** After the API container starts, it is
limited to the ECS task's CPU and memory:
`docker update --cpus 0.5 --memory 1g --memory-swap 1g grain-bin-monitoring-api-1`.

**Server-side figures.** After each run, the API's own metrics
(`/actuator/prometheus`) supplied the connection-pool wait time
(`hikaricp_connections_acquire_seconds`) and the scheduled checks' durations
(`tasks_scheduled_execution_seconds`). The API container was recreated before
each run, so each run's figures are its own. Postgres's checkpoint lines come
from its container log (`log_checkpoints` is on by default).

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
