**English** · [Русский](../ADR-001-SYSTEM-CONFIRMATION.md) · [Architecture](ARCHITECTURE.md)

# ADR 001: a safe handle for system confirmation

Date: **20 October 2026**. Status: **accepted and implemented; 1.2.2 results are recorded in Verification**.

## Problem

Package Installer returns a confirmation Intent that may contain a Binder and launch-protection tokens. On a Google APIs API 37 emulator, persisting it through `Parcel.marshall()` failed with `RuntimeException: Tried to marshall a Parcel that contains objects (binders or FDs).` A diagnostic run reproduced the failure; its local log is `.toolchain/qa122/api37/confirmation-diagnostic-error.txt`.

Parcel is an IPC mechanism. Android explicitly excludes its marshalled representation from supported persistent storage formats because its format can change between platform versions. See [Parcel.marshall()](https://developer.android.com/reference/android/os/Parcel#marshall()). A synthetic Intent without a Binder therefore does not establish that Parcel bytes are a suitable journal format.

The system confirmation, session correlation, and late-result handling must survive as far as the platform permits. Recovery must retain prepared files, avoid repeating commit, and preserve Android's launch protections.

## Decision

Create an **immutable activity PendingIntent** for the original Intent, with Android owning the retained action. Original extras, Binder objects, and launch-protection tokens stay in the system object. The app does not copy them into JSON, Base64, Parcel bytes, or a reconstructed Intent. Android describes PendingIntent as a reference to a system token and defines its identity through filtered Intent fields, request code, and flags; extras do not create a distinct identity. See [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent).

Persist only a bounded **v1 envelope**:

| Field | Purpose |
| --- | --- |
| Version `1` | Explicit format and compatibility check |
| `sessionId` | Package Installer session |
| Generation UUID | Distinguishes confirmations, including after operation replacement |
| `requestCode` | Part of PendingIntent identity |
| Filter Intent URI | Fields for locating the same PendingIntent; no extras |

The process keeps a bounded registry of strong PendingIntent references, retaining the handle during receiver-to-UI handoff. After process recreation, recovery only looks up the existing handle through `FLAG_NO_CREATE` with the same identity and `FLAG_IMMUTABLE`. It never creates a new confirmation Intent from the envelope. Availability of the system token after process death is not guaranteed: cold recovery is best effort.

The envelope requires confirmed `flush()` before the UI event. Immediately before a launch attempt, UI checks session/generation and sends only while lifecycle is at least `STARTED`. Sender-side `ActivityOptions` use `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` on API 36+ and `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on API 34/35 with the same lifecycle gate. ACK records a launch attempt; installation outcome arrives separately. The ACK mutation is conditional on the current session, generation, and confirmation state, so an event checked before suspension cannot overwrite a newer ledger state. This follows [secure Activity launch rules](https://developer.android.com/guide/components/activities/secure-bal). `removeLaunchSecurityProtection()` is not called.

Persist and confirm the final installation result first. Only then cancel the obsolete PendingIntent for that generation. A durable-write failure does not authorize early handle cancellation; another session or a newer generation remains untouched.

## Unknown outcomes and migration

A missing, cancelled, or invalid handle, and a legacy serialized-Intent record, cause recovery to return **UNKNOWN**. Old Parcel data is not decoded as a fallback. Retain the work directory, selected APKs, and session correlation so a late proven callback can finish its operation. UNKNOWN neither repeats commit, abandons a sealed Android session, nor removes files needed to establish the outcome.

## Alternatives and tradeoffs

- Persist Parcel bytes: excluded by Android's contract and the reproduced Binder failure.
- Strip extras, reconstruct the Intent, or remove launch protection: loses opaque data and system-confirmation safeguards.
- Keep only an in-memory Intent: process recreation loses the entire handle; the selected design can still locate an existing system token.
- Guarantee recovery after process death: the selected API provides no such guarantee. The tradeoff is explicit UNKNOWN when the token is unavailable rather than an unproven action.

## Verification

Tests must cover the v1 envelope and its limits, absence of extras/Parcel, session/generation correlation, stale callbacks, found/missing `NO_CREATE` handles, legacy records, the bounded registry, and durable finish → cancel ordering. A failed result write must retain late-result handling.

Dedicated API 34 and API 37 runs must cover real Binder-bearing confirmations, ordinary installation, disabled-app updates, Activity recreation, process loss, cancelled tokens, and late results. Background launches must respect lifecycle/BAL gates. Record the exact APK, fingerprint, process-termination method, journal, and actual Package Manager state for each outcome. One successful cold restore does not establish guaranteed recovery across devices. Completed results belong in [Verification](VERIFICATION.md).
