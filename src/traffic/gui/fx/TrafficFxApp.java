package traffic.gui.fx;

import java.util.List;
import java.util.Locale;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import traffic.model.Car;
import traffic.model.EmergencyVehicle;
import traffic.model.Lane;
import traffic.model.ParkingSpot;
import traffic.model.PoliceVehicle;
import traffic.model.Street;
import traffic.model.Vehicle;
import traffic.sim.ScenarioPlan;
import traffic.sim.Simulation;
import traffic.sim.StatisticsTracker;
import traffic.sim.TrafficController;

/**
 * JavaFX front end for the roadside-parking simulation. Replaces the old Swing
 * GUI entirely: {@code Application}/{@code Stage}/{@code Scene}/{@code BorderPane}
 * for layout, {@code Canvas}+{@code GraphicsContext} for the live view, and an
 * {@code AnimationTimer} for the 60 FPS render + fixed-step simulation loop.
 *
 * <p>The Phase selector switches between Phase 1 (basic curb parking) and
 * Phase 2 (managed parking) live, so the two solutions can be compared on the
 * same screen.
 */
public class TrafficFxApp extends Application {

    private static final int FRAME_GUARD = 8;
    private static final long STATS_REFRESH_NS = 200_000_000L;

    private Simulation simulation;
    private int streetLength;
    private int maxCycles;

    private final StreetCanvasRenderer renderer = new StreetCanvasRenderer();
    private Canvas canvas;

    // Controls
    private final Button btnStart = new Button("Start");
    private final Button btnPause = new Button("Pause");
    private final Button btnReset = new Button("Reset");
    private final Button btnApplyRoad = new Button("Apply Road");
    private final Button btnAccident = new Button("Add Accident");
    private final Button btnEmergency = new Button("Add Emergency");
    private final Spinner<Integer> spnTraffic = new Spinner<>(0, 300, 15, 5);
    private final Spinner<Integer> spnLanes = new Spinner<>(1, 5, 2, 1);
    private final Spinner<Integer> spnParking = new Spinner<>(0, Street.MAX_PARKING_SPOTS, 10, 1);
    private final Slider sliderSpeed = new Slider(25, 300, 100);
    private final ComboBox<String> phaseSelector = new ComboBox<>();
    private final CheckBox chkVehicleIds = new CheckBox("IDs");
    private final CheckBox chkStateMarkers = new CheckBox("Markers");
    private final CheckBox chkLaneCounts = new CheckBox("Lane counts");
    private final CheckBox chkExitDiagnostics = new CheckBox("Exit diagnostics");
    private final CheckBox chkRandomMovement = new CheckBox("Random movement");

    // KPI labels
    private final Label lblPhase = new Label();
    private final Label lblCycle = new Label();
    private final Label lblCars = new Label();
    private final Label lblWait = new Label();
    private final Label lblWaitingNow = new Label();
    private final Label lblRedirectedNow = new Label();
    private final Label lblLaneChanges = new Label();
    private final Label lblSpeed = new Label();
    private final Label lblCong = new Label();
    private final Label lblBlocked = new Label();
    private final Label lblEmergency = new Label();
    private final Label lblReserved = new Label();
    private final Label lblEnforcement = new Label();
    private final Label lblImprovement = new Label();
    private final Label lblLaneDetails = new Label();
    private final Label lblFree = new Label();

    // loop state
    private boolean playing;
    private long lastFrameNanos;
    private long lastStatsRefreshNanos;
    private double accumulatorSeconds;
    // Phase 2 replay state. Switching to Phase 2 re-runs the SAME cars Phase 1
    // recorded (a ScenarioPlan), from cycle 0, under the Phase 2 rules — not a
    // mere live flag flip. phase1TotalWait keeps Phase 1's result for the
    // improvement readout; suppressPhaseListener avoids re-entrant selector events.
    private boolean inPhase2Replay;
    private boolean suppressPhaseListener;
    private boolean phase1Captured;
    private long phase1TotalWait;

