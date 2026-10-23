# Ingest load test

[`ingest.js`](ingest.js) is a [k6](https://k6.io) test of `POST /api/v1/readings`.
Each virtual user is one device. The devices ramp up, hold steady, and post
a batch every few seconds, with a known share of batches deliberately sent
twice. The results, and how they were measured, are in
[`docs/results.md`](../../docs/results.md).

## Running it

You need k6 2.x (`winget install GrafanaLabs.k6`, or `brew install k6`) and
an admin token. The script registers its own bins and devices, one per
virtual user, so it never touches existing data.

**Locally**, against the production image:

```powershell
docker compose --profile api up -d --build --wait db api
$env:ADMIN_TOKEN = (Select-String -Path .env -Pattern '^ADMIN_TOKEN=(.+)').Matches[0].Groups[1].Value
k6 run -e DEVICES=1000 -e INTERVAL=5 load-tests/k6/ingest.js
```

**Against AWS:** the same command, with `-e BASE_URL=https://<distribution>.cloudfront.net`
and the token from Secrets Manager (see `infra/terraform/README.md`).

**At the Fargate task's size, locally:** limit the API container to what the
ECS task gets, then run as above.

```powershell
docker update --cpus 0.5 --memory 1g --memory-swap 1g grain-bin-monitoring-api-1
```

## Settings

All of them are optional, and are given with `-e NAME=value`.

| Setting | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | The API, or the CloudFront URL |
| `ADMIN_TOKEN` | (required) | To register the run's bins and devices |
| `DEVICES` | 50 | Peak number of devices (virtual users) |
| `INTERVAL` | 5 | Seconds between one device's batches |
| `CABLES`, `DEPTHS` | 4, 6 | Sensors per batch: `CABLES` × `DEPTHS`, so 24 readings |
| `DUP_RATE` | 0.05 | Share of batches that resend the device's previous batch |
| `RAMP`, `HOLD` | 2m, 5m | Stage lengths. A 30-second ramp-down follows |
| `TIMEOUT` | 10s | How long k6 waits for a response before counting it as an error |
| `LABEL` | local | Recorded in the summary |
| `SUMMARY_FILE` | (none) | Also writes the full summary, every metric, as JSON |

The offered load is `DEVICES / INTERVAL` requests a second, times 24 readings
each.

## Reading the output

```
ingest load test: local-b-fargate-size-normal, 1000 devices every 5s, 24 readings a batch
  hold (5m):  200.0 requests/s, 4559 readings/s accepted
  latency (hold):  p50 4.6 ms, p95 6.3 ms, p99 12.6 ms, max 86.1 ms
  requests:        74983, errors 0.00%, fell behind 0
  readings:        1799592 sent, 1710744 accepted, 88848 duplicate, 0 rejected
  correctness:     accounting 100.00%, 3702 resent batches detected 100.00%
  stored:          1710744 readings in the database for this run's bins, of 1710744 unique readings sent: every one stored exactly once
```

- **Hold** numbers cover only the steady stage, so the ramp doesn't dilute them.
  Readings per second counts accepted readings, not duplicates.
- **Fell behind** counts requests that took longer than the interval. Each
  device waits out the rest of its interval after a response, so when the API
  saturates, devices fall behind and the offered load drops. A non-zero count
  means the run found the ceiling, and the hold rate *is* that ceiling.
- **Correctness** is pass/fail, and a failure makes k6 exit non-zero. Every
  response must account for every reading (`accepted + duplicates + rejected`
  equals readings sent, and a fresh batch is accepted whole), and every resent
  batch must come back as all duplicates.
- **Stored** is counted after the run from the readings API, so it comes from
  the database, not from the API's responses. Every unique reading sent must be
  stored exactly once: never more than the unique readings sent (nothing stored
  twice), and never fewer than the accepted ones (nothing acknowledged and
  lost). A request whose response never arrived, such as one past `TIMEOUT`,
  may or may not have been stored, so the line says how many responses were
  lost and whether those readings were stored.

## Notes

- **Test data stays.** The run's bins are named `k6 <run id> #n` on site
  `load test`. There is no delete endpoint yet (enhancement E8). Locally,
  `docker compose down -v` clears everything. To keep other data, delete the
  run's bins, devices, alerts and readings by that name and site, then run
  `VACUUM (ANALYZE)` and **`REINDEX TABLE readings`**. A deleted run leaves the
  readings indexes at full size, and the next run is slower for it: one
  afternoon's runs grew the empty partition's primary key to 1.5 GB.
- **The run's devices report a 24-hour interval.** That keeps them from
  opening a `DEVICE_OFFLINE` alert each when the run ends. The scheduled
  checks still evaluate every one of them.
- **k6 needs about 2 MB of memory per virtual user,** so 2,000 devices take
  around 4.5 GB on the machine running k6. For more load with fewer devices,
  shorten `INTERVAL`.
