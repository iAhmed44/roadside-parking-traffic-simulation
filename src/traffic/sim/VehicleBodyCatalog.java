package traffic.sim;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import traffic.model.EmergencyVehicle;
import traffic.model.Vehicle;

/**
 * Converts the source sprite proportions into simulation body dimensions.
 * The same dimensions drive rendering and collision checks.
 */
final class VehicleBodyCatalog {

    static final class BodySize {
        final double length;
        final double width;

        BodySize(double length, double width) {
            this.length = length;
            this.width = width;
        }
    }

    private final List<BodySize> carSizes = new ArrayList<>();
    private final BodySize emergencySize;

    VehicleBodyCatalog() {
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
            BodySize size = readSize(path);
            if (size != null)
                carSizes.add(size);
        }
        emergencySize = readSizeOrDefault("2D TOP DOWN PIXEL CARS/Truck/truck_red.png", 2.7, 0.95);
    }

    void applyTo(Vehicle vehicle) {
        BodySize size;
        if (vehicle instanceof EmergencyVehicle) {
            size = emergencySize;
        } else if (!carSizes.isEmpty()) {
            size = carSizes.get(Math.floorMod(vehicle.getVehicleId() * 7, carSizes.size()));
        } else {
            size = new BodySize(2.0, 0.78);
        }
        vehicle.setBodySize(size.length, size.width);
    }

    private BodySize readSizeOrDefault(String path, double length, double width) {
        BodySize size = readSize(path);
        return size != null ? size : new BodySize(length, width);
    }

    private BodySize readSize(String path) {
        try {
            File file = new File(path);
            if (!file.isFile())
                return null;
            BufferedImage image = ImageIO.read(file);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0)
                return null;
            double naturalRatio = image.getHeight() / (double) image.getWidth();
            double width = Math.max(0.62, Math.min(0.92, image.getWidth() / 34.0));
            double length = Math.max(1.45, Math.min(2.85, width * naturalRatio));
            return new BodySize(length, width);
        } catch (IOException ignored) {
            return null;
        }
    }
}