    @Override
    public void start(Stage stage) {
        int[] cfg = parseArgs();
        streetLength = cfg[1];
        maxCycles = cfg[3];
        simulation = new Simulation(cfg[0], cfg[1], cfg[2], cfg[3]);

        spnLanes.getValueFactory().setValue(simulation.getStreet().getNumberOfLanes());
        spnParking.getValueFactory().setValue(simulation.getStreet().getParkingSpots().size());
        phaseSelector.getItems().addAll("Phase 1", "Phase 2");
        phaseSelector.setValue("Phase 1");
        chkVehicleIds.setSelected(false);
        chkStateMarkers.setSelected(false);
        chkLaneCounts.setSelected(false);
        chkExitDiagnostics.setSelected(false);
        chkRandomMovement.setSelected(true);

        BorderPane root = new BorderPane();
        root.setTop(buildControls());

        Pane canvasHolder = new Pane();
        canvas = new Canvas();
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvasHolder.getChildren().add(canvas);
        canvasHolder.setMinSize(680, 460);
        root.setCenter(canvasHolder);
        root.setRight(buildSidePanel());

        applyControlStateTo(simulation);
        simulation.startRecording(); // Phase 1 records its cars so Phase 2 can replay them
        hookControls();

        Scene scene = new Scene(root, 1300, 820);
        stage.setScene(scene);
        stage.setTitle("Roadside Parking Traffic Simulation (JavaFX)");
        stage.show();

        startLoop();
        refreshKpis();
    }

    // ---- control bar ----
    private VBox buildControls() {
        FlowPane buttons = row();
        buttons.getChildren().addAll(btnStart, btnPause, btnReset, btnApplyRoad, btnAccident, btnEmergency);

        FlowPane settings = row();
        settings.getChildren().addAll(
                new Label("Traffic:"), spnTraffic,
                new Label("Lanes:"), spnLanes,
                new Label("Parking:"), spnParking,
                new Label("Speed:"), sliderSpeed,
                new Label("Phase:"), phaseSelector);
        spnTraffic.setPrefWidth(80);
        spnLanes.setPrefWidth(70);
        spnParking.setPrefWidth(70);
        sliderSpeed.setPrefWidth(160);
        sliderSpeed.setShowTickMarks(true);
        sliderSpeed.setMajorTickUnit(50);

        FlowPane options = row();
        options.getChildren().addAll(chkVehicleIds, chkStateMarkers, chkLaneCounts,
                chkExitDiagnostics, chkRandomMovement);

        VBox box = new VBox(2, buttons, settings, options);
        box.setPadding(new Insets(8));
        return box;
    }

