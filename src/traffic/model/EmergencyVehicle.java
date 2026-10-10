package traffic.model;

/**
 * Ambulance / police / fire truck. Moves faster and may force
 * waiting cars out of its way.
 */

public class EmergencyVehicle extends Vehicle {

    // Fallback step for move() when simulation isn't running.
    public static final double FALLBACK_STEP_SECONDS = 1.0 / 60.0;

    String emergencyType; // e.g. "ambulance" or "police"
    boolean sirenActive;

    public EmergencyVehicle(int id, String type) {
        super(id);
        emergencyType = type;
        sirenActive = true;
        // A reasonable default speed for standalone usage.
        speed = 14.0;
    }

    @Override
    public void move() {
        position += speed * FALLBACK_STEP_SECONDS;
    }

    public String getEmergencyType() {
        return emergencyType;
    }

    public boolean isSirenActive() {
        return sirenActive;
    }

    public void setSirenActive(boolean s) {
        sirenActive = s;
    }
}
