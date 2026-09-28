# 0018 - Dashboard stack, and a token held in memory only

**Status:** Accepted, 2026-09-28
**Applies to:** `frontend/`

## Context

The README fixes React, TypeScript, Vite and Recharts, with strict mode on, and
says the admin token must be kept in memory only, never in `localStorage`. It
leaves routing, data fetching, testing and linting open. The owner is newer to
TypeScript, so every library is another thing to explain.

## Decision

- **React Router** for pages, and **TanStack Query** for fetching, caching,
  polling and retries -- chosen by the owner over hand-rolled hash routing and
  plain `fetch`, for being what most React teams use.
- **Vitest** and **React Testing Library**, with `fetch` replaced by a fake that
  answers like the API. It is the browser's boundary, not the database, so the
  no-mocking rule for repositories does not apply.
- **ESLint**, not the Oxlint that create-vite 9 now defaults to, as the linter
  most teams expect.
- **The token lives in React state.** It is checked against the API before it
  is accepted, and a reload asks for it again.
- **`AuthProvider` owns the query cache.** Signing out, or a 401 from any
  request, empties it.
- **The bin detail page is loaded lazily**, so the chart library is fetched only
  when a bin is opened.

## Alternatives

**No routing or fetching library.** About thirty lines of hash routing and a
typed `fetch` wrapper: fewer dependencies, but hand-written caching, polling,
retries and cache invalidation.

**`localStorage` for the token.** Survives reloads, but anything in it is
readable by any script that ever runs on the page, and it outlives the tab.
Excluded by the README.

**Retry every failed request.** TanStack's default. Replaced: only network
failures and 5xx responses are retried, because a 4xx gets the same answer
every time -- a missing bin took seconds to report under the default.

## Consequences

- **Data never outlives the token that fetched it.** A test rotates the token
  on a fake server mid-session and checks the user is signed out, with the
  reason shown. Disabling the 401 handler fails exactly that test.
- A test asserts that `localStorage`, `sessionStorage` and cookies are all empty
  after sign-in.
- Types mirror the backend's records by hand; they are a compile-time contract,
  not a runtime check. The field names were compared against real responses.
- The chart library more than doubled the bundle. Lazy loading keeps the login
  screen and bin list at about 95 kB gzipped, with 110 kB more fetched for the
  detail page.
