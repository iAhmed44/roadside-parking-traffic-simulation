package traffic.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One horizontal lane of a fixed length (in cells).
 * Knows nothing about rendering — just the vehicles inside it.
 */
public class Lane {

    private final int laneId;
    private final int length;
    private final List<Vehicle> vehiclesInLane;
    private boolean laneBlocked;

    public Lane(int laneId, int length) {
        if (laneId < 0)
            throw new IllegalArgumentException("laneId must be >= 0 (got " + laneId + ")");
        if (length <= 0)
            throw new IllegalArgumentException("lane length must be > 0 (got " + length + ")");
        this.laneId = laneId;
        this.length = length;
        this.vehiclesInLane = new ArrayList<>();
    }

    // Add/remove vehicles while keeping the back-reference on the vehicle.
    public void addVehicle(Vehicle v) {
        if (!vehiclesInLane.contains(v))
            vehiclesInLane.add(v);
        v.setCurrentLane(this);
    }

    public void removeVehicle(Vehicle v) {
        if (vehiclesInLane.remove(v))
            v.setCurrentLane(null);
    }

    /** Congestion = fraction of the lane's cells that are occupied. */
    public double getCongestion() {
        if (length == 0)
            return 0;
        return (double) vehiclesInLane.size() / length;
    }

    /** Nearest vehicle strictly ahead of the given position (null if none). */
    public Vehicle getNextVehicleAhead(double fromPos) {
        Vehicle closest = null;
        double minDist = Double.MAX_VALUE;
        for (Vehicle v : vehiclesInLane) {
            double dist = v.getRearPosition() - fromPos;
            if (dist > 0 && dist < minDist) {
                minDist = dist;
                closest = v;
            }
        }
        return closest;
    }

    public int getLaneId() {
        return laneId;
    }

    public int getLength() {
        return length;
    }

    /**
     * Read-only view of the cars currently in this lane. Mutations must go
     * through {@link #addVehicle(Vehicle)} / {@link #removeVehicle(Vehicle)}
     * so the bidirectional car ⇄ lane link stays consistent.
     */
    public List<Vehicle> getVehiclesInLane() {
        return Collections.unmodifiableList(vehiclesInLane);
    }

    public boolean isLaneBlocked() {
        return laneBlocked;
    }

    public void setLaneBlocked(boolean b) {
        this.laneBlocked = b;
    }

    @Override
    public String toString() {
        return "Lane#" + laneId + "[len=" + length + ", vehicles=" + vehiclesInLane.size()
                + (laneBlocked ? ", BLOCKED" : "") + "]";
    }
}