    private FlowPane row() {
        FlowPane row = new FlowPane(6, 4);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ---- right side: KPI panel + marker legend ----
    private ScrollPane buildSidePanel() {
        VBox kpis = new VBox(3,
                lblPhase, lblCycle, lblCars, lblWait, lblWaitingNow, lblRedirectedNow,
                lblLaneChanges, lblSpeed, lblCong, lblBlocked, lblEmergency,
                lblReserved, lblEnforcement, lblImprovement, lblLaneDetails, lblFree);
        kpis.setPadding(new Insets(8));
        TitledPane kpiPane = new TitledPane("Phase KPIs", kpis);
        kpiPane.setCollapsible(false);

        TitledPane legendPane = new TitledPane("Marker Legend", buildLegend());
        legendPane.setCollapsible(false);

        VBox side = new VBox(8, kpiPane, legendPane);
        side.setPadding(new Insets(8));
        side.setPrefWidth(270);

        ScrollPane scroll = new ScrollPane(side);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private VBox buildLegend() {
        VBox legend = new VBox(1);
        legend.setPadding(new Insets(4));
        String[] lines = {
                "SW  = switched lane automatically",
                "CLR = cleared the curb (parking) lane",
                "L1/L2 = moved toward lane 1 / lane 2",
                "YIELD = slowing to let a car merge",
                "BRAKE = braking after a nearby merge",
                "MERGE = parked car asking for a gap",
                "Q   = queued / stopped in traffic",
                "W   = WAITING (parking full or lane blocked)",
                "R   = redirected (told to keep moving)",
                "E   = emergency vehicle (ambulance)",
                "P   = police patrol (Phase 2)",
                "EXIT = parked car is trying to exit",
                "BLK = a parked car's exit is BLOCKED",
                "WARN/FINE = police warning / camera fine (Phase 2)",
        };
        Font legendFont = Font.font("SansSerif", 10.5);
        for (String line : lines) {
            Label l = new Label(line);
            l.setFont(legendFont);
            l.setWrapText(true);
            legend.getChildren().add(l);
        }
        Label note = new Label("Markers appear above cars while the sim runs. "
                + "Turn on \"Markers\" to show them.");
        note.setFont(Font.font("SansSerif", 9.5));
        note.setWrapText(true);
        legend.getChildren().addAll(new Separator(), note);
        return legend;
    }

    // ---- wiring ----
    private void hookControls() {
        btnStart.setOnAction(e -> {
            playing = true;
            lastFrameNanos = System.nanoTime();
            lastStatsRefreshNanos = lastFrameNanos;
            simulation.setRunning(true);
        });
        btnPause.setOnAction(e -> {
            playing = false;
            simulation.setRunning(false);
        });
        btnReset.setOnAction(e -> rebuildSimulation());
        btnApplyRoad.setOnAction(e -> rebuildSimulation());
        btnAccident.setOnAction(e -> simulation.triggerManualAccident());
        btnEmergency.setOnAction(e -> simulation.triggerManualEmergency());

        spnTraffic.valueProperty().addListener(
                (obs, o, n) -> simulation.setTrafficDemandPer100Ticks(n));
        chkRandomMovement.selectedProperty().addListener(
                (obs, o, n) -> simulation.setMovementRandomnessEnabled(n));
        chkVehicleIds.selectedProperty().addListener((obs, o, n) -> renderer.setShowVehicleIds(n));
        chkStateMarkers.selectedProperty().addListener((obs, o, n) -> renderer.setShowStateMarkers(n));
        chkExitDiagnostics.selectedProperty().addListener((obs, o, n) -> renderer.setShowExitDiagnostics(n));
        chkLaneCounts.selectedProperty().addListener((obs, o, n) -> {
            renderer.setShowLaneCounts(n);
            refreshKpis();
        });
        phaseSelector.valueProperty().addListener((obs, o, n) -> {
            if (!suppressPhaseListener)
                applyPhase(n);
        });
    }

    // Switching the Phase selector does NOT just flip a flag. Going to Phase 2
    // re-runs the SAME cars Phase 1 recorded, from cycle 0, with managed parking
    // (a fair "same scenario, better method" comparison). Going back to Phase 1
    // starts a fresh recorded Phase 1 run.
    private void applyPhase(String selection) {
        if ("Phase 2".equals(selection)) {
            if (!inPhase2Replay)
                startPhase2Replay();
        } else if (inPhase2Replay) {
            rebuildSimulation();
        }
    }

    // Build a fresh simulation that replays Phase 1's recorded scenario (same
    // cars, same arrival ticks) under the Phase 2 rules, starting at cycle 0.
    private void startPhase2Replay() {
        ScenarioPlan plan = simulation.getRecordedPlan();
        if (plan == null || plan.getCarCount() == 0) {
            // Phase 1 produced no cars yet: nothing to replay, so just enable
            // Phase 2 live on the current run.
            simulation.setPhase2Enabled(true);
            inPhase2Replay = true;
            refreshKpis();
            return;
        }
        // Remember Phase 1's waiting total so we can show the improvement.
        phase1TotalWait = simulation.getStats().getTotalWaitTime();
        phase1Captured = true;

        // Same road, and the same number of cycles Phase 1 actually ran.
        Street road = simulation.getStreet();
        Simulation replay = new Simulation(road.getNumberOfLanes(), road.getStreetLength(),
                road.getParkingSpots().size(), Math.max(1, plan.getRecordedTicks()));
        applyControlStateTo(replay); // demand / randomness / renderer; phase2 from the selector
        replay.setPhase2Enabled(true);
        replay.startReplay(plan);
        simulation = replay;

        inPhase2Replay = true;
        accumulatorSeconds = 0.0;
        lastFrameNanos = System.nanoTime();
        refreshKpis();
    }

    private void setPhaseSelectorSilently(String value) {
        suppressPhaseListener = true;
        phaseSelector.setValue(value);
        suppressPhaseListener = false;
    }

    private void applyControlStateTo(Simulation sim) {
        sim.setTrafficDemandPer100Ticks(spnTraffic.getValue());
        sim.setMovementRandomnessEnabled(chkRandomMovement.isSelected());
        sim.setPhase2Enabled("Phase 2".equals(phaseSelector.getValue()));
        renderer.setShowVehicleIds(chkVehicleIds.isSelected());
        renderer.setShowStateMarkers(chkStateMarkers.isSelected());
        renderer.setShowLaneCounts(chkLaneCounts.isSelected());
        renderer.setShowExitDiagnostics(chkExitDiagnostics.isSelected());
    }

    private void rebuildSimulation() {
        playing = false;
        accumulatorSeconds = 0.0;
        inPhase2Replay = false;
        phase1Captured = false;
        setPhaseSelectorSilently("Phase 1"); // a rebuild always starts a fresh Phase 1
        simulation = new Simulation(spnLanes.getValue(), streetLength, spnParking.getValue(), maxCycles);
        applyControlStateTo(simulation);
        simulation.startRecording(); // record this Phase 1 run for a later Phase 2 replay
        refreshKpis();
    }

    // ---- render + simulation loop ----
    private void startLoop() {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                double elapsed = Math.min(0.10, (now - lastFrameNanos) / 1_000_000_000.0);
                lastFrameNanos = now;

                if (playing && simulation.getCurrentCycle() >= simulation.getMaxCycles()) {
                    playing = false;
                    simulation.setRunning(false);
                }
                if (playing) {
                    accumulatorSeconds += elapsed * (sliderSpeed.getValue() / 100.0);
                    int guard = 0;
                    while (accumulatorSeconds >= Simulation.FIXED_STEP_SECONDS
                            && simulation.getCurrentCycle() < simulation.getMaxCycles()
                            && guard < FRAME_GUARD) {
                        simulation.step(Simulation.FIXED_STEP_SECONDS);
                        accumulatorSeconds -= Simulation.FIXED_STEP_SECONDS;
                        guard++;
                    }
                    if (guard == FRAME_GUARD)
                        accumulatorSeconds = 0.0;
                }

                renderer.setInterpolationAlpha(
                        Math.min(1.0, accumulatorSeconds / Simulation.FIXED_STEP_SECONDS));
                renderer.render(canvas.getGraphicsContext2D(),
                        canvas.getWidth(), canvas.getHeight(), simulation);

                if (now - lastStatsRefreshNanos >= STATS_REFRESH_NS) {
                    refreshKpis();
                    lastStatsRefreshNanos = now;
                }
            }
        }.start();
    }

