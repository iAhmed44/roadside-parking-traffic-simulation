# Complete Code Reference — EE364 Roadside Parking Simulation

A single, exhaustive reference: every class, its important variables and methods,
the data structures used (and *why*), the OOP concepts, how the engine runs, and
a viva Q&A. Pair it with `OOP_CONCEPTS.txt` (exact file:line index) and
`UNDERSTANDING_THE_CODE.md` (a shorter reading tour).

## Table of contents
1. [Big picture](#1-big-picture)
2. [Data structures used (and why)](#2-data-structures-used-and-why)
3. [Class catalog](#3-class-catalog)
4. [The tick loop](#4-the-tick-loop-how-it-actually-runs)
5. [Phase 1 vs Phase 2 + record/replay](#5-phase-1-vs-phase-2--recordreplay)
6. [OOP concepts](#6-oop-concepts)
7. [Viva Q&A](#7-likely-viva-questions--answers)
8. [Glossary of on-screen markers](#8-glossary-of-on-screen-markers)

---

## 1. Big picture

A **`Street`** has several **`Lane`s** and a row of **`ParkingSpot`s**. **Vehicles**
(`Car`, `EmergencyVehicle`, `PoliceVehicle`) drive along lanes. A fixed-step loop
in **`Simulation.step()`** advances the world each tick (spawn → move → park →
accidents → stats). A **JavaFX GUI** (`TrafficFxApp` + `StreetCanvasRenderer`)
draws it ~60×/sec.

- **Phase 1** = basic curb parking; a seeker waits in the live lane when the curb
  is full (shows the *problem*).
- **Phase 2** = a **`TrafficController`** adds reservations + early redirect +
  police patrol (the *solution*), and the GUI shows the improvement percentage.

Units: continuous "road units" along a lane; time advances in fixed steps of
`1/60` second (`Simulation.FIXED_STEP_SECONDS`).

---

## 2. Data structures used (and why)

| Structure | Java type | Where | Why this structure |
|---|---|---|---|
| Dynamic list | `ArrayList` via `List<Vehicle>` | `Lane.vehiclesInLane` | Ordered, grows/shrinks as cars enter/leave; fast iteration to find neighbours. |
| Dynamic list | `List<Lane>`, `List<ParkingSpot>` | `Street.lanes`, `Street.parkingSpots` | Fixed-ish ordered collections indexed by id. |
| Dynamic list | `List<ScenarioPlan.CarArrival>`, `List<Integer>` | `ScenarioPlan.arrivals`, `emergencyTicks` | Append-only recording of the traffic stream. |
| Dynamic list | `List<Double>` | `StatisticsTracker.congestionHistory` | Append each cycle's congestion, average at the end. |
| Hash map | `HashMap` via `Map<Car, ParkingSpot>` | `TrafficController.reservations` | O(1) "which spot is reserved for this car?" lookup/removal. |
| Hash map | `Map<Integer, List<CarArrival>>` | `ScenarioPlan.arrivalsByTick()` / `Simulation.replayByTick` | O(1) "which cars spawn on tick N?" during replay. |
| Hash set | `HashSet` via `Set<Integer>` | `Simulation.redirectedSeen` | Count each redirected car **once** (membership test, no duplicates). |
| Hash set | `Set<Integer>` | `Simulation.replayEmergencyTicks` / `ScenarioPlan.emergencyTickSet()` | O(1) "is there an emergency on tick N?". |
| Hash set | `Set<Vehicle>` | `Simulation.moveAllVehicles` (`processedThisTick`) | Avoid moving the same car twice when a lane list changes mid-iteration. |
| Record | `record CarArrival(...)` | `ScenarioPlan` | Immutable value object holding one recorded car's properties. |
| Array | `int[]` | `TrafficFxApp.parseArgs`, `Simulation.adjacentLaneIds` | Fixed, tiny, fast. |
| Array | `String[]` | the sprite catalogs | Fixed list of image paths. |
| Defensive wrapper | `Collections.unmodifiableList(...)` | `Lane.getVehiclesInLane`, `Street.getLanes`/`getParkingSpots` | Encapsulation — callers can read but not corrupt internal lists. |
| RNG | `java.util.Random` | `Simulation.rng` | Pseudo-random spawning + driver profiles; **seedable** for deterministic runs. |
| Concurrency | `CountDownLatch`, `AtomicReference` | `FxRenderSmokeTest` | Wait for the JavaFX thread to finish the headless render. |

> Note for the viva: this project uses **List, Map, Set, an immutable
> record, and arrays**. It does **not** use a `Queue`/`Stack`/tree — the per-lane
> ordered `List` plus the reservation `Map` already model the "waiting line" and
> "who holds which spot" behaviour. (A `Queue` would be a natural future
> extension for a strict FIFO waiting line.)

---

## 3. Class catalog

### Package `traffic.interfaces`

#### `Movable` (interface)
The contract for anything that drives.
| Method | Meaning |
|---|---|
| `void move()` | Advance the object. |
| `void stop()` | Stop moving. |

Implemented by `Vehicle`. Used through the interface type by
`Simulation.halt(Movable)`.

#### `Reservable` (interface)
The contract for something that can be held before use.
| Method | Meaning |
|---|---|
| `boolean reserve(Object owner)` | Hold this for `owner`; false if already taken/reserved. |
| `void release()` | Drop the hold. |
| `boolean isReserved()` | Is a hold active? |

Implemented by `ParkingSpot`. Used by `TrafficController` (Phase 2 reservations).

---

### Package `traffic.model`

#### `Vehicle` (abstract, implements `Movable`)
Base class for everything on the road.

**Important fields**
| Field | Type | Meaning |
|---|---|---|
| `vehicleId` | `final int` | Unique id (also the default sprite seed). |
| `position` / `previousPosition` | `double` | Current / last-tick position (last is for smooth drawing). |
| `speed` | `double` | Road units per second. |
| `bodyLength` / `bodyWidth` | `double` | Physical size (drives collisions + drawing). |
| `headingDegrees` / `previousHeadingDegrees` | `double` | Facing angle (and last value for smooth rotation). |
| `previousLaneId` / `renderLaneId` | `double` | Used to animate a lane change. |
| `state` | `String` | One of the `STATE_*` tags. |
| `currentLane` | `Lane` | Back-reference to its lane. |
| `laneChangeFlashTicks`, `mergeYieldTicks`, `postMergeBrakeTicks`, … | `int` | Short visual/behaviour timers. |

**State constants:** `STATE_MOVING`, `STATE_WAITING`, `STATE_PARKED`,
`STATE_WAITING_TO_EXIT`, `STATE_EXIT_BLOCKED`, `STATE_EXITING`, `STATE_REMOVED`.

**Important methods**
| Method | What it does |
|---|---|
| `abstract void move()` | Each subclass defines its own motion (polymorphism). |
| `void stop()` | Speed 0, state WAITING (overrides `Movable.stop`). |
| `void beginStep()` | Snapshot previous position/heading for interpolation. |
| `void tickVisualMarkers()` | Count down the badge/cooldown timers. |
| `markLaneChanged`, `requestMergeYield`, `requestPostMergeBrake`, `markLaneIntent` | Set the short timers/markers shown above the car. |
| `getPositionExact/setPositionExact/moveToPositionExact`, `getSpeedExact/setSpeedExact`, `getFrontPosition/getRearPosition`, `setBodySize`, `getState/setState`, `getCurrentLane/setCurrentLane` | Core getters/setters used by the engine. |
| `getRenderPosition/Heading/LaneId(alpha)` | Interpolated values for smooth drawing. |

#### `Car` (extends `Vehicle`)
A car that may seek parking.

**Important fields**
| Field | Type | Meaning |
|---|---|---|
| `targetParkingSpot` | `ParkingSpot` | The spot it's aiming for (null if none). |
| `waitingTime` | `int` | Ticks spent waiting. |
| `seekingParking` | `boolean` | Currently hunting for a spot. |
| `redirected` | `boolean` | Was told to give up and keep moving. |
| `parkingIntent` | `boolean` | Still *wants* to park (stays true until it actually parks; lets a redirected car re-seek). |
| `hasReservation` | `boolean` | Holds a Phase 2 reservation. |
| `stubbornParker` | `boolean` | Insists on waiting for the curb. |
| `warningFlashTicks` / `cameraFineFlashTicks` | `int` | Phase 2 enforcement flash timers. |
| `preferredCruiseSpeed`, `accelerationRate`, `brakingRate`, `headwayBuffer` | `double` | Driver profile (car-following parameters). |
| `aggressiveDriver` | `boolean` | Drives more aggressively. |
| `colorSeed` | `int` | Sprite/color id; `-1` = use the vehicle id. A replayed Phase 2 car copies its Phase 1 id so it looks identical. |

**Important methods:** `move()` (override), `tickVisualMarkers()` (override that
calls `super`), `incrementWait`, `noteStoppedInTraffic`/`clearStoppedInTraffic`,
`warnByPolice`/`fineByCamera`, `getColorSeedId()`/`setColorSeed`,
`hasParkingIntent()`/`setParkingIntent`, plus getters/setters for the fields.

#### `EmergencyVehicle` (extends `Vehicle`)
An ambulance: faster and clears accidents.
- Fields: `emergencyType` (`String`), `sirenActive` (`boolean`).
- Methods: `move()` override, `getEmergencyType`, `isSirenActive`/`setSirenActive`.

#### `PoliceVehicle` (extends `EmergencyVehicle`)
The Phase 2 patrol car. Just a constructor: `PoliceVehicle(int id)` →
`super(id, "POLICE")`. This is the **third level** of the inheritance hierarchy.

#### `Lane`
One lane of fixed length.
- Fields: `laneId` (`final int`), `length` (`final int`), `vehiclesInLane`
  (`List<Vehicle>`), `laneBlocked` (`boolean`).
- Methods: `addVehicle`/`removeVehicle` (keep the car⇄lane link consistent),
  `getCongestion()` (= vehicles/length), `getNextVehicleAhead(double)` (nearest
  car ahead of a position), `getVehiclesInLane()` (unmodifiable view),
  `isLaneBlocked`/`setLaneBlocked`.

#### `Street`
The whole road.
- Constant: `MAX_PARKING_SPOTS = 18`.
- Fields: `numberOfLanes`, `streetLength` (`final int`), `lanes` (`List<Lane>`),
  `parkingSpots` (`List<ParkingSpot>`), `activeAccident` (`Accident`).
- Constructor validates inputs and lays out evenly-spaced parking spots.
- Methods: `addVehicle(v, laneId)`, `triggerAccident(severity, x, laneId)` +
  `clearAccident()`, `countFreeSpots()`, `getLanes()`/`getParkingSpots()`
  (unmodifiable), **`allVehicles()` → `List<Vehicle>`**, `getActiveAccident()`.

#### `ParkingSpot` (implements `Reservable`)
A curb spot.
- Fields: `spotId`, `positionX` (`int`), `occupied`/`reserved` (`boolean`),
  `parkedCar`/`reservedBy` (`Car`), and exit-diagnostics fields
  (`exitRequested`, `exitBlockedSeconds`, `exitBlockReason`,
  `exitBlockerVehicleId`).
- Methods: `occupy()`/`occupy(Car)`, `vacate()`, **`reserve(Object)`/`release()`/
  `isReserved()`** (the `Reservable` contract), `getReservedBy`, `isFree()`
  (= not occupied and not reserved), `requestExit`/`markExitBlocked`/
  `releaseForExit`/`isExitStarved` (the curb-exit handshake).

#### `Accident`
A lane blockage.
- Fields: `severity` (`final int`), `cyclesElapsed` (`int`), `positionX`
  (`final double`), `laneId` (`final int`), `cleared` (`boolean`).
- Methods: `tick()` (age it), `markCleared()`, `isCleared()`, `getPositionExact()`,
  `getLaneId()`, `getCyclesElapsed()`. Cleared only when an emergency vehicle
  reaches it.

---

### Package `traffic.sim`

#### `Simulation` (the engine — the biggest class)
Owns the `Street`, `TrafficController`, `StatisticsTracker`, the `Random`, and all
the rules.

**Important fields**
| Field | Type | Meaning |
|---|---|---|
| `currentCycle` / `maxCycles` | `int` | Tick counter / planned end. |
| `phase2Enabled` | `boolean` | Phase 1 vs Phase 2 switch. |
| `street`, `controller`, `stats` | objects | The world, the Phase 2 brain, the KPIs. |
| `rng` | `Random` | Seedable RNG for spawning + driver profiles. |
| `bodyCatalog` | `VehicleBodyCatalog` | Gives new vehicles their body size. |
| `recordPlan` | `ScenarioPlan` | Non-null while recording (Phase 1). |
| `replayMode`, `replayByTick`, `replayEmergencyTicks` | — | Drive spawning from a recorded plan (Phase 2). |
| `redirectedSeen` | `Set<Integer>` | Count each redirected car once. |
| `nextVehicleId` | `int` | Hands out unique ids. |
| `trafficDemandPer100Ticks`, `parkingSeekerRatio`, `emergencySpawnRate`, `parkingTurnover`, `movementRandomnessEnabled` | — | Tunable knobs (set by the GUI). |

**The public API** (what the GUI/tests call): `tick()`, `step(dt)`, `run()`,
`report()`, `startRecording()`, `getRecordedPlan()`, `startReplay(plan)`,
`setPhase2Enabled`, `triggerManualAccident()`, `triggerManualEmergency()`,
`getStreet/getController/getStats/getCurrentCycle/getMaxCycles`, and the setter
knobs above.

**Method families** (the private helpers, grouped):
- *Spawning:* `spawnVehicles`, `createCar`, `assignDriverProfile`,
  `chooseRandomOpenEntryLane`, `isEntryClear`; replay: `spawnFromPlan`,
  `carFromArrival`, `pickReplayLane`, `arrivalOf`.
- *Movement core:* `moveAllVehicles` (sorts leaders first, then dispatches),
  `processCarMovement` (the big one: WAITING recovery → accident avoidance →
  lane-change policies → parking commit → Gipps safe-follow speed),
  `processEmergencyMovement`, `getDesiredSpeed`, `openGapAhead`, `halt(Movable)`.
- *Lane changing:* `tryChangeLaneToBestAdjacent`, `tryPolicyLaneChange`,
  `completeLaneChange`, `isLaneChangeSafe`, `sweptLaneSpaceClear`,
  `laneChangeScore`, `lanePolicyScore`, `laneRiskPenalty`, `adjacentLaneIds`,
  and the curb policies `shouldClearCurbLane`, `shouldSpilloverOutward`,
  `shouldTryTrafficSpillover`, `isCommittedToImmediateParking`.
- *Parking:* `canParkInSpot`, `canWaitingCarResume`,
  `refreshUnreservedParkingTarget` (Phase 1), `shouldWaitForParking` (Phase 1),
  `redirectAwayFromParking`, `isNearParkingSpot`.
- *Accidents & emergencies:* `tickAccident`, `dispatchEmergencyForAccident`,
  `prepareForEmergencyVehicles`, `forceEmergencyClearance`,
  `resolveBodyCollisions`, `createCollisionAccident`.
- *Curb exit / turnover:* `simulateParkingTurnover`, `tryExitParkingSpot`,
  `checkParkingExitGap`, `maybeResolveExitDeadlock`, `proactivelyResolveExitBlocker`.
- *Police (Phase 2):* `spawnPoliceIfNeeded`, `hasPoliceVehicle`,
  `policeWarnWaitingDrivers`.
- *Bookkeeping:* `beginVehicleStep`, `tickVisualMarkers`, `removeExited`,
  `recordStats`.

#### `TrafficController` (the Phase 2 brain)
Adds managed parking. Phase 1 callers also use `bestReachableSpot` (find without
reserving).

**Important fields**
| Field | Type | Meaning |
|---|---|---|
| `reservations` | `Map<Car, ParkingSpot>` | Who holds which spot. |
| `street` | `Street` | The world it manages. |
| `maxWaitTimeLimit` | `int` | Wait limit before enforcement. |
| `policePatrolEnabled`, `cameraEnabled` | `boolean` | Enforcement toggles (on in Phase 2). |
| `warningsIssued`, `finesIssued` | `int` | Enforcement counters (KPIs). |

**Important methods**
| Method | What it does |
|---|---|
| `assignParking()` | Per tick: reserve the nearest reachable spot for each seeker, **or** redirect it early if the curb near it is full. Also lets a redirected car re-seek when a spot opens (`canResumeSeeking`). |
| `bestReachableSpot(car, lane)` | Nearest reachable free/own spot in the curb lane (no reserving). |
| `assignOrUpgradeReservation(car, spot)` | Reserve a spot (or upgrade to a closer one), keeping the map and the car's target in sync. |
| `clearStaleReservation`, `releaseReservationFor`, `forgetReservation` | Release/consume reservations safely (avoid ghost-reserved spots). |
| `enforceWaitLimit()` | Redirect (and optionally camera-fine) cars that waited too long. |
| `policePatrol()` | Warn + redirect cars that have waited even briefly (visible enforcement). |

#### `StatisticsTracker`
Accumulates KPIs.
- Cumulative: `totalWaitTime` (`long`), `carsProcessed`, `blockedLaneCycles`,
  `avgSpeed`, `redirectedCars`, parking-usage sums, `congestionHistory`
  (`List<Double>`).
- Per-phase (for the in-run improvement): `phase1WaitSum`/`phase2WaitSum`,
  `phase1Cycles`/`phase2Cycles`, etc.
- Methods: `recordCycle`, `carProcessed`, `noteRedirectedCar`, `recordParking`,
  `snapshotPhase1`, `computeImprovement()`, and getters.

#### `ScenarioPlan` (record/replay)
The recorded traffic so Phase 2 can replay the same cars.
- Inner **`record CarArrival(tick, seeking, aggressive, stubborn, cruiseSpeed,
  accel, brake, headway, bodyLength, bodyWidth, entryLaneHint, colorSeed)`** —
  one car's recorded properties.
- Fields: `arrivals` (`List<CarArrival>`), `emergencyTicks` (`List<Integer>`),
  `recordedTicks`.
- Methods: `recordCar`, `recordEmergency`, `arrivalsByTick()` (→ `Map`),
  `emergencyTickSet()` (→ `Set`), `getCarCount`/`getSeekerCount`, and
  **`signature()`** — a stable hash proving two runs used the identical scenario.

#### `VehicleBodyCatalog`
Reads the car sprite images and derives realistic body sizes; `applyTo(vehicle)`
sets a vehicle's `bodyLength`/`bodyWidth`. Uses the same `id*7 mod count` index as
the renderer, so a car's size and sprite stay consistent.

---

### Package `traffic.gui.fx`

#### `TrafficFxApp` (extends `Application`)
The JavaFX front end.
- **Fields:** the buttons/spinners/sliders/checkboxes, the KPI `Label`s, loop
  state (`playing`, `accumulatorSeconds`), and replay state (`inPhase2Replay`,
  `phase1TotalWait`, `suppressPhaseListener`).
- **Loop:** `startLoop()` uses a JavaFX `AnimationTimer`; an accumulator calls
  `simulation.step(FIXED_STEP_SECONDS)` a fixed number of times per frame so the
  world speed is independent of frame rate.
- **Phase replay:** `applyPhase()` → `startPhase2Replay()` grabs the recorded
  plan, builds a fresh `Simulation` at cycle 0, enables Phase 2, and replays the
  same cars. `rebuildSimulation()` (Reset/Apply Road) starts a fresh recorded
  Phase 1.
- **KPIs:** `refreshKpis()` updates the side panel; the `count*` helpers use
  `street.allVehicles()` + `instanceof` to tally waiting/redirected/emergency.

#### `StreetCanvasRenderer`
Draws everything onto a `Canvas`: backdrop, parking row, lanes, vehicles,
accident, badges, legend. Uses interpolation (`getRenderPosition`, etc.) for
smooth motion. Inner `Layout` record holds the computed pixel geometry.

#### `FxVehicleImageCatalog`
Loads the top-down car sprites and draws a rotated/scaled body. `spriteFor()`
picks a car's image from its **color seed** (`Car.getColorSeedId()`), so a
replayed car keeps its Phase 1 look; police/ambulance use fixed sprites.

#### `FxRenderSmokeTest`
A headless test that boots JavaFX, renders both phases off-screen, and asserts
visible content + sprite loading. Uses `CountDownLatch` + `AtomicReference` to
sync with the JavaFX thread.

---

## 4. The tick loop (how it actually runs)

`TrafficFxApp` → `AnimationTimer` → `Simulation.step(dt)` runs this checklist:

1. `beginVehicleStep()` — snapshot previous positions (smooth drawing).
2. `spawnVehicles()` — random new cars, or `spawnFromPlan()` in replay mode.
3. `tickAccident()` — age/clear the accident; dispatch an ambulance if needed.
4. **Phase 2 only:** `assignParking()`, `enforceWaitLimit()`, `policePatrol()`.
5. `spawnPoliceIfNeeded()`, `policeWarnWaitingDrivers()`.
6. `prepareForEmergencyVehicles()` — open a path for ambulances.
7. `moveAllVehicles(dt)` — the core movement (per-vehicle, leaders first).
8. `resolveBodyCollisions()` — separate overlaps; reckless overlap → crash.
9. `tickVisualMarkers()` — count down badge timers.
10. `simulateParkingTurnover(dt)` — parked cars leave and merge back.
11. `removeExited()` — drop cars off the end (count as processed).
12. `recordStats()` — tally waiting/speed/congestion for this cycle.

---

## 5. Phase 1 vs Phase 2 + record/replay

- `phase2Enabled` flips the rules; turning it on also enables police + camera.
- **Phase 1:** a full curb makes a seeker `halt()` and wait in the lane
  (`shouldWaitForParking`) — the problem.
- **Phase 2:** `assignParking()` runs *before* movement and either reserves a spot
  (`ParkingSpot.reserve`) or redirects the seeker early — it never blocks the lane.
- **Same cars:** Phase 1 `startRecording()` saves each car into a `ScenarioPlan`;
  switching to Phase 2 in the GUI rebuilds a fresh sim and `startReplay(plan)`
  re-creates the exact same cars on the same ticks (`spawnFromPlan`). The
  improvement % then compares like-for-like. (Details: `PHASE1_TO_PHASE2_CARS.md`.)

---

## 6. OOP concepts

- **Interfaces:** `Movable` (move/stop) implemented by `Vehicle`; `Reservable`
  (reserve/release/isReserved) implemented by `ParkingSpot`. Used through the
  interface type by `Simulation.halt(Movable)` and `TrafficController`.
- **Inheritance (3 levels):** `Vehicle` (abstract, abstract `move()`) → `Car`,
  `EmergencyVehicle` → `PoliceVehicle`. `Car`/`EmergencyVehicle` override
  `move()`; `Car.tickVisualMarkers()` overrides **and calls `super`**; every
  subclass chains `super(id)`.
- **Polymorphism:** `List<Vehicle>` (Lane) and `Street.allVehicles()` (also a
  `List<Vehicle>`); looping over them and calling `vehicle.beginStep()` /
  `vehicle.tickVisualMarkers()` dispatches to the right override at runtime;
  `halt(Movable)` works through the interface;
  `instanceof` pattern matching picks subtype behaviour in `moveAllVehicles`, the
  GUI counts, and the renderer.
- **Encapsulation:** non-public fields + getters/setters; `Lane`/`Street` hand out
  `Collections.unmodifiableList(...)` so internals can't be mutated from outside.

Exact `file:line` for each of the above is in **`OOP_CONCEPTS.txt`**.

---

## 7. Likely viva questions & answers

**Q: What real-world problem does this simulate?**
Curb-side parking on a busy street. When parking fills, drivers stop in the live
lane to wait, jamming traffic. Phase 2 fixes it with reservations, early redirect,
and police enforcement.

**Q: Where does the program start?**
`Main.main()` → `TrafficFxApp.main()` → JavaFX `launch()` → `start()`.

**Q: What is the "engine" and how does it advance time?**
`Simulation.step(dt)` runs once per fixed `1/60`s tick (the 12-step checklist in
§4). The GUI calls it via an accumulator so simulation speed is independent of
frame rate.

**Q: How is Phase 2 a *fair* improvement over Phase 1?**
Phase 1 records its exact traffic into a `ScenarioPlan`; Phase 2 replays the same
cars (same arrival ticks and traits), so only the *method* differs. `signature()`
proves the scenarios are identical.

**Q: Why is `Vehicle` abstract?**
A bare "vehicle" has no concrete motion — `move()` is abstract; only `Car` /
`EmergencyVehicle` / `PoliceVehicle` are real. It also lets the engine treat them
all uniformly as `Vehicle`.

**Q: Give a concrete polymorphism example.**
A loop over `street.allVehicles()` calling `vehicle.tickVisualMarkers()` — for a
`Car` it runs Car's overridden version (which adds the police/fine flash), for
others the base version. The right method is chosen at runtime.

**Q: Why a `Map` for reservations and a `Set` for "redirected seen"?**
`Map<Car,ParkingSpot>` answers "which spot is this car holding?" in O(1) and lets
us release it cleanly. `Set<Integer>` lets us count each redirected car exactly
once (membership test, no duplicates).

**Q: How do you prevent two cars occupying the same space?**
Movement caps each step by the gap to the leader (`openGapAhead` + a hard
geometric cap), and `resolveBodyCollisions()` snaps any overlap apart afterward.

**Q: How does a car decide its speed?**
A Gipps-style safe-following speed in `processCarMovement`:
`v_safe = sqrt(v_leader² + 2·b·gap)`, capped by the driver's preferred cruise
speed and rate-limited acceleration/braking.

**Q: What data structures did you use?** See §2 — List, Map, Set, Stream, an
immutable record, and arrays (no Queue/tree).

**Q: How is encapsulation enforced?**
Fields are non-public; collections are returned as
`Collections.unmodifiableList`, so all mutation goes through `addVehicle` /
`removeVehicle` and the car⇄lane link stays consistent.

**Q: What happens on an accident?**
`triggerAccident` blocks the lane; cars merge away or stop; an ambulance is
dispatched (`dispatchEmergencyForAccident`); when it reaches the scene the
accident is `markCleared()` and the lane reopens.

---

## 8. Glossary of on-screen markers

| Badge | Meaning |
|---|---|
| `W` | Waiting (parking full or lane blocked). |
| `R` | Redirected (told to keep moving). |
| `Q` | Queued / stopped in traffic. |
| `SW` | Switched lane. |
| `YIELD` / `BRAKE` / `MERGE` | Yielding for a merge / braking after a merge / a parked car asking for a gap. |
| `E` / `P` | Emergency vehicle / police patrol. |
| `WARN` / `FINE` | Police warning / camera fine (Phase 2). |
| `EXIT` / `BLK` | A parked car trying to exit / its exit is blocked. |

---

*Companion docs:* `UNDERSTANDING_THE_CODE.md` (reading tour),
`OOP_CONCEPTS.txt` (exact file:line index), `OOP_CONCEPTS_DETAILED.md` (deep dive),
`PHASE1_TO_PHASE2_CARS.md` (same-cars replay), `README.md` (build/run),
`uml/full_diagram.*` (class diagram).
