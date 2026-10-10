package traffic.model;

/**
 * Visible Phase 2 patrol car. It drives along the street like an emergency
 * vehicle and lets the {@code TrafficController} warn cars that are illegally
 * waiting for a full curb. Phase 1 never spawns one of these — police patrol is
 * part of the Phase 2 "managed" solution so students can see enforcement
 * happening on screen, not just a number changing in the KPI panel.
 */
public class PoliceVehicle extends EmergencyVehicle {

    public PoliceVehicle(int id) {
        super(id, "POLICE");
    }
}
