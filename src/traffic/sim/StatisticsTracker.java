package traffic.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Records per-cycle KPIs. Phase 1 used a single set of cumulative counters;
 * Phase 2 adds parallel "phase 1" and "phase 2" accumulators so a single
 * running simulation can compute a fair before/after improvement when the user
 * flips the Phase toggle mid-run. (The headless comparison runner instead uses
 * two separate simulations and reads the cumulative counters from each.)
 */
public class StatisticsTracker {

    // cumulative (all cycles)
    private long totalWaitTime; // sum of (cars-waiting) across cycles
    private int carsProcessed; // cars that parked or left the street
    private int blockedLaneCycles; // sum of (#blocked lanes) per cycle
    private final List<Double> congestionHistory = new ArrayList<>();
    private double avgSpeed;
    private long totalSpeedSumX100;
    private int speedSamples;

    // extra KPIs for the fair Phase 1 vs Phase 2 comparison
    private int redirectedCars; // cars turned away / sent on (counted once each)
    private long parkingOccupiedSum; // sum of occupied spots per cycle
    private int parkingSamples; // number of cycles sampled
    private int parkingSpotsTotal; // total curb spots (for usage %)

    // per-phase accumulators — the key to a fair in-run comparison
    private boolean inPhase2;
    private int phase1Cycles;
    private int phase2Cycles;
    private long phase1WaitSum;
    private long phase2WaitSum;
    private double phase1CongSum;
    private double phase2CongSum;
    private int phase1BlockedCycles;
    private int phase2BlockedCycles;

    public void recordCycle(double avgCongestion, int waitingNow,
            int blockedLanesNow, double currentAvgSpeed) {
        congestionHistory.add(avgCongestion);
        totalWaitTime += waitingNow;
        blockedLaneCycles += blockedLanesNow;

        totalSpeedSumX100 += (long) (currentAvgSpeed * 100);
        speedSamples++;
        if (speedSamples > 0)
            avgSpeed = (totalSpeedSumX100 / 100.0) / speedSamples;

        if (inPhase2) {
            phase2Cycles++;
            phase2WaitSum += waitingNow;
            phase2CongSum += avgCongestion;
            phase2BlockedCycles += blockedLanesNow;
        } else {
            phase1Cycles++;
            phase1WaitSum += waitingNow;
            phase1CongSum += avgCongestion;
            phase1BlockedCycles += blockedLanesNow;
        }
    }

    public void carProcessed() {
        carsProcessed++;
    }

    /** Count one more car that was redirected (turned away from parking). */
    public void noteRedirectedCar() {
        redirectedCars++;
    }

    /** Record this cycle's parking occupancy for an average-usage KPI. */
    public void recordParking(int occupiedSpots, int totalSpots) {
        parkingOccupiedSum += occupiedSpots;
        parkingSamples++;
        parkingSpotsTotal = totalSpots;
    }

    /** Mark the boundary. Call once when the user toggles Phase 2 on mid-run. */
    public void snapshotPhase1() {
        inPhase2 = true;
    }

    /**
     * Improvement = (phase1 avg waiting - phase2 avg waiting) / phase1 avg
     * waiting, as a percentage. Positive means Phase 2 had fewer waiters per
     * cycle on average.
     */
    public double computeImprovement() {
        if (phase1Cycles == 0 || phase2Cycles == 0)
            return 0;
        double p1 = (double) phase1WaitSum / phase1Cycles;
        double p2 = (double) phase2WaitSum / phase2Cycles;
        if (p1 <= 0)
            return 0;
        return (p1 - p2) / p1 * 100.0;
    }

    public double getCurrentAvgCongestion() {
        if (congestionHistory.isEmpty())
            return 0;
        double sum = 0;
        for (double value : congestionHistory)
            sum += value;
        return sum / congestionHistory.size();
    }

    // ---- cumulative accessors (Phase 1 API, unchanged) ----
    public long getTotalWaitTime() {
        return totalWaitTime;
    }

    public int getCarsProcessed() {
        return carsProcessed;
    }

    public int getBlockedLaneCycles() {
        return blockedLaneCycles;
    }

    public double getAvgSpeed() {
        return avgSpeed;
    }

    public List<Double> getCongestionHistory() {
        return congestionHistory;
    }

    // ---- per-phase accessors (Phase 2) ----
    public boolean isInPhase2() {
        return inPhase2;
    }

    public int getPhase1Cycles() {
        return phase1Cycles;
    }

    public int getPhase2Cycles() {
        return phase2Cycles;
    }

    public long getPhase1WaitSum() {
        return phase1WaitSum;
    }

    public long getPhase2WaitSum() {
        return phase2WaitSum;
    }

    public double getPhase1AvgCongestion() {
        return phase1Cycles == 0 ? 0 : phase1CongSum / phase1Cycles;
    }

    public double getPhase2AvgCongestion() {
        return phase2Cycles == 0 ? 0 : phase2CongSum / phase2Cycles;
    }

    public int getPhase1BlockedCycles() {
        return phase1BlockedCycles;
    }

    public int getPhase2BlockedCycles() {
        return phase2BlockedCycles;
    }

    // ---- comparison KPIs ----
    public int getRedirectedCars() {
        return redirectedCars;
    }

    public int getParkingSpotsTotal() {
        return parkingSpotsTotal;
    }

    /** Average number of occupied curb spots across all sampled cycles. */
    public double getAvgParkingOccupancy() {
        return parkingSamples == 0 ? 0 : (double) parkingOccupiedSum / parkingSamples;
    }

    /** Average curb usage as a percentage of total spots (0..100). */
    public double getAvgParkingUsagePct() {
        if (parkingSamples == 0 || parkingSpotsTotal == 0)
            return 0;
        return getAvgParkingOccupancy() / parkingSpotsTotal * 100.0;
    }
}
