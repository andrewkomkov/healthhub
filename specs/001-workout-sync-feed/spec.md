# Feature Specification: Workout Sync & Activity Feed

**Feature Branch**: `001-workout-sync-feed`

**Created**: 2026-07-27

**Status**: Draft

**Input**: Product Requirement Document `google_health_strava_prd.pdf` (HealthHub Web, v1.0
Draft) plus scope decisions taken with the product owner: the Android target is a full
application rather than a headless sync agent; the platform is multi-user with sign-up; the
first shippable slice covers workouts — feed, map, and charts — with sleep, recovery and
body-composition surfaces following in a later iteration.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Get my workouts off the phone and into the app (Priority: P1)

An athlete records workouts with a watch or phone app that writes into Google Health
Connect. They install HealthHub on their Android phone, create an account, grant the
workout-related Health Connect permissions, and the app pulls in their existing workout
history and then keeps itself up to date on its own. The athlete sees a running count of
what was imported and can tell at a glance whether their data is complete.

**Why this priority**: Nothing else in the product exists without ingested data. This story
alone already delivers value — a durable, portable copy of workout history that Google does
not otherwise expose outside the phone.

**Independent Test**: Install on the Samsung test device with existing Health Connect
workout data, sign up, grant permissions, run a sync, and confirm the imported session
count and date range match what Health Connect reports.

**Acceptance Scenarios**:

1. **Given** a new account on a phone with existing Health Connect workout history,
   **When** the athlete grants permissions and starts the first sync, **Then** every
   workout session in the granted history is imported and the app reports how many sessions
   and how many samples were transferred.
2. **Given** a completed first sync, **When** a new workout is recorded later,
   **Then** it appears in HealthHub without the athlete opening the app manually.
3. **Given** a sync interrupted by loss of connectivity, **When** connectivity returns,
   **Then** the sync resumes from where it stopped and no session is imported twice.
4. **Given** the athlete denies a Health Connect permission, **When** sync runs,
   **Then** the app continues with the permissions it has, and clearly states which data is
   missing and why.
5. **Given** a record type the app does not yet understand, **When** it is encountered,
   **Then** it is reported in the sync report as unhandled rather than silently skipped.

---

### User Story 2 - Browse my activity feed (Priority: P1)

The athlete opens HealthHub — on the phone or in a browser — and lands on a reverse-
chronological feed of their activities. Each entry reads like a card in a social fitness
app: sport type, title, date, a route thumbnail when the activity has GPS, and the headline
numbers (distance, moving time, pace or speed, elevation, average heart rate). Tapping an
entry opens the full activity.

**Why this priority**: The feed is the product's front door and the thing that makes it feel
like a Strava-class app rather than a data export tool. Combined with Story 1 it is a
complete, demonstrable product.

**Independent Test**: With workouts already synced, open the feed on the phone and in the
browser and confirm both show the same activities, in the same order, with matching summary
figures.

**Acceptance Scenarios**:

1. **Given** synced workouts, **When** the athlete opens the app, **Then** activities are
   listed newest first with sport, date, distance, duration and pace/speed.
2. **Given** an activity with GPS data, **When** it appears in the feed, **Then** its card
   shows a route preview.
3. **Given** a long history, **When** the athlete scrolls, **Then** older activities load
   continuously without the athlete waiting on a blank screen.
4. **Given** an account with no activities yet, **When** the feed opens, **Then** an empty
   state explains what to do next instead of showing a blank list.
5. **Given** the athlete is offline on the phone, **When** they open the feed, **Then**
   previously loaded activities are still browsable.

---

### User Story 3 - Analyse a single workout (Priority: P1)

The athlete opens one activity and gets the full picture: the route on an interactive map,
stacked charts of heart rate, speed, elevation, cadence and power over time or distance,
automatic per-kilometre splits, and summary statistics including heart-rate zone
distribution. Moving the cursor along a chart highlights the matching point on the map, and
selecting a range on a chart shows the statistics for just that range.

**Why this priority**: This is the analytical payoff and the reason an athlete would choose
HealthHub over the stock phone app. It is P1 because Stories 1–3 together form the promised
first slice.

**Independent Test**: Open a synced outdoor ride or run and verify route rendering, chart
alignment against the recorded samples, split boundaries at each kilometre, and cursor
linkage between chart and map.

