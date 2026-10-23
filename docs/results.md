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
then holds for 5 minutes; the figures are for the hold.

The figures are from **three consecutive sets of four runs** (sets 3-5,
13:20-15:01 local time), given as the range across the sets. Earlier sets
exposed two problems in the procedure (see "What changed between sets" below)
and are not used. The full summaries, every metric included, are in
[`load-tests/k6/results/2026-10-23/`](../load-tests/k6/results/2026-10-23/).

| Run | API container | Offered | Achieved (hold) | Readings/s | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|---|---|---|
| B | **0.5 vCPU, 1 GB** (the Fargate task) | 1,000 devices / 5 s = 200 req/s | **200 req/s** | 4,557-4,559 | 4.6-4.7 ms | 6.3-26.1 ms | 12.6-166 ms | 0 |
| C | **0.5 vCPU, 1 GB** | 2,000 devices / 2 s = 1,000 req/s | **400-407 req/s** (the ceiling) | 9,118-9,268 | 4.8-4.9 s | 5.9-6.3 s | 6.7-7.9 s | 0 |
| A | unlimited | 2,000 devices / 2 s = 1,000 req/s | **922-942 req/s** | 21,029-21,466 | 18-29 ms | 2.2-3.3 s | 6.1-6.2 s | 0 |
| A2 | unlimited, rate-of-rise check hourly | the same | 917-936 req/s | 20,922-21,348 | 21-71 ms | 2.6-3.3 s | 6.1-6.6 s | 0 |

**Correctness held in every one of the 12 runs, including far past
saturation.** Across them: 2,846,328 requests and 140,640 resent batches,
every resend detected as duplicates. And 64,936,512 unique readings were sent,
of which **64,936,512 are in the database: every one stored exactly once.**
64,936,440 were acknowledged; the other 72, three batches in the ceiling runs,
were stored, but their responses came after k6's 10-second timeout.

**What the runs show:**

- **The Fargate-sized task tops out at about 400 requests a second (9,200
  readings a second).** The container sits at its 0.5-CPU limit while Postgres
  has cores to spare. Past the ceiling, requests queue: in run C each waited
  233-238 ms on average for a database connection, out of a pool of 10. Latency
  climbs to seconds and devices fall behind their cadence, but nothing fails
  and nothing is miscounted.
- **At 200 requests a second the same task is comfortable,** with a median of
  4.6 ms. The tail varies from set to set (p99 12.6-166 ms): the load generator
  and both containers share one desktop. This is the steady state the AWS
  deployment is compared against.
- **Unlimited, at 1,000 requests a second, this desktop's limit is the local
  Postgres's disk housekeeping, not the API.** Latency is low between stalls of
  20-60 seconds, and the stalls line up with checkpoints. At 21,000 readings a
  second, the stock image's 1 GB `max_wal_size` fills in about a minute, so
  Postgres checkpoints back to back instead of every 5 minutes. During these
  sets it started 48 WAL-triggered checkpoints and logged "checkpoints are
  occurring too frequently" 8 times. A2, which moves the rate-of-rise check to
  hourly so that it never runs, is no better than A. So the check isn't the
  cause.
- **The rate-of-rise check holds up at these sizes,** but its cost grows with
  the bins. Over 2,003 bins it took 1.5-1.6 seconds at 200 requests a second,
  7.0-7.2 seconds unlimited at 1,000, and 15-16 seconds on the 0.5-CPU task
  while it was saturated. It judges bins one by one, so its cost is linear in
  the number of bins (enhancement E17).

**What changed between sets.** Five sets were run in all. The first two
exposed two problems, and they aren't used:

- **Lost responses broke the stored-count check.** In set 2, 269 requests
  outlasted k6's 10-second timeout. The server stored all of them, so the
  stored count exceeded the acknowledged count by exactly those 260 fresh
  batches. Nothing was stored twice, but the check, which required stored to
  equal accepted, called it a failure. The check now requires every *unique*
  reading sent to be stored exactly once, with stored between accepted and
  unique-sent. It was tested by forcing every response to time out (sets 3-5
  use it).
- **Deleting a run's readings bloated the indexes.** Clearing millions of rows
  between runs left the empty October partition with a 1,564 MB primary-key
  index, far beyond the 128 MB `shared_buffers`. Real ingest only appends, so
  this was an artifact of the procedure. The cleanup now rebuilds the indexes
  (`REINDEX TABLE readings`) as well as vacuuming (sets 4-5).
- **Not explained:** set 1, and short pilot runs before it, were faster: a
  ceiling of 486-490 requests a second, and a p99 of 5.3-5.6 ms at 200. Sets
  2-5 never returned there, even once the indexes were rebuilt, so bloat wasn't
  the cause. Set 1 ran with the PC unattended and its screen off. From set 2
  the PC was in use, with the display held on to stop it sleeping, and k6
  shared its CPU with everything else. That is the most likely difference,
  but it was not tested. On this desktop, figures from separate sessions can
  differ by 20%.

**Environment:**

- **Machine:** AMD Ryzen 5 7600 (6 cores, 12 threads) with 31 GB of RAM, on
  Windows 11 Pro. Kept awake for the runs: sleep and display-off were blocked
  through `SetThreadExecutionState`.
- **Containers:** Rancher Desktop with dockerd, in a WSL 2 VM with 12 CPUs and
  16 GB.
- **API:** the production image from commit `fa21520` (Temurin 21.0.12.1, JRE
  on Alpine), run through `docker compose --profile api`. Tomcat's and Hikari's
  defaults: 200 threads, and a pool of 10 connections.
- **Database:** PostgreSQL 16.15 (`postgres:16-alpine`), with the stock
  configuration: `shared_buffers` 128 MB, `max_wal_size` 1 GB.
- **Load generator:** k6 v2.2.0, on the same machine. It competes with the
  containers for CPU, and 2,000 virtual users take about 4.5 GB of memory.
- **Starting state of each run:** the previous run's bins deleted, then
  `VACUUM ANALYZE` and `REINDEX TABLE readings`, leaving 3 bins and 69,168
  readings, with 4 MB of readings indexes.

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
   database. That total must equal the unique readings sent: no fewer than the
   acknowledged ones, and no more than the unique ones. A request whose
   response never arrived, such as one that outlasts the 10-second timeout,
   may or may not have been stored, so the summary shows how many responses
   were lost and whether those readings were stored.

**Locally, at the Fargate task's size.** After the API container starts, it is
limited to the ECS task's CPU and memory:
`docker update --cpus 0.5 --memory 1g --memory-swap 1g grain-bin-monitoring-api-1`.

**Between runs,** the previous run's bins, devices and readings are deleted,
then `VACUUM (ANALYZE)` and `REINDEX TABLE readings` are run, so every run starts
from the same small database with compact indexes.

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
