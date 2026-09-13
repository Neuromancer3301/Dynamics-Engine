package ui.nbody;

import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CelestialBody3DModelTest {

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
        }
    }

    @Test
    void test3DModelSnapshotGeneration() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];

        Platform.runLater(() -> {
            try {
                // 1. Earth
                CelestialBody3DModel earthModel = CelestialBody3DRegistry.getModel("Earth", false, false, false);
                assertNotNull(earthModel);
                Image earthSnapshot = earthModel.getSnapshot(0.0);
                assertNotNull(earthSnapshot);
                int earthPixels = countNonTransparent(earthSnapshot);
                System.out.println("earthSnapshot non-transparent pixels: " + earthPixels);
                assertTrue(earthPixels > 1000, "Earth snapshot should have > 1000 rendered pixels");
                saveImage(earthSnapshot, "earth_3d_test.png");

                // 2. Saturn with 3D rings
                CelestialBody3DModel saturnModel = CelestialBody3DRegistry.getModel("Saturn", false, false, false);
                assertNotNull(saturnModel);
                Image saturnSnapshot = saturnModel.getSnapshot(0.25);
                assertNotNull(saturnSnapshot);
                int saturnPixels = countNonTransparent(saturnSnapshot);
                System.out.println("saturnSnapshot non-transparent pixels: " + saturnPixels);
                assertTrue(saturnPixels > 1000, "Saturn snapshot should have > 1000 rendered pixels");
                saveImage(saturnSnapshot, "saturn_3d_test.png");

                // 3. Sun
                CelestialBody3DModel sunModel = CelestialBody3DRegistry.getModel("Sun", true, false, false);
                assertNotNull(sunModel);
                Image sunSnapshot = sunModel.getSnapshot(0.0);
                assertNotNull(sunSnapshot);
                int sunPixels = countNonTransparent(sunSnapshot);
                System.out.println("sunSnapshot non-transparent pixels: " + sunPixels);
                assertTrue(sunPixels > 1000, "Sun snapshot should have > 1000 rendered pixels");
                saveImage(sunSnapshot, "sun_3d_test.png");

                // 4. Jupiter
                CelestialBody3DModel jupiterModel = CelestialBody3DRegistry.getModel("Jupiter", false, false, false);
                assertNotNull(jupiterModel);
                Image jupiterSnapshot = jupiterModel.getSnapshot(0.1);
                assertNotNull(jupiterSnapshot);
                int jupiterPixels = countNonTransparent(jupiterSnapshot);
                System.out.println("jupiterSnapshot non-transparent pixels: " + jupiterPixels);
                assertTrue(jupiterPixels > 1000, "Jupiter snapshot should have > 1000 rendered pixels");
                saveImage(jupiterSnapshot, "jupiter_3d_test.png");

                // 5. Mars
                CelestialBody3DModel marsModel = CelestialBody3DRegistry.getModel("Mars", false, false, false);
                assertNotNull(marsModel);
                Image marsSnapshot = marsModel.getSnapshot(0.3);
                assertNotNull(marsSnapshot);
                int marsPixels = countNonTransparent(marsSnapshot);
                System.out.println("marsSnapshot non-transparent pixels: " + marsPixels);
                assertTrue(marsPixels > 1000, "Mars snapshot should have > 1000 rendered pixels");
                saveImage(marsSnapshot, "mars_3d_test.png");


            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test timed out");
        if (failure[0] != null) {
            throw new AssertionError("3D model snapshot test failed", failure[0]);
        }
    }

    private static void saveImage(Image img, String filename) {
        try {
            int w = (int) img.getWidth();
            int h = (int) img.getHeight();
            java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            PixelReader pr = img.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    b.setRGB(x, y, pr.getArgb(x, y));
                }
            }
            new java.io.File("target/screenshots").mkdirs();
            javax.imageio.ImageIO.write(b, "png", new java.io.File("target/screenshots", filename));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int countNonTransparent(Image img) {
        if (img == null) return 0;
        PixelReader pr = img.getPixelReader();
        if (pr == null) return 0;
        int nonTransparentPixels = 0;
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = pr.getArgb(x, y);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha > 10) {
                    nonTransparentPixels++;
                }
            }
        }
        return nonTransparentPixels;
    }
}
