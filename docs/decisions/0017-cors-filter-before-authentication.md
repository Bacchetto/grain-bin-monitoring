# 0017 - A CORS filter ahead of the authentication filters

**Status:** Accepted, 2026-09-28
**Applies to:** `config.CorsConfig`, `config.ApiSecurityConfig`

## Context

The dashboard runs on a different origin from the API: `localhost:5173` against
`localhost:8080` locally, and CloudFront against the ALB in AWS. A browser will
not let a page read another origin's responses unless that server says it may,
which is what CORS headers do.

Every dashboard request carries an `Authorization` header, which makes it a
"non-simple" request. The browser first sends a **preflight**: an `OPTIONS`
request, without the token, asking whether the real request is allowed.
`AdminAuthFilter` answers any request without a token with 401.

Spring MVC's own CORS support (`addCorsMappings`, `@CrossOrigin`) runs inside
the `DispatcherServlet`, after every servlet filter. The auth filter would
reject the preflight before Spring's CORS handling ever saw it, and the browser
would never send the real request.

## Decision

Spring's `CorsFilter`, registered as a **servlet filter ordered before both
authentication filters**. It answers a preflight itself without passing it on,
and adds the CORS headers to every other response before the auth filters run.

- Origins come from `CORS_ALLOWED_ORIGINS`: **exact origins only**. A wildcard
  stops the application at startup, and an empty value allows none.
- `allowCredentials` is off. The token travels in a header the page sets, not
  in a cookie, so the browser never needs to attach cookies.
- Allowed: `GET`, `POST`, `PATCH`; the `Authorization` and `Content-Type`
  headers; `Location` exposed; preflight answers cached for an hour.

## Alternatives

**Let the auth filter pass `OPTIONS` through, and use Spring MVC's CORS.** It
works, but it teaches the security filter about CORS, and the second benefit
below is lost.

**Proxy the API through Vite in development.** It avoids CORS locally, but
production -- CloudFront and the ALB -- is cross-origin anyway, so the problem
would only be deferred.

**Spring Security's CORS support.** Handles this ordering for you, but this
project deliberately does not use Spring Security (ADR 0003).

## Consequences

- **A 401 carries CORS headers too.** Without them the browser hides the 401
  from the page, and the dashboard could not tell an expired token from a
  network failure -- the case its sign-out handling depends on.
- CORS is not a security boundary. It restricts browsers only; curl and devices
  ignore it. The admin token remains the boundary.
- Moving the filter after the auth filters fails exactly the three preflight
  and 401 tests -- checked by doing so.
