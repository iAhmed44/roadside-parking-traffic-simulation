# Understanding the Code — A Guided Tour

A step-by-step path for reading this project from scratch. Read the sections in
order; each one builds on the last. File references use **class and method
names** (robust); for exact `file:line` pointers see **`OOP_CONCEPTS.txt`**.

---

## 0. The 30-second mental model

It's a **traffic simulation** of a street with curb-side parking:

- A **`Street`** holds several **`Lane`s** and a row of **`ParkingSpot`s**.
- **Vehicles** drive along the lanes: **`Car`**, **`EmergencyVehicle`**, and
  **`PoliceVehicle`**.
- A **fixed-step loop** (`Simulation.step`) advances the whole world a little bit
  each tick: spawn cars, move them, park them, handle accidents, collect stats.
- A **JavaFX GUI** (`TrafficFxApp` + `StreetCanvasRenderer`) draws it ~60 times a
  second.

There are two **phases** of the *same* program:

- **Phase 1** — basic curb parking. When the curb is full, a seeker **waits in
  the live lane** and blocks traffic. This *shows the problem*.
- **Phase 2** — managed parking. A **`TrafficController`** adds **reservations**,
  **early redirect**, and **police patrol** so seekers never block the lane.
  This *solves the problem*, and the GUI shows the improvement percentage.

---

## 1. Where to start — recommended reading order

Follow this exact order. For each file, the "look for" tells you what matters.

1. **`Main.java`** (package `traffic`) — the entry point. Tiny. It just launches
   the JavaFX app. *Look for:* `main()` → `TrafficFxApp.main()`.

2. **`interfaces/Movable.java`** and **`interfaces/Reservable.java`** — the two
   interfaces, a few lines each. *Look for:* the method signatures only — these
   are contracts, no logic.

3. **`model/Vehicle.java`** — the **abstract base class** for everything that
   drives. *Look for:* the fields (position, speed, state, body size), the
   `abstract move()`, the concrete `stop()`, and the visual timers
   (`beginStep`, `tickVisualMarkers`). This is the heart of the model.

4. **`model/Car.java`**, **`model/EmergencyVehicle.java`**,
   **`model/PoliceVehicle.java`** — the three concrete vehicles. *Look for:* how
   each `extends` its parent and what it adds (Car adds parking-seeker state;
   EmergencyVehicle adds a siren; PoliceVehicle is just an EmergencyVehicle with
   a fixed type).

5. **`model/Lane.java`**, **`model/Street.java`**, **`model/ParkingSpot.java`**,
   **`model/Accident.java`** — the "world". *Look for:* `Street` owns the lanes
   and parking spots; `Lane` holds a `List<Vehicle>`; `ParkingSpot` implements
   `Reservable`; `Accident` blocks a lane until an emergency clears it.

6. **`sim/Simulation.java`** — **the engine** (the biggest file). Start at
   `step(double dt)` and read it top to bottom as a checklist (see §3). Don't try
   to read every helper yet — just understand the order of operations.

7. **`sim/TrafficController.java`** — the **Phase 2 brain**. *Look for:*
   `assignParking()` (reserve a spot or redirect early), `enforceWaitLimit()`,
   `policePatrol()`.

8. **`sim/ScenarioPlan.java`** and **`sim/StatisticsTracker.java`** — supporting
   data. `ScenarioPlan` records the cars so Phase 2 can replay the *same* ones;
   `StatisticsTracker` accumulates the KPIs (waiting, speed, congestion).

9. **`gui/fx/TrafficFxApp.java`** — the GUI: controls, the animation loop, and
   the Phase 1 → Phase 2 replay. **`gui/fx/StreetCanvasRenderer.java`** draws the
   road and vehicles; **`gui/fx/FxVehicleImageCatalog.java`** picks each car's
   sprite.

> Tip: keep `OOP_CONCEPTS.txt` open beside you — it pinpoints the exact lines
> for the interface / inheritance / polymorphism examples.

---

## 2. The package map (where everything lives)

