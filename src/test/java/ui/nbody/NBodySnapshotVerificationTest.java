package ui.nbody;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import physics.nbody.NBodyConfig;
import physics.nbody.NBodyEngine;
import physics.nbody.NBodyState;
import physics.nbody.Presets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NBodySnapshotVerificationTest {

    private static final File OUTPUT_DIR = new File("target/screenshots");

    @BeforeAll
    static void initJavaFX() {
        OUTPUT_DIR.mkdirs();
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // Already started
        }
    }

    private static void saveSnapshot(WritableImage image, String filename) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage bImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                bImage.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        File out = new File(OUTPUT_DIR, filename);
        ImageIO.write(bImage, "png", out);
        assertTrue(out.exists() && out.length() > 0, "Screenshot file should be generated and non-empty: " + filename);
    }

    @Test
    void testPresetRenderingAndSnapshots() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];

        Platform.runLater(() -> {
            try {
                NBodyCanvas canvas = new NBodyCanvas(600, 600);
                StackPane root = new StackPane(canvas);
                Scene scene = new Scene(root, 600, 600);

                // 1. Home Solar System
                NBodyConfig solar = Presets.homeSolarSystem();
                NBodyEngine solarEngine = new NBodyEngine(solar);
                canvas.render(solarEngine.getState());
                canvas.fitToContent();
                canvas.render(solarEngine.getState());
                WritableImage img1 = canvas.snapshot(null, null);
                saveSnapshot(img1, "01_solar_system.png");

                // 2. TRAPPIST-1
                NBodyConfig trappist = Presets.trappist1();
                NBodyEngine trappistEngine = new NBodyEngine(trappist);
                canvas.render(trappistEngine.getState());
                canvas.fitToContent();
                canvas.render(trappistEngine.getState());
                WritableImage img2 = canvas.snapshot(null, null);
                saveSnapshot(img2, "02_trappist_one.png");

                // 3. Alpha Centauri
                NBodyConfig alpha = Presets.alphaCentauri();
                NBodyEngine alphaEngine = new NBodyEngine(alpha);
                canvas.render(alphaEngine.getState());
                canvas.fitToContent();
                canvas.render(alphaEngine.getState());
                WritableImage img3 = canvas.snapshot(null, null);
                saveSnapshot(img3, "03_alpha_centauri.png");

                // 4. Clear All (N = 0)
                NBodyConfig empty = Presets.clearAll();
                NBodyEngine emptyEngine = new NBodyEngine(empty);
                canvas.render(emptyEngine.getState());
                WritableImage img4 = canvas.snapshot(null, null);
                saveSnapshot(img4, "04_clear_all.png");

                // 5. Sun with coronal flare (LOD 1 Star)
                canvas.setFollowMode(NBodyCanvas.FollowMode.SELECTED_BODY);
                canvas.setSelectedBody(0);
                canvas.render(solarEngine.getState());
                WritableImage img5 = canvas.snapshot(null, null);
                saveSnapshot(img5, "05_sun_corona.png");

                // 6. Black Hole with photon ring and lensing halo (LOD 1 Compact Object)
                NBodyConfig bhCfg = new NBodyConfig(
                        1,
                        new double[]{10.0 * NBodyState.SOLAR_MASS},
                        new double[]{10_000.0}, // 10 km physical radius < 29.5 km rs -> Black Hole
                        new double[]{0.0},
                        new double[]{0.0},
                        new double[]{0.0},
                        new double[]{0.0},
                        new String[]{"Cygnus X-1"},
                        new double[]{0.0},
                        1.0,
                        6.6743e-11,
                        1.0
                );
                NBodyEngine bhEngine = new NBodyEngine(bhCfg);
                canvas.setSelectedBody(0);
                canvas.render(bhEngine.getState());
                // Set scale directly so Cygnus X-1 renders with radius 25px (D = 50px >= 10px LOD 1)
                canvas.getCamera().setScale(25.0 / bhCfg.getRadius(0));
                canvas.render(bhEngine.getState());
                WritableImage img6 = canvas.snapshot(null, null);
                saveSnapshot(img6, "06_black_hole_lensing.png");

                // 7. Rotating Planet billboard (Jupiter)
                solarEngine.step(15000.0); // Advance time
                canvas.setSelectedBody(8); // Jupiter is index 8 (Sun=0, Merc=1, Ven=2, Earth=3, Moon=4, Mars=5, Phob=6, Deim=7, Jup=8)
                canvas.render(solarEngine.getState());
                WritableImage img7 = canvas.snapshot(null, null);
                saveSnapshot(img7, "07_jupiter_rotating_billboard.png");

                // 8. Zoomed-in Jupiter Spacetime Curvature
                canvas.setFollowMode(NBodyCanvas.FollowMode.SELECTED_BODY);
                canvas.setSelectedBody(8); // Jupiter
                canvas.getCamera().setScale(45.0 / solarEngine.getState().radius[8]); // Zoomed in so Jupiter is prominent
                canvas.render(solarEngine.getState());
                WritableImage img8 = canvas.snapshot(null, null);
                saveSnapshot(img8, "08_jupiter_zoomed_spacetime_warping.png");

                // 9. Zoomed-in Earth Model & Spacetime Curvature
                canvas.setSelectedBody(3); // Earth
                canvas.getCamera().setScale(5.0e-6); // Zoomed in so Earth is ~64px across
                canvas.render(solarEngine.getState());
                WritableImage img9 = canvas.snapshot(null, null);
                saveSnapshot(img9, "09_earth_zoomed_spacetime_warping.png");

                // 10. Saturn Model with 3D circumstellar rings and Cassini Division
                canvas.setSelectedBody(13); // Saturn
                canvas.getCamera().setScale(45.0 / solarEngine.getState().radius[13]);
                canvas.render(solarEngine.getState());
                WritableImage img10 = canvas.snapshot(null, null);
                saveSnapshot(img10, "10_saturn_3d_rings.png");

                // 11. Mars Model with Syrtis Major and Polar Ice Caps
                canvas.setSelectedBody(5); // Mars
                canvas.getCamera().setScale(40.0 / solarEngine.getState().radius[5]);
                canvas.render(solarEngine.getState());
                WritableImage img11 = canvas.snapshot(null, null);
                saveSnapshot(img11, "11_mars_polar_ice_and_features.png");

                // 12. Halley's Comet with Coma, Ion Tail, and Dust Tail
                canvas.setSelectedBody(33); // Halley's Comet
                canvas.getCamera().setScale(5.0e-6);
                canvas.render(solarEngine.getState());
                WritableImage img12 = canvas.snapshot(null, null);
                saveSnapshot(img12, "12_halley_comet_tail_and_coma.png");

            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Snapshot rendering timed out");
        if (failure[0] != null) {
            throw new AssertionError("Snapshot rendering failed", failure[0]);
        }
    }
}
