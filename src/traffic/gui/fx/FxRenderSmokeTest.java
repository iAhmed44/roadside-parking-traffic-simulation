package traffic.gui.fx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import traffic.model.Car;
import traffic.model.EmergencyVehicle;
import traffic.model.PoliceVehicle;
import traffic.sim.Simulation;

/**
 * Framework-free rendering check for the JavaFX street canvas (the FX analog of
 * the Swing {@code RenderSmokeTest}). It boots the JavaFX toolkit headlessly,
 * runs the simulation in both phases, renders into an off-screen Canvas, and
 * verifies that visible content was drawn and the sprite assets loaded.
 *
 * Run with:
 * java --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.graphics \
 *      -cp out traffic.gui.fx.FxRenderSmokeTest
 */
public final class FxRenderSmokeTest {

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> contentPixels = new AtomicReference<>(0);
        AtomicReference<Boolean> sprites = new AtomicReference<>(false);

        Platform.startup(() -> {
            try {
                StreetCanvasRenderer renderer = new StreetCanvasRenderer();
                renderer.setShowVehicleIds(true);
                renderer.setShowStateMarkers(true);
                renderer.setShowLaneCounts(true);
                renderer.setShowExitDiagnostics(true);
                sprites.set(renderer.catalogHasSprites());

                Simulation sim = new Simulation(2, 36, 6, 500);
                sim.getStreet().getParkingSpots().get(0).occupy();
                sim.getStreet().getParkingSpots().get(1).occupy();
                Car car = new Car(1, true);
                car.setPosition(4);
                sim.getStreet().addVehicle(car, 0);
                EmergencyVehicle emergency = new EmergencyVehicle(3, "AMBULANCE");
                emergency.setPosition(6);
                sim.getStreet().addVehicle(emergency, 1);
                PoliceVehicle police = new PoliceVehicle(5);
                police.setPosition(2);
                sim.getStreet().addVehicle(police, 0);

                // Exercise Phase 1 then Phase 2 so both code paths render.
                for (int i = 0; i < 120; i++)
                    sim.tick();
                sim.setPhase2Enabled(true);
                for (int i = 0; i < 120; i++)
                    sim.tick();

                Canvas canvas = new Canvas(1000, 600);
                GraphicsContext g = canvas.getGraphicsContext2D();
                renderer.render(g, 1000, 600, sim);
                renderer.render(g, 1000, 600, sim);

                WritableImage image = canvas.snapshot(new SnapshotParameters(), null);
                contentPixels.set(countContentPixels(image));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(30, TimeUnit.SECONDS)) {
            Platform.exit();
            throw new AssertionError("JavaFX render smoke test timed out");
        }
        Platform.exit();

        if (failure.get() != null)
            throw new AssertionError("JavaFX rendering threw", failure.get());
        assertTrue(sprites.get(), "vehicle sprite assets should load from the car image folder");
        assertTrue(contentPixels.get() > 1000,
                "canvas should render visible road, cars, parking and markers (drew "
                        + contentPixels.get() + " content pixels)");
        System.out.println("JavaFX render smoke test passed (content pixels="
                + contentPixels.get() + ", sprites=" + sprites.get() + ").");
    }

    // Count pixels that differ from the flat backdrop colour, i.e. actual content.
    private static int countContentPixels(WritableImage image) {
        PixelReader reader = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int backdrop = reader.getArgb(2, 2); // top-left is the plain backdrop
        int count = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                if (reader.getArgb(x, y) != backdrop)
                    count++;
            }
        }
        return count;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
