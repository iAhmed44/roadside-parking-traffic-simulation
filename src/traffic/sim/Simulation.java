package traffic.sim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import traffic.interfaces.Movable;
import traffic.model.*;

/**
 * Fixed-step simulation engine. All rules live here so that
 * the domain objects (Car, Lane, ...) stay small and explainable.
 *
 * Model: continuous road units. Cars carry visible body dimensions, and
 * collisions are checked against those same body intervals.
 */
public class Simulation {

    // =====================================================================
    // Configuration and constants
    // =====================================================================
    private int currentCycle;
    private int maxCycles;
    private boolean phase2Enabled;
    private boolean running;

    private final Street street;
    private final TrafficController controller;
    private final StatisticsTracker stats;
    private final Random rng = new Random();
    private final VehicleBodyCatalog bodyCatalog = new VehicleBodyCatalog();

    // --- Scenario record/replay (for a fair Phase 1 vs Phase 2 comparison) ---
    // When recordPlan != null, Phase 1 records every car/emergency it spawns.
    // When replayMode is true, spawning is driven by a recorded plan instead of
    // the RNG, so Phase 2 faces the exact same traffic demand as Phase 1.
    private ScenarioPlan recordPlan;
    private boolean replayMode;
    private Map<Integer, List<ScenarioPlan.CarArrival>> replayByTick;
    private Set<Integer> replayEmergencyTicks;
    // Ids of cars already counted as "redirected" so each is counted once.
    private final Set<Integer> redirectedSeen = new HashSet<>();

    // Exposed through setters for the GUI.
    private int nextVehicleId = 1;
    private int trafficDemandPer100Ticks; // total cars spawned across all lanes per 100 ticks
    private double parkingSeekerRatio = 0.70; // fraction of new cars hunting for a spot
    private double emergencySpawnRate = 0.005;
    private double parkingTurnover = 0.0005; // chance per parked spot per fixed step to vacate
    private boolean movementRandomnessEnabled;
    private static final int PARKING_LANE_ID = 0;
    private static final double ACCIDENT_LOOKAHEAD = 4.0;
    private static final double MIN_PARKING_WAIT_POSITION = 6.0;
    private static final double PARKING_WAIT_DISTANCE = 2.0;
    private static final double QUEUE_LANE_CHANGE_LOOKAHEAD = 9.0;
    private static final double STOPPING_BUFFER = 0.18;
    public static final double FIXED_STEP_SECONDS = 1.0 / 60.0;
    private static final double NORMAL_CRUISE_SPEED = 13.5;
    private static final double SLOW_CRUISE_SPEED = 7.0;
    private static final double REDIRECT_SPEED = 10.0;
    private static final double CAUTIOUS_CRUISE_SPEED = 12.0;
    private static final double BRISK_CRUISE_SPEED = 14.2;
    private static final double ASSERTIVE_CRUISE_SPEED = 15.0;
    private static final double POLICE_SPEED = 14.0;
    private static final double EMERGENCY_SPEED = 16.0;
    private static final double CURB_CLEAR_START_POSITION = 4.0;
    private static final double PARKING_COMMIT_DISTANCE = 5.5;
    private static final double MERGE_PRESSURE_RANGE = 8.0;
    private static final double SPECIAL_YIELD_SPEED = 6.0;
    private static final double POST_MERGE_BRAKE_SPEED = 9.5;
    private static final double MIN_IMMINENT_MERGE_GAP = 1.4;
    private static final double PARKING_APPROACH_MIN_SPEED = 2.8;
    private static final double MAX_HELPABLE_GAP_DELTA = 0.8;
    private static final double STRONG_SPILLOVER_CONGESTION_DELTA = 0.10;
    private static final double EXIT_REQUIRED_AHEAD = 2.2;
    private static final double EXIT_REQUIRED_BEHIND = 2.0;
    private static final double EXIT_MERGE_ZONE_BUFFER = 0.28;
    private static final double EXIT_STARVATION_SECONDS = 5.0;
    // Ambulances only need to be alongside the accident to clear it.
    private static final double ACCIDENT_CLEAR_RADIUS = 2.0;

    // =====================================================================
    // Constructor
    // =====================================================================

    public Simulation(int lanes, int streetLength, int parkingSpots, int maxCycles) {
        this.street = new Street(lanes, streetLength, parkingSpots);
        this.controller = new TrafficController(street, 25);
        this.stats = new StatisticsTracker();
        this.maxCycles = maxCycles;
        this.trafficDemandPer100Ticks = 15;
    }

    // =====================================================================
    // Main tick
    // =====================================================================
    public void tick() {
        step(FIXED_STEP_SECONDS);
    }

    /**
     * Advances one fixed-duration simulation step. The GUI calls this through
     * an accumulator so rendering can stay near 60 FPS while world speed varies.
     */
    public void step(double dt) {
        currentCycle++;

        // The tick loop runs a short checklist each step. Think of it like:
        // 1) Let cars prepare, 2) add new cars, 3) handle accidents, 4) move cars,
        // 5) fix collisions, 6) update visual flags, 7) handle parked cars,
        // 8) remove cars that left the street, 9) collect statistics.
        beginVehicleStep();
        spawnVehicles();
        tickAccident();

        // Phase 2 controller acts BEFORE movement so its reservations and
        // redirects take effect this same tick. Phase 1 skips all of this.
        if (phase2Enabled) {
            controller.assignParking();
            controller.enforceWaitLimit();
            controller.policePatrol();
        }
        spawnPoliceIfNeeded();
        policeWarnWaitingDrivers();

        prepareForEmergencyVehicles();
        moveAllVehicles(dt);
        resolveBodyCollisions();
        tickVisualMarkers();
        simulateParkingTurnover(dt);
        removeExited();
        recordStats();
    }

    public void run() { // headless mode
        running = true;
        while (running && currentCycle < maxCycles)
            tick();
    }

    public void report() {
        System.out.println("=== Simulation Report ===");
        System.out.println("Cycles            : " + currentCycle);
        System.out.println("Cars processed    : " + stats.getCarsProcessed());
        System.out.println("Total waiting-cycles: " + stats.getTotalWaitTime());
        System.out.printf(Locale.US, "Avg speed         : %.2f%n", stats.getAvgSpeed());
        System.out.println("Blocked-lane cycles: " + stats.getBlockedLaneCycles());
        if (stats.isInPhase2()) {
            System.out.printf(Locale.US, "Improvement (Phase1 -> Phase2): %.1f%%%n",
                    stats.computeImprovement());
        }
    }

    // =====================================================================
    // Spawning
    // =====================================================================
    private void beginVehicleStep() {
        // Polymorphic call: each vehicle runs its own beginStep() via the base type.
        for (Vehicle vehicle : street.allVehicles())
            vehicle.beginStep();
    }

    private void spawnVehicles() {
        // Replay mode (Phase 2 comparison): inject the cars Phase 1 recorded
        // instead of generating new random ones.
        if (replayMode) {
            spawnFromPlan();
            return;
        }

        double expectedCarsThisTick = trafficDemandPer100Ticks / 100.0;
        int carsToSpawn = (int) expectedCarsThisTick;
        if (rng.nextDouble() < expectedCarsThisTick - carsToSpawn)
            carsToSpawn++;

        for (int i = 0; i < carsToSpawn; i++) {
            Car car = createCar();
            Lane lane = chooseRandomOpenEntryLane(car.getBodyLength());
            // Record the demand (the car and its chosen entry lane) so Phase 2
            // can replay this exact arrival later.
            if (recordPlan != null)
                recordPlan.recordCar(arrivalOf(car, lane));
            if (lane == null)
                break;
            lane.addVehicle(car);
        }

        if (rng.nextDouble() < emergencySpawnRate) {
            Lane lane = street.getLanes().get(rng.nextInt(street.getNumberOfLanes()));
            EmergencyVehicle ev = new EmergencyVehicle(nextVehicleId++, "AMBULANCE");
            bodyCatalog.applyTo(ev);
            if (isEntryClear(lane, ev.getBodyLength())) {
                ev.setPositionExact(ev.getBodyLength() / 2.0);
                ev.setSpeedExact(EMERGENCY_SPEED);
                lane.addVehicle(ev);
                if (recordPlan != null)
                    recordPlan.recordEmergency(currentCycle);
            }
        }
    }

    // ---- Scenario record/replay helpers ----

    /** Start capturing every spawned car/emergency into a fresh plan (Phase 1). */
    public void startRecording() {
        this.recordPlan = new ScenarioPlan();
    }

