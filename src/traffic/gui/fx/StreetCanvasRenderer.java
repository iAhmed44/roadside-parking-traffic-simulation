package traffic.gui.fx;

import java.util.Locale;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import traffic.model.Accident;
import traffic.model.Car;
import traffic.model.EmergencyVehicle;
import traffic.model.Lane;
import traffic.model.ParkingSpot;
import traffic.model.PoliceVehicle;
import traffic.model.Street;
import traffic.model.Vehicle;
import traffic.sim.Simulation;

/**
 * Draws the live top-down view (road, parking row, vehicles, accidents,
 * markers) onto a JavaFX {@link GraphicsContext}. All drawing happens on a
 * Canvas: layout maths first, then the parking row, lanes, vehicles, accident
 * and the bottom colour legend.
 */
final class StreetCanvasRenderer {

    private static final double EXIT_STARVATION_SECONDS = 5.0;
    private static final int QUEUED_IN_TRAFFIC_TICKS = 15;

    private final FxVehicleImageCatalog imageCatalog = new FxVehicleImageCatalog();
    private final Text measuringText = new Text(); // reused for text-width measuring

    private boolean showVehicleIds = true;
    private boolean showStateMarkers = true;
    private boolean showLaneCounts = true;
    private boolean showExitDiagnostics = true;
    private double interpolationAlpha = 1.0;

    boolean catalogHasSprites() {
        return imageCatalog.hasSprites();
    }

    void setShowVehicleIds(boolean v) {
        this.showVehicleIds = v;
    }

    void setShowStateMarkers(boolean v) {
        this.showStateMarkers = v;
    }

    void setShowLaneCounts(boolean v) {
        this.showLaneCounts = v;
    }

    void setShowExitDiagnostics(boolean v) {
        this.showExitDiagnostics = v;
    }

    void setInterpolationAlpha(double alpha) {
        this.interpolationAlpha = Math.max(0.0, Math.min(1.0, alpha));
    }

    void render(GraphicsContext g, double width, double height, Simulation simulation) {
        g.setTextBaseline(VPos.BASELINE);
        Street street = simulation.getStreet();
        Layout layout = layoutFor(street, width, height);
        drawBackdrop(g, layout, width, height, street);
        drawParkingRow(g, street, layout);
        for (Lane lane : street.getLanes()) {
            drawLane(g, lane, layout);
        }
        for (Lane lane : street.getLanes()) {
            for (Vehicle vehicle : lane.getVehiclesInLane()) {
                drawVehicle(g, vehicle, layout, simulation);
            }
        }
        drawAccident(g, street, layout);
        drawLegend(g, layout, height, street);
    }

