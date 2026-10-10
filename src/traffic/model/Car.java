package traffic.model;

/**
 * A car: a specific type of vehicle that can seek parking and has
 * various driving parameters. Inherits the basic position/speed/state
 * fields and methods from Vehicle.
 */

public class Car extends Vehicle {

    // Where the car wants to park (null if none).
    ParkingSpot targetParkingSpot;
    // How long the car has been waiting (used for statistics/behavior).
    int waitingTime;
    // Whether the car is currently looking for a parking spot.
    boolean seekingParking;
    // If true, the car is leaving instead of waiting for parking.
    boolean redirected;
    // The car's standing intent to park. Unlike seekingParking/redirected
    // (which flip as the car gives up at a full curb or is sent on), this stays
    // true until the car actually parks. It lets a car that was redirected off
    // a full curb resume seeking when a spot opens, instead of giving up for
    // good — while a car that already parked (and may later exit) never does.
    boolean parkingIntent;

    // --- Phase 2 fields (ignored while running in Phase 1) ---
    // True once the TrafficController has reserved a curb spot for this car.
    boolean hasReservation;
    // A stubborn parker insists on waiting for the curb even when redirected
    // would be smarter; Phase 2 still has to manage these explicitly.
    boolean stubbornParker;
    // Short visual timers so the GUI can flash a police warning / camera fine.
    int warningFlashTicks;
    int cameraFineFlashTicks;

    // Driving preferences and physical/behavioral parameters:
    double preferredCruiseSpeed = 13.5; // comfortable top speed
    double accelerationRate = 6.2; // how quickly it speeds up
    double brakingRate = 9.0; // how quickly it slows down
    double headwayBuffer = 0.18; // personal space in front
    int stoppedInTrafficTicks; // counter for showing queue badges
    boolean aggressiveDriver; // whether the driver drives aggressively
    // Which sprite/color to draw. Normally the vehicle id picks it, but a
    // replayed Phase 2 car copies its Phase 1 id here so the SAME car shows the
    // SAME image in both phases. -1 means "use the id".
    int colorSeed = -1;

    // Constructor: create a car with an id and whether it seeks parking.
    public Car(int id, boolean seekingParking) {
        super(id); // call the Vehicle constructor to set common fields
        this.seekingParking = seekingParking;
        this.parkingIntent = seekingParking;
    }

    /*
     * The actual collision-aware movement happens in Simulation because a
     * car needs to know about neighbours. This `move` method is a
     * fallback that simply advances position by speed when nothing else
     * is coordinating the motion.
     */

    // fps is 60, so this moves the car forward by speed/60 each tick.
    public static final double FALLBACK_STEP_SECONDS = 1.0 / 60.0;

    @Override
    public void move() {
        if (STATE_WAITING.equals(state)) {
            waitingTime++;
            return;
        }
        position += speed * FALLBACK_STEP_SECONDS;
    }

    // Simple helpers for queue counting used by the GUI.
    public void incrementWait() {
        this.waitingTime++;
    }

    // Lets the GUI show a "Q" badge for normal traffic queues.
    // We bound the counter so it doesn't grow forever.
    public void noteStoppedInTraffic() {
        if (stoppedInTrafficTicks < 100000)
            stoppedInTrafficTicks++;
    }

    // Reset the queue counter whenever the car actually moves.
    public void clearStoppedInTraffic() {
        this.stoppedInTrafficTicks = 0;
    }

    public int getStoppedInTrafficTicks() {
        return stoppedInTrafficTicks;
    }

    // ---- Phase 2 enforcement effects ----

    // Police patrol asks this car to stop illegally waiting; flash a marker.
    public void warnByPolice() {
        this.warningFlashTicks = 35;
    }

    // Camera enforcement fines a long-waiting blocker; flash a marker.
    public void fineByCamera() {
        this.cameraFineFlashTicks = 35;
    }

    // Tick down the Phase 2 flash timers on top of the shared visual markers.
    @Override
    public void tickVisualMarkers() {
        super.tickVisualMarkers();
        if (warningFlashTicks > 0)
            warningFlashTicks--;
        if (cameraFineFlashTicks > 0)
            cameraFineFlashTicks--;
    }

    // ---- getters / setters (simple read/write helpers) ----
    public ParkingSpot getTargetParkingSpot() {
        return targetParkingSpot;
    }

    public void setTargetParkingSpot(ParkingSpot p) {
        this.targetParkingSpot = p;
    }

    public int getWaitingTime() {
        return waitingTime;
    }

    public void setWaitingTime(int w) {
        this.waitingTime = w;
    }

    public boolean isSeekingParking() {
        return seekingParking;
    }

    public void setSeekingParking(boolean s) {
        this.seekingParking = s;
    }

    public boolean isRedirected() {
        return redirected;
    }

    public void setRedirected(boolean r) {
        this.redirected = r;
    }

    // True while the car still intends to park (until it actually parks).
    public boolean hasParkingIntent() {
        return parkingIntent;
    }

    public void setParkingIntent(boolean p) {
        this.parkingIntent = p;
    }

    // ---- Phase 2 getters / setters ----
    public boolean hasReservation() {
        return hasReservation;
    }

    public void setHasReservation(boolean r) {
        this.hasReservation = r;
    }

    public boolean isStubbornParker() {
        return stubbornParker;
    }

    public void setStubbornParker(boolean stubbornParker) {
        this.stubbornParker = stubbornParker;
    }

    public int getWarningFlashTicks() {
        return warningFlashTicks;
    }

    public int getCameraFineFlashTicks() {
        return cameraFineFlashTicks;
    }

    public double getPreferredCruiseSpeed() {
        return preferredCruiseSpeed;
    }

    public void setPreferredCruiseSpeed(double preferredCruiseSpeed) {
        // clamp the value so it stays within a reasonable range
        this.preferredCruiseSpeed = Math.max(9.0, Math.min(16.0, preferredCruiseSpeed));
    }

    public double getAccelerationRate() {
        return accelerationRate;
    }

    public void setAccelerationRate(double accelerationRate) {
        this.accelerationRate = Math.max(2.5, Math.min(10.0, accelerationRate));
    }

    public double getBrakingRate() {
        return brakingRate;
    }

    // Braking rate is how quickly the car can decelerate when slowing down or
    // stopping.
    public void setBrakingRate(double brakingRate) {
        this.brakingRate = Math.max(4.0, Math.min(14.0, brakingRate));
    }

    public double getHeadwayBuffer() {
        return headwayBuffer;
    }

    // Headway buffer is the desired minimum distance to the car in front, in road
    // units.
    public void setHeadwayBuffer(double headwayBuffer) {
        this.headwayBuffer = Math.max(0.12, Math.min(0.85, headwayBuffer));
    }

    public boolean isAggressiveDriver() {
        return aggressiveDriver;
    }

    // Aggressive driver flag indicates whether the car tends to drive more
    // aggressively.
    public void setAggressiveDriver(boolean aggressiveDriver) {
        this.aggressiveDriver = aggressiveDriver;
    }

    // The id used to choose this car's sprite/color: the replay-recorded seed
    // if set, otherwise the vehicle id.
    public int getColorSeedId() {
        return colorSeed >= 0 ? colorSeed : getVehicleId();
    }

    public void setColorSeed(int seed) {
        this.colorSeed = seed;
    }
}
