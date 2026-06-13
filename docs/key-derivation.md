# Password key derivation — why DocSafe uses Argon2id

This document explains how DocSafe turns your master password (and PIN) into an encryption key,
why **Argon2id** was chosen, and why that is the strongest, most-proven option available — not a
weaker substitute for an "older, more official" algorithm.

## Where the KDF fits

DocSafe never encrypts your documents directly with your password. It uses a two-layer key
scheme:

```
password ──Argon2id──▶ KEK (key-encryption key) ──unwraps──▶ DEK (random 256-bit data key)
                                                                  │
                                              AES-256-GCM ◀────────┘  encrypts the index + every blob
```

- The **DEK** is a random 256-bit key (full entropy). It encrypts the vault index and every
  attachment with AES-256-GCM.
- The **KEK** is derived from your password with Argon2id and only ever *wraps/unwraps* the DEK.

Consequently the KDF's job is narrow but critical: make **offline guessing of the password**
as expensive as possible for an attacker who has a copy of the encrypted vault file. The cipher
protecting the data (AES-256-GCM with a random key) is not weakened by a weak password — only
the password-guessing resistance is.

## Why Argon2id

Argon2id is the modern standard for password hashing / password-based key derivation:

- **Password Hashing Competition winner (2015).** Argon2 was selected through an open,
  multi-year public cryptographic competition created specifically to choose a successor to
  PBKDF2 / bcrypt / scrypt. Argon2id is the recommended hybrid variant.
- **Standardized in [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106) (IETF, 2021),** which
  explicitly recommends **Argon2id** as the default choice and publishes concrete parameter
  profiles.
- **OWASP's first recommendation** for password storage / key derivation.
- **Memory-hard.** Each guess must occupy a large, configurable amount of RAM. This is the
  property that defeats the real-world threat — attackers running the KDF on GPUs, FPGAs, and
  ASICs, which have enormous compute parallelism but little memory per core. Memory-hardness
  collapses that advantage.
- **No practical break** and very widely deployed.

A note on the word "proven": **no password KDF has a mathematical security *proof*.** Their
security is empirical — it rests on the strength of the underlying primitive (Argon2 uses
BLAKE2b) and on how costly the best known brute-force attack is. By the realistic bar that
applies to every algorithm in this category — *won an open competition + standardized by the
IETF + recommended by OWASP + battle-tested in the field* — Argon2id is as proven as it gets,
and is the current state of the art.

## Why not "switch to something more official" (PBKDF2)?

The algorithm with the longest formal/government track record is **PBKDF2**
([NIST SP 800-132](https://csrc.nist.gov/pubs/sp/800/132/final), FIPS-approved). It is well
studied — but for this use case it is **strictly weaker**, and moving to it would *reduce*
security:

- PBKDF2 is **not memory-hard** — it is only computation-hard. A GPU/ASIC attacker therefore
  gets a very large parallelism advantage that memory-hard functions deny them.
- Having **no constraint on encryption/decryption speed does not close this gap.** Increasing
  PBKDF2's iteration count scales the attacker's cost only *linearly*, and they parallelize
  cheaply on commodity hardware. Argon2id instead raises the cost where cheap attack hardware
  is weakest (memory).

So "more standardized" (PBKDF2) would trade a small compliance/paperwork benefit for a large
real-world loss in brute-force resistance. scrypt and bcrypt are reasonable memory-/cost-hard
alternatives, but Argon2id supersedes both and is the explicit modern recommendation.

## Parameters DocSafe uses

Argon2id cost parameters are tunable and are stored in cleartext in each vault's header, so any
device with the password can re-derive the key, and the cost can be raised over time without
breaking older vaults.

| Purpose | Memory | Passes (t) | Parallelism (p) | Basis |
| --- | --- | --- | --- | --- |
| **Master password** (vault import) | 64 MiB | 3 | 1 | RFC 9106 "memory-constrained" profile; well above the OWASP minimum |
| **PIN** (quick unlock) | 19 MiB | 2 | 1 | OWASP interactive minimum |

For comparison, the OWASP Argon2id minimum is m=19 MiB, t=2, p=1 — so the master-password
setting is already several times stronger than the floor.

Notes:
- The PIN path is **defense-in-depth, not the sole barrier**: the PIN-wrapped DEK is additionally
  stored in Android **Keystore-backed `EncryptedSharedPreferences`**, and unlocking is gated by
  the OS (biometric / device credential). A PIN is inherently low-entropy, which is why it is
  never the only thing standing between an attacker and the data.
- Because the parameters live in the header, they can be increased for new vaults at any time.
  Since unlock is a one-time, per-session cost, the memory/passes can be pushed higher (e.g.
  toward RFC 9106's high-memory profile) on devices with enough RAM; the practical ceiling is
  **device memory**, not speed.

## Implementation

- Argon2id is provided by **Bouncy Castle** (`bcprov-jdk18on`, a pure-Java implementation), so
  the security-critical code runs and is unit-tested on the host JVM with no device or native
  libraries required.
- Key separation uses **HKDF-SHA-256**; bulk encryption uses **AES-256-GCM** (authenticated).
- See [`core/crypto`](../core/crypto) for the implementation and tests
  (`Argon2idKdf`, `KdfParams`, `BlobCipher`, key envelope).

## References

- RFC 9106 — *Argon2 Memory-Hard Function for Password Hashing and Proof-of-Work Applications*:
  https://www.rfc-editor.org/rfc/rfc9106
- Password Hashing Competition (Argon2, 2015): https://www.password-hashing.net/
- OWASP Password Storage Cheat Sheet:
  https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- NIST SP 800-132 (PBKDF2): https://csrc.nist.gov/pubs/sp/800/132/final
