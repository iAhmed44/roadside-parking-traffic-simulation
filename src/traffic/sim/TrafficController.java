package traffic.sim;

import java.util.HashMap;
import java.util.Map;
import traffic.model.Car;
import traffic.model.Lane;
import traffic.model.ParkingSpot;
import traffic.model.Street;
import traffic.model.Vehicle;

/**
 * Phase 2 "brain" for managed parking. It replaces the simple Phase 1
 * first-come parking helper (which only found the nearest free spot) with a
 * smarter coordinator that, each tick while Phase 2 is on:
 *
 * <ol>
 *   <li>{@link #assignParking()} — proactively <em>reserves</em> the nearest
 *       reachable free spot for every seeker, and redirects seekers early when
 *       the curb near them is full (so they never stop and block lane 0);</li>
 *   <li>{@link #enforceWaitLimit()} — any car that has still waited too long is
 *       redirected (and optionally camera-fined);</li>
 *   <li>{@link #policePatrol()} — an optional, more visible version that warns
 *       cars that have waited even briefly.</li>
 * </ol>
 *
 * <p>It also works for Phase 1 callers through {@link #bestReachableSpot},
 * which finds a spot without reserving it — that lets the single
 * {@code Simulation} class run both phases from the same code.
 */
public class TrafficController {

    private static final int PARKING_LANE_ID = 0;
    private static final int PARKING_DECISION_DISTANCE = 2;
    private static final int MIN_REDIRECT_POSITION = 6;
    // How far ahead a redirected-but-still-wanting car will look for a freshly
    // opened spot before deciding it's worth seeking again.
    private static final double RESEEK_LOOKAHEAD = 30.0;
    private static final double RESEEK_MIN_AHEAD = 1.0;
    // Speed a redirected car resumes at (matches Simulation.SLOW_CRUISE_SPEED)
    // so a mid-tick redirect eases out of the WAITING freeze instead of
    // snapping straight to cruise speed.
    private static final double REDIRECT_RESUME_SPEED = 7.0;

    private int maxWaitTimeLimit;
    private final Map<Car, ParkingSpot> reservations = new HashMap<>();
    private final Street street;

    private boolean phase2Enabled;
    private boolean policePatrolEnabled;
    private boolean cameraEnabled;
    private int finesIssued;
    private int warningsIssued;

    public TrafficController(Street street, int maxWaitTimeLimit) {
        this.street = street;
        this.maxWaitTimeLimit = maxWaitTimeLimit;
    }

    /** Kick out cars that have been waiting too long. */
    public void enforceWaitLimit() {
        for (Lane lane : street.getLanes()) {
            for (Vehicle v : lane.getVehiclesInLane()) {
                if (!(v instanceof Car c))
                    continue;
                if (!Vehicle.STATE_WAITING.equals(c.getState()))
                    continue;
                int limit = cameraEnabled ? Math.max(5, maxWaitTimeLimit / 3) : maxWaitTimeLimit;
                if (c.getWaitingTime() <= limit)
                    continue;

                redirectWaitingCar(c);
                if (cameraEnabled) {
                    finesIssued++;
                    c.fineByCamera();
                }
            }
        }
    }

    /**
     * The single source of truth for "give up on parking, start moving again".
     * Releases the car's reservation first (so the spot frees up the same tick),
     * then pushes it back into MOVING at a modest speed.
     */
    private void redirectWaitingCar(Car c) {
        if (c.hasReservation() && c.getTargetParkingSpot() != null) {
            c.getTargetParkingSpot().release();
            reservations.remove(c);
            c.setHasReservation(false);
        }
        c.setTargetParkingSpot(null);
        c.setSeekingParking(false);
        c.setRedirected(true);
        c.setState(Vehicle.STATE_MOVING);
        c.setSpeedExact(REDIRECT_RESUME_SPEED);
        c.setWaitingTime(0);
        c.clearStoppedInTraffic();
    }

