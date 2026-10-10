# Phase 1 → Phase 2: Sharing the Same Cars

## Short answer

When you finish Phase 1 in the GUI and switch the **Phase** selector to "Phase 2",
the app re-runs the **exact same cars** Phase 1 generated — from cycle 0, under
the managed-parking rules. Only the *method* changes, not the traffic, so the
improvement percentage in the KPI panel is a fair before/after.

## Where the cars are stored

The cars are **not saved to disk**. They live in a `ScenarioPlan` object in
memory ([`src/traffic/sim/ScenarioPlan.java`](src/traffic/sim/ScenarioPlan.java)).

While Phase 1 runs, every time the simulation spawns a car it appends a
`CarArrival` record to the plan (in `Simulation.spawnVehicles`), holding
everything needed to recreate that car later:

- spawn `tick`
- `seeking` flag (is this a parking seeker?)
- `aggressive`, `stubborn` driver traits
- `cruiseSpeed`, `accel`, `brake`, `headway`
- `bodyLength`, `bodyWidth`
- `entryLaneHint` (which lane it entered on)

Emergency arrivals are recorded the same way. The whole `ScenarioPlan` lives
only inside the running JVM — there is no file, no serialization, no JSON.

## How the GUI does it

The flow is wired into `TrafficFxApp` (`startPhase2Replay`) on top of three
`Simulation` methods:

```java
// While Phase 1 runs, it records every car it spawns:
simulation.startRecording();            // recordPlan = new ScenarioPlan()

// When you switch the Phase selector to "Phase 2":
ScenarioPlan plan = simulation.getRecordedPlan();   // the recorded Phase 1 cars
Simulation replay = new Simulation(/* same road, cycle count = recorded ticks */);
replay.setPhase2Enabled(true);
replay.startReplay(plan);               // replayMode = true, arrivals indexed by tick
simulation = replay;                    // cycle counter is now back at 0
```

### What `startReplay(plan)` does

```java
public void startReplay(ScenarioPlan plan) {
    this.replayMode = true;
    this.replayByTick = plan.arrivalsByTick();
    this.replayEmergencyTicks = plan.emergencyTickSet();
}
```

It flips `replayMode = true` and indexes the recorded arrivals by their spawn
tick for fast lookup.

### What happens each tick in replay mode

With `replayMode` on, spawning skips the random generator and calls
`spawnFromPlan()`, which rebuilds each recorded car on the matching tick:

```java
private void spawnFromPlan() {
    List<ScenarioPlan.CarArrival> arrivals = replayByTick.get(currentCycle);
    if (arrivals != null) {
        for (ScenarioPlan.CarArrival a : arrivals) {
            Car car = carFromArrival(a);   // rebuild the exact car (same traits/size)
            Lane lane = pickReplayLane(a.entryLaneHint(), car.getBodyLength());
            if (lane == null) break;
            lane.addVehicle(car);
        }
    }
    // (emergency replay follows the same pattern)
}
```

So on every tick, Phase 2 reconstructs the cars Phase 1 spawned on that same
tick, with the same traits and entry lane — the same traffic, handled by the
managed-parking logic.

## A note on car colors

A car's sprite/color is derived purely from its id
(`carSprites.get(floorMod(id * 7, 20))` in
[`FxVehicleImageCatalog.java`](src/traffic/gui/fx/FxVehicleImageCatalog.java)).
The replayed cars are the same cars (same arrival ticks and traits), but Phase 2
also spawns police-patrol cars that consume ids, so a given car's color may not
line up one-to-one with its Phase 1 appearance. The *cars themselves* are
identical; only the color mapping can shift.

## Summary

| Question | Answer |
|---|---|
| Same cars in Phase 2 as Phase 1? | Yes — switching to Phase 2 replays Phase 1's recorded cars |
| Where are they stored? | In memory, in a `ScenarioPlan` on the Phase 1 `Simulation` |
| File format? | None — pure Java object, same JVM process |
| How does Phase 2 get them? | `startPhase2Replay()` → `getRecordedPlan()` + a fresh sim with `startReplay(plan)`, reset to cycle 0 |
| Does the cycle reset? | Yes — Phase 2 is a fresh sim that starts at cycle 0 |

## Phase 2 parking fixes

Two logic issues were found while investigating "cars reserve a spot but never take it" and "too many redirected cars," and fixed:

1. **Committed seekers were pulled out of the curb lane (`Simulation.shouldTryTrafficSpillover`).** A seeker decelerating toward its reserved spot, sitting behind a slow/stopped car near that spot, could be lane-changed into lane 1 by the generic traffic-spillover rule; the next tick its reservation was released as "off lane 0," so the spot sat reserved-but-never-taken. Fix: skip discretionary spillover when `isCommittedToImmediateParking(c, lane)` is true (the same exemption `shouldClearCurbLane` already made).

2. **Redirect was permanent (`Car.parkingIntent` + `TrafficController.assignParking`).** Once a seeker was redirected off a full curb it never sought again, even if a spot opened right ahead — so freed spots near already-redirected cars went unused. Fix: a new `parkingIntent` flag (true until the car actually parks) lets a redirected car resume seeking when a genuinely free spot opens ahead in the curb lane, as long as it isn't mid-enforcement (police/camera flash) and there's no active accident.

Verified by a clean build and by exercising the GUI's Phase 1 → Phase 2 replay on the same recorded cars: actual parks increase and total waiting stays at 0, with throughput/usage/congestion unchanged. The gain is bounded because parking demand far exceeds the few curb spots, so the curb stays near-saturated and most seekers still cannot park — the fix mainly recovers spots that were previously wasted.
