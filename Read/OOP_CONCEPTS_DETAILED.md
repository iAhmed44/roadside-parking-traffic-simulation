# OOP Concepts — Detailed Guide

> Companion to `OOP_CONCEPTS.txt` (the quick file:line index).
> This document explains **what** each OOP concept is, **why** we used it in
> this project, **where** it lives, and **how** it works — with code snippets
> and design rationale you can use in the viva.

---

## Table of Contents

1. [Why OOP in a traffic simulation?](#1-why-oop-in-a-traffic-simulation)
2. [Interfaces](#2-interfaces)
   - [`Movable`](#21-movable)
   - [`Reservable`](#22-reservable)
3. [Inheritance](#3-inheritance)
   - [The 3-level hierarchy](#31-the-3-level-hierarchy)
   - [Abstract classes & abstract methods](#32-abstract-classes--abstract-methods)
   - [Method overriding (`@Override`) and `super`](#33-method-overriding-override-and-super)
   - [Constructor chaining](#34-constructor-chaining)
4. [Polymorphism](#4-polymorphism)
   - [Subtype polymorphism in collections](#41-subtype-polymorphism-in-collections)
   - [Dynamic dispatch via base-type loops](#42-dynamic-dispatch-via-base-type-loops)
   - [Interface polymorphism — `halt(Movable)`](#43-interface-polymorphism--haltmovable)
   - [Runtime type checks — `instanceof`](#44-runtime-type-checks--instanceof)
5. [Encapsulation (supporting principle)](#5-encapsulation-supporting-principle)
6. [Putting it all together — one full tick of the simulator](#6-putting-it-all-together--one-full-tick-of-the-simulator)
7. [Quick-reference table](#7-quick-reference-table)

---

## 1. Why OOP in a traffic simulation?

A real-world traffic problem has many *kinds* of things on the road (cars,
ambulances, police, parking spots, accidents, lanes, the street itself) and
each kind has its own state and rules. OOP lets us:

- model each *kind of thing* as its own **class** with its own state,
- share common rules with **inheritance** (every vehicle moves and stops),
- keep state safe with **encapsulation** (the engine can't accidentally
  overwrite a parking spot's internals),
- treat related things uniformly with **polymorphism** (the engine iterates
  `Vehicle`s without caring which subclass each one is),
- and lock down *behaviour contracts* with **interfaces** (everything that
  moves promises to implement `move()` and `stop()`).

Without OOP, the engine would be a tangle of `if (type == 1) ... else if
(type == 2) ...`. With OOP, each class knows how to behave when asked.

---

## 2. Interfaces

> **What is an interface?** A contract. It lists method signatures but no
> implementation. Any class that says `implements X` promises to provide every
> method `X` declares. The caller can then work with the interface type without
> caring *which* concrete class is underneath — this is the core of
> *programming to an interface, not an implementation.*

We have **two** interfaces, both genuinely used (not just declared).

### 2.1 `Movable`

**File:** [`src/traffic/interfaces/Movable.java`](src/traffic/interfaces/Movable.java)

```java
public interface Movable {           // Movable.java:12
    void move();                     // Movable.java:15
    void stop();                     // Movable.java:18
}
```

**Why we need it.** The simulator deals with several kinds of vehicles
(`Car`, `EmergencyVehicle`, `PoliceVehicle`) and each one needs to be able to
move forward one tick and to come to a halt. Rather than the engine knowing
about every subclass, we promise via this interface: *anything Movable
supports `move()` and `stop()`*.

**Who implements it.**

```java
// Vehicle.java:9
public abstract class Vehicle implements Movable {
    ...
    @Override
    public abstract void move();     // Vehicle.java:63 — every subclass must
                                     // provide its own move()
    @Override
    public void stop() {             // Vehicle.java:66-68 — shared, concrete
        speed = 0;
        state = STATE_WAITING;
    }
}
```

`Vehicle` (the abstract base) implements `Movable`, gives a concrete `stop()`
to everyone, and leaves `move()` abstract so each subtype writes its own.

**How it's used through the interface type.** The engine has a small helper
that proves we program to the interface:

```java
// Simulation.java:776 — programming-to-the-interface helper
private static void halt(Movable movable) {
    movable.stop();
}
```

It is called at the stop sites:
- when a parking seeker must wait because the curb is full,
- when a car is blocked by an accident ahead.

The caller never says *"if it's a Car do this, if it's an EmergencyVehicle do
that"* — it just says `halt(c)`. Whichever subtype is underneath, the right
`stop()` runs. This is the whole point of an interface.

### 2.2 `Reservable`

**File:** [`src/traffic/interfaces/Reservable.java`](src/traffic/interfaces/Reservable.java)

```java
public interface Reservable {                // Reservable.java:19
    boolean reserve(Object owner);           // Reservable.java:22
    void release();                          // Reservable.java:25
    boolean isReserved();                    // Reservable.java:28
}
```

**Why we need it.** Phase 2's whole improvement is *managed parking* — the
`TrafficController` holds a spot for one specific car before that car arrives,
so seekers stop fighting over the same curb space. The "can be reserved
ahead of time" capability is exactly the kind of thing an interface should
express. Phase 1 simply never calls `reserve()`, so the same `ParkingSpot`
class works in both phases.

**Who implements it.**

```java
// ParkingSpot.java:14
public class ParkingSpot implements Reservable {
    ~ reserved: boolean;
    ~ reservedBy: Car;

    @Override
    public boolean reserve(Object owner) {      // ParkingSpot.java:88-89
        if (occupied || reserved) return false;
        if (!(owner instanceof Car)) return false;
        reserved = true;
        reservedBy = (Car) owner;
        return true;
    }

    @Override
    public void release() {                     // ParkingSpot.java:100-101
        reserved = false;
        reservedBy = null;
    }

    @Override
    public boolean isReserved() { return reserved; }   // ParkingSpot.java:106-107
}
```

**How it's used.** The Phase 2 brain works through the interface methods:

```java
// TrafficController.java:218  — reserve a spot for a specific car
if (!newSpot.reserve(car)) { return false; }

// TrafficController.java:222/235/253/334 — give it back when done
current.release();

// TrafficController.java:249/273 — ask the spot whether it's held
if (spot.isReserved() && spot.getReservedBy() != car) ...
```

The controller never reaches inside the parking spot to flip a private field
— it goes through the interface contract every time.

---

## 3. Inheritance

> **What is inheritance?** A class declared `class B extends A` *inherits*
> A's fields and methods. It can use them as-is, override them, or add new
> ones. We model "B *is a* kind of A" relationships this way.

### 3.1 The 3-level hierarchy

```
Movable  (interface)
   ▲ implements
Vehicle  (abstract)            ← src/traffic/model/Vehicle.java:9
   ├── Car                     ← src/traffic/model/Car.java:9
   └── EmergencyVehicle        ← src/traffic/model/EmergencyVehicle.java:8
          └── PoliceVehicle    ← src/traffic/model/PoliceVehicle.java:10
```

- **`Vehicle`** is everything common: id, position, speed, state, the lane it
  is in, the visual heading angle, the lane-change flash timer, helpers like
  `stop()` / `beginStep()` / `tickVisualMarkers()`. It is `abstract` because a bare
  `Vehicle` has no real `move()` — only concrete subtypes do.
- **`Car`** adds parking-seeker state (`seekingParking`, `targetParkingSpot`,
  `hasReservation`, `stubbornParker`) and the police-warning / camera-fine
  flash timers used by the GUI. It overrides `move()` and
  `tickVisualMarkers()`.
- **`EmergencyVehicle`** adds `emergencyType` ("AMBULANCE" / "POLICE") and a
  `sirenActive` flag. It overrides `move()`.
- **`PoliceVehicle`** is the most specific — it `extends EmergencyVehicle`
  and only sets the type to `"POLICE"`. It inherits everything else.

### 3.2 Abstract classes & abstract methods

```java
// Vehicle.java:9
public abstract class Vehicle implements Movable {
    ...
    // Vehicle.java:63 — no body; subclasses MUST provide one
    @Override
    public abstract void move();
}
```

**Why abstract?** Two reasons:
1. **You should never be able to create a bare `Vehicle`** — the world only
   has cars, ambulances and police, not abstract "vehicles". `abstract`
   prevents `new Vehicle(...)`.
2. **`move()` doesn't have a single right answer** — a `Car` advances
   forward, an `EmergencyVehicle` does too but with siren rules. Making it
   `abstract` forces each subclass to provide its own.

### 3.3 Method overriding (`@Override`) and `super`

Every concrete subclass supplies `move()`:

```java
// Car.java:65-66
@Override
public void move() {
    if (STATE_WAITING.equals(state)) { waitingTime++; return; }
    position += speed * FALLBACK_STEP_SECONDS;
}

// EmergencyVehicle.java:24-25
@Override
public void move() {
    position += speed * FALLBACK_STEP_SECONDS;
}
```

`Car` also extends the base's visual-marker tick:

```java
// Car.java:108-110
@Override
public void tickVisualMarkers() {
    super.tickVisualMarkers();   // do the base behaviour first
    if (warningFlashTicks > 0) warningFlashTicks--;
    if (cameraFineFlashTicks > 0) cameraFineFlashTicks--;
}
```

This is `super` doing real work: *call the parent's version, then add my
own behaviour on top.* The base class's flash-timer ticking still happens,
and the Car's Phase-2 timers tick alongside.

### 3.4 Constructor chaining

Subclass constructors call the parent constructor with `super(...)`:

```java
// Car.java:50
public Car(int id, boolean seekingParking) {
    super(id);          // sets up the Vehicle fields
    this.seekingParking = seekingParking;
}

// EmergencyVehicle.java:17
public EmergencyVehicle(int id, String type) {
    super(id);          // sets up the Vehicle fields
    this.emergencyType = type;
    this.sirenActive = true;
}

// PoliceVehicle.java:13
public PoliceVehicle(int id) {
    super(id, "POLICE");  // 2-level chain: PoliceVehicle -> EmergencyVehicle -> Vehicle
}
```

`PoliceVehicle` is the cleanest example — it doesn't *do* anything extra in
its constructor, it just promises to call `super` with the right type. All
of `Vehicle`'s and `EmergencyVehicle`'s setup happens automatically.

---

## 4. Polymorphism

> **What is polymorphism?** "One name, many forms." The same call site can
> behave differently depending on the actual runtime type. We use four
> flavours: collections of a base type, base-type loops, interface
> parameters, and explicit `instanceof` dispatch.

### 4.1 Subtype polymorphism in collections

The lane doesn't know about `Car` or `EmergencyVehicle` — it just holds
`Vehicle`s:

```java
// Lane.java:15
private final List<Vehicle> vehiclesInLane;

// Street.java:102 — gather every vehicle on every lane into one list
public List<Vehicle> allVehicles() {
    List<Vehicle> all = new ArrayList<>();
    for (Lane lane : lanes)
        for (Vehicle vehicle : lane.getVehiclesInLane())
            all.add(vehicle);
    return all;
}
```

The whole engine can iterate `street.allVehicles()` and treat each one as a
`Vehicle`. The actual subclass underneath doesn't matter at the iteration
site — the *behaviour* will be the right one because of dynamic dispatch
(next subsection).

### 4.2 Dynamic dispatch via base-type loops

The base type is enough — at runtime Java calls the most specific override:

```java
// Simulation.java:162-163
for (Vehicle vehicle : street.allVehicles())
    vehicle.beginStep();

// Simulation.java:1367-1368
for (Vehicle vehicle : street.allVehicles())
    vehicle.tickVisualMarkers();
```

When the loop reaches a `Car`, `Car.tickVisualMarkers()` runs (which calls
`super.tickVisualMarkers()` *and* decrements the police/camera flash
timers). When it reaches an `EmergencyVehicle`, the base
`Vehicle.tickVisualMarkers()` runs. We just call `vehicle.tickVisualMarkers()`
on the base type and Java picks the right override at every step.

### 4.3 Interface polymorphism — `halt(Movable)`

The stop helper is *typed by the interface*, not by a class:

```java
// Simulation.java:776
private static void halt(Movable movable) {
    movable.stop();
}
```

Anything that `implements Movable` can be passed in. Today that means
`Vehicle` (and therefore `Car`, `EmergencyVehicle`, `PoliceVehicle`). If
tomorrow we added a `Bicycle implements Movable`, this helper would already
work on it. That extensibility is the *point* of programming to an interface.

### 4.4 Runtime type checks — `instanceof`

Sometimes we genuinely need to branch on the *concrete* subtype, e.g. to
pick a different speed for police vehicles. Java's pattern-matching
`instanceof` keeps that clean:

```java
// Simulation.java:551-553 — engine dispatches movement by subtype
if (v instanceof Car c)              processCarMovement(c, c.getCurrentLane(), dt);
else if (v instanceof EmergencyVehicle e) processEmergencyMovement(e, e.getCurrentLane(), dt);

// Simulation.java:1128 — police drive a bit slower than ambulances
double desiredSpeed = ev instanceof PoliceVehicle ? POLICE_SPEED : EMERGENCY_SPEED;

// Simulation.java:382 — is there a police car anywhere?
for (Vehicle vehicle : street.allVehicles())
    if (vehicle instanceof PoliceVehicle) return true;

// TrafficFxApp.java:439/455 — KPI counters in the GUI
if (v instanceof Car car && car.isRedirected()) count++;
if (v instanceof EmergencyVehicle && !(v instanceof PoliceVehicle)) count++;
```

The `instanceof Car c` form *binds a variable* `c` of type `Car` only inside
the `if`, so we get type safety with no cast. We use `instanceof` only when
the behaviour really *is* subtype-specific (different speeds, different
sprites, different KPIs).

---

## 5. Encapsulation (supporting principle)

> **What is encapsulation?** Keeping fields private (or controlled) and
> exposing behaviour through methods, so callers can't accidentally break
> the object's invariants.

Examples in our code:

- **Private fields with getters/setters.** Model classes like
  `Simulation` and `StatisticsTracker` keep every field `private` and
  publish a clean API.
- **Defensive read-only views.** `Lane.getVehiclesInLane()`,
  `Street.getLanes()`, `Street.getParkingSpots()` all return
  `Collections.unmodifiableList(...)`. A caller that holds the list cannot
  `add` or `remove` from it — the only legal way is through
  `Lane.addVehicle()` / `removeVehicle()`, which also fixes the
  back-reference `Vehicle.currentLane`.
- **Validation in setters.** `Car.setPreferredCruiseSpeed()` clamps the
  value to `[9.0, 16.0]`. `Street`'s constructor refuses zero lanes /
  negative parking. Encapsulation isn't just "use private"; it's "*the
  class enforces its own invariants*."

This matters because the simulator runs 60 times per second across many
objects. One stray write to a field that should have been validated would
cause silent corruption that's hard to debug. Encapsulation keeps each
object honest.

---

## 6. Putting it all together — one full tick of the simulator

To make it concrete, here's how all four concepts cooperate in **one tick**
of the simulation:

1. **`Simulation.step(dt)`** runs the tick. The first thing it does is
   ```java
   for (Vehicle vehicle : street.allVehicles())
       vehicle.beginStep();
   ```
   That loop uses **subtype polymorphism** (the list's element type is the
   base class `Vehicle`) plus **dynamic dispatch** (calling `beginStep()` on
   the base type runs whatever subclass override exists).

2. **Phase 2 controller runs** if enabled. `TrafficController.assignParking()`
   reserves a spot for each seeker by calling
   `newSpot.reserve(car)` — **interface in action**: it never touches
   `ParkingSpot` private state, only the `Reservable` contract.

3. **Movement runs.** `moveAllVehicles` uses `instanceof` to dispatch on
   subtype:
   ```java
   if (v instanceof Car c) processCarMovement(c, ...);
   else if (v instanceof EmergencyVehicle e) processEmergencyMovement(e, ...);
   ```
   **Runtime type check polymorphism**.

4. **Inside `processCarMovement`**, if the car needs to stop, we call
   `halt(c)` — **interface polymorphism** through the `Movable` helper.

5. **End of tick.** A loop calls `vehicle.tickVisualMarkers()` on every
   vehicle again. For `Car` instances, **method overriding + `super` call**
   means the base ticking *and* the Phase-2 flash timers all advance.

6. **Stats are recorded** through `stats.recordCycle(...)`,
   `stats.noteRedirectedCar()`, `stats.recordParking(...)` — pure
   **encapsulation**: the engine pushes data in, it never reaches into
   `StatisticsTracker` internals.

One tick exercises **all four** OOP concepts. That's why removing or
weakening any one of them would actually break the design — they aren't
just "checked boxes for the rubric."

---

## 7. Quick-reference table

| Concept | Where (`file:line`) | What it shows |
|---|---|---|
| **Interface declared** | `Movable.java:12` (`move`, `stop`) · `Reservable.java:19` (`reserve`, `release`, `isReserved`) | Pure contracts |
| **Interface implemented** | `Vehicle.java:9` (`implements Movable`) · `ParkingSpot.java:14` (`implements Reservable`) | Contract honored |
| **Interface used by type** | `Simulation.java:776` (`halt(Movable)`) · `TrafficController.java:218/222/249/...` | Programming-to-interface |
| **Abstract class** | `Vehicle.java:9` | Can't be instantiated alone |
| **Abstract method** | `Vehicle.java:63` (`abstract void move()`) | Forces subclass to implement |
| **Subclass `extends`** | `Car.java:9` · `EmergencyVehicle.java:8` · `PoliceVehicle.java:10` | 3-level hierarchy |
| **`@Override`** | `Car.java:65, 108` · `EmergencyVehicle.java:24` · `ParkingSpot.java:88/100/106` · `Vehicle.java:62` | Compiler-checked overrides |
| **`super.method()`** | `Car.java:110` | Extend, don't replace |
| **`super(...)` constructor** | `Car.java:50` · `EmergencyVehicle.java:17` · `PoliceVehicle.java:13` | Constructor chain |
| **Polymorphic collection** | `Lane.java:15` (`List<Vehicle>`) · `Street.java:102` (`List<Vehicle>`) | All subtypes uniformly |
| **Dynamic dispatch (base-type loop)** | `Simulation.java:162-163` (`beginStep`), `:1367-1368` (`tickVisualMarkers`) | Right override runs |
| **Interface polymorphism** | `Simulation.java:776` (`halt(Movable)`) | Works on any `Movable` |
| **`instanceof` dispatch** | `Simulation.java:551/553/382/458/1128` · `TrafficFxApp.java:439/455` · `ParkingSpot.java:92` | Subtype-specific branch |
| **Encapsulation: private fields + accessors** | All `sim/*` classes, `Simulation`, `StatisticsTracker`, `TrafficController` | Internals hidden |
| **Encapsulation: unmodifiable views** | `Lane.java:74` (`getVehiclesInLane`), `Street.getLanes/getParkingSpots` | No outside mutation |

---

### TL;DR for the viva

- **Interfaces** — `Movable` (move/stop) and `Reservable` (reserve/release/isReserved); both are *used through their interface type*, not just declared.
- **Inheritance** — 3-level: `Vehicle` (abstract) → `Car`, `EmergencyVehicle` → `PoliceVehicle`; uses `@Override`, `super`, and constructor chaining.
- **Polymorphism** — `List<Vehicle>` base-type collections, dynamic dispatch in loops, `halt(Movable)`, and `instanceof` dispatch across engine + GUI.
- **Encapsulation** — `private` fields, validating setters, `Collections.unmodifiableList(...)` defensive views.