    // ---- KPI panel refresh ----
    private void refreshKpis() {
        StatisticsTracker stats = simulation.getStats();
        TrafficController controller = simulation.getController();
        boolean phase2 = simulation.isPhase2Enabled();

        lblPhase.setText("Phase: " + (phase2
                ? "2 (managed parking" + (inPhase2Replay ? ", replaying Phase 1 cars)" : ")")
                : "1 (roadside parking)"));
        lblCycle.setText("Cycle: " + simulation.getCurrentCycle() + " / " + simulation.getMaxCycles());
        lblCars.setText("Cars processed: " + stats.getCarsProcessed());
        lblWait.setText("Total waiting-cycles: " + stats.getTotalWaitTime());
        lblWaitingNow.setText("Waiting now: " + countWaitingNow());
        lblRedirectedNow.setText("Redirected now: " + countRedirectedNow());
        lblLaneChanges.setText("Recent lane changes: " + countRecentLaneChanges());
        lblSpeed.setText(String.format(Locale.US, "Avg speed: %.2f", stats.getAvgSpeed()));
        lblCong.setText(String.format(Locale.US, "Avg congestion: %.2f", stats.getCurrentAvgCongestion()));
        lblBlocked.setText("Blocked-lane cycles: " + stats.getBlockedLaneCycles());
        lblEmergency.setText("Emergency vehicles: " + countEmergencyVehicles());
        lblReserved.setText("Reserved spots: " + countReservedSpots()
                + (phase2 ? "" : " (Phase 2 only)"));
        lblEnforcement.setText("Police warnings: " + controller.getWarningsIssued()
                + " | fines: " + controller.getFinesIssued());
        if (inPhase2Replay && phase1Captured) {
            long p2Wait = stats.getTotalWaitTime();
            double improvement = phase1TotalWait <= 0 ? 0.0
                    : (phase1TotalWait - p2Wait) * 100.0 / phase1TotalWait;
            lblImprovement.setText(String.format(Locale.US,
                    "Improvement vs Phase 1: %.1f%% (P1 wait %d → P2 wait %d)",
                    improvement, phase1TotalWait, p2Wait));
        } else {
            lblImprovement.setText("Improvement vs Phase 1: (finish Phase 1, then pick Phase 2)");
        }
        lblLaneDetails.setText(chkLaneCounts.isSelected() ? laneDetailsText() : "Lane details: hidden");
        lblFree.setText("Free parking spots: " + simulation.getStreet().countFreeSpots()
                + "/" + simulation.getStreet().getParkingSpots().size());
    }