    /** Reserve or upgrade to the nearest reachable parking spot for any seeker. */
    public void assignParking() {
        for (Lane lane : street.getLanes()) {
            for (Vehicle v : lane.getVehiclesInLane()) {
                if (!(v instanceof Car c))
                    continue;
                // A car redirected off a full curb resumes seeking when a real
                // spot has opened ahead of it — otherwise a redirect was forever
                // and freshly freed spots near already-redirected cars went
                // unused. Cars that already parked, were just warned/fined, or
                // are clearing an accident are left alone (see canResumeSeeking).
                if (c.isRedirected() && canResumeSeeking(c, lane)) {
                    c.setRedirected(false);
                    c.setSeekingParking(true);
                }
                if (!c.isSeekingParking() || c.isRedirected())
                    continue;

                ParkingSpot spot = bestReachableSpot(c, lane);
                if (spot != null) {
                    assignOrUpgradeReservation(c, spot);
                } else if (shouldRedirectForNoParking(c, lane)) {
                    if (c.isStubbornParker()) {
                        // A stubborn parker refuses to leave; it still waits,
                        // but Phase 2 has explicitly accounted for it.
                        c.stop();
                        c.incrementWait();
                        continue;
                    }
                    // No spot near the curb decision area: give up early and
                    // keep moving instead of stopping in the live lane.
                    c.setSeekingParking(false);
                    c.setRedirected(true);
                }
            }
        }
    }

    /**
     * Should a redirected car start hunting for parking again? Only if it still
     * intends to park, sits in the curb lane, isn't mid-enforcement (a fresh
     * police warning / camera fine), there's no accident in play, and a genuinely
     * free spot has opened a little way ahead of it.
     */
    private boolean canResumeSeeking(Car c, Lane lane) {
        if (lane.getLaneId() != PARKING_LANE_ID)
            return false;
        if (!c.hasParkingIntent())
            return false;
        if (c.getWarningFlashTicks() > 0 || c.getCameraFineFlashTicks() > 0)
            return false;
        if (street.getActiveAccident() != null)
            return false;
        return hasFreeReachableSpotAhead(c);
    }

    private boolean hasFreeReachableSpotAhead(Car c) {
        for (ParkingSpot spot : street.getParkingSpots()) {
            if (!spot.isFree())
                continue;
            double ahead = spot.getPositionX() - c.getPositionExact();
            if (ahead >= RESEEK_MIN_AHEAD && ahead <= RESEEK_LOOKAHEAD)
                return true;
        }
        return false;
    }