**Acceptance Scenarios**:

1. **Given** an activity with a GPS track, **When** it is opened, **Then** the route is
   drawn on an interactive map that can be panned and zoomed.
2. **Given** an activity with heart-rate and speed samples, **When** it is opened, **Then**
   those series are charted on a shared time axis aligned with the elevation profile.
3. **Given** a charted activity, **When** the athlete moves the cursor along a chart,
   **Then** a marker tracks the corresponding location on the map and the values at that
   instant are displayed.
4. **Given** a charted activity, **When** the athlete selects a range on a chart, **Then**
   distance, duration, average pace/speed and average heart rate are shown for that range.
5. **Given** an activity longer than one kilometre, **When** it is opened, **Then**
   per-kilometre splits are listed with pace, elevation change and average heart rate.
6. **Given** an activity recorded indoors with no GPS, **When** it is opened, **Then**
   charts and statistics are shown and the map area is replaced with an explanatory state
   rather than an empty box.
7. **Given** an activity with more than 100,000 recorded samples, **When** it is opened,
   **Then** charts become interactive quickly and remain responsive while panning and
   zooming.

---

### User Story 4 - Sign up and use my data from any device (Priority: P2)

A new athlete creates a HealthHub account, signs in on the web, and sees the data their
phone has been syncing. Their phone is linked to the account as a registered device. Data
belonging to one account is never visible to another.

**Why this priority**: Multi-user accounts are required for the platform to be usable by
more than its author and are a prerequisite for the social modules planned later, but the
single-athlete experience in Stories 1–3 can be demonstrated before this is polished.

**Independent Test**: Create two accounts, sync data on one, and confirm the second account
sees none of it and cannot reach it by guessing identifiers.

**Acceptance Scenarios**:

1. **Given** a new visitor, **When** they sign up and confirm their account, **Then** they
   can sign in on both the web and the Android app with the same credentials.
2. **Given** a signed-in athlete on the phone, **When** they sign in on the web, **Then**
   they see the activities their phone synced.
3. **Given** two separate accounts, **When** one requests another's activity directly,
   **Then** access is refused.
4. **Given** an athlete who signs out on a device, **When** that device next attempts a
   sync, **Then** the sync is refused until they sign in again.
5. **Given** an athlete who deletes their account, **When** deletion completes, **Then**
   their activities, tracks and stored files are removed and no longer retrievable.

---

### User Story 5 - Trust the interface (Priority: P2)

Every screen — feed, activity detail, charts, tables, dialogs, settings — presents a single
coherent, modern, expressive visual language, identical in character on Android and on the
web, in both light and dark appearance. Charts are part of that language rather than a
foreign element with their own palette.

**Why this priority**: Visual coherence is an explicit product differentiator, but it is
verifiable only once the screens from Stories 2–3 exist.

**Independent Test**: Place the Android and web feed and activity screens side by side in
both light and dark appearance and confirm colour roles, typography, shape and motion match,
including within charts.

**Acceptance Scenarios**:

1. **Given** the Android app and the web app, **When** the same activity is opened in both,
   **Then** colour roles, typography, corner shapes and chart colours correspond.
2. **Given** either client, **When** the system appearance switches between light and dark,
   **Then** every surface including charts and map overlays adapts and remains legible.
3. **Given** any chart, **When** it is displayed, **Then** its series colours come from the
   shared product palette rather than a charting library default.
4. **Given** a phone with a large accessibility font size, **When** the feed is opened,
   **Then** the layout adapts without text truncation or overlap.

---

### Edge Cases

- An activity has a GPS track but no heart-rate or power data — charts render only the
  series that exist, with no empty axes.
- An activity's GPS track contains gaps (tunnel, signal loss) — the route shows the gap
  rather than a false straight line through it.
- Two devices sync overlapping history for the same account — the activity appears once.
- A workout is deleted in Health Connect after it was synced — the change is reflected on
  the next sync rather than leaving a phantom activity.
- A five-hour ride records over a million samples — import completes without exhausting
  phone memory or being killed by the system.
- The phone syncs on a metered mobile connection — the athlete can restrict syncing to
  unmetered networks.
- The phone's clock or the recording device's timezone is wrong — activities are placed by
  their recorded local time and the discrepancy does not reorder the feed incorrectly.