    /** The plan recorded so far (call after a Phase 1 run). */
    public ScenarioPlan getRecordedPlan() {
        if (recordPlan != null)
            recordPlan.setRecordedTicks(currentCycle);
        return recordPlan;
    }

    /** Drive spawning from a previously recorded plan (Phase 2 replay). */
    public void startReplay(ScenarioPlan plan) {
        this.replayMode = true;
        this.replayByTick = plan.arrivalsByTick();
        this.replayEmergencyTicks = plan.emergencyTickSet();
    }

    public boolean isReplayMode() {
        return replayMode;
    }

    private ScenarioPlan.CarArrival arrivalOf(Car car, Lane lane) {
        return new ScenarioPlan.CarArrival(currentCycle,
                car.isSeekingParking(), car.isAggressiveDriver(), car.isStubbornParker(),
                car.getPreferredCruiseSpeed(), car.getAccelerationRate(),
                car.getBrakingRate(), car.getHeadwayBuffer(),
                car.getBodyLength(), car.getBodyWidth(),
                lane == null ? -1 : lane.getLaneId(), car.getColorSeedId());
    }

    private void spawnFromPlan() {
        List<ScenarioPlan.CarArrival> arrivals = replayByTick.get(currentCycle);
        if (arrivals != null) {
            for (ScenarioPlan.CarArrival a : arrivals) {
                Car car = carFromArrival(a);
                Lane lane = pickReplayLane(a.entryLaneHint(), car.getBodyLength());
                if (lane == null)
                    break; // same "stop spawning this tick if the entrance is full" rule
                lane.addVehicle(car);
            }
        }
        if (replayEmergencyTicks.contains(currentCycle)) {
            EmergencyVehicle ev = new EmergencyVehicle(nextVehicleId++, "AMBULANCE");
            bodyCatalog.applyTo(ev);
            Lane lane = pickReplayLane(-1, ev.getBodyLength());
            if (lane != null) {
                ev.setPositionExact(ev.getBodyLength() / 2.0);
                ev.setSpeedExact(EMERGENCY_SPEED);
                lane.addVehicle(ev);
            }
        }
    }

    // Rebuild a car exactly as Phase 1 created it: same seeking flag, driver
    // profile, aggressive/stubborn traits and body size — only the id is new.
    private Car carFromArrival(ScenarioPlan.CarArrival a) {
        Car c = new Car(nextVehicleId++, a.seeking());
        c.setBodySize(a.bodyLength(), a.bodyWidth());
        c.setPositionExact(c.getBodyLength() / 2.0);
        c.setSpeedExact(SLOW_CRUISE_SPEED);
        c.setAggressiveDriver(a.aggressive());
        c.setStubbornParker(a.stubborn());
        c.setPreferredCruiseSpeed(a.cruiseSpeed());
        c.setAccelerationRate(a.accel());
        c.setBrakingRate(a.brake());
        c.setHeadwayBuffer(a.headway());
        c.setColorSeed(a.colorSeed()); // render the same sprite/color as in Phase 1
        return c;
    }

    // Deterministic entry-lane choice for replay (no RNG): prefer the recorded
    // lane, otherwise the first lane whose entrance is clear.
    private Lane pickReplayLane(int hint, double bodyLength) {
        if (hint >= 0 && hint < street.getNumberOfLanes()) {
            Lane preferred = street.getLanes().get(hint);
            if (isEntryClear(preferred, bodyLength))
                return preferred;
        }
        for (Lane lane : street.getLanes()) {
            if (isEntryClear(lane, bodyLength))
                return lane;
        }
        return null;
    }

    private Lane chooseRandomOpenEntryLane(double bodyLength) {
        if (street.getNumberOfLanes() == 0)
            return null;
        int start = rng.nextInt(street.getNumberOfLanes());
        for (int offset = 0; offset < street.getNumberOfLanes(); offset++) {
            Lane lane = street.getLanes().get((start + offset) % street.getNumberOfLanes());
            if (isEntryClear(lane, bodyLength))
                return lane;
        }
        return null;
    }

    private boolean isEntryClear(Lane lane, double bodyLength) {
        double requiredFront = bodyLength + STOPPING_BUFFER;
        for (Vehicle vehicle : lane.getVehiclesInLane()) {
            if (vehicle.getRearPosition() < requiredFront)
                return false;
        }
        return true;
    }

    private Car createCar() {
        boolean seeking = rng.nextDouble() < parkingSeekerRatio;
        Car c = new Car(nextVehicleId++, seeking);
        bodyCatalog.applyTo(c);
        c.setPositionExact(c.getBodyLength() / 2.0);
        c.setSpeedExact(SLOW_CRUISE_SPEED);
        c.setAggressiveDriver(rng.nextDouble() < 0.18);
        c.setStubbornParker(seeking && rng.nextDouble() < 0.20);
        if (movementRandomnessEnabled) {
            assignDriverProfile(c);
        } else {
            c.setPreferredCruiseSpeed(NORMAL_CRUISE_SPEED);
            c.setAccelerationRate(6.2);
            c.setBrakingRate(9.0);
            c.setHeadwayBuffer(0.18);
        }
        return c;
    }

    private void assignDriverProfile(Car car) {
        double roll = rng.nextDouble();
        if (roll < 0.22) {
            car.setPreferredCruiseSpeed(CAUTIOUS_CRUISE_SPEED + rng.nextDouble() * 0.6);
            car.setAccelerationRate(4.8 + rng.nextDouble() * 0.8);
            car.setBrakingRate(8.4 + rng.nextDouble() * 0.8);
            car.setHeadwayBuffer(0.26 + rng.nextDouble() * 0.10);
        } else if (roll < 0.82) {
            car.setPreferredCruiseSpeed(NORMAL_CRUISE_SPEED + (rng.nextDouble() - 0.5) * 0.8);
            car.setAccelerationRate(5.8 + rng.nextDouble() * 0.9);
            car.setBrakingRate(8.8 + rng.nextDouble() * 0.9);
            car.setHeadwayBuffer(0.18 + rng.nextDouble() * 0.08);
        } else if (roll < 0.96) {
            car.setPreferredCruiseSpeed(BRISK_CRUISE_SPEED + rng.nextDouble() * 0.5);
            car.setAccelerationRate(6.6 + rng.nextDouble() * 0.9);
            car.setBrakingRate(9.2 + rng.nextDouble() * 0.8);
            car.setHeadwayBuffer(0.15 + rng.nextDouble() * 0.06);
        } else {
            car.setPreferredCruiseSpeed(ASSERTIVE_CRUISE_SPEED + rng.nextDouble() * 0.4);
            car.setAccelerationRate(7.1 + rng.nextDouble() * 0.9);
            car.setBrakingRate(9.8 + rng.nextDouble() * 0.8);
            car.setHeadwayBuffer(0.14 + rng.nextDouble() * 0.05);
        }
    }

    // =====================================================================
    // Phase 2 police patrol (visible enforcement)
    // =====================================================================
    private void spawnPoliceIfNeeded() {
        if (!phase2Enabled || !controller.isPolicePatrolEnabled())
            return;
        if (hasPoliceVehicle())
            return;

        Lane lane = street.getLanes().get(Math.min(PARKING_LANE_ID, street.getNumberOfLanes() - 1));
        PoliceVehicle police = new PoliceVehicle(nextVehicleId++);
        bodyCatalog.applyTo(police);
        if (!isEntryClear(lane, police.getBodyLength())) {
            nextVehicleId--; // no room yet; reclaim the id and retry next tick
            return;
        }
        police.setPositionExact(police.getBodyLength() / 2.0);
        police.setSpeedExact(POLICE_SPEED);
        lane.addVehicle(police);
    }

    private boolean hasPoliceVehicle() {
        for (Vehicle vehicle : street.allVehicles())
            if (vehicle instanceof PoliceVehicle)
                return true;
        return false;
    }

    // When a police car is near a curb-waiting driver, send that driver on its
    // way so it stops blocking the live lane (the visible Phase 2 fix).
    private void policeWarnWaitingDrivers() {
        if (!phase2Enabled || !controller.isPolicePatrolEnabled())
            return;
        for (Lane lane : street.getLanes()) {
            for (Vehicle police : lane.getVehiclesInLane()) {
                if (!(police instanceof PoliceVehicle))
                    continue;
                for (Lane watchedLane : street.getLanes()) {
                    for (Vehicle v : watchedLane.getVehiclesInLane()) {
                        if (!(v instanceof Car c))
                            continue;
                        if (!Vehicle.STATE_WAITING.equals(c.getState()))
                            continue;
                        double distance = c.getRearPosition() - police.getFrontPosition();
                        if (distance < -2 || distance > 16)
                            continue;

                        controller.releaseReservationFor(c);
                        c.setSeekingParking(false);
                        c.setRedirected(true);
                        c.setState(Vehicle.STATE_MOVING);
                        c.setSpeedExact(SLOW_CRUISE_SPEED);
                        c.setWaitingTime(0);
                        controller.warnCar(c);
                    }
                }
            }
        }
    }