    /**
     * Nearest reachable free (or already-mine) spot in the curb lane, without
     * reserving it. Phase 1 uses this directly; Phase 2 wraps it with
     * reservation bookkeeping.
     */
    public ParkingSpot bestReachableSpot(Car car, Lane lane) {
        if (car == null || lane == null)
            return null;
        if (!car.isSeekingParking() || car.isRedirected())
            return null;
        if (lane.getLaneId() != PARKING_LANE_ID)
            return null;

        ParkingSpot current = normalizedReservation(car);
        ParkingSpot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ParkingSpot spot : street.getParkingSpots()) {
            if (!isReachableCandidate(car, spot, current))
                continue;
            double distance = spot.getPositionX() - car.getPositionExact();
            if (distance < bestDistance) {
                bestDistance = distance;
                best = spot;
            }
        }
        return best;
    }

    public boolean refreshParkingTarget(Car car, Lane lane) {
        ParkingSpot best = bestReachableSpot(car, lane);
        if (best == null) {
            clearStaleReservation(car);
            return false;
        }
        return assignOrUpgradeReservation(car, best);
    }

    public boolean assignOrUpgradeReservation(Car car, ParkingSpot newSpot) {
        if (car == null || newSpot == null)
            return false;
        ParkingSpot current = normalizedReservation(car);
        if (current == newSpot) {
            car.setTargetParkingSpot(newSpot);
            car.setHasReservation(true);
            reservations.put(car, newSpot);
            return true;
        }

        // Reserve the NEW spot first; only release the old one if that worked.
        // (Releasing first risked leaving the car with nothing when two cars
        // raced for the same upgrade in one tick.)
        if (!newSpot.reserve(car)) {
            return false;
        }
        if (current != null && current != newSpot) {
            current.release();
        }
        reservations.put(car, newSpot);
        car.setTargetParkingSpot(newSpot);
        car.setHasReservation(true);
        return true;
    }

    public void clearStaleReservation(Car car) {
        ParkingSpot current = reservations.remove(car);
        if (current == null)
            current = car.getTargetParkingSpot();
        if (current != null && current.getReservedBy() == car) {
            current.release();
        }
        car.setTargetParkingSpot(null);
        car.setHasReservation(false);
    }

    private ParkingSpot normalizedReservation(Car car) {
        ParkingSpot mapped = reservations.get(car);
        ParkingSpot target = car.getTargetParkingSpot();

        if (mapped != null && mapped.getReservedBy() != car) {
            reservations.remove(car);
            mapped = null;
        }
        if (target != null && target.getReservedBy() != car && target.isReserved()) {
            target = null;
        }
        if (mapped != null && target != null && mapped != target) {
            mapped.release();
            reservations.remove(car);
            mapped = null;
        }
        ParkingSpot current = mapped != null ? mapped : target;
        if (current != null && current.getReservedBy() == car) {
            car.setTargetParkingSpot(current);
            car.setHasReservation(true);
            reservations.put(car, current);
            return current;
        }
        if (current != null && current.isFree())
            return null;
        car.setHasReservation(false);
        return null;
    }

    private boolean isReachableCandidate(Car car, ParkingSpot spot, ParkingSpot current) {
        if (spot == null || spot.isOccupied())
            return false;
        if (spot.isReserved() && spot.getReservedBy() != car)
            return false;
        double distance = spot.getPositionX() - car.getPositionExact();
        return distance >= -0.35 || spot == current;
    }

    private boolean shouldRedirectForNoParking(Car c, Lane lane) {
        if (lane.getLaneId() != PARKING_LANE_ID)
            return false;
        if (c.getPositionExact() < MIN_REDIRECT_POSITION)
            return false;
        if (street.getParkingSpots().isEmpty())
            return c.getPositionExact() > 5;

        for (ParkingSpot spot : street.getParkingSpots()) {
            if (Math.abs(spot.getPositionX() - c.getPositionExact()) <= PARKING_DECISION_DISTANCE) {
                return true;
            }
        }

        ParkingSpot last = street.getParkingSpots().get(street.getParkingSpots().size() - 1);
        return c.getPositionExact() > last.getPositionX();
    }

    /** Police patrol is visible; it warns cars that have already waited a bit. */
    public void policePatrol() {
        if (!policePatrolEnabled)
            return;
        for (Lane lane : street.getLanes()) {
            for (Vehicle v : lane.getVehiclesInLane()) {
                if (!(v instanceof Car c))
                    continue;
                if (!Vehicle.STATE_WAITING.equals(c.getState()))
                    continue;
                if (c.getWaitingTime() <= Math.max(4, maxWaitTimeLimit / 3))
                    continue;

                redirectWaitingCar(c);
                warnCar(c);
            }
        }
    }

    public void warnCar(Car c) {
        c.warnByPolice();
        warningsIssued++;
    }

    /**
     * Called when a car leaves the world outside the normal parking path
     * (collision despawn or end-of-street exit). Without this the reservation
     * map would keep a reference to the removed car and its spot would stay
     * stuck "reserved" forever. Safe to call on any car.
     */
    public void releaseReservationFor(Car c) {
        if (c == null)
            return;
        ParkingSpot spot = reservations.remove(c);
        if (spot == null)
            spot = c.getTargetParkingSpot();
        if (spot != null && spot.getReservedBy() == c)
            spot.release();
        c.setHasReservation(false);
        c.setTargetParkingSpot(null);
    }

    /**
     * Called after a successful park. The reservation is <em>consumed</em>:
     * the spot goes straight from "reserved by c" to "occupied by c", so we
     * drop the bookkeeping without calling {@link ParkingSpot#release()}.
     */
    public void forgetReservation(Car c) {
        reservations.remove(c);
        c.setHasReservation(false);
    }

    // ---- getters / setters ----
    public int getMaxWaitTimeLimit() {
        return maxWaitTimeLimit;
    }

    public void setMaxWaitTimeLimit(int m) {
        this.maxWaitTimeLimit = m;
    }

    public boolean isPhase2Enabled() {
        return phase2Enabled;
    }

    public void setPhase2Enabled(boolean p) {
        this.phase2Enabled = p;
    }

    public boolean isPolicePatrolEnabled() {
        return policePatrolEnabled;
    }

    public void setPolicePatrolEnabled(boolean p) {
        this.policePatrolEnabled = p;
    }

    public boolean isCameraEnabled() {
        return cameraEnabled;
    }

    public void setCameraEnabled(boolean c) {
        this.cameraEnabled = c;
    }

    public int getFinesIssued() {
        return finesIssued;
    }

    public int getWarningsIssued() {
        return warningsIssued;
    }

    public Map<Car, ParkingSpot> getReservations() {
        return reservations;
    }
}