- Health Connect is not installed or is unavailable on the device — the app explains the
  requirement instead of failing silently.
- An activity is opened in the browser before its detailed telemetry finishes transferring —
  the summary is shown immediately and detail fills in progressively.

## Requirements *(mandatory)*

### Functional Requirements

**Ingestion**

- **FR-001**: The Android app MUST read workout sessions and their associated samples from
  Google Health Connect for all permissions the athlete has granted.
- **FR-002**: The app MUST perform incremental synchronisation, transferring only records
  that are new or changed since the previous successful sync.
- **FR-003**: Synchronisation MUST be resumable and safe to retry: interrupting and
  repeating a sync MUST NOT produce duplicate or partial activities.
- **FR-004**: The app MUST buffer pending data locally so that recording and importing work
  without connectivity, transferring when connectivity returns.
- **FR-005**: The app MUST run synchronisation in the background on a schedule and when new
  workout data becomes available, without requiring the athlete to open it.
- **FR-006**: The app MUST produce a sync report stating how many sessions and samples were
  transferred, what failed, and what record types were encountered but not handled.
- **FR-007**: Deletions and edits made in Health Connect MUST propagate on the next sync.
- **FR-008**: The athlete MUST be able to restrict synchronisation to unmetered networks
  and to trigger a sync manually.
- **FR-009**: Sample data MUST be transferred without lossy rounding or downsampling of the
  stored original.

**Activity feed**

- **FR-010**: Both clients MUST present a reverse-chronological feed of the athlete's
  activities.
- **FR-011**: Each feed entry MUST show sport type, title, start date and time, distance,
  duration, pace or speed, elevation gain and average heart rate where those exist.
- **FR-012**: Feed entries for activities with GPS MUST display a route preview.
- **FR-013**: The feed MUST load additional history as the athlete scrolls, without a
  blocking wait.
- **FR-014**: The Android feed MUST remain browsable offline for activities already
  retrieved.
- **FR-015**: The athlete MUST be able to filter the feed by sport type and date range.
- **FR-016**: The athlete MUST be able to rename an activity and add a description.

**Activity detail**

- **FR-017**: Activities with GPS MUST render their route on an interactive, pannable and
  zoomable map.
- **FR-018**: Available series — heart rate, speed/pace, elevation, cadence, power — MUST be
  charted on a shared, aligned axis, with the athlete able to choose between a time axis and
  a distance axis.
- **FR-019**: Moving the cursor on a chart MUST highlight the corresponding point on the map
  and display the values at that point.
- **FR-020**: Selecting a range on a chart MUST show aggregate statistics for that range.
- **FR-021**: The system MUST compute and display per-kilometre splits with pace, elevation
  change and average heart rate, and MUST allow the unit to be switched to miles.
- **FR-022**: The system MUST display heart-rate zone distribution for activities with
  heart-rate data.
- **FR-023**: Activity detail MUST display total distance, moving and elapsed time, average
  and maximum values per series, elevation gain and loss, and calories where available.
- **FR-024**: Activities without GPS MUST present their charts and statistics with an
  explanatory state in place of the map.
- **FR-025**: Derived values — pace, splits, zones, averages, smoothed series — MUST be
  computed on the athlete's own device or in their own browser, never by a shared server.

**Accounts and privacy**

- **FR-026**: Visitors MUST be able to create an account and sign in on both web and
  Android.
- **FR-027**: An athlete MUST only ever be able to read or modify their own data; requests
  for another account's data MUST be refused.
- **FR-028**: Each Android installation MUST be registered to exactly one account and MUST
  be individually revocable.
- **FR-029**: The athlete MUST be able to delete their account, which MUST remove all of
  their stored activities, tracks and files.
- **FR-030**: The app MUST request only the Health Connect permissions needed for enabled
  features and MUST function in a reduced form when a permission is refused.
- **FR-031**: The system MUST NOT include third-party analytics, advertising or crash
  reporting that transmits health or location data.

**Interface**

- **FR-032**: Android and web MUST implement the same expressive design language, driven by
  one shared set of design tokens.
- **FR-033**: Charts, tables, maps overlays and dashboards MUST draw their colours,
  typography, shape and motion from those shared tokens.
- **FR-034**: Both clients MUST support light and dark appearance across every surface.
- **FR-035**: Both clients MUST remain usable with enlarged system font sizes and MUST meet
  common contrast expectations for text and essential graphics.