    // =====================================================================
    // Accident handling
    // =====================================================================
    private void tickAccident() {
        Accident a = street.getActiveAccident();
        if (a == null)
            return;
        a.tick();

        // Accidents clear only when an emergency vehicle reaches the scene.
        if (!a.isCleared()) {
            for (Lane lane : street.getLanes()) {
                for (Vehicle v : lane.getVehiclesInLane()) {
                    if (!(v instanceof EmergencyVehicle ev))
                        continue;
                    if (!ev.isSirenActive())
                        continue;
                    double dx = Math.abs(ev.getPositionExact() - a.getPositionExact());
                    if (dx <= ACCIDENT_CLEAR_RADIUS) {
                        a.markCleared();
                        break;
                    }
                }
                if (a.isCleared())
                    break;
            }
        }

        if (a.isCleared())
            street.clearAccident();

        // Avoid deadlock in quiet tests where random emergencies are disabled.
        if (!a.isCleared() && !anyEmergencyOnStreet()) {
            dispatchEmergencyForAccident();
        }
    }

    private boolean anyEmergencyOnStreet() {
        for (Vehicle vehicle : street.allVehicles())
            if (vehicle instanceof EmergencyVehicle)
                return true;
        return false;
    }

    /** Dispatches an ambulance when an accident has no responder. */
    private void dispatchEmergencyForAccident() {
        EmergencyVehicle ev = new EmergencyVehicle(nextVehicleId++, "AMBULANCE");
        bodyCatalog.applyTo(ev);
        Lane lane = chooseRandomOpenEntryLane(ev.getBodyLength());
        if (lane == null) {
            // No entry lane has space right now; we'll retry next tick.
            nextVehicleId--; // take back the id so we don't leak id space
            return;
        }
        ev.setPositionExact(ev.getBodyLength() / 2.0);
        ev.setSpeedExact(EMERGENCY_SPEED);
        lane.addVehicle(ev);
    }

    // =====================================================================
    // Vehicle movement
    // =====================================================================
    private void prepareForEmergencyVehicles() {
        Accident accident = street.getActiveAccident();
        if (accident == null)
            return;
        for (Lane lane : street.getLanes()) {
            List<Vehicle> laneSnapshot = new ArrayList<>(lane.getVehiclesInLane());
            for (Vehicle v : laneSnapshot) {
                if (!(v instanceof EmergencyVehicle))
                    continue;

                forceEmergencyClearance(lane, accident, v.getPositionExact());

                List<Vehicle> candidateSnapshot = new ArrayList<>(lane.getVehiclesInLane());
                for (Vehicle candidate : candidateSnapshot) {
                    if (!(candidate instanceof Car c))
                        continue;
                    double distanceAhead = c.getRearPosition() - v.getFrontPosition();
                    if (distanceAhead <= 0 || distanceAhead > 10)
                        continue;
                    if (!Vehicle.STATE_WAITING.equals(c.getState()))
                        continue;
                    if (!c.isSeekingParking())
                        continue;

                    controller.releaseReservationFor(c);
                    c.setSeekingParking(false);
                    c.setRedirected(true);
                    c.setState(Vehicle.STATE_MOVING);
                    c.setSpeedExact(SLOW_CRUISE_SPEED);
                    c.setWaitingTime(0);
                }
            }
        }
    }

    private void forceEmergencyClearance(Lane lane, Accident accident, double emergencyPosition) {
        double accidentFront = accident.getPositionExact() + ACCIDENT_CLEAR_RADIUS;
        for (Vehicle candidate : new ArrayList<>(lane.getVehiclesInLane())) {
            if (!(candidate instanceof Car car))
                continue;
            if (car.getRearPosition() <= emergencyPosition)
                continue;
            if (car.getFrontPosition() > accidentFront)
                continue;

            if (tryChangeLaneToBestAdjacent(car, lane))
                continue;

            controller.releaseReservationFor(car);
            car.requestMergeYield();
            car.setSeekingParking(false);
            car.setRedirected(true);
            car.setState(Vehicle.STATE_MOVING);
            car.setSpeedExact(Math.max(car.getSpeedExact(), SPECIAL_YIELD_SPEED));
            car.clearStoppedInTraffic();
        }
    }

    private void moveAllVehicles(double dt) {
        Set<Vehicle> processedThisTick = new HashSet<>();
        for (Lane lane : street.getLanes()) {
            // Iterate a copy because we may remove cars (parking) during iteration.
            List<Vehicle> snapshot = new ArrayList<>(lane.getVehiclesInLane());
            // Process leading vehicles first so followers can fill their cells.
            snapshot.sort((a, b) -> Double.compare(b.getPositionExact(), a.getPositionExact()));

            for (Vehicle v : snapshot) {
                if (processedThisTick.contains(v))
                    continue;
                processedThisTick.add(v);
                if (v instanceof Car c)
                    processCarMovement(c, c.getCurrentLane(), dt);
                else if (v instanceof EmergencyVehicle e)
                    processEmergencyMovement(e, e.getCurrentLane(), dt);
            }
        }
    }

    private void resolveBodyCollisions() {
        if (street.getActiveAccident() != null)
            return;
        for (Lane lane : street.getLanes()) {
            List<Vehicle> vehicles = new ArrayList<>(lane.getVehiclesInLane());
            vehicles.sort((a, b) -> Double.compare(a.getRearPosition(), b.getRearPosition()));
            for (int i = 0; i < vehicles.size() - 1; i++) {
                Vehicle rear = vehicles.get(i);
                Vehicle front = vehicles.get(i + 1);
                double overlap = rear.getFrontPosition() - front.getRearPosition();
                if (overlap <= 0.0)
                    continue;

                boolean recklessImpact = movementRandomnessEnabled
                        && rear instanceof Car c && c.isAggressiveDriver()
                        && rear.getSpeedExact() > front.getSpeedExact() + 0.05
                        && overlap > 0.08;
                if (!recklessImpact) {
                    // Preserve previousPosition so the renderer can smooth the snap-back.
                    double snapTo = front.getRearPosition()
                            - rear.getBodyLength() / 2.0 - STOPPING_BUFFER;
                    rear.moveToPositionExact(snapTo);
                    rear.setSpeedExact(0.0);
                    if (rear instanceof Car queuedCar)
                        queuedCar.noteStoppedInTraffic();
                    continue;
                }

                createCollisionAccident(lane, rear, front);
                return;
            }
        }
    }

    private void createCollisionAccident(Lane lane, Vehicle rear, Vehicle front) {
        double crashX = (rear.getFrontPosition() + front.getRearPosition()) / 2.0;
        // Release any reservation before removing crashed cars, otherwise the
        // controller keeps a reference to a despawned car and its spot stays
        // ghost-reserved forever.
        if (rear instanceof Car rc)
            controller.releaseReservationFor(rc);
        if (front instanceof Car fc)
            controller.releaseReservationFor(fc);
        lane.removeVehicle(rear);
        lane.removeVehicle(front);
        rear.setState(Vehicle.STATE_REMOVED);
        front.setState(Vehicle.STATE_REMOVED);
        street.triggerAccident(2, crashX, lane.getLaneId());
    }

