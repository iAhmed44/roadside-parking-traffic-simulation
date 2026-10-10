package traffic.gui.fx;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import traffic.model.Car;
import traffic.model.EmergencyVehicle;
import traffic.model.PoliceVehicle;
import traffic.model.Vehicle;

/**
 * JavaFX counterpart of the Swing {@code VehicleImageCatalog}. Loads the same
 * top-down car sprites from the project asset folder and draws a rotated,
 * scaled body for any vehicle straight onto a {@link GraphicsContext}.
 *
 * <p>Rotation/scaling is done with the canvas transform (save → translate →
 * rotate → drawImage → restore) since a JavaFX Canvas can draw an
 * {@link Image} at any size directly — no scaled-instance cache is needed.
 */
final class FxVehicleImageCatalog {

    private final List<Image> carSprites = new ArrayList<>();
    private Image emergencyBase;
    private Image policeBase;
    private Image parkedBase;

    FxVehicleImageCatalog() {
        String[] paths = {
                "2D TOP DOWN PIXEL CARS/Compact/compact_blue.png",
                "2D TOP DOWN PIXEL CARS/Compact/compact_green.png",
                "2D TOP DOWN PIXEL CARS/Compact/compact_orange.png",
                "2D TOP DOWN PIXEL CARS/Compact/compact_red.png",
                "2D TOP DOWN PIXEL CARS/Coupe/coupe_blue.png",
                "2D TOP DOWN PIXEL CARS/Coupe/coupe_green.png",
                "2D TOP DOWN PIXEL CARS/Coupe/coupe_midnight.png",
                "2D TOP DOWN PIXEL CARS/Coupe/coupe_red.png",
                "2D TOP DOWN PIXEL CARS/Sedan/sedan_blue.png",
                "2D TOP DOWN PIXEL CARS/Sedan/sedan_gray.png",
                "2D TOP DOWN PIXEL CARS/Sedan/sedan_green.png",
                "2D TOP DOWN PIXEL CARS/Sedan/sedan_red.png",
                "2D TOP DOWN PIXEL CARS/Sport/sport_blue.png",
                "2D TOP DOWN PIXEL CARS/Sport/sport_green.png",
                "2D TOP DOWN PIXEL CARS/Sport/sport_red.png",
                "2D TOP DOWN PIXEL CARS/Sport/sport_yellow.png",
                "2D TOP DOWN PIXEL CARS/Truck/truck_blue.png",
                "2D TOP DOWN PIXEL CARS/Truck/truck_cream.png",
                "2D TOP DOWN PIXEL CARS/Truck/truck_green.png",
                "2D TOP DOWN PIXEL CARS/Truck/truck_red.png"
        };
        for (String path : paths) {
            Image image = load(path);
            if (image != null)
                carSprites.add(image);
        }
        emergencyBase = loadFirstExisting(
                "2D TOP DOWN PIXEL CARS/Truck/truck_red.png",
                "2D TOP DOWN PIXEL CARS/Sport/sport_red.png",
                "2D TOP DOWN PIXEL CARS/Sedan/sedan_red.png");
        policeBase = loadFirstExisting(
                "2D TOP DOWN PIXEL CARS/Sedan/sedan_blue.png",
                "2D TOP DOWN PIXEL CARS/Coupe/coupe_blue.png",
                "2D TOP DOWN PIXEL CARS/Sport/sport_blue.png");
        parkedBase = loadFirstExisting(
                "2D TOP DOWN PIXEL CARS/Sedan/sedan_gray.png",
                "2D TOP DOWN PIXEL CARS/Compact/compact_green.png");
    }

    boolean hasSprites() {
        return !carSprites.isEmpty();
    }

