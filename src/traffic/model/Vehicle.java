package traffic.model;

import traffic.interfaces.Movable;

/**
 * Abstract base for everything that drives on the street.
 * Concrete subclasses: Car, EmergencyVehicle.
 */
public abstract class Vehicle implements Movable {

    // --- simple named states ---
    // These are words we use to say what the vehicle is doing right now.
    // Think of them like tags: "moving", "parked", "waiting", etc.
    public static final String STATE_MOVING = "moving";
    public static final String STATE_WAITING = "waiting";
    public static final String STATE_PARKED = "parked";
    public static final String STATE_WAITING_TO_EXIT = "waiting_to_exit";
    public static final String STATE_EXIT_BLOCKED = "exit_blocked";
    public static final String STATE_EXITING = "exiting";
    public static final String STATE_REMOVED = "removed";

    // --- main properties of a vehicle ---
    final int vehicleId; // a unique sticker number for the vehicle
    double position; // where the vehicle is along the lane (decimal allowed)
    double previousPosition; // where it was in the last step (for smooth drawing)
    double speed; // how fast the car is going (road units per second)
    double bodyLength; // how long the car is (used for collisions and drawing)
    double bodyWidth; // how wide the car is (in lane units)
    double headingDegrees; // which way the car points (like a compass)
    double previousHeadingDegrees; // previous heading for smooth rotation
    double previousLaneId; // previous lane id used for animation
    double renderLaneId; // where we draw the car (may be between lanes during lane-change)
    String state; // current state tag (one of the STATE_* values)
    Lane currentLane; // which lane the car is in (null if none)

    // --- small counters used for visual effects and behavior timing ---
    int laneChangeFlashTicks; // how long to flash when lane changes
    int laneChangeCooldownTicks; // small cooldown so cars don't spam lane changes
    int mergeYieldTicks; // short timer for yielding to a merging car
    int mergeHelpCooldownTicks; // cooldown for asking other cars for merge help
    int postMergeBrakeTicks; // brief braking after merging
    String laneIntentMarker = ""; // short text shown above car, like "YIELD"
    int laneIntentMarkerTicks; // how long to show the lane intent marker

    // Constructor: called when a subclass (like Car) is created.
    Vehicle(int vehicleId) {
        this.vehicleId = vehicleId; // sticker number for this car
        // start in a safe default state and place
        position = 0.0;
        previousPosition = 0.0;
        speed = 8.0; // default gentle speed
        bodyLength = 1.9; // default size values
        bodyWidth = 0.72;
        headingDegrees = 90.0; // facing direction; 90 is a conventional default
        previousHeadingDegrees = 90.0;
        previousLaneId = 0.0;
        renderLaneId = 0.0;
        state = STATE_MOVING; // start as moving by default
    }

    // Each subclass decides how it moves.
    @Override
    public abstract void move();

    // Tell the vehicle to stop: set speed to 0 and mark it as waiting.
    @Override
    public void stop() {
        speed = 0;
        state = STATE_WAITING;
    }

    // Called when the vehicle just completed a lane change.
    public void markLaneChanged() {
        laneChangeFlashTicks = 12; // flash for a short time
        laneChangeCooldownTicks = 45; // wait a while before another change
    }

    // Ask the vehicle to yield for a merging car (set a short timer).
    public void requestMergeYield() {
        mergeYieldTicks = Math.max(mergeYieldTicks, 10);
        markLaneIntent("YIELD");
    }

    // Ask the vehicle to do a short brake after a merge.
    public void requestPostMergeBrake() {
        postMergeBrakeTicks = Math.max(postMergeBrakeTicks, 10);
        markLaneIntent("BRAKE");
    }

    // Returns true if this car can ask for help merging (cooldown expired).
    public boolean canRequestMergeHelp() {
        return mergeHelpCooldownTicks <= 0;
    }

    // Arm a cooldown so we don't spam merge-help requests.
    public void armMergeHelpCooldown() {
        mergeHelpCooldownTicks = 16;
    }

    // Show a small text marker over the car (like YIELD or BRAKE).
    public void markLaneIntent(String marker) {
        laneIntentMarker = marker == null ? "" : marker;
        laneIntentMarkerTicks = laneIntentMarker.isEmpty() ? 0 : 18;
    }

