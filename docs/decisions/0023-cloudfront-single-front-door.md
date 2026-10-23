# 0023 - CloudFront as the single front door, with an internal load balancer

**Status:** Accepted, 2026-09-29 (recorded 2026-10-23)
**Applies to:** `infra/terraform/frontend.tf`, `alb.tf`, `spa-rewrite.js`; resolves enhancement E15

## Context

The spec places the dashboard in a private S3 bucket behind CloudFront, and the
API behind an Application Load Balancer that serves HTTPS only if a domain and
certificate are provided. There is no domain. Taken literally, that gives two
public addresses: an HTTPS dashboard on `*.cloudfront.net`, and an HTTP API on
the load balancer's own name. Three problems follow:

- **Mixed content.** Browsers block an HTTPS page from calling an HTTP API. The
  dashboard could not reach its own API at all.
- **Everything on the load balancer is public.** `/actuator/prometheus` needs no
  credentials, because Prometheus has none to present. On a public load
  balancer it would publish request rates, error counts and alert activity to
  anyone (enhancement E15).
- **Single-page app routes.** `/bins/3` is a route inside `index.html`, not a
  file in S3. The usual fix is a CloudFront custom error response that turns
  every 403 or 404 into `index.html`. But those responses apply to the whole
  distribution, so every API 404 or 409 Problem Details would come back as the
  dashboard's HTML.

## Decision

**One CloudFront distribution, one HTTPS address, two behaviours:**

- `/api/*` goes to the load balancer, **never cached**, with every viewer header
  forwarded (the managed *AllViewer* request policy), so `Authorization` and
  `X-Device-Key` reach the API. Viewers must use HTTPS for it (`https-only`, so
  a plain-HTTP `POST` is refused rather than silently redirected).
- Everything else goes to the S3 bucket through Origin Access Control, cached,
  and redirected to HTTPS.

**The load balancer is internal.** It has no public address and sits in the
private subnets. CloudFront reaches it through a **VPC origin**: a network
interface CloudFront places in those subnets, connecting over AWS's network
rather than the internet. Its security group admits only CloudFront's managed
origin-facing prefix list, and its listener forwards only `/api/*`. Anything
else gets a fixed 404 from the load balancer itself.

**A CloudFront Function on the dashboard behaviour only** rewrites any path
whose last segment has no file extension to `/index.html`. Requests for real
files (`/assets/index-abc123.js`) pass through, and `/api/*` never sees the
function, so API errors keep their Problem Details.

## Alternatives

**A public load balancer with a custom domain and an ACM certificate.** Fixes
mixed content, but needs a domain, keeps the load balancer public (E15 would
need its own fix), and leaves two origins, so production needs CORS.

**CloudFront in front of a public load balancer over HTTP**, locked to
CloudFront's prefix list. One address, but the CloudFront-to-load-balancer leg
crosses the public internet unencrypted. And the prefix list admits *every*
CloudFront distribution, anyone's, so it would also need a secret header that
the load balancer checks.

**Custom error responses for SPA routing.** Simpler than a function, but they
rewrite API errors too, as above.

**Serve the dashboard from the API itself.** One origin with no CloudFront
tricks, but static files would compete with ingest for the same task, and the
dashboard could not be cached at the edge.

## Consequences

- **Same origin:** the dashboard calls `/api/v1` on its own address, so
  production needs no CORS (`CORS_ALLOWED_ORIGINS` stays empty). The build sets
  `VITE_API_URL` to empty for this.
- **E15 is settled by the network rather than by credentials.** Nothing but
  `/api/*` reaches the API from the internet, and the load balancer's own
  health check reaches `/actuator/health` from inside the VPC. Verified on the
  first deploy: `/actuator/prometheus` through CloudFront returns the
  dashboard's HTML, and `/api/actuator/prometheus` gets the load balancer's 404.
- **Forwarding every header is safe only because `/api/*` is never cached.**
  With caching on, a response fetched with one user's token could be served to
  another. Anyone adding caching to an API path has to revisit this.
- The HTTP hop from CloudFront to the load balancer runs inside AWS's network,
  on the VPC origin connection.
- **VPC origins aren't available in every zone.** The network excludes
  `cac1-az3` for this reason.
- A distribution takes 5-15 minutes to create, change or delete, which makes up
  most of an apply or a destroy.
- Each new stack gets a new `*.cloudfront.net` address. A custom domain
  (`domain_name` and `acm_certificate_arn`) would make it stable.