```
src/traffic/
  Main.java                 → launches the GUI
  interfaces/               → the contracts
    Movable      (move/stop)                     ← implemented by Vehicle
    Reservable   (reserve/release/isReserved)    ← implemented by ParkingSpot
  model/                    → the "nouns" (real-world things)
    Vehicle (abstract)  → Car, EmergencyVehicle → PoliceVehicle
    Lane, Street, ParkingSpot, Accident
  sim/                      → the "verbs" (rules / engine)
    Simulation          → the tick loop and all movement rules
    TrafficController    → Phase 2 reservations + redirect + police
    StatisticsTracker    → KPIs
    ScenarioPlan         → record/replay the exact traffic (same cars)
    VehicleBodyCatalog   → car body sizes from the sprite images
  gui/fx/                   → the JavaFX front end
    TrafficFxApp         → Application, controls, loop, phase replay
    StreetCanvasRenderer → draws everything onto a Canvas
    FxVehicleImageCatalog→ loads + draws the car sprites
    FxRenderSmokeTest    → headless render check
```

**Rule of thumb:** `model/` = data and small per-object behaviour; `sim/` = the
rules that coordinate many objects; `gui/fx/` = drawing and user input.

---

## 3. How one tick works (the engine)

Everything time-related funnels through **`Simulation.step(double dt)`**. It runs
this checklist every tick (the GUI calls it ~60×/sec through an accumulator):

1. **`beginVehicleStep()`** — each vehicle saves its previous position (so the
   renderer can draw smooth motion between ticks).
2. **`spawnVehicles()`** — add new cars. In normal mode they're random; in
   **replay mode** they come from a recorded `ScenarioPlan` (see §4).
3. **`tickAccident()`** — age/clear any active accident; dispatch an ambulance if
   none is responding.
4. **Phase 2 only:** `controller.assignParking()`, `enforceWaitLimit()`,
   `policePatrol()` — reserve spots, redirect early, enforce limits. Phase 1
   skips all of this.
5. **`spawnPoliceIfNeeded()` / `policeWarnWaitingDrivers()`** — keep one patrol
   car around (Phase 2) and have it move waiting blockers along.
6. **`prepareForEmergencyVehicles()`** — clear a path for ambulances.
7. **`moveAllVehicles(dt)`** — the core. For each vehicle (leaders first) it calls
   `processCarMovement` or `processEmergencyMovement`: car-following speed,
   lane-changes, and the parking decision.
8. **`resolveBodyCollisions()`** — nudge cars apart; a reckless overlap can
   trigger a crash.
9. **`tickVisualMarkers()`** — count down the little on-car badge timers.
10. **`simulateParkingTurnover(dt)`** — parked cars occasionally request to leave
    and merge back into the lane.
11. **`removeExited()`** — drop cars that drove off the end (counts them as
    processed).
12. **`recordStats()`** — tally waiting, speed, congestion for this cycle.

> The methods are deliberately small and named after what they do, so the loop
> reads like bullet points. To understand any single behaviour, jump to the
> method named after it.

**The most important helper** is `processCarMovement(Car, Lane, dt)`: it handles
the WAITING→moving recovery, accident avoidance, the three lane-change policies
(`shouldClearCurbLane`, `shouldSpilloverOutward`, `shouldTryTrafficSpillover`),
the parking commit (`canParkInSpot` → `ParkingSpot.occupy`), and finally the
Gipps-style safe-following speed update.

---

## 4. Phase 1 vs Phase 2 (and the "same cars" replay)

- A single boolean, `Simulation.phase2Enabled`, switches the rules. Phase 2 also
  turns on the controller's police patrol and camera (`setPhase2Enabled`).
- **Phase 1** lets a seeker `halt()` and wait in the curb lane when parking is
  full (`shouldWaitForParking`). That's the visible problem.
- **Phase 2** runs `TrafficController.assignParking()` *before* movement, so a
  seeker either gets a **reservation** (`assignOrUpgradeReservation` →
  `ParkingSpot.reserve`) or is **redirected early** and keeps moving.

