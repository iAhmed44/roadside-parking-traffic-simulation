package traffic.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A recorded traffic scenario: the exact ordered sequence of car arrivals (each
 * with its driver properties) and emergency arrivals produced by a Phase 1 run.
 *
 * <p>This is what makes the Phase 1 vs Phase 2 comparison fair. Phase 1 runs
 * first and records its generated demand into a {@code ScenarioPlan}; Phase 2
 * then <em>replays the same plan</em> (same cars, same spawn ticks, same
 * seeking/aggressive/stubborn traits, same driver profile and body size) using
 * the Phase 2 logic — instead of generating a brand new random run. Both phases
 * are therefore tested against identical traffic demand.
 */
public final class ScenarioPlan {

    /** One recorded car arrival: when it spawned plus its intrinsic properties. */
    public record CarArrival(int tick, boolean seeking, boolean aggressive, boolean stubborn,
            double cruiseSpeed, double accel, double brake, double headway,
            double bodyLength, double bodyWidth, int entryLaneHint, int colorSeed) {
    }

    private final List<CarArrival> arrivals = new ArrayList<>();
    private final List<Integer> emergencyTicks = new ArrayList<>();
    private int recordedTicks;

    public void recordCar(CarArrival arrival) {
        arrivals.add(arrival);
    }

    public void recordEmergency(int tick) {
        emergencyTicks.add(tick);
    }

    public void setRecordedTicks(int ticks) {
        this.recordedTicks = ticks;
    }

    public List<CarArrival> getArrivals() {
        return Collections.unmodifiableList(arrivals);
    }

    public int getRecordedTicks() {
        return recordedTicks;
    }

    public int getCarCount() {
        return arrivals.size();
    }

    public int getSeekerCount() {
        int count = 0;
        for (CarArrival a : arrivals)
            if (a.seeking())
                count++;
        return count;
    }

    public int getEmergencyCount() {
        return emergencyTicks.size();
    }

    /** Group arrivals by spawn tick for fast replay lookup. */
    public Map<Integer, List<CarArrival>> arrivalsByTick() {
        Map<Integer, List<CarArrival>> map = new HashMap<>();
        for (CarArrival a : arrivals) {
            List<CarArrival> sameTick = map.get(a.tick());
            if (sameTick == null) {
                sameTick = new ArrayList<>();
                map.put(a.tick(), sameTick);
            }
            sameTick.add(a);
        }
        return map;
    }

    public Set<Integer> emergencyTickSet() {
        return new HashSet<>(emergencyTicks);
    }

    /**
     * A small stable signature of the plan. Printing the same signature in both
     * phase reports is concrete proof that Phase 1 and Phase 2 used the very
     * same recorded scenario.
     */
    public long signature() {
        long h = 1125899906842597L;
        h = 31 * h + recordedTicks;
        h = 31 * h + arrivals.size();
        h = 31 * h + emergencyTicks.size();
        for (CarArrival a : arrivals) {
            h = 31 * h + a.tick();
            h = 31 * h + (a.seeking() ? 1 : 0);
            h = 31 * h + (a.aggressive() ? 2 : 0);
            h = 31 * h + (a.stubborn() ? 4 : 0);
            h = 31 * h + Double.hashCode(a.cruiseSpeed());
            h = 31 * h + Double.hashCode(a.bodyLength());
            h = 31 * h + a.entryLaneHint();
        }
        for (int t : emergencyTicks)
            h = 31 * h + t;
        return h;
    }
}