    /**
     * Draw a rotated/scaled vehicle body centred at (centerX, centerY).
     * A {@code null} vehicle is drawn as a generic parked car (used for the
     * occupied-spot preview).
     */
    void drawVehicleBody(GraphicsContext g, Vehicle vehicle, double centerX, double centerY,
            double lengthPx, double widthPx, double headingDegrees, boolean phasePulse) {
        Image sprite = spriteFor(vehicle);
        double localWidth = Math.max(1.0, widthPx);
        double localHeight = Math.max(1.0, lengthPx);

        g.save();
        g.translate(centerX, centerY);
        g.rotate(headingDegrees);

        double x = -localWidth / 2.0;
        double y = -localHeight / 2.0;
        if (sprite == null) {
            drawFallbackVehicle(g, vehicle, x, y, localWidth, localHeight);
        } else {
            g.drawImage(sprite, x, y, localWidth, localHeight);
        }

        if (vehicle instanceof EmergencyVehicle) {
            drawEmergencyDetails(g, x, y, localWidth, localHeight, vehicle, phasePulse);
        } else if (vehicle instanceof Car c) {
            drawCarStatusDetails(g, c, x, y, localWidth, localHeight);
        }
        g.restore();
    }

    private Image spriteFor(Vehicle vehicle) {
        if (vehicle == null)
            return parkedBase;
        if (vehicle instanceof PoliceVehicle && policeBase != null)
            return policeBase;
        if (vehicle instanceof EmergencyVehicle && emergencyBase != null)
            return emergencyBase;
        // Cars pick their sprite from a color seed (the id by default; a replayed
        // Phase 2 car copies its Phase 1 id so the same car looks identical).
        int colorId = (vehicle instanceof Car car) ? car.getColorSeedId() : vehicle.getVehicleId();
        return spriteByIndex(colorId);
    }

    private Image spriteByIndex(int index) {
        if (carSprites.isEmpty())
            return null;
        int safeIndex = Math.floorMod(index * 7, carSprites.size());
        return carSprites.get(safeIndex);
    }

    private Image loadFirstExisting(String... paths) {
        for (String path : paths) {
            Image image = load(path);
            if (image != null)
                return image;
        }
        return null;
    }

    private Image load(String path) {
        try {
            File file = new File(path);
            if (!file.isFile())
                return null;
            Image image = new Image(file.toURI().toString());
            return image.isError() ? null : image;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void drawEmergencyDetails(GraphicsContext g, double x, double y,
            double width, double height, Vehicle vehicle, boolean pulse) {
        double cx = x + width / 2;
        double cy = y + height / 2;
        boolean police = vehicle instanceof PoliceVehicle;
        // White cross badge so emergencies read clearly even when zoomed out.
        g.setFill(Color.rgb(255, 255, 255, 0.90));
        g.fillRect(cx - 2, cy - 8, 4, 16);
        g.fillRect(cx - 8, cy - 2, 16, 4);
        // Flashing roof light: blue for police, red/amber for ambulance.
        Color light = police
                ? (pulse ? Color.rgb(90, 160, 255, 0.9) : Color.rgb(40, 70, 220, 0.9))
                : (pulse ? Color.rgb(255, 245, 120, 0.9) : Color.rgb(255, 65, 65, 0.9));
        g.setFill(light);
        g.fillOval(x + width / 2 - 4, y + 1, 8, 5);
    }

    private void drawCarStatusDetails(GraphicsContext g, Car car, double x, double y,
            double width, double height) {
        if (car.isRedirected()) {
            // Small forward arrow = "redirected, keep moving" (Phase 2 effect).
            g.setStroke(Color.rgb(255, 155, 35, 0.9));
            g.setLineWidth(1.0);
            g.strokeLine(x + width - 9, y + height / 2, x + width - 3, y + height / 2);
            g.strokeLine(x + width - 6, y + height / 2 - 3, x + width - 3, y + height / 2);
            g.strokeLine(x + width - 6, y + height / 2 + 3, x + width - 3, y + height / 2);
        }
    }

    private void drawFallbackVehicle(GraphicsContext g, Vehicle vehicle, double x, double y,
            double width, double height) {
        Color color = Color.rgb(80, 140, 255);
        if (vehicle instanceof PoliceVehicle)
            color = Color.rgb(40, 90, 220);
        else if (vehicle instanceof EmergencyVehicle)
            color = Color.rgb(230, 30, 30);
        else if (vehicle instanceof Car c && c.isRedirected())
            color = Color.rgb(255, 140, 0);
        g.setFill(color);
        g.fillRoundRect(x, y, width, height, 6, 6);
        g.setStroke(Color.BLACK);
        g.setLineWidth(1.0);
        g.strokeRoundRect(x, y, width, height, 6, 6);
    }
}
