package traffic.model;

import traffic.interfaces.Reservable;

/**
 * A single curb-side parking spot.
 *
 * <p>Phase 2 adds reservation support via {@link Reservable}: the
 * {@code TrafficController} can hold a spot for one specific car before it
 * arrives, so seekers stop fighting over the same curb space. Phase 1 simply
 * never reserves, so {@code reserved} stays {@code false} and {@link #isFree()}
 * behaves exactly as before.
 */
public class ParkingSpot implements Reservable {

    // Unique numeric id for this spot (helps us print and identify it).
    int spotId;

    // X position along the street where this spot sits.
    int positionX;

    // True when a car is parked here.
    boolean occupied;

    // The car currently in this spot, or null when empty.
    Car parkedCar;

    // Phase 2 reservation state: a spot can be held for one car before it
    // arrives. A reserved-but-not-occupied spot is NOT free for anyone else.
    boolean reserved;
    Car reservedBy;

    // When a parked car asks to leave the curb, this becomes true until it exits.
    boolean exitRequested;

    // How long (seconds) the car has been blocked while trying to exit.
    double exitBlockedSeconds;

    // A short human-readable reason for why the exit is blocked.
    String exitBlockReason = "";

    // If some other vehicle is blocking the exit, we store its id here.
    int exitBlockerVehicleId = -1;

    public ParkingSpot(int SpotId, int PositionX) {
        spotId = SpotId;
        positionX = PositionX;
    }

    // Mark the spot as occupied without a tracked car object. (Used for setup or
    // tests.)

    public void occupy() {
        occupied = true;
        reserved = false;
        reservedBy = null;
        parkedCar = null;
        // Clear any previous exit diagnostics since this is a fresh occupy.
        clearExitDiagnostics();
    }

    // Park a specific car in this spot. Sets the car's state to PARKED.

    public void occupy(Car car) {
        occupied = true;
        reserved = false;
        reservedBy = null;
        parkedCar = car;
        // Clear any previous exit diagnostics since this is a fresh occupy.
        clearExitDiagnostics();
        if (car != null)
            car.setState(Vehicle.STATE_PARKED);
    }

    // Make the spot empty again and clear any exit diagnostics.

    public void vacate() {
        occupied = false;
        reserved = false;
        reservedBy = null;
        parkedCar = null;
        clearExitDiagnostics();
    }

    // --- Reservable (Phase 2) ---

    // Try to hold this spot for a car. Fails if already occupied or reserved.
    @Override
    public boolean reserve(Object owner) {
        if (occupied || reserved)
            return false;
        if (!(owner instanceof Car))
            return false;
        reserved = true;
        reservedBy = (Car) owner;
        return true;
    }

    // Drop a reservation so the spot can be reserved or parked by anyone.
    @Override
    public void release() {
        reserved = false;
        reservedBy = null;
    }

    @Override
    public boolean isReserved() {
        return reserved;
    }

    // The car this spot is being held for, or null when not reserved.
    public Car getReservedBy() {
        return reservedBy;
    }

    // True when the spot is free for another car to use (neither parked-in nor held).

    public boolean isFree() {
        return !occupied && !reserved;
    }

    public int getSpotId() {
        return spotId;
    }

    public int getPositionX() {
        return positionX;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public Car getParkedCar() {
        return parkedCar;
    }

    public boolean isExitRequested() {
        return exitRequested;
    }

    public double getExitBlockedSeconds() {
        return exitBlockedSeconds;
    }

    public String getExitBlockReason() {
        return exitBlockReason;
    }

    public int getExitBlockerVehicleId() {
        return exitBlockerVehicleId;
    }

    /*
     * Helper: has the car been waiting longer than a threshold while trying to
     * exit?
     */
    public boolean isExitStarved(double thresholdSeconds) {
        return exitRequested && exitBlockedSeconds >= thresholdSeconds;
    }

    /*
     * Called when the parked car wants to leave the curb. We record that the
     * car requested exit and set its state so the simulator can try to merge it
     * back into traffic.
     */
    public void requestExit() {
        if (!occupied || parkedCar == null)
            return;
        exitRequested = true;
        exitBlockedSeconds = 0.0;
        exitBlockReason = "checking merge gap";
        exitBlockerVehicleId = -1;
        parkedCar.setState(Vehicle.STATE_WAITING_TO_EXIT);
    }

    /*
     * When the simulator detects the car cannot leave (blocked by other
     * vehicles), it calls this to accumulate how long the car has been
     * prevented from exiting and store a short reason for debugging.
     */
    public void markExitBlocked(double deltaSeconds, String reason, int blockerVehicleId) {
        if (!exitRequested || parkedCar == null)
            return;
        exitBlockedSeconds += Math.max(0.0, deltaSeconds);
        exitBlockReason = reason == null ? "exit blocked" : reason;
        exitBlockerVehicleId = blockerVehicleId;
        parkedCar.setState(Vehicle.STATE_EXIT_BLOCKED);
    }

    /**
     * Free the car so the simulation can place it back into the driving lane.
     * Returns the car object that is leaving the spot (or null).
     */
    public Car releaseForExit() {
        Car exiting = parkedCar;
        if (exiting != null)
            exiting.setState(Vehicle.STATE_EXITING);
        occupied = false;
        parkedCar = null;
        clearExitDiagnostics();
        return exiting;
    }

    /**
     * Reset diagnostics used while a car is trying to exit. Called when the
     * spot becomes empty or a fresh occupy occurs.
     */
    void clearExitDiagnostics() {
        exitRequested = false;
        exitBlockedSeconds = 0.0;
        exitBlockReason = "";
        exitBlockerVehicleId = -1;
    }

    @Override
    public String toString() {
        String status = occupied ? "OCCUPIED" : reserved ? "RESERVED" : "FREE";
        return "ParkingSpot#" + spotId + "@x=" + positionX + "[" + status + "]";
    }
}