    // Read-only KPI counts over every vehicle (Street.allVehicles() returns the
    // base Vehicle type; the instanceof checks pick the concrete subtypes).
    private long countWaitingNow() {
        long count = 0;
        for (Vehicle v : simulation.getStreet().allVehicles())
            if (Vehicle.STATE_WAITING.equals(v.getState()))
                count++;
        return count;
    }

    private long countRedirectedNow() {
        long count = 0;
        for (Vehicle v : simulation.getStreet().allVehicles())
            if (v instanceof Car car && car.isRedirected())
                count++;
        return count;
    }

    private long countRecentLaneChanges() {
        long count = 0;
        for (Vehicle v : simulation.getStreet().allVehicles())
            if (v.getLaneChangeFlashTicks() > 0)
                count++;
        return count;
    }

    private long countEmergencyVehicles() {
        long count = 0;
        for (Vehicle v : simulation.getStreet().allVehicles())
            if (v instanceof EmergencyVehicle && !(v instanceof PoliceVehicle))
                count++;
        return count;
    }

    private int countReservedSpots() {
        int reserved = 0;
        for (ParkingSpot spot : simulation.getStreet().getParkingSpots())
            if (spot.isReserved())
                reserved++;
        return reserved;
    }

    private String laneDetailsText() {
        StringBuilder text = new StringBuilder("Lane counts: ");
        List<Lane> lanes = simulation.getStreet().getLanes();
        for (Lane lane : lanes) {
            if (lane.getLaneId() > 0)
                text.append(" | ");
            text.append(lane.getLaneId()).append("=").append(lane.getVehiclesInLane().size());
            if (lane.isLaneBlocked())
                text.append(" blocked");
        }
        return text.toString();
    }

    private int[] parseArgs() {
        List<String> raw = getParameters() == null ? List.of() : getParameters().getRaw();
        return new int[] {
                parseArg(raw, 0, 2),
                parseArg(raw, 1, 60),
                parseArg(raw, 2, 10),
                parseArg(raw, 3, 5000)
        };
    }

    private int parseArg(List<String> a, int i, int fallback) {
        if (i >= a.size())
            return fallback;
        try {
            return Integer.parseInt(a.get(i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