    // Called at the start of each simulation step to remember previous values
    // so the renderer can draw smooth motion between ticks.
    public void beginStep() {
        previousPosition = position;
        previousHeadingDegrees = headingDegrees;
        previousLaneId = renderLaneId;
    }

    // Used to start the visual transition when changing lanes.
    public void startLaneChangeVisual(int fromLaneId, int toLaneId) {
        previousLaneId = fromLaneId;
        renderLaneId = toLaneId;
    }

    // Tick down various visual and behavior timers each simulation step.
    public void tickVisualMarkers() {
        if (laneChangeFlashTicks > 0)
            laneChangeFlashTicks--;
        if (laneChangeCooldownTicks > 0)
            laneChangeCooldownTicks--;
        if (mergeYieldTicks > 0)
            mergeYieldTicks--;
        if (mergeHelpCooldownTicks > 0)
            mergeHelpCooldownTicks--;
        if (postMergeBrakeTicks > 0)
            postMergeBrakeTicks--;
        if (laneIntentMarkerTicks > 0) {
            laneIntentMarkerTicks--;
            if (laneIntentMarkerTicks == 0)
                laneIntentMarker = "";
        }
    }

    // ---- getters / setters ----
    public int getVehicleId() {
        return vehicleId;
    }

    public void setPosition(int p) {
        position = p;
        previousPosition = p;
    }

    public double getPositionExact() {
        return position;
    }

    public void setPositionExact(double p) {
        position = p;
        previousPosition = p;
    }

    public void moveToPositionExact(double p) {
        position = p;
    }

    public double getRenderPosition(double alpha) {
        return previousPosition + (position - previousPosition) * alpha;
    }

    public double getSpeedExact() {
        return speed;
    }

    public void setSpeed(int s) {
        speed = Math.max(0, s) * 7.0;
    }

    public void setSpeedExact(double s) {
        speed = Math.max(0.0, s);
    }

    public double getBodyLength() {
        return bodyLength;
    }

    public double getBodyWidth() {
        return bodyWidth;
    }

    public void setBodySize(double length, double width) {
        bodyLength = Math.max(0.8, length);
        bodyWidth = Math.max(0.35, width);
    }

    public double getRearPosition() {
        return position - bodyLength / 2.0;
    }

    public double getFrontPosition() {
        return position + bodyLength / 2.0;
    }

    public double getHeadingDegrees() {
        return headingDegrees;
    }

    public void setHeadingDegrees(double headingD) {
        headingDegrees = headingD;
    }

    public double getRenderHeadingDegrees(double alpha) {
        return previousHeadingDegrees + (headingDegrees - previousHeadingDegrees) * alpha;
    }

    public double getRenderLaneId(double alpha) {
        return previousLaneId + (renderLaneId - previousLaneId) * alpha;
    }

    public double getPreviousLaneId() {
        return previousLaneId;
    }

    public String getState() {
        return state;
    }

    public void setState(String s) {
        state = s;
    }

    public Lane getCurrentLane() {
        return currentLane;
    }

    public void setCurrentLane(Lane l) {
        boolean firstLaneAssignment = currentLane == null;
        currentLane = l;
        if (l != null) {
            previousLaneId = firstLaneAssignment ? l.getLaneId() : renderLaneId;
            renderLaneId = l.getLaneId();
        }
    }

    public int getLaneChangeFlashTicks() {
        return laneChangeFlashTicks;
    }

    public int getLaneChangeCooldownTicks() {
        return laneChangeCooldownTicks;
    }

    public boolean isYieldingForMerge() {
        return mergeYieldTicks > 0;
    }

    public boolean isBrakingAfterMerge() {
        return postMergeBrakeTicks > 0;
    }

    public String getLaneIntentMarker() {
        return laneIntentMarkerTicks > 0 ? laneIntentMarker : "";
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "#" + vehicleId
                + "[lane=" + (currentLane == null ? "-" : currentLane.getLaneId())
                + ", pos=" + String.format(java.util.Locale.ROOT, "%.2f", position)
                + ", v=" + String.format(java.util.Locale.ROOT, "%.2f", speed)
                + ", " + state + "]";
    }
}
