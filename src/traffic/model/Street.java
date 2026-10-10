package traffic.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The full street: a collection of lanes plus a row of parking
 * spots down one side. Holds the currently-active accident (if any).
 */
public class Street {
    /**
     * Maximum allowed parking spots in the simulation. Caps UI and constructors.
     */
    public static final int MAX_PARKING_SPOTS = 18;

    private final int numberOfLanes;
    private final int streetLength;
    private final List<Lane> lanes;
    private final List<ParkingSpot> parkingSpots;
    private Accident activeAccident;

    public Street(int numberOfLanes, int streetLength, int numParkingSpots) {
        if (numberOfLanes <= 0)
            throw new IllegalArgumentException("numberOfLanes must be > 0 (got " + numberOfLanes + ")");
        if (streetLength <= 0)
            throw new IllegalArgumentException("streetLength must be > 0 (got " + streetLength + ")");
        if (numParkingSpots < 0)
            throw new IllegalArgumentException("numParkingSpots must be >= 0 (got " + numParkingSpots + ")");
        // Enforce a hard cap on the number of parking spots so the program
        // cannot create an excessively large number at runtime.
        if (numParkingSpots > MAX_PARKING_SPOTS)
            numParkingSpots = MAX_PARKING_SPOTS;

        this.numberOfLanes = numberOfLanes;
        this.streetLength = streetLength;

        this.lanes = new ArrayList<>();
        for (int i = 0; i < numberOfLanes; i++)
            lanes.add(new Lane(i, streetLength));

        this.parkingSpots = new ArrayList<>();
        // distribute parking spots evenly along the street
        int spacing = Math.max(1, (streetLength - 4) / Math.max(1, numParkingSpots));
        for (int i = 0; i < numParkingSpots; i++) {
            int x = 2 + i * spacing;
            if (x >= streetLength)
                x = streetLength - 1;
            parkingSpots.add(new ParkingSpot(i, x));
        }
    }

    public void addVehicle(Vehicle v, int laneId) {
        if (laneId >= 0 && laneId < lanes.size())
            lanes.get(laneId).addVehicle(v);
    }

    public void triggerAccident(int severity, double positionX, int laneId) {
        this.activeAccident = new Accident(severity, positionX, laneId);
        if (laneId >= 0 && laneId < lanes.size())
            lanes.get(laneId).setLaneBlocked(true);
    }

    public void clearAccident() {
        if (activeAccident != null) {
            int lid = activeAccident.getLaneId();
            if (lid >= 0 && lid < lanes.size())
                lanes.get(lid).setLaneBlocked(false);
        }
        this.activeAccident = null;
    }

    public int countFreeSpots() {
        int c = 0;
        for (ParkingSpot p : parkingSpots)
            if (p.isFree())
                c++;
        return c;
    }

    public int getNumberOfLanes() {
        return numberOfLanes;
    }

    public int getStreetLength() {
        return streetLength;
    }

    /** Read-only list of lanes. The lanes themselves are mutable. */
    public List<Lane> getLanes() {
        return Collections.unmodifiableList(lanes);
    }

    /**
     * Every vehicle on the street, across all lanes, gathered into one list.
     * The element type is the base class {@link Vehicle}, so callers can handle
     * Car / EmergencyVehicle / PoliceVehicle uniformly (subtype polymorphism).
     *
     * <p>Use only for READ-ONLY passes — the returned list is a fresh copy, but
     * do not add/remove vehicles from the lanes while looping over it.
     */
    public List<Vehicle> allVehicles() {
        List<Vehicle> all = new ArrayList<>();
        for (Lane lane : lanes) {
            for (Vehicle vehicle : lane.getVehiclesInLane()) {
                all.add(vehicle);
            }
        }
        return all;
    }

    /** Read-only list of parking spots. The spots themselves are mutable. */
    public List<ParkingSpot> getParkingSpots() {
        return Collections.unmodifiableList(parkingSpots);
    }

    public Accident getActiveAccident() {
        return activeAccident;
    }
}