    // ---- layout maths (identical to the Swing panel) ----
    private Layout layoutFor(Street street, double width, double height) {
        int marginX = 34;
        int top = 34;
        int bottom = 78;
        int gap = 12;
        int usableW = (int) Math.max(500, width - marginX * 2);
        int laneCount = Math.max(1, street.getNumberOfLanes());
        int availableH = (int) Math.max(360, height - top - bottom);
        int parkingH = clamp(availableH / 8, 42, 68);
        int laneH = clamp((availableH - parkingH - gap * (laneCount + 1)) / laneCount, 58, 98);
        double scaleX = usableW / (double) Math.max(1, street.getStreetLength());
        int laneTop = top + parkingH + gap * 2;
        return new Layout(marginX, top, usableW, parkingH, laneTop, laneH, gap, scaleX);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int worldX(Layout layout, double position) {
        return layout.left + (int) Math.round(position * layout.scaleX);
    }

    private void drawBackdrop(GraphicsContext g, Layout layout, double width, double height, Street street) {
        g.setFill(Color.rgb(28, 32, 37));
        g.fillRect(0, 0, width, height);

        int h = layout.laneTop + street.getNumberOfLanes() * layout.laneHeight
                + Math.max(20, layout.gap) - layout.top;
        g.setFill(Color.rgb(29, 35, 31));
        g.fillRoundRect(layout.left - 18, layout.top - 18, layout.roadWidth + 36, h + 36, 12, 12);
        g.setStroke(Color.rgb(67, 75, 69));
        g.setLineWidth(1.0);
        g.strokeRoundRect(layout.left - 18, layout.top - 18, layout.roadWidth + 36, h + 36, 12, 12);
    }

    private void drawParkingRow(GraphicsContext g, Street street, Layout layout) {
        g.setFill(Color.rgb(245, 248, 252));
        g.setFont(LABEL_FONT);
        g.fillText("Parking (curb)", layout.left, layout.top - 8);

        g.setFill(Color.rgb(93, 98, 101));
        g.fillRoundRect(layout.left, layout.top, layout.roadWidth, layout.parkingHeight, 8, 8);
        g.setStroke(Color.rgb(220, 225, 228));
        g.strokeRoundRect(layout.left, layout.top, layout.roadWidth, layout.parkingHeight, 8, 8);

        double spotLength = parkingSpotLength(street);
        for (ParkingSpot spot : street.getParkingSpots()) {
            int x = worldX(layout, spot.getPositionX() - spotLength / 2.0);
            int w = Math.max(38, (int) Math.round(spotLength * layout.scaleX));
            int y = layout.top + 5;
            int hh = layout.parkingHeight - 10;
            // Occupied = dark, reserved (Phase 2) = amber tint, free = light.
            Color fill = spot.isOccupied() ? Color.rgb(55, 61, 67)
                    : spot.isReserved() ? Color.rgb(120, 96, 40)
                            : Color.rgb(232, 235, 238);
            g.setFill(fill);
            g.fillRoundRect(x, y, w, hh, 6, 6);
            g.setStroke(Color.BLACK);
            g.strokeRoundRect(x, y, w, hh, 6, 6);
            if (spot.isOccupied()) {
                Car parkedCar = spot.getParkedCar();
                double lengthPx;
                double widthPx;
                if (parkedCar != null) {
                    lengthPx = Math.min(w - 6, parkedCar.getBodyLength() * layout.scaleX);
                    widthPx = lengthPx / Math.max(1.2, parkedCar.getBodyLength() / parkedCar.getBodyWidth());
                    if (widthPx > hh - 8) {
                        double ratio = (hh - 8) / widthPx;
                        widthPx *= ratio;
                        lengthPx *= ratio;
                    }
                } else {
                    lengthPx = Math.min(w - 6, hh * 2.45);
                    widthPx = Math.min(hh - 8, lengthPx / 2.45);
                }
                imageCatalog.drawVehicleBody(g, parkedCar, x + w / 2.0, y + hh / 2.0,
                        lengthPx, widthPx, 90.0, false);
                if (showExitDiagnostics && spot.isExitRequested()) {
                    drawExitDiagnostic(g, spot, x, y, w, hh, layout);
                }
            }
        }
    }

    private void drawExitDiagnostic(GraphicsContext g, ParkingSpot spot, int x, int y, int w, int h, Layout layout) {
        boolean starved = spot.isExitStarved(EXIT_STARVATION_SECONDS);
        boolean blocked = spot.getExitBlockedSeconds() > 0.05;
        Color bg = starved ? Color.rgb(185, 35, 35, 0.90)
                : blocked ? Color.rgb(245, 142, 35, 0.90)
                        : Color.rgb(255, 215, 75, 0.90);
        Color fg = starved || blocked ? Color.WHITE : Color.BLACK;

        int mergeX = worldX(layout, spot.getPositionX() - 1.4);
        int mergeW = Math.max(30, (int) Math.round(2.8 * layout.scaleX));
        int laneY = layout.laneTop + 8;
        g.setFill(starved ? Color.rgb(255, 40, 40, 0.19) : Color.rgb(255, 210, 70, 0.16));
        g.fillRoundRect(mergeX, laneY, mergeW, Math.max(12, layout.laneHeight - 16), 8, 8);
        g.setStroke(starved ? Color.rgb(255, 80, 80, 0.47) : Color.rgb(255, 220, 80, 0.43));
        g.strokeRoundRect(mergeX, laneY, mergeW, Math.max(12, layout.laneHeight - 16), 8, 8);
        g.setFont(TINY_BOLD_FONT);
        g.setFill(starved ? Color.rgb(255, 190, 190) : Color.rgb(255, 235, 120));
        g.fillText("KEEP CLEAR", mergeX + 4, laneY + 11);

        String title = (starved ? "EXIT DEADLOCK "
                : blocked ? "EXIT BLOCKED " : "EXIT WAIT ")
                + String.format(Locale.US, "%.1fs", spot.getExitBlockedSeconds());
        String reason = spot.getExitBlockReason();
        if (spot.getExitBlockerVehicleId() >= 0 && !reason.contains("#")) {
            reason += " #" + spot.getExitBlockerVehicleId();
        }
        reason = shorten(reason, 36);

        g.setFont(SMALL_BOLD_FONT);
        double labelW = Math.max(measure(title, SMALL_BOLD_FONT), measure(reason, SMALL_FONT)) + 8;
        int labelX = (int) Math.max(layout.left + 4, Math.min(x + w / 2 - labelW / 2,
                layout.left + layout.roadWidth - labelW - 4));
        int labelY = Math.max(4, y - 25);
        g.setFill(bg);
        g.fillRoundRect(labelX, labelY, labelW, 22, 5, 5);
        g.setStroke(Color.BLACK);
        g.strokeRoundRect(labelX, labelY, labelW, 22, 5, 5);
        g.setFill(fg);
        g.fillText(title, labelX + 4, labelY + 10);
        if (!reason.isEmpty()) {
            g.setFont(SMALL_FONT);
            g.fillText(reason, labelX + 4, labelY + 19);
        }
    }

    private String shorten(String text, int maxChars) {
        if (text == null)
            return "";
        if (text.length() <= maxChars)
            return text;
        return text.substring(0, Math.max(0, maxChars - 1)) + "...";
    }

    private double parkingSpotLength(Street street) {
        if (street.getParkingSpots().size() <= 1)
            return 2.8;
        double spacing = Math.max(2.6, street.getStreetLength() / (double) street.getParkingSpots().size());
        return Math.min(3.8, spacing * 0.86);
    }

    private void drawLane(GraphicsContext g, Lane lane, Layout layout) {
        int y = layout.laneTop + lane.getLaneId() * layout.laneHeight;
        g.setFill(lane.isLaneBlocked() ? Color.rgb(78, 36, 36) : Color.rgb(50, 53, 58));
        g.fillRect(layout.left, y, layout.roadWidth, layout.laneHeight);
        g.setStroke(Color.rgb(18, 18, 20));
        g.setLineWidth(1.0);
        g.strokeRect(layout.left, y, layout.roadWidth, layout.laneHeight);

        g.setFill(Color.rgb(85, 90, 96));
        g.fillRect(layout.left, y, layout.roadWidth, 4);
        g.fillRect(layout.left, y + layout.laneHeight - 5, layout.roadWidth, 5);
        g.setStroke(Color.rgb(235, 210, 70));
        for (int x = layout.left + 10; x < layout.left + layout.roadWidth; x += 48) {
            g.strokeLine(x, y + layout.laneHeight - 9, x + 26, y + layout.laneHeight - 9);
        }

        String label = "Lane " + lane.getLaneId();
        if (showLaneCounts)
            label += "  cars: " + lane.getVehiclesInLane().size();
        g.setFill(Color.rgb(245, 248, 252));
        g.setFont(LABEL_FONT);
        g.fillText(label, layout.left + 8, y + 16);
    }

    private void drawVehicle(GraphicsContext g, Vehicle vehicle, Layout layout, Simulation simulation) {
        double renderLane = vehicle.getRenderLaneId(interpolationAlpha);
        double laneY = layout.laneTop + renderLane * layout.laneHeight;
        double cx = worldX(layout, vehicle.getRenderPosition(interpolationAlpha));
        double cy = laneY + layout.laneHeight * 0.60;
        double lengthPx = vehicle.getBodyLength() * layout.scaleX;
        double widthPx = lengthPx / Math.max(1.2, vehicle.getBodyLength() / vehicle.getBodyWidth());
        double maxWidth = layout.laneHeight * 0.56;
        if (widthPx > maxWidth) {
            double ratio = maxWidth / widthPx;
            widthPx *= ratio;
            lengthPx *= ratio;
        }

        imageCatalog.drawVehicleBody(g, vehicle, cx, cy, lengthPx, widthPx,
                vehicle.getRenderHeadingDegrees(interpolationAlpha),
                (simulation.getCurrentCycle() / 6) % 2 == 0);

        if (showVehicleIds)
            drawVehicleId(g, vehicle, cx, cy, widthPx);
        if (showStateMarkers)
            drawStateMarker(g, vehicle, cx, cy, lengthPx, widthPx, simulation);
    }

    private void drawAccident(GraphicsContext g, Street street, Layout layout) {
        Accident accident = street.getActiveAccident();
        if (accident == null)
            return;
        int laneY = layout.laneTop + accident.getLaneId() * layout.laneHeight;
        int cx = worldX(layout, accident.getPositionExact());
        int w = Math.max(42, (int) Math.round(2.2 * layout.scaleX));
        int h = Math.max(30, (int) (layout.laneHeight * 0.58));
        int y = laneY + (layout.laneHeight - h) / 2;

        g.setFill(Color.rgb(255, 40, 40, 0.72));
        g.fillRoundRect(cx - w / 2.0, y, w, h, 8, 8);
        g.setFill(Color.WHITE);
        g.setFont(LABEL_BOLD_FONT);
        g.fillText("CRASH", cx - 18, y + h / 2.0 + 5);

        double ageSeconds = accident.getCyclesElapsed() * Simulation.FIXED_STEP_SECONDS;
        String ageLabel = "waiting " + String.format(Locale.ROOT, "%.1fs", ageSeconds);
        g.setFont(SMALL_FONT);
        double textW = measure(ageLabel, SMALL_FONT);
        int tx = (int) (cx - textW / 2);
        int ty = y - 4;
        g.setFill(Color.rgb(0, 0, 0, 0.70));
        g.fillRoundRect(tx - 3, ty - 9, textW + 6, 13, 4, 4);
        g.setFill(Color.rgb(255, 220, 220));
        g.fillText(ageLabel, tx, ty);
    }

    private void drawVehicleId(GraphicsContext g, Vehicle vehicle, double cx, double cy, double widthPx) {
        String id = String.valueOf(vehicle.getVehicleId());
        g.setFont(SMALL_BOLD_FONT);
        double textW = measure(id, SMALL_BOLD_FONT);
        int tx = (int) Math.round(cx - textW / 2.0);
        int ty = (int) Math.round(cy - widthPx / 2.0 - 5);
        g.setFill(Color.rgb(0, 0, 0, 0.70));
        g.fillRoundRect(tx - 2, ty - 9, textW + 4, 13, 4, 4);
        g.setFill(Color.WHITE);
        g.fillText(id, tx, ty);
    }

    private void drawStateMarker(GraphicsContext g, Vehicle vehicle, double cx, double cy,
            double lengthPx, double widthPx, Simulation simulation) {
        int x = (int) Math.round(cx - lengthPx / 2.0 + 2);
        int y = (int) Math.round(cy - widthPx / 2.0 + 2);
        if (vehicle instanceof PoliceVehicle) {
            drawBadge(g, "P", x, y, Color.WHITE, Color.rgb(40, 90, 220));
        } else if (vehicle instanceof EmergencyVehicle) {
            drawBadge(g, "E", x, y, Color.WHITE, Color.rgb(160, 0, 0));
        } else if (Vehicle.STATE_WAITING.equals(vehicle.getState())) {
            drawBadge(g, "W", x, y, Color.BLACK, Color.rgb(255, 235, 60));
        } else if (vehicle instanceof Car c && c.isRedirected()) {
            drawBadge(g, "R", x, y, Color.BLACK, Color.rgb(255, 170, 35));
        } else if (vehicle instanceof Car c
                && Vehicle.STATE_MOVING.equals(c.getState())
                && c.getStoppedInTrafficTicks() >= QUEUED_IN_TRAFFIC_TICKS) {
            drawBadge(g, "Q", x, y, Color.BLACK, Color.rgb(220, 225, 230));
        }
        if (vehicle.getLaneChangeFlashTicks() > 0) {
            drawBadge(g, "SW", (int) Math.round(cx + lengthPx / 2.0 - 22), y,
                    Color.BLACK, Color.rgb(80, 230, 255));
        }
        if (!vehicle.getLaneIntentMarker().isEmpty()) {
            drawBadge(g, vehicle.getLaneIntentMarker(),
                    (int) Math.round(cx - lengthPx / 2.0 + 2),
                    (int) Math.round(cy + widthPx / 2.0 - 12),
                    Color.WHITE, Color.rgb(64, 94, 140));
        }
        // Phase 2 enforcement flashes.
        if (vehicle instanceof Car cc && cc.getWarningFlashTicks() > 0) {
            drawBadge(g, "WARN", (int) Math.round(cx - 12),
                    (int) Math.round(cy - widthPx / 2.0 - 18), Color.WHITE, Color.rgb(40, 90, 220));
        }
        if (vehicle instanceof Car cf && cf.getCameraFineFlashTicks() > 0) {
            drawBadge(g, "FINE", (int) Math.round(cx + 2),
                    (int) Math.round(cy - widthPx / 2.0 - 18), Color.WHITE, Color.rgb(190, 40, 130));
        }
        if (showExitDiagnostics && isExitBlocker(vehicle.getVehicleId(), simulation)) {
            drawBadge(g, "BLK", (int) Math.round(cx + lengthPx / 2.0 - 24),
                    (int) Math.round(cy + widthPx / 2.0 - 12),
                    Color.WHITE, Color.rgb(165, 45, 25));
        }
    }

    private boolean isExitBlocker(int vehicleId, Simulation simulation) {
        if (!showExitDiagnostics)
            return false;
        for (ParkingSpot spot : simulation.getStreet().getParkingSpots()) {
            if (spot.isExitRequested() && spot.getExitBlockerVehicleId() == vehicleId)
                return true;
        }
        return false;
    }

    private void drawBadge(GraphicsContext g, String text, int x, int y, Color fg, Color bg) {
        g.setFont(SMALL_BOLD_FONT);
        double w = Math.max(12, measure(text, SMALL_BOLD_FONT) + 4);
        g.setFill(bg);
        g.fillRoundRect(x, y, w, 11, 4, 4);
        g.setStroke(Color.BLACK);
        g.setLineWidth(1.0);
        g.strokeRoundRect(x, y, w, 11, 4, 4);
        g.setFill(fg);
        g.fillText(text, x + 2, y + 9);
    }

    private void drawLegend(GraphicsContext g, Layout layout, double height, Street street) {
        int y = (int) Math.min(height - 26,
                layout.laneTop + street.getNumberOfLanes() * layout.laneHeight + 18);
        int x = layout.left;
        g.setFont(LABEL_FONT);
        x = legendBox(g, x, y, Color.rgb(80, 140, 255), "Moving car");
        x = legendBox(g, x, y, Color.YELLOW, "Waiting");
        x = legendBox(g, x, y, Color.rgb(230, 30, 30), "Emergency");
        x = legendBox(g, x, y, Color.rgb(40, 90, 220), "Police (Phase 2)");
        legendBox(g, x, y, Color.rgb(120, 96, 40), "Reserved spot (Phase 2)");
    }

    private int legendBox(GraphicsContext g, int x, int y, Color color, String label) {
        g.setFill(color);
        g.fillRect(x, y, 12, 12);
        g.setStroke(Color.BLACK);
        g.setLineWidth(1.0);
        g.strokeRect(x, y, 12, 12);
        g.setFill(Color.WHITE);
        g.fillText(label, x + 16, y + 11);
        return x + 16 + (int) measure(label, LABEL_FONT) + 16;
    }

    private double measure(String text, Font font) {
        measuringText.setFont(font);
        measuringText.setText(text == null ? "" : text);
        return measuringText.getLayoutBounds().getWidth();
    }

    // Fonts reused across frames.
    private static final Font LABEL_FONT = Font.font("SansSerif", 12);
    private static final Font LABEL_BOLD_FONT = Font.font("SansSerif", FontWeight.BOLD, 12);
    private static final Font SMALL_FONT = Font.font("SansSerif", 9);
    private static final Font SMALL_BOLD_FONT = Font.font("SansSerif", FontWeight.BOLD, 9);
    private static final Font TINY_BOLD_FONT = Font.font("SansSerif", FontWeight.BOLD, 8);

    private static final class Layout {
        final int left;
        final int top;
        final int roadWidth;
        final int parkingHeight;
        final int laneTop;
        final int laneHeight;
        final int gap;
        final double scaleX;

        Layout(int left, int top, int roadWidth, int parkingHeight,
                int laneTop, int laneHeight, int gap, double scaleX) {
            this.left = left;
            this.top = top;
            this.roadWidth = roadWidth;
            this.parkingHeight = parkingHeight;
            this.laneTop = laneTop;
            this.laneHeight = laneHeight;
            this.gap = gap;
            this.scaleX = scaleX;
        }
    }
}
