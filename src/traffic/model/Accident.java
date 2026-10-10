package traffic.model;

/**
 * Lane blockage at a fixed X. An accident blocks a lane until an
 * {@link EmergencyVehicle} arrives and the simulation marks it cleared.
 */
public class Accident {

    private final int severity; // 1..3, just tells how bad it looks
    private int cyclesElapsed; // how many simulation ticks have passed since crash
    private final double positionX; // where on the road (floating point)
    private final int laneId; // which lane is blocked
    private boolean cleared; // whether help has arrived

    public Accident(int severity, double positionX, int laneId) {
        this.severity = severity;
        this.cyclesElapsed = 0;
        this.positionX = positionX;
        this.laneId = laneId;
        this.cleared = false;
    }

    /** Accumulate the diagnostic age counter. Does NOT auto-clear. */
    public void tick() {
        if (!cleared)
            cyclesElapsed++;
    }

    /** Mark the accident cleared when emergency help arrives. */
    public void markCleared() {
        this.cleared = true;
    }

    // Simple getters used by the simulation and GUI.
    public boolean isCleared() {
        return cleared;
    }

    public int getCyclesElapsed() {
        return cyclesElapsed;
    }

    public double getPositionExact() {
        return positionX;
    }

    public int getLaneId() {
        return laneId;
    }

    @Override
    public String toString() {
        return "Accident[lane=" + laneId
                + ", x=" + String.format(java.util.Locale.ROOT, "%.2f", positionX)
                + ", severity=" + severity
                + ", age=" + cyclesElapsed
                + (cleared ? ", CLEARED" : "") + "]";
    }
}
