# EE364 Final Project — Roadside Parking Traffic Simulation

**Team 1 — Fall 2026**
- Ahmed Abdul Latif Al-Ghamdi (2237826)
- Ahmed Abdullah Al-Ghamdi (2339540)
- Ryan Khaled Abdul-Jabbar (2238374)
- Alwaleed Al-Malki (2336515)

## The problem we simulate

A busy multi-lane street with curb-side parking. Cars drive along, some are
looking for a parking spot. When parking is full, **Phase 1** lets parking
seekers stop and *wait in the live curb lane*, which blocks following traffic.
The problem is easy to see in Phase 1: total waiting-cycles climb, average speed
drops, throughput collapses. **Phase 2** adds a managed-parking solution —
reservations, early redirect, and police patrol — so seekers never stop in the
live lane. The headless comparison shows the exact improvement percentage.

## What this project demonstrates

- A real-life OOP simulation with classes that match the real world.
- Two interfaces (`Movable`, `Reservable`) and a 3-level inheritance hierarchy
  (`Vehicle` abstract → `Car`, `EmergencyVehicle` → `PoliceVehicle`).
- Polymorphism: `List<Vehicle>` base-type collections and `instanceof` dispatch.
- A fair Phase 1 vs Phase 2 comparison: Phase 1 *records* its generated
  scenario, Phase 2 *replays the same scenario* with the managed-parking logic.
- A JavaFX GUI with a Phase 1 / Phase 2 selector, KPI panel and Marker Legend.

## Requirements

- JDK 17 or newer (developed and tested on JDK 25).
- JavaFX is **bundled** in `lib/javafx-sdk-21.0.6/` — no separate install needed.

Run every command from the project root (the folder with this README).

## Compile

**Windows PowerShell:**
```powershell
$src = (Get-ChildItem -Recurse src -Filter *.java).FullName
javac --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics -d out $src
```

**Linux / macOS / Git Bash:**
```bash
javac --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics -d out $(find src -name "*.java")
```

## Run

**The JavaFX GUI** (use the *Phase* selector in the window to switch
Phase 1 / Phase 2; the right panel has the KPI counters and the Marker Legend):
```bash
java --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics -cp out traffic.Main
```

**Comparing Phase 1 vs Phase 2:** the GUI is the whole program. Let Phase 1
run, then switch the *Phase* selector to "Phase 2" — it re-runs the same
recorded cars under the managed-parking rules (from cycle 0) and shows the
improvement percentage in the KPI panel. The Phase 1 vs Phase 2 report files are
in `output/`.

## Tests

```bash
java --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics -cp out traffic.gui.fx.FxRenderSmokeTest
```

## Project layout

```
src/traffic/
  Main.java                  - launches the JavaFX GUI
  interfaces/
    Movable.java             - move() / stop()                       [interface]
    Reservable.java          - reserve() / release() / isReserved()  [interface]
  model/
    Vehicle.java             - abstract base, implements Movable
    Car.java                 - extends Vehicle
    EmergencyVehicle.java    - extends Vehicle
    PoliceVehicle.java       - extends EmergencyVehicle  (Phase 2 patrol)
    Lane.java, Street.java, ParkingSpot.java, Accident.java
  sim/
    Simulation.java          - the tick engine (Phase 1 + Phase 2 toggle)
    TrafficController.java   - Phase 2 brain: reservations + redirect + police
    StatisticsTracker.java   - per-cycle KPIs + per-phase totals + improvement %
    ScenarioPlan.java        - records / replays the traffic demand stream
    VehicleBodyCatalog.java
  gui/fx/
    TrafficFxApp.java        - JavaFX Application: scene, controls, KPI, legend
    StreetCanvasRenderer.java - Canvas + GraphicsContext renderer
    FxVehicleImageCatalog.java - sprite drawing
    FxRenderSmokeTest.java   - JavaFX render test

lib/javafx-sdk-21.0.6/       - bundled JavaFX (no separate install needed)
2D TOP DOWN PIXEL CARS/      - vehicle sprites used by the GUI
output/                      - the generated KPI reports
docs/javadoc/                - generated Javadoc HTML
uml/                         - full UML class diagram (.puml + svg/png/pdf)
OOP_CONCEPTS.txt             - exact file:line map of interface / inheritance /
                               polymorphism (read this for the viva)
```

## OOP concepts (the short version)

See **`OOP_CONCEPTS.txt`** for the precise line-by-line map. Headline:

- **Interfaces:** `Movable` (`Vehicle` implements it) and `Reservable`
  (`ParkingSpot` implements it; `TrafficController` uses it).
- **Inheritance:** `Vehicle` (abstract) → `Car`, `EmergencyVehicle` →
  `PoliceVehicle`. Abstract `move()` overridden in each subclass; `Car`
  overrides `tickVisualMarkers()` and calls `super`.
- **Polymorphism:** `List<Vehicle>` collections treat every subtype uniformly;
  looping over them and calling `vehicle.beginStep()` dispatches dynamically;
  `halt(Movable)` works through the interface; `instanceof` chooses subtype
  behaviour where needed.

## UML diagram

- `uml/full_diagram.puml` — the full class diagram (all classes with their
  non-private members; only implements/extends arrows).
- `uml/full_diagram.svg`, `uml/full_diagram.pdf`, and `uml/full_diagram.png` —
  rendered views (SVG and PDF are vector — no quality loss at any zoom).
  Re-render from the `.puml` online at <https://www.plantuml.com/plantuml/>.

## How Phase 1 vs Phase 2 are compared fairly

When you finish Phase 1 and switch the GUI's *Phase* selector to Phase 2, the
app **records** the traffic Phase 1 generated (every car's spawn tick, seeking
flag, aggressive/stubborn traits, driver profile, body size, plus emergency
arrivals) into a `ScenarioPlan`, then restarts at cycle 0 and **replays the
exact same cars** under the managed-parking logic. Both phases face identical
traffic demand — only the method differs — so the improvement percentage in the
KPI panel is a fair before/after.
