# 0004 - SHA-256 for device API keys, not bcrypt

**Status:** Accepted, 2026-09-23
**Applies to:** `devices.DeviceApiKey`, `common.Digests`, `devices.api_key_hash`

## Context

Each device authenticates with its own API key. The key is shown once at
registration and only a digest is stored, so a database disclosure does not
hand over the ability to write readings.

The reflex for "storing a credential" is a slow KDF -- bcrypt, scrypt, argon2.
That reflex is correct for passwords and wrong here, and the difference is
worth stating explicitly because it looks like a security shortcut and is not.

## Decision

Keys are 32 bytes from `SecureRandom`, Base64url-encoded, prefixed `gbk_`.
The stored value is a plain **SHA-256** hex digest.

## Alternatives

**bcrypt or argon2.** Rejected for two independent reasons.

*It does not buy anything here.* A KDF is slow so that guessing is
impractical against a credential that is guessable in principle: a password is
short, low-entropy, human-chosen and frequently reused. This key is none of
those. It is 256 bits of `SecureRandom` output, never chosen, never reused and
never typed from memory. There is no dictionary to run and no meaningful
search space to narrow. Slowing the hash defends against an attack that does
not apply.

*It would cost a great deal.* The key is verified on every ingest request,
which is the one path in this service expected to sustain load. A KDF tuned to
a conventional 100ms would cap throughput at roughly ten requests per second
per core, before any work is done. The load-testing figures this project
exists to produce would be measuring bcrypt.

**Storing keys encrypted rather than hashed.** Reversible, so it requires key
management, and buys nothing: the server never needs to recover the plaintext.

**HMAC with a server-side pepper.** Marginally better against an attacker with
the database but not the application config. Rejected as not worth the key
management for a credential that is already full-entropy random, but it is the
natural next step if that threat model ever matters.

## Consequences

- Verification is a single indexed lookup on `devices.api_key_hash`, which is
  what makes device authentication cheap enough to sit on the hot path.
- Because the digest is deterministic, the lookup is by equality on an indexed
  column rather than a scan-and-compare across rows. A KDF with a per-row salt
  could not do this: it would require fetching candidate rows and running the
  KDF against each.
- There is no timing-safe comparison in the device path and none is needed.
  The lookup is by digest, and an attacker learning that a given 256-bit random
  key does not exist has learned nothing. Timing safety matters for the admin
  token, which is one long-lived secret compared against a presented value, and
  that is handled in [ADR 0003](0003-filter-based-auth.md).
- A leaked key is recognisable as one because of the `gbk_` prefix, which is
  what secret-scanning tools match on. The prefix is constant and adds no
  entropy.

### The condition this rests on

**This reasoning holds only while keys are full-entropy and randomly
generated.** If keys ever become operator-chosen, shorter, derived from
something memorable, or reused across devices, then this decision becomes
wrong and a KDF becomes correct. That constraint is stated on
`DeviceApiKey` itself, next to the generator, rather than only here.

## See also

- [0003 - Servlet filters for authentication](0003-filter-based-auth.md)