**Same-cars comparison (record/replay):**
- While Phase 1 runs, `Simulation.startRecording()` makes it save every spawned
  car into a `ScenarioPlan` (`recordCar` / `arrivalOf`).
- In the GUI, switching the **Phase** selector to "Phase 2" calls
  `TrafficFxApp.startPhase2Replay()`: it grabs the recorded plan, builds a
  **fresh `Simulation` at cycle 0**, turns Phase 2 on, and calls
  `startReplay(plan)`. From then on `spawnFromPlan()` rebuilds the *exact same
  cars* on the same ticks, so only the *method* differs.
- (For why the colors line up, see `PHASE1_TO_PHASE2_CARS.md`.)

---

## 5. The OOP concepts (read this for the viva)

This is the part graders focus on. Exact line numbers are in `OOP_CONCEPTS.txt`;
here's the "why and where" in plain English.

### 5a. Interfaces (the contracts)

- **`Movable`** = `move()` + `stop()`. **`Vehicle` implements `Movable`**, so
  *every* vehicle promises it can move and stop. The engine uses this through the
  interface: `Simulation.halt(Movable m)` calls `m.stop()` without caring whether
  it's a Car or an ambulance — *programming to the interface*.
- **`Reservable`** = `reserve(owner)` + `release()` + `isReserved()`.
  **`ParkingSpot` implements `Reservable`**. The Phase 2 `TrafficController` holds
  a spot for one specific car before it arrives — the core of "managed parking".

### 5b. Inheritance (the 3-level hierarchy)

```
Movable (interface)
   ▲ implements
Vehicle (abstract)          ← shared state + behaviour for all vehicles
   ├── Car                  ← adds parking-seeker state, colorSeed, etc.
   └── EmergencyVehicle     ← adds a siren
          └── PoliceVehicle ← an EmergencyVehicle fixed to type "POLICE"
```

- `Vehicle` is **abstract** with an **abstract `move()`** — you can't make a bare
  `Vehicle`; only concrete subtypes exist.
- **Method overriding:** `Car.move()` and `EmergencyVehicle.move()` each override
  `Vehicle.move()`. `Car.tickVisualMarkers()` overrides the base version **and
  calls `super.tickVisualMarkers()`** (extends rather than replaces).
- **Constructor chaining:** every subclass calls `super(id)` (and
  `PoliceVehicle` calls `super(id, "POLICE")`), passing work up the chain.
- **Reuse:** Car and EmergencyVehicle inherit all of `Vehicle`'s position/speed/
  state fields and helpers without re-writing them.

### 5c. Polymorphism (one type, many behaviours)

- **Base-type collections:** `Lane` stores a `List<Vehicle>`, and
  `Street.allVehicles()` returns a `List<Vehicle>`. The same code iterates Cars,
  ambulances, and police uniformly.
- **Dynamic dispatch via base-type loops:** a loop over `street.allVehicles()`
  calls `vehicle.beginStep()` / `vehicle.tickVisualMarkers()`. For a `Car`, the
  JVM runs Car's overridden `tickVisualMarkers()`; for others, the base version
  — chosen at runtime.
- **Interface polymorphism:** `halt(Movable)` accepts anything `Movable`.
- **`instanceof` pattern matching** to pick subtype behaviour: in
  `Simulation.moveAllVehicles` (`if (v instanceof Car c) … else if (v instanceof
  EmergencyVehicle e) …`), in `hasPoliceVehicle`/`anyEmergencyOnStreet`, in the
  GUI KPI counts, and in the renderer when choosing sprites.

### 5d. Encapsulation (supporting principle)

- Model classes keep fields non-public and expose getters/setters.
- `Lane.getVehiclesInLane()` and `Street.getLanes()`/`getParkingSpots()` return
  `Collections.unmodifiableList(...)`, so outside code can read but not corrupt
  the internal lists — all mutation goes through `addVehicle`/`removeVehicle`.