    private void processCarMovement(Car c, Lane lane, double dt) {
        if (Vehicle.STATE_PARKED.equals(c.getState()) ||
                Vehicle.STATE_REMOVED.equals(c.getState()))
            return;

        // Cars currently stopped in the lane must re-check the world every tick.
        // Accident/traffic stops should recover when the path opens; parking waits
        // continue until a spot opens or traffic lets them move on.
        if (Vehicle.STATE_WAITING.equals(c.getState())) {
            if (!canWaitingCarResume(c, lane)) {
                c.incrementWait();
                return;
            }
            c.setState(Vehicle.STATE_MOVING);
            c.setSpeedExact(SLOW_CRUISE_SPEED);
            lane = c.getCurrentLane();
        }

        // Accident ahead: merge away if possible, otherwise stop.
        Accident acc = street.getActiveAccident();
        if (acc != null && acc.getLaneId() == lane.getLaneId()) {
            double distToAcc = acc.getPositionExact() - c.getFrontPosition();
            double desiredStep = Math.max(SLOW_CRUISE_SPEED, c.getSpeedExact()) * dt;
            if (distToAcc > 0 && distToAcc <= desiredStep + ACCIDENT_LOOKAHEAD) {
                if (!tryChangeLane(c, lane)) {
                    halt(c);
                    c.incrementWait();
                }
                return;
            }
        }

        // The leader controls how much room this car has.
        Vehicle ahead = lane.getNextVehicleAhead(c.getFrontPosition());
        double gap = openGapAhead(lane, c);

        if (shouldClearCurbLane(c, lane)
                && tryPolicyLaneChange(c, lane, lane.getLaneId() + 1, "CLR")) {
            return;
        }

        if (shouldSpilloverOutward(c, lane, ahead, gap)
                && tryPolicyLaneChange(c, lane, lane.getLaneId() + 1, "L" + (lane.getLaneId() + 1))) {
            return;
        }

        if (shouldTryTrafficSpillover(c, lane, ahead, gap)
                && tryChangeLaneToBestAdjacent(c, lane)) {
            return;
        }

        // Parking seekers pick a curb spot. Phase 1 grabs the nearest free spot
        // on the fly (and may wait in the live lane when the curb is full).
        // Phase 2 instead uses the controller's reservation that was assigned
        // before movement, and never stops to wait — it gets redirected early.
        if (c.isSeekingParking()) {
            ParkingSpot target = c.getTargetParkingSpot();

            if (phase2Enabled) {
                controller.refreshParkingTarget(c, lane);
                target = c.getTargetParkingSpot();
            } else {
                ParkingSpot best = controller.bestReachableSpot(c, lane);
                if (best != null && (target == null
                        || best.getPositionX() < target.getPositionX()
                        || target.isOccupied()
                        || (target.isReserved() && target.getReservedBy() != c))) {
                    c.setTargetParkingSpot(best);
                    target = best;
                }
            }

            // Arrived at (or immediately next to) our target spot?
            if (target != null) {
                double dist = target.getPositionX() - c.getPositionExact();
                if (canParkInSpot(c, lane, target, dist)) {
                    boolean mine = target.isFree()
                            || (target.isReserved() && target.getReservedBy() == c);
                    if (mine) {
                        c.setHeadingDegrees(90.0);
                        controller.forgetReservation(c);
                        target.occupy(c);
                        c.setState(Vehicle.STATE_PARKED);
                        c.setSpeedExact(0.0);
                        c.setParkingIntent(false); // fulfilled; don't re-seek if it later exits
                        lane.removeVehicle(c);
                        stats.carProcessed();
                        return;
                    } else {
                        // Somebody else grabbed it. Retry next tick.
                        controller.clearStaleReservation(c);
                    }
                }
            } else if (!phase2Enabled) {
                // A full curb is the Phase 1 problem: the seeker may wait in
                // the live lane instead of getting a reservation or redirect.
                if (shouldWaitForParking(c, lane, gap)) {
                    halt(c);
                    c.incrementWait();
                    return;
                }
                // Phase 2: the controller already redirected us; keep driving.
            }
        }

        // Standard car-following with rate-limited acceleration and braking.
        double desiredSpeed = getDesiredSpeed(c);
        double buffer = Math.max(STOPPING_BUFFER, c.getHeadwayBuffer());
        double usableGap = Math.max(0.0, gap - buffer);
        double brake = Math.max(1.0, c.getBrakingRate());
        double leaderSpeed = (ahead == null) ? desiredSpeed : Math.max(0.0, ahead.getSpeedExact());
        // Gipps-style safe follow: v_safe = sqrt(v_leader^2 + 2*b*usableGap)
        double safeFollowSpeed = Math.sqrt(Math.max(0.0,
                leaderSpeed * leaderSpeed + 2.0 * brake * usableGap));
        double targetSpeed = Math.min(desiredSpeed, safeFollowSpeed);
        double currentSpeed = c.getSpeedExact();
        double newSpeed;
        if (targetSpeed >= currentSpeed) {
            newSpeed = Math.min(targetSpeed, currentSpeed + c.getAccelerationRate() * dt);
        } else {
            newSpeed = Math.max(targetSpeed, currentSpeed - brake * dt);
        }
        newSpeed = Math.max(0.0, newSpeed);
        double step = newSpeed * dt;

        // Geometric safety net: the car must never cross into the stopping
        // buffer of the vehicle ahead in a single step, even if the physical
        // model produced a slightly optimistic speed. This is a hard cap.
        double maxStepThisTick = Math.max(0.0, gap - STOPPING_BUFFER);
        if (step > maxStepThisTick) {
            step = maxStepThisTick;
            newSpeed = step / Math.max(0.001, dt);
        }

        // Rare reckless surge from aggressive drivers, still bounded by
        // the raw gap, so at worst it causes a real collision (that
        // resolveBodyCollisions handles), not a visual phase-through.
        double desiredStep = desiredSpeed * dt;
        if (movementRandomnessEnabled && c.isAggressiveDriver()
                && ahead instanceof Car
                && gap < desiredStep * 0.55
                && rng.nextDouble() < 0.12) {
            double recklessStep = Math.min(desiredStep, Math.max(0.0, gap));
            if (recklessStep > step) {
                step = recklessStep;
                newSpeed = step / Math.max(0.001, dt);
            }
        }

        if (step <= 0.001) {
            c.setSpeedExact(0.0);
            c.noteStoppedInTraffic();
            return;
        }
        c.clearStoppedInTraffic();
        c.moveToPositionExact(c.getPositionExact() + step);
        c.setSpeedExact(newSpeed);
    }

    /**
     * Bring any {@link Movable} to a halt through the interface contract.
     * The engine works with concrete Cars and EmergencyVehicles, but
     * "stop moving" is behaviour every {@code Movable} shares, so the stop
     * sites in the movement loop go through the interface instead of a concrete
     * type. This keeps the movement layer programming-to-the-interface and is
     * the small {@code Movable} helper the assignment asked for.
     */
    private static void halt(Movable movable) {
        movable.stop();
    }

    private boolean canParkInSpot(Car car, Lane lane, ParkingSpot target, double distToSpot) {
        if (lane == null || lane.getLaneId() != PARKING_LANE_ID)
            return false;
        if (car.getCurrentLane() != lane)
            return false;
        if (!car.isSeekingParking() || car.isRedirected())
            return false;
        if (target == null)
            return false;
        if (Math.abs(car.getPositionExact() - target.getPositionX()) > PARKING_WAIT_DISTANCE)
            return false;

        double approachWindowBehind = Math.max(0.45, car.getBodyLength() * 0.35);
        double approachWindowAhead = Math.max(0.75, car.getBodyLength() * 0.45);
        if (distToSpot < -approachWindowBehind || distToSpot > approachWindowAhead)
            return false;

        Vehicle ahead = lane.getNextVehicleAhead(car.getFrontPosition());
        return ahead == null || ahead.getRearPosition() - car.getFrontPosition() >= STOPPING_BUFFER;
    }

    // =====================================================================
    // Parking behavior
    // =====================================================================

    private double getDesiredSpeed(Car c) {
        double desired = movementRandomnessEnabled ? c.getPreferredCruiseSpeed() : NORMAL_CRUISE_SPEED;
        if (c.isRedirected())
            desired = Math.min(REDIRECT_SPEED, desired);
        if (c.isYieldingForMerge())
            desired = Math.min(SPECIAL_YIELD_SPEED, desired);
        if (c.isBrakingAfterMerge())
            desired = Math.min(POST_MERGE_BRAKE_SPEED, desired);

        // Seeker approach-deceleration: fade speed down to PARKING_APPROACH_MIN_SPEED
        // as the car closes on its target spot. Prevents the "cruise at full
        // speed, then park instantly" jolt that used to shock following cars.
        if (c.isSeekingParking() && !c.isRedirected()) {
            ParkingSpot target = c.getTargetParkingSpot();
            if (target != null) {
                double distance = target.getPositionX() - c.getPositionExact();
                if (distance > 0.0 && distance < PARKING_COMMIT_DISTANCE) {
                    double factor = distance / PARKING_COMMIT_DISTANCE;
                    double approachSpeed = PARKING_APPROACH_MIN_SPEED
                            + factor * (desired - PARKING_APPROACH_MIN_SPEED);
                    desired = Math.min(desired, Math.max(PARKING_APPROACH_MIN_SPEED, approachSpeed));
                }
            }
        }

        // Minimum desired speed so cars keep creeping toward resolution rather
        // than planting themselves. The earlier hard floor of 4 was odd for
        // seekers about to park; use a softer floor when approaching a spot.
        double floor = (c.isSeekingParking() && !c.isRedirected()) ? 2.0 : 4.0;
        return Math.max(floor, desired);
    }

