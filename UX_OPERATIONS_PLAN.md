# UX Operations Plan

## Goal

Make the app easier to operate for first-time hotspot setups while preserving the deeper runtime data needed for troubleshooting. The next iteration should separate "get me serving quickly" from "help me debug why this device is flaky".

## Current Baseline

The app already has the underlying pieces, but they are presented as one dense screen:

- `MainActivity` exposes runtime state, last error, diagnostics fields, logs, copy-last-error, copy-control-URL, share-logs, and battery optimization actions in one flow.
- `ProxyService` already supports notification actions for stop, copy URL, and clear error.
- `ProxyPreferences` persists diagnostics fields such as PID, selected interface/IP, bind result, probe result, and hotspot detection state.

This is good operational depth, but it does not create a clear default path for a user who just wants to start serving Tailscale on a hotspot.

## Product Direction

### 1. Add explicit operating modes

Introduce two user-facing modes in the main UI:

- `Quick Start`
  - Primary default mode on fresh install.
  - Focus only on the minimum decisions: listen port, detected local IP, effective control URL, Start/Stop, permission blockers, and battery guidance.
  - Hide logs, detailed diagnostics, last exit code, PID, interface selection details, and verbose error copy unless the user opts into diagnostics.

- `Advanced Diagnostics`
  - Full operational view for troubleshooting.
  - Keep the existing runtime details, logs, bind/probe results, error metadata, and share/export actions.
  - Surface device-state checks that matter for hotspot reliability.

Recommended UX shape:

- Replace the current long single-screen stack with a mode switch near the top:
  - segmented control, tabs, or a two-state chip row
- Preserve one underlying `ProxyStatus`, but render two different content groups from it.

### 2. Turn copy/share into a guided troubleshooting flow

The app already supports copy URL, copy error, and share logs, but these are fragmented. Consolidate them into a lightweight "Troubleshooting actions" cluster.

Recommended actions:

- `Copy Control URL`
  - One tap from Quick Start and Advanced Diagnostics.
  - Always visible when an effective URL is available.
  - Copy the exact URL the user should paste into the client device.

- `Share Diagnostics`
  - New primary troubleshooting action.
  - Share a single text payload plus optional log attachment.
  - Payload should include:
    - current runtime state
    - active URL
    - detected interface and IP
    - hotspot active yes/no
    - bind result
    - probe result
    - last exit code
    - last failure detail
    - recommended action
    - whether notifications are allowed
    - whether battery optimization is still active
    - device/manufacturer/model and Android version
  - Include a "plain text only" path and an "attach logs too" path.

- `Copy Last Error`
  - Keep, but demote under Advanced Diagnostics or fold into Share Diagnostics.

This should optimize for "send me exactly what your Android phone says" when debugging between devices.

### 3. Strengthen battery and background guidance

Battery/background restrictions are currently present, but they read like a generic warning. They should become a first-class readiness check.

Recommended additions:

- Add a "Device readiness" card in Quick Start with three high-signal checks:
  - notification permission
  - battery optimization exemption
  - hotspot/private IP detected

- Add stronger OEM-specific language when battery optimization is still active:
  - call out Motorola and similar vendors explicitly
  - explain the observed symptom: proxy starts, then becomes unreachable or gets killed in background

- Add a one-tap path to:
  - battery optimization exemption request
  - app info screen
  - notification settings

- Show contextual battery guidance before start, not only after failure.

Recommended copy pattern:

- "This device may stop the hotspot proxy in the background unless battery optimization is disabled."
- "Motorola-class Android builds are known to be aggressive here."
- "Disable battery optimization before relying on this device as a hotspot control server."

## Implementation Plan

### Phase 1. Restructure the UI around modes

- Add a persisted UI preference for `Quick Start` vs `Advanced Diagnostics`.
- Split the current `activity_main.xml` into logical sections or include layouts:
  - quick-start status/actions
  - advanced diagnostics/logs
  - device readiness guidance
- Keep the existing state model, but build dedicated render methods:
  - `renderQuickStart(status, validation, readiness)`
  - `renderAdvancedDiagnostics(status)`
  - `renderReadiness()`

Acceptance target:

- A first-time user can understand what URL to use and how to start serving without scrolling through diagnostics noise.

### Phase 2. Unify export and troubleshooting actions

- Add a diagnostics share builder in `MainActivity` or a small helper object.
- Add a structured diagnostics text export built from:
  - `ProxyStatus`
  - validation result
  - current device readiness signals
  - app/device metadata
- Replace the current standalone log share emphasis with:
  - `Copy Control URL`
  - `Share Diagnostics`
  - optional `Share Logs`

Acceptance target:

- A user can send another person one coherent bundle that explains both the target URL and why startup or reachability failed.

### Phase 3. Make readiness and battery guidance proactive

- Introduce a small readiness model in code:
  - notifications ok
  - battery optimization exempt
  - local hotspot/LAN IP found
  - proxy currently healthy
- Render this as explicit checklist UI with blocking vs warning states.
- Add OEM-aware guidance copy keyed off manufacturer where appropriate.

Acceptance target:

- Before pressing Start, the user can see the likely reasons the app may fail on their device class.

## Technical Notes

### Suggested code changes

- `MainActivity.kt`
  - split rendering into mode-specific helpers
  - add diagnostics-share payload builder
  - add readiness model and readiness render logic
  - move copy/share actions into a dedicated action cluster

- `activity_main.xml`
  - reorganize into distinct Quick Start, Diagnostics, and Device Readiness sections
  - reduce default vertical density in the first screenful

- `ProxyPreferences.kt`
  - persist selected UI mode if we want it sticky across launches
  - optionally persist a lightweight last-shared timestamp or last diagnostics snapshot version

- `ProxyService.kt`
  - keep notification URL copy
  - optionally add a notification action for diagnostics share later, but this can wait until the in-app share flow is solid

- `strings.xml`
  - add explicit Quick Start/Advanced Diagnostics copy
  - add stronger Motorola/background management guidance
  - add diagnostics share labels and summary text

## Non-Goals For This Pass

- No backend or protocol changes.
- No attempt to solve OEM battery restrictions technically beyond better guidance and deep links.
- No large architecture rewrite; keep this as an iterative UI/UX reframe around the existing status and diagnostics model.

## Open Questions

- Should Quick Start allow manual advertised URL editing, or should that move behind an "Advanced" affordance?
- Should Share Diagnostics include the raw log file by default, or should that require explicit opt-in?
- Do we want manufacturer-specific copy only for known aggressive OEMs, or a generic warning for all devices plus a stronger Motorola note?
- Should the app expose a "Troubleshooting report" preview screen before invoking the system share sheet?

## Recommended Order

1. Land the mode split and device readiness card.
2. Add the unified Share Diagnostics flow.
3. Tighten copy and OEM-specific battery guidance.
4. Revisit whether the notification should also expose diagnostics sharing.
