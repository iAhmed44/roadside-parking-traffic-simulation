package traffic;

import traffic.gui.fx.TrafficFxApp;

/**
 * Entry point for the program. Launches the JavaFX GUI.
 *
 * <p>How to run (JavaFX must be on the module path):
 *
 * <pre>
 * java --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics \
 *      -cp out traffic.Main [lanes] [length] [parkingSpots] [maxCycles]
 * </pre>
 *
 * <ul>
 *   <li>{@code lanes} — number of traffic lanes (default 2)</li>
 *   <li>{@code length} — street length in cells (default 60)</li>
 *   <li>{@code parkingSpots} — curb parking spots (default 10)</li>
 *   <li>{@code maxCycles} — planned simulation steps (default 5000)</li>
 * </ul>
 *
 * <p>Use the in-window Phase selector to switch between Phase 1 (basic) and
 * Phase 2 (managed parking); switching to Phase 2 replays the same cars Phase 1
 * generated and shows the improvement percentage in the KPI panel.
 */
public class Main {

    public static void main(String[] args) {
        // Delegate to the JavaFX Application. This separate, non-Application
        // launcher avoids the "JavaFX runtime components are missing" error
        // that occurs when the main class itself extends Application.
        TrafficFxApp.main(args);
    }
}