---

## 6. File-by-file cheat sheet

| File | One-line job |
|---|---|
| `Main` | Launches the JavaFX GUI. |
| `Movable` | Interface: `move()` / `stop()`. |
| `Reservable` | Interface: `reserve()` / `release()` / `isReserved()`. |
| `Vehicle` | Abstract base: position/speed/state + visual timers; implements `Movable`. |
| `Car` | A car: parking-seeker state, driver profile, sprite color seed. |
| `EmergencyVehicle` | Ambulance: faster, has a siren. |
| `PoliceVehicle` | Phase 2 patrol car (subclass of EmergencyVehicle). |
| `Lane` | One lane; holds the `List<Vehicle>`; congestion + neighbour lookups. |
| `Street` | All lanes + parking spots + the active accident. |
| `ParkingSpot` | A curb spot; implements `Reservable`; tracks exit state. |
| `Accident` | A lane blockage cleared by an emergency vehicle. |
| `Simulation` | The tick engine: spawning, movement, parking, accidents, stats. |
| `TrafficController` | Phase 2: reservations, early redirect, police. |
| `StatisticsTracker` | Per-cycle + per-phase KPIs and the improvement %. |
| `ScenarioPlan` | Records/replays the exact traffic so both phases match. |
| `VehicleBodyCatalog` | Derives car body sizes from the sprite images. |
| `TrafficFxApp` | GUI: controls, animation loop, Phase 1 → Phase 2 replay. |
| `StreetCanvasRenderer` | Draws road, lanes, parking, vehicles, markers. |
| `FxVehicleImageCatalog` | Loads sprites; picks a car's image from its color seed. |
| `FxRenderSmokeTest` | Headless check that rendering works. |

---

## 7. How to run and experiment

Compile and run (from the project root):

```bash
# compile
javac --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics -d out $(find src -name "*.java")
# run the GUI
java --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics -cp out traffic.Main
```

Good first experiments to build intuition:
- Run, press **Start**, watch Phase 1 jam the curb lane. Switch the **Phase**
  selector to **Phase 2** and watch it clear (same cars, from cycle 0).
- Turn on the **IDs** / **Markers** checkboxes to see per-car state (W=waiting,
  R=redirected, Q=queued, etc. — see the Marker Legend panel).
- Add an **Accident** or **Emergency** with the buttons and watch the lane-change
  / clearance logic respond.

---

## 8. Viva quick-reference (where to point)

| If asked about… | Point to… |
|---|---|
| Interface | `Movable` (Vehicle implements), `Reservable` (ParkingSpot implements); `halt(Movable)` and `TrafficController` use them. |
| Abstract class / method | `Vehicle` is abstract with abstract `move()`. |
| Overriding + `super` | `Car.move()`, `EmergencyVehicle.move()`; `Car.tickVisualMarkers()` calls `super`. |
| Inheritance chain | `Vehicle → Car`, `Vehicle → EmergencyVehicle → PoliceVehicle`. |
| Polymorphism | `List<Vehicle>` base-type collections, dynamic dispatch in loops, `instanceof` in `moveAllVehicles`. |
| Encapsulation | non-public fields + `Collections.unmodifiableList` in `Lane`/`Street`. |
| The main loop | `Simulation.step()` — the 12-step checklist. |
| Phase 1 vs 2 | `phase2Enabled`; `TrafficController.assignParking`; record/replay via `ScenarioPlan`. |

---

## 9. Related docs in this repo

- **`OOP_CONCEPTS.txt`** — the exact `file:line` index for every OOP concept.
- **`OOP_CONCEPTS_DETAILED.md`** — longer prose explanation of each concept.
- **`PHASE1_TO_PHASE2_CARS.md`** — how the same-cars replay (and matching colors)
  works.
- **`uml/full_diagram.*`** — the full class diagram (`.puml` source plus
  SVG/PNG/PDF renders; only interface/inheritance arrows).
- **`README.md`** — build/run instructions and project layout.