    private double openGapAhead(Lane lane, Vehicle vehicle) {
        Vehicle ahead = lane.getNextVehicleAhead(vehicle.getFrontPosition());
        if (ahead == null)
            return Double.MAX_VALUE;
        return ahead.getRearPosition() - vehicle.getFrontPosition();
    }

    private boolean shouldTryTrafficSpillover(Car c, Lane lane, Vehicle ahead, double gap) {
        if (street.getNumberOfLanes() < 2)
            return false;
        // A seeker that is committed to its reserved spot must stay in the curb
        // lane so it can actually park. Without this, generic traffic-spillover
        // would pull it into lane 1 right as it approaches its spot (a slow/
        // stopped car ahead near the spot is exactly what triggers spillover),
        // and the next tick its reservation would be released as "off lane 0",
        // leaving spots reserved-but-never-taken. shouldClearCurbLane already
        // makes this same exemption.
        if (isCommittedToImmediateParking(c, lane))
            return false;
        if (c.getLaneChangeCooldownTicks() > 0)
            return false;
        if (ahead == null)
            return false;
        if (gap <= STOPPING_BUFFER)
            return true;
        if (ahead.isYieldingForMerge())
            return false;
        if (ahead != null && Vehicle.STATE_WAITING.equals(ahead.getState())
                && gap <= QUEUE_LANE_CHANGE_LOOKAHEAD)
            return true;
        if (ahead != null && ahead.getSpeedExact() < 0.15 && gap <= QUEUE_LANE_CHANGE_LOOKAHEAD)
            return true;
        if (ahead != null && ahead.getSpeedExact() + 0.25 < c.getSpeedExact()
                && gap <= Math.max(2.5, c.getBodyLength() * 1.8))
            return true;

        double currentCongestion = lane.getCongestion();
        for (int lid : adjacentLaneIds(lane.getLaneId())) {
            if (lid < 0 || lid >= street.getNumberOfLanes())
                continue;
            Lane other = street.getLanes().get(lid);
            if (!other.isLaneBlocked() && currentCongestion > other.getCongestion() + 0.05
                    && localGapAhead(other, c.getPositionExact(), c.getBodyLength()) > gap + c.getBodyLength()) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldClearCurbLane(Car car, Lane lane) {
        if (street.getNumberOfLanes() < 2)
            return false;
        if (lane == null || lane.getLaneId() != PARKING_LANE_ID)
            return false;
        if (car.getLaneChangeCooldownTicks() > 0)
            return false;
        if (car.getPositionExact() < CURB_CLEAR_START_POSITION)
            return false;
        if (isCommittedToImmediateParking(car, lane))
            return false;
        return !car.isSeekingParking()
                || car.isRedirected()
                || isUrgentCurbClear(car, lane);
    }

    private boolean isUrgentCurbClear(Car car, Lane lane) {
        if (blocksActiveExitCorridor(car) || occupiedExitCorridorOverlapCount(car) > 0)
            return true;
        double gap = openGapAhead(lane, car);
        return gap <= Math.max(MIN_IMMINENT_MERGE_GAP, car.getBodyLength() * 0.95);
    }

    private boolean isCommittedToImmediateParking(Car car, Lane lane) {
        if (car == null || lane == null || lane.getLaneId() != PARKING_LANE_ID)
            return false;
        if (!car.isSeekingParking() || car.isRedirected())
            return false;
        ParkingSpot target = car.getTargetParkingSpot();
        if (target == null)
            target = controller.bestReachableSpot(car, lane);
        if (target == null)
            return false;
        double distance = target.getPositionX() - car.getPositionExact();
        return distance >= -0.5 && distance <= PARKING_COMMIT_DISTANCE;
    }

    private boolean shouldSpilloverOutward(Car car, Lane lane, Vehicle ahead, double gap) {
        if (street.getNumberOfLanes() < 3)
            return false;
        if (lane == null || lane.getLaneId() <= PARKING_LANE_ID)
            return false;
        if (lane.getLaneId() >= street.getNumberOfLanes() - 1)
            return false;
        if (car.getLaneChangeCooldownTicks() > 0)
            return false;

        Lane outer = street.getLanes().get(lane.getLaneId() + 1);
        if (outer.isLaneBlocked())
            return false;
        boolean innerPressure = hasInnerMergePressure(lane.getLaneId(), car.getPositionExact());
        boolean blockedAhead = ahead != null
                && !ahead.isYieldingForMerge()
                && (Vehicle.STATE_WAITING.equals(ahead.getState())
                        || ahead.getSpeedExact() < 0.4
                        || gap <= Math.max(2.2, car.getBodyLength() * 1.35));
        if (!innerPressure && !blockedAhead)
            return false;
        if (innerPressure && blockedAhead
                && localGapAhead(outer, car.getPositionExact(), car.getBodyLength()) > gap
                        + car.getBodyLength() * 0.55) {
            return true;
        }
        return lane.getCongestion() > outer.getCongestion() + STRONG_SPILLOVER_CONGESTION_DELTA
                && localGapAhead(outer, car.getPositionExact(), car.getBodyLength()) > gap + car.getBodyLength() * 0.8;
    }

    private boolean hasInnerMergePressure(int laneId, double position) {
        int innerLaneId = laneId - 1;
        if (innerLaneId < 0)
            return false;
        Lane inner = street.getLanes().get(innerLaneId);
        for (Vehicle vehicle : inner.getVehiclesInLane()) {
            if (!(vehicle instanceof Car innerCar))
                continue;
            if (Math.abs(innerCar.getPositionExact() - position) > MERGE_PRESSURE_RANGE)
                continue;
            if (innerLaneId == PARKING_LANE_ID && shouldClearCurbLane(innerCar, inner)
                    && isUrgentCurbClear(innerCar, inner))
                return true;
            if (innerLaneId > PARKING_LANE_ID
                    && !innerCar.isYieldingForMerge()
                    && inner.getCongestion() > street.getLanes().get(laneId).getCongestion()
                            + STRONG_SPILLOVER_CONGESTION_DELTA) {
                return true;
            }
        }
        return false;
    }

    private boolean canWaitingCarResume(Car c, Lane lane) {
        if (isIllegalExitCorridorWait(c, lane)) {
            if (tryChangeLaneToBestAdjacent(c, lane))
                return true;
            if (openGapAhead(lane, c) > STOPPING_BUFFER) {
                redirectAwayFromParking(c);
                return true;
            }
            return false;
        }

        Accident acc = street.getActiveAccident();
        if (acc != null && acc.getLaneId() == lane.getLaneId()) {
            double distToAcc = acc.getPositionExact() - c.getFrontPosition();
            if (distToAcc > 0 && distToAcc <= ACCIDENT_LOOKAHEAD + 1) {
                return tryChangeLaneToBestAdjacent(c, lane);
            }
        }

        double gap = openGapAhead(lane, c);
        if (gap <= STOPPING_BUFFER)
            return false;

        if (!c.isSeekingParking())
            return true;

        boolean hasReachableSpot = phase2Enabled
                ? controller.refreshParkingTarget(c, lane)
                : refreshUnreservedParkingTarget(c, lane);
        ParkingSpot target = c.getTargetParkingSpot();
        if (hasReachableSpot && target != null && (target.isFree()
                || (target.isReserved() && target.getReservedBy() == c))) {
            return true;
        }

        if (!phase2Enabled
                && (lane.getLaneId() != PARKING_LANE_ID || !isNearParkingSpot(c.getPositionExact()))) {
            return true;
        }

        return phase2Enabled || c.isRedirected();
    }

    // Phase 1 target refresh: find the nearest reachable spot WITHOUT reserving
    // it (first-come parking). Phase 2 uses controller.refreshParkingTarget,
    // which reserves instead.
    private boolean refreshUnreservedParkingTarget(Car car, Lane lane) {
        ParkingSpot best = controller.bestReachableSpot(car, lane);
        ParkingSpot target = car.getTargetParkingSpot();
        if (best == null) {
            if (target != null && (target.isOccupied()
                    || (target.isReserved() && target.getReservedBy() != car))) {
                car.setTargetParkingSpot(null);
            }
            return false;
        }
        if (target == null || best.getPositionX() < target.getPositionX()
                || target.isOccupied()
                || (target.isReserved() && target.getReservedBy() != car)) {
            car.setTargetParkingSpot(best);
        }
        return true;
    }

    private boolean shouldWaitForParking(Car c, Lane lane, double gap) {
        if (street.countFreeSpots() > 0)
            return false;
        if (c.isRedirected())
            return false;
        if (lane.getLaneId() != PARKING_LANE_ID)
            return false;
        if (c.getPositionExact() < MIN_PARKING_WAIT_POSITION)
            return false;
        if (c.getPositionExact() >= street.getStreetLength() - 4)
            return false;
        if (!isNearParkingSpot(c.getPositionExact()))
            return false;
        if (isIllegalExitCorridorWait(c, lane)) {
            redirectAwayFromParking(c);
            return false;
        }

        boolean singleLane = street.getNumberOfLanes() == 1;

        return gap <= STOPPING_BUFFER || singleLane || rng.nextDouble() < 0.85;
    }

    private boolean isIllegalExitCorridorWait(Car car, Lane lane) {
        if (car == null || lane == null || lane.getLaneId() != PARKING_LANE_ID)
            return false;
        if (blocksActiveExitCorridor(car))
            return true;
        return occupiedExitCorridorOverlapCount(car) > 1;
    }

    private boolean blocksActiveExitCorridor(Vehicle vehicle) {
        if (vehicle == null)
            return false;
        double rear = vehicle.getRearPosition() - STOPPING_BUFFER;
        double front = vehicle.getFrontPosition() + STOPPING_BUFFER;
        for (ParkingSpot spot : street.getParkingSpots()) {
            Car parked = spot.getParkedCar();
            if (!spot.isExitRequested() || parked == null)
                continue;
            if (front > exitMergeRear(spot, parked) && rear < exitMergeFront(spot, parked))
                return true;
        }
        return false;
    }

    private int occupiedExitCorridorOverlapCount(Vehicle vehicle) {
        if (vehicle == null)
            return 0;
        double rear = vehicle.getRearPosition() - STOPPING_BUFFER;
        double front = vehicle.getFrontPosition() + STOPPING_BUFFER;
        int count = 0;
        for (ParkingSpot spot : street.getParkingSpots()) {
            Car parked = spot.getParkedCar();
            if (parked == null)
                continue;
            if (front > exitMergeRear(spot, parked) && rear < exitMergeFront(spot, parked))
                count++;
        }
        return count;
    }

    private void redirectAwayFromParking(Car car) {
        controller.clearStaleReservation(car);
        car.setTargetParkingSpot(null);
        car.setHasReservation(false);
        car.setSeekingParking(false);
        car.setRedirected(true);
        car.setState(Vehicle.STATE_MOVING);
        car.setSpeedExact(SLOW_CRUISE_SPEED);
        car.setWaitingTime(0);
        // The redirected car is actively moving again; drop any queued-in-
        // traffic history so the "Q" badge clears on the next frame.
        car.clearStoppedInTraffic();
    }

    private boolean isNearParkingSpot(double position) {
        for (ParkingSpot spot : street.getParkingSpots()) {
            double distance = Math.abs(spot.getPositionX() - position);
            if (distance <= PARKING_WAIT_DISTANCE)
                return true;
        }
        return false;
    }

    private void processEmergencyMovement(EmergencyVehicle ev, Lane lane, double dt) {
        Vehicle ahead = lane.getNextVehicleAhead(ev.getFrontPosition());
        double gap = openGapAhead(lane, ev);
        double desiredSpeed = ev instanceof PoliceVehicle ? POLICE_SPEED : EMERGENCY_SPEED;
        double desiredStep = desiredSpeed * dt;

        if (gap <= desiredStep + STOPPING_BUFFER) {
            if (!tryChangeLane(ev, lane)) {
                // Nudge the blocker back into motion; the siren-pressure pass
                // above already redirects parking waiters farther up the queue.
                if (ahead instanceof Car blocker) {
                    blocker.setState(Vehicle.STATE_MOVING);
                    blocker.setSpeedExact(Math.max(0.45, blocker.getSpeedExact()));
                    blocker.setSeekingParking(false);
                    blocker.setRedirected(true);
                }
            }
        }

        double step = Math.min(desiredStep, Math.max(0.0, gap - STOPPING_BUFFER));
        if (step > 0) {
            ev.moveToPositionExact(ev.getPositionExact() + step);
            ev.setSpeedExact(step / Math.max(0.001, dt));
        } else {
            ev.setSpeedExact(0.0);
        }
    }

    // =====================================================================
    // Lane changing
    // =====================================================================
    /** Try to move a vehicle into an adjacent unblocked lane at the same X. */
    private boolean tryChangeLane(Vehicle v, Lane current) {
        return tryChangeLaneToBestAdjacent(v, current);
    }

    private boolean tryChangeLaneToBestAdjacent(Vehicle v, Lane current) {
        if (street.getNumberOfLanes() < 2)
            return false;
        if (v.getLaneChangeCooldownTicks() > 0)
            return false;
        int[] candidates = adjacentLaneIds(current.getLaneId());
        Lane bestLane = null;
        double currentScore = laneChangeScore(current, v);
        double bestScore = currentScore;
        for (int lid : candidates) {
            if (lid < 0 || lid >= street.getNumberOfLanes())
                continue;
            Lane other = street.getLanes().get(lid);
            if (other.isLaneBlocked())
                continue;

            boolean safe = isLaneChangeSafe(current, other, v);
            if (!safe)
                continue;

            double score = laneChangeScore(other, v);
            if (score > bestScore) {
                bestScore = score;
                bestLane = other;
            }
        }
        if (bestLane == null)
            return false;

        completeLaneChange(v, current, bestLane, "SW");
        return true;
    }

    private boolean tryPolicyLaneChange(Vehicle vehicle, Lane current, int targetLaneId, String marker) {
        if (targetLaneId < 0 || targetLaneId >= street.getNumberOfLanes())
            return false;
        if (vehicle.getLaneChangeCooldownTicks() > 0)
            return false;
        Lane target = street.getLanes().get(targetLaneId);
        if (target.isLaneBlocked())
            return false;
        if (!isLaneChangeSafe(current, target, vehicle))
            return false;
        completeLaneChange(vehicle, current, target, marker);
        return true;
    }

    private void completeLaneChange(Vehicle v, Lane current, Lane bestLane, String marker) {
        current.removeVehicle(v);
        bestLane.addVehicle(v);
        v.setHeadingDegrees(90.0);
        v.startLaneChangeVisual(current.getLaneId(), bestLane.getLaneId());
        v.markLaneChanged();
        v.markLaneIntent(marker);
        // A car that just changed lane is by definition no longer queued in
        // traffic. Reset so the "Q" badge clears immediately instead of
        // lingering for a tick on the new lane.
        if (v instanceof Car movedCar)
            movedCar.clearStoppedInTraffic();
        applyPostMergeDisturbance(bestLane, v);
    }

    private int[] adjacentLaneIds(int laneId) {
        return new int[] { laneId + 1, laneId - 1 };
    }

    private boolean isLaneChangeSafe(Lane currentLane, Lane targetLane, Vehicle vehicle) {
        double position = vehicle.getPositionExact();
        double requiredAhead = Math.max(vehicle.getBodyLength() * 1.15, STOPPING_BUFFER * 3.0);
        double requiredBehind = Math.max(vehicle.getBodyLength() * 0.9, STOPPING_BUFFER * 3.0);
        if (localGapAhead(targetLane, position, vehicle.getBodyLength()) < requiredAhead)
            return false;
        if (localGapBehind(targetLane, position, vehicle.getBodyLength()) < requiredBehind)
            return false;
        // The lane being left gets more space, so only the target lane matters.
        return sweptLaneSpaceClear(targetLane.getLaneId(), vehicle);
    }

    private void applyPostMergeDisturbance(Lane lane, Vehicle mergedVehicle) {
        Vehicle follower = closestVehicleBehind(lane, mergedVehicle.getPositionExact(), mergedVehicle.getBodyLength());
        if (follower == null)
            return;
        follower.requestPostMergeBrake();
        Vehicle secondFollower = closestVehicleBehind(lane, follower.getPositionExact(), follower.getBodyLength());
        if (secondFollower != null
                && follower.getPositionExact() - secondFollower.getPositionExact() < follower.getBodyLength() * 1.9) {
            secondFollower.requestPostMergeBrake();
        }
    }

    private boolean sweptLaneSpaceClear(int targetLaneId, Vehicle vehicle) {
        double sweptRear = vehicle.getRearPosition() - STOPPING_BUFFER;
        double sweptFront = vehicle.getFrontPosition() + STOPPING_BUFFER;
        for (Lane lane : street.getLanes()) {
            for (Vehicle other : lane.getVehiclesInLane()) {
                if (other == vehicle)
                    continue;
                if (!occupiesLaneDuringTransition(other, targetLaneId))
                    continue;
                double otherRear = other.getRearPosition() - STOPPING_BUFFER;
                double otherFront = other.getFrontPosition() + STOPPING_BUFFER;
                if (sweptFront > otherRear && sweptRear < otherFront) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean occupiesLaneDuringTransition(Vehicle vehicle, int laneId) {
        Lane lane = vehicle.getCurrentLane();
        if (lane != null && lane.getLaneId() == laneId)
            return true;
        return vehicle.getLaneChangeFlashTicks() > 0
                && Math.round(vehicle.getPreviousLaneId()) == laneId;
    }

    private double localGapAhead(Lane lane, double position, double bodyLength) {
        double front = position + bodyLength / 2.0;
        Vehicle next = lane.getNextVehicleAhead(front);
        return next == null ? lane.getLength() : next.getRearPosition() - front;
    }

    private double localGapBehind(Lane lane, double position, double bodyLength) {
        double rear = position - bodyLength / 2.0;
        double best = lane.getLength();
        for (Vehicle other : lane.getVehiclesInLane()) {
            double gap = rear - other.getFrontPosition();
            if (gap > 0 && gap < best)
                best = gap;
        }
        return best;
    }

    private Vehicle closestVehicleBehind(Lane lane, double position, double bodyLength) {
        double rear = position - bodyLength / 2.0;
        Vehicle closest = null;
        double best = Double.MAX_VALUE;
        for (Vehicle other : lane.getVehiclesInLane()) {
            double gap = rear - other.getFrontPosition();
            if (gap >= 0 && gap < best) {
                best = gap;
                closest = other;
            }
        }
        return closest;
    }

    private double laneChangeScore(Lane lane, Vehicle vehicle) {
        double position = vehicle.getPositionExact();
        double bodyLength = vehicle.getBodyLength();
        return localGapAhead(lane, position, bodyLength) * 3.0
                + localGapBehind(lane, position, bodyLength)
                - (int) Math.round(lane.getCongestion() * 25)
                - laneRiskPenalty(lane, position)
                + lanePolicyScore(lane, vehicle);
    }

    private double lanePolicyScore(Lane lane, Vehicle vehicle) {
        if (!(vehicle instanceof Car car))
            return 0.0;
        Lane current = vehicle.getCurrentLane();
        if (current == null)
            return 0.0;
        double score = 0.0;
        if (lane.getLaneId() == PARKING_LANE_ID && !isCommittedToImmediateParking(car, current)) {
            score -= 12.0;
        }
        if (current.getLaneId() == PARKING_LANE_ID && lane.getLaneId() == PARKING_LANE_ID + 1
                && shouldClearCurbLane(car, current)) {
            score += 16.0;
        }
        if (lane.getLaneId() == current.getLaneId() + 1
                && current.getLaneId() > PARKING_LANE_ID
                && hasInnerMergePressure(current.getLaneId(), vehicle.getPositionExact())) {
            score += 8.0;
        }
        if (lane.getLaneId() == PARKING_LANE_ID
                && (blocksActiveExitCorridor(vehicle) || occupiedExitCorridorOverlapCount(vehicle) > 0)) {
            score -= 16.0;
        }
        return score;
    }

    private double laneRiskPenalty(Lane lane, double position) {
        int penalty = lane.isLaneBlocked() ? 40 : 0;
        if (!phase2Enabled && street.countFreeSpots() == 0
                && lane.getLaneId() == PARKING_LANE_ID) {
            penalty += 18;
        }
        for (Vehicle v : lane.getVehiclesInLane()) {
            double distanceAhead = v.getRearPosition() - position;
            if (distanceAhead <= 0 || distanceAhead > QUEUE_LANE_CHANGE_LOOKAHEAD)
                continue;
            if (v.isYieldingForMerge())
                continue;
            if (Vehicle.STATE_WAITING.equals(v.getState()) || v.getSpeedExact() < 0.15) {
                penalty += 15;
            }
        }
        return penalty;
    }

    private void tickVisualMarkers() {
        // Polymorphic dispatch: Car overrides tickVisualMarkers() (adds the
        // police/camera flash timers), the base Vehicle version runs otherwise.
        for (Vehicle vehicle : street.allVehicles())
            vehicle.tickVisualMarkers();
    }

    // =====================================================================
    // Parking exit and turnover
    // =====================================================================
    private void simulateParkingTurnover(double dt) {
        for (ParkingSpot spot : street.getParkingSpots()) {
            if (!spot.isOccupied())
                continue;
            if (spot.getParkedCar() == null) {
                if (rng.nextDouble() < parkingTurnover)
                    spot.vacate();
                continue;
            }
            if (!spot.isExitRequested() && rng.nextDouble() < parkingTurnover) {
                spot.requestExit();
            }
            if (spot.isExitRequested()) {
                tryExitParkingSpot(spot, dt);
            }
        }
    }

    private void tryExitParkingSpot(ParkingSpot spot, double dt) {
        Car car = spot.getParkedCar();
        if (car == null) {
            spot.vacate();
            return;
        }
        Lane lane = street.getLanes().get(Math.min(PARKING_LANE_ID, street.getNumberOfLanes() - 1));
        ExitCheck check = checkParkingExitGap(spot, car, lane);
        if (!check.allowed) {
            spot.markExitBlocked(dt, check.reason, check.blockerVehicleId);
            proactivelyResolveExitBlocker(spot, lane);
            maybeResolveExitDeadlock(spot, lane);
            return;
        }

        Car exiting = spot.releaseForExit();
        exiting.setSeekingParking(false);
        exiting.setRedirected(true);
        exiting.setTargetParkingSpot(null);
        exiting.setHasReservation(false);
        exiting.setPositionExact(spot.getPositionX());
        exiting.setHeadingDegrees(90.0);
        exiting.setSpeedExact(SLOW_CRUISE_SPEED);
        exiting.setState(Vehicle.STATE_MOVING);
        lane.addVehicle(exiting);
    }

    private ExitCheck checkParkingExitGap(ParkingSpot spot, Car car, Lane lane) {
        if (street.getActiveAccident() != null && street.getActiveAccident().getLaneId() == lane.getLaneId()) {
            return ExitCheck.blocked("lane blocked by accident", -1);
        }
        double mergeRear = exitMergeRear(spot, car);
        double mergeFront = exitMergeFront(spot, car);

        Vehicle closestAhead = null;
        Vehicle closestBehind = null;
        double aheadGap = Double.MAX_VALUE;
        double behindGap = Double.MAX_VALUE;
        for (Vehicle vehicle : lane.getVehiclesInLane()) {
            if (mergeFront > vehicle.getRearPosition() && mergeRear < vehicle.getFrontPosition()) {
                return ExitCheck.blocked(reasonForBlockingVehicle(vehicle, "lane occupied during exit path"),
                        vehicle.getVehicleId());
            }
            double candidateAhead = vehicle.getRearPosition() - mergeFront;
            if (candidateAhead >= 0 && candidateAhead < aheadGap) {
                aheadGap = candidateAhead;
                closestAhead = vehicle;
            }
            double candidateBehind = mergeRear - vehicle.getFrontPosition();
            if (candidateBehind >= 0 && candidateBehind < behindGap) {
                behindGap = candidateBehind;
                closestBehind = vehicle;
            }
        }
        if (aheadGap < EXIT_REQUIRED_AHEAD) {
            return ExitCheck.blocked(reasonForBlockingVehicle(closestAhead, "no safe merge gap ahead"),
                    closestAhead == null ? -1 : closestAhead.getVehicleId());
        }
        if (behindGap < EXIT_REQUIRED_BEHIND) {
            if (closestBehind != null && car.canRequestMergeHelp()) {
                double gapDelta = EXIT_REQUIRED_BEHIND - behindGap;
                if (gapDelta <= MAX_HELPABLE_GAP_DELTA && gapDelta > 0.05
                        && closestBehind.getSpeedExact() > 0.8 && !closestBehind.isYieldingForMerge()) {
                    closestBehind.requestMergeYield();
                    car.armMergeHelpCooldown();
                    car.markLaneIntent("MERGE");
                }
            }
            return ExitCheck.blocked(reasonForBlockingVehicle(closestBehind, "no safe merge gap behind"),
                    closestBehind == null ? -1 : closestBehind.getVehicleId());
        }
        return ExitCheck.allowed();
    }

    private double exitMergeRear(ParkingSpot spot, Car car) {
        return spot.getPositionX() - exitMergeHalfLength(car);
    }

    private double exitMergeFront(ParkingSpot spot, Car car) {
        return spot.getPositionX() + exitMergeHalfLength(car);
    }

    private double exitMergeHalfLength(Car car) {
        return car.getBodyLength() / 2.0 + EXIT_MERGE_ZONE_BUFFER;
    }

    private String reasonForBlockingVehicle(Vehicle blocker, String fallback) {
        if (blocker == null)
            return fallback;
        if (Vehicle.STATE_WAITING.equals(blocker.getState())) {
            return "blocked by waiting car #" + blocker.getVehicleId() + " in lane 0";
        }
        if (blocker.getSpeedExact() < 0.15) {
            return "blocked by stopped car #" + blocker.getVehicleId() + " in lane 0";
        }
        return "blocked by moving traffic #" + blocker.getVehicleId() + " in lane 0";
    }

    private void maybeResolveExitDeadlock(ParkingSpot spot, Lane lane) {
        int blockerId = spot.getExitBlockerVehicleId();
        if (!spot.isExitStarved(EXIT_STARVATION_SECONDS) || blockerId < 0)
            return;
        for (Vehicle vehicle : lane.getVehiclesInLane()) {
            if (vehicle.getVehicleId() != blockerId)
                continue;
            if (!(vehicle instanceof Car blocker))
                return;
            if (!Vehicle.STATE_WAITING.equals(blocker.getState()))
                return;

            double gap = openGapAhead(lane, blocker);
            if (gap <= STOPPING_BUFFER)
                return;
            redirectAwayFromParking(blocker);
            return;
        }
    }

    private void proactivelyResolveExitBlocker(ParkingSpot spot, Lane lane) {
        int blockerId = spot.getExitBlockerVehicleId();
        if (blockerId < 0)
            return;
        for (Vehicle vehicle : lane.getVehiclesInLane()) {
            if (vehicle.getVehicleId() != blockerId)
                continue;
            if (!(vehicle instanceof Car blocker))
                return;
            if (!Vehicle.STATE_WAITING.equals(blocker.getState()))
                return;
            if (!blocksActiveExitCorridor(blocker))
                return;
            if (tryChangeLaneToBestAdjacent(blocker, lane))
                return;
            if (openGapAhead(lane, blocker) > STOPPING_BUFFER) {
                redirectAwayFromParking(blocker);
            }
            return;
        }
    }

    private static final class ExitCheck {
        final boolean allowed;
        final String reason;
        final int blockerVehicleId;

        private ExitCheck(boolean allowed, String reason, int blockerVehicleId) {
            this.allowed = allowed;
            this.reason = reason;
            this.blockerVehicleId = blockerVehicleId;
        }

        static ExitCheck allowed() {
            return new ExitCheck(true, "", -1);
        }

        static ExitCheck blocked(String reason, int blockerVehicleId) {
            return new ExitCheck(false, reason, blockerVehicleId);
        }
    }

    private void removeExited() {
        for (Lane lane : street.getLanes()) {
            List<Vehicle> toRemove = new ArrayList<>();
            for (Vehicle v : lane.getVehiclesInLane()) {
                if (v.getRearPosition() >= street.getStreetLength())
                    toRemove.add(v);
            }
            for (Vehicle v : toRemove) {
                // Release any reservation before removing the car.
                if (v instanceof Car c)
                    controller.releaseReservationFor(c);
                lane.removeVehicle(v);
                v.setState(Vehicle.STATE_REMOVED);
                if (v instanceof Car)
                    stats.carProcessed();
            }
        }
    }

    // =====================================================================
    // Statistics and bookkeeping
    // =====================================================================

    private void recordStats() {
        double totalCong = 0;
        int waitingNow = 0;
        int blockedNow = 0;
        double speedSum = 0.0;
        int speedCount = 0;

        for (Lane lane : street.getLanes()) {
            totalCong += lane.getCongestion();
            if (lane.isLaneBlocked())
                blockedNow++;
            for (Vehicle v : lane.getVehiclesInLane()) {
                if (Vehicle.STATE_WAITING.equals(v.getState()))
                    waitingNow++;
                // Count each car once the first time we see it redirected, so
                // the KPI reflects how many cars were turned away / sent on.
                if (v instanceof Car c && c.isRedirected()
                        && redirectedSeen.add(c.getVehicleId()))
                    stats.noteRedirectedCar();
                speedSum += v.getSpeedExact();
                speedCount++;
            }
        }

        int totalSpots = street.getParkingSpots().size();
        stats.recordParking(totalSpots - street.countFreeSpots(), totalSpots);

        double avgCong = totalCong / Math.max(1, street.getNumberOfLanes());
        double avgSpd = (speedCount == 0) ? 0 : speedSum / speedCount;
        stats.recordCycle(avgCong, waitingNow, blockedNow, avgSpd);
    }

    // =====================================================================
    // GUI controls
    // =====================================================================
    public void triggerManualAccident() {
        if (street.getActiveAccident() != null)
            return;
        Lane lane = street.getLanes().get(rng.nextInt(street.getNumberOfLanes()));
        double pos = 10 + rng.nextDouble() * Math.max(1, street.getStreetLength() - 20);

        Car lead = new Car(nextVehicleId++, false);
        Car rear = new Car(nextVehicleId++, false);
        bodyCatalog.applyTo(lead);
        bodyCatalog.applyTo(rear);
        lead.setPositionExact(pos);
        rear.setPositionExact(pos - Math.min(0.25, rear.getBodyLength() / 4.0));
        lead.setSpeedExact(0.0);
        rear.setSpeedExact(NORMAL_CRUISE_SPEED);
        lane.addVehicle(lead);
        lane.addVehicle(rear);
        createCollisionAccident(lane, rear, lead);
    }

    public void triggerManualEmergency() {
        Lane lane = street.getLanes().get(rng.nextInt(street.getNumberOfLanes()));
        EmergencyVehicle ev = new EmergencyVehicle(nextVehicleId++, "AMBULANCE");
        bodyCatalog.applyTo(ev);
        if (!isEntryClear(lane, ev.getBodyLength()))
            return;
        ev.setPositionExact(ev.getBodyLength() / 2.0);
        ev.setSpeedExact(EMERGENCY_SPEED);
        lane.addVehicle(ev);
    }

    // =====================================================================
    // Getters and setters
    // =====================================================================
    public Street getStreet() {
        return street;
    }

    public TrafficController getController() {
        return controller;
    }

    public StatisticsTracker getStats() {
        return stats;
    }

    public boolean isPhase2Enabled() {
        return phase2Enabled;
    }

    // Turn the Phase 2 managed-parking solution on/off. Also flips the
    // controller's enforcement options so police patrol + camera become active
    // in Phase 2 and silent in Phase 1.
    public void setPhase2Enabled(boolean p) {
        this.phase2Enabled = p;
        this.controller.setPhase2Enabled(p);
        this.controller.setPolicePatrolEnabled(p);
        this.controller.setCameraEnabled(p);
    }

    public int getCurrentCycle() {
        return currentCycle;
    }

    public int getMaxCycles() {
        return maxCycles;
    }

    public void setMaxCycles(int m) {
        this.maxCycles = m;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean r) {
        this.running = r;
    }

    public double getSpawnRate() {
        return trafficDemandPer100Ticks / (100.0 * Math.max(1, street.getNumberOfLanes()));
    }

    public void setSpawnRate(double r) {
        setTrafficDemandPer100Ticks((int) Math.round(r * 100 * Math.max(1, street.getNumberOfLanes())));
    }

    public int getTrafficDemandPer100Ticks() {
        return trafficDemandPer100Ticks;
    }

    public void setTrafficDemandPer100Ticks(int demand) {
        this.trafficDemandPer100Ticks = Math.max(0, demand);
    }

    public void setParkingSeekerRatio(double r) {
        this.parkingSeekerRatio = r;
    }

    public void setEmergencySpawnRate(double r) {
        this.emergencySpawnRate = r;
    }

    public void setParkingTurnover(double r) {
        this.parkingTurnover = r;
    }

    public void setRandomSeed(long seed) {
        this.rng.setSeed(seed);
    }

    public boolean isMovementRandomnessEnabled() {
        return movementRandomnessEnabled;
    }

    public void setMovementRandomnessEnabled(boolean enabled) {
        this.movementRandomnessEnabled = enabled;
    }

}