- **FR-036**: The Android application MUST be structured so that additional capability areas
  — social features in particular — can be added later as self-contained modules without
  modifying existing ones.

### Key Entities

- **Athlete**: A registered account holder. Owns everything below. Has credentials, display
  preferences and unit preferences.
- **Device**: A registered Android installation belonging to exactly one athlete, carrying
  its own sync state and revocable authorisation.
- **Activity**: One workout session — sport type, title, description, start and end time,
  timezone, and the summary figures shown in the feed. The unit the feed lists and the
  detail screen opens.
- **Telemetry Series**: The ordered samples belonging to an activity for one measured
  quantity — position, heart rate, speed, elevation, cadence, power. Large, append-only and
  read whole rather than queried piecewise.
- **Derived Summary**: Values computed from telemetry — splits, zone distribution, averages,
  moving time. Attached to an activity so the feed and lists never need the raw samples.
- **Sync Cursor**: The per-device marker of how far ingestion has progressed, making sync
  incremental and resumable.
- **Sync Report**: The record of a sync attempt — counts transferred, failures, and record
  types encountered but unhandled.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new athlete can go from installing the app to seeing their workout history
  in the feed in under 5 minutes, without instructions.
- **SC-002**: 100% of workout sessions present in Health Connect within the granted
  permission scope appear in HealthHub after the first sync, with distance and duration
  matching the source within rounding tolerance.
- **SC-003**: A five-hour activity containing at least one million samples imports
  successfully on the test device without the app being terminated.
- **SC-004**: The feed shows its first screen of activities within 2 seconds of opening on
  both clients.
- **SC-005**: An activity with 100,000 or more samples becomes interactive within 3 seconds
  of opening, and chart panning and zooming stay smooth thereafter.
- **SC-006**: Repeating a full sync produces zero duplicate activities.
- **SC-007**: An interrupted sync resumes and completes without athlete intervention in at
  least 95% of interruption cases.
- **SC-008**: Summary figures for the same activity are identical between the Android app
  and the web app.
- **SC-009**: No account can retrieve another account's activity, verified by direct
  attempt.
- **SC-010**: Every screen on both clients renders correctly in light and dark appearance,
  with no surface falling back to a foreign palette — verified screen by screen.
- **SC-011**: Text and essential graphics meet standard contrast expectations on all
  screens, including charts — 4.5:1 for text, 3:1 for graphics that carry meaning, in both
  appearances. Measured over the token palette by `web/src/core/m3e/contrast.test.ts`, which
  also records the two places the palette cannot meet it and what stands in instead: three
  slots of the light series palette fall below 3:1, and two pairs of the dark one are
  indistinguishable under simulated protanopia and deuteranopia. Both are covered by the
  relief rule — every channel is directly labelled and carries its own value readout, so
  colour is never the only thing telling two series apart. Screen-by-screen verification with
  a real screen reader is still outstanding.
- **SC-012**: A new capability area can be added to the Android app without modifying source
  files belonging to existing capability areas — demonstrated by adding one. The cost of
  attaching one is fixed and lives entirely outside the feature layer: a destination declared
  in `core:navigation`, an `include` in `settings.gradle.kts`, and one dependency line in
  `:app`. No existing `feature:*` module is read or edited.

## Assumptions

- The athlete's workouts already reach Google Health Connect from a watch or recording app;
  HealthHub reads from Health Connect and does not itself record workouts.
- Health Connect is available on the athlete's Android device; devices without it are out of
  scope.
- Sleep, recovery, HRV, body composition, blood pressure, glucose, menstrual and symptom
  surfaces are specified in the source PRD but are deliberately outside this first slice;
  ingestion is designed so they can be added without reworking it.
- Social capability — following, clubs, kudos, comments, segments, public sharing — is
  planned for later modules and is out of scope here; the feed in this slice shows only the
  athlete's own activities.
- Power curve, training-stress scoring, privacy zones, file export and webhooks are later
  phases from the PRD roadmap and are out of scope here, but privacy-zone masking and export
  are anticipated in the data model so they do not require restructuring.
- Units default to metric with an athlete-level switch to imperial.
- Map imagery comes from an openly licensed source that does not require a proprietary
  account.
- Verification of Android behaviour is performed on the connected Samsung SM-G780F.
