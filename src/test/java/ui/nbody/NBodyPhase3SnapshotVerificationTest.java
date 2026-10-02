package ui.nbody;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import physics.nbody.CelestialMagnetismRegistry;
import physics.nbody.MagnetopauseCalculator;
import physics.nbody.NBodyConfig;
import physics.nbody.NBodyEngine;
import physics.nbody.NBodyState;
import physics.nbody.Presets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NBodyPhase3SnapshotVerificationTest {

    private static final File OUTPUT_DIR = new File("target/screenshots");
    private static final File ARTIFACT_DIR = new File("/Users/anaguib/.gemini/antigravity-cli/brain/1333ae6a-d1ac-48a4-900c-786a9a6c3861");

    @BeforeAll
    static void initJavaFX() {
        OUTPUT_DIR.mkdirs();
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // Already initialized
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
        assertTrue(out.exists() && out.length() > 0, "Screenshot file should be non-empty: " + filename);

        if (ARTIFACT_DIR.exists()) {
            File artOut = new File(ARTIFACT_DIR, filename);
            Files.copy(out.toPath(), artOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void testPhase3LinesOfForceVisualizations() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];

        Platform.runLater(() -> {
            try {
                NBodyCanvas canvas = new NBodyCanvas(700, 700);
                StackPane root = new StackPane(canvas);
                Scene scene = new Scene(root, 700, 700);

                // =========================================================================
                // 1. Earth Parametric Dipole Loops & Dayside Chapman-Ferraro Compression
                // =========================================================================
                NBodyConfig solar = Presets.homeSolarSystem();
                NBodyEngine solarEngine = new NBodyEngine(solar);
                NBodyState state = solarEngine.getState();

                int earthIdx = -1;
                int moonIdx = -1;
                int jupiterIdx = -1;
                int marsIdx = -1;
                for (int i = 0; i < state.getN(); i++) {
                    if ("Earth".equalsIgnoreCase(state.name[i])) earthIdx = i;
                    if ("Moon".equalsIgnoreCase(state.name[i])) moonIdx = i;
                    if ("Jupiter".equalsIgnoreCase(state.name[i])) jupiterIdx = i;
                    if ("Mars".equalsIgnoreCase(state.name[i])) marsIdx = i;
                }

                assertTrue(earthIdx >= 0 && state.hasMagneticField(earthIdx), "Earth must have active magnetic field");
                assertTrue(moonIdx >= 0 && !state.hasMagneticField(moonIdx), "Moon must be inactive non-magnetic");
                assertTrue(marsIdx >= 0 && !state.hasMagneticField(marsIdx), "Mars must be inactive non-magnetic");
                assertEquals(31.0, state.equatorialFieldMicroTesla(earthIdx), 0.5, "Earth B0 should be ~31 µT");
                assertEquals(11.3, state.magneticTiltDegrees[earthIdx], 0.1, "Earth magnetic tilt should be 11.3°");

                double earthStandoff = MagnetopauseCalculator.computeStandoff(earthIdx, state);
                double earthRadius = state.radius[earthIdx];
                double standoffEarthRadii = earthStandoff / earthRadius;
                assertTrue(standoffEarthRadii >= 10.0 && standoffEarthRadii <= 11.5,
                        "Earth standoff must be 10-11 R_E (was: " + standoffEarthRadii + ")");

                // Zoom in on Earth using instant follow framing
                canvas.render(state);
                canvas.snapFollowTo(earthIdx);
                canvas.setTracingMode(NBodyRenderer.TracingMode.PARAMETRIC);
                canvas.setShowMagneticFields(true);
                canvas.setShowBowShock(true);
                canvas.setShowSolarWind(true);
                canvas.setFieldLineDensity(14);
                canvas.setAuroralLuminescence(0.9);

                // Render a few frames to stream the local solar wind across Earth's magnetosphere
                for (int f = 0; f < 10; f++) {
                    canvas.render(state);
                }
                WritableImage imgEarthParametric = canvas.snapshot(null, null);
                saveSnapshot(imgEarthParametric, "13_earth_parametric_dipole.png");

                // =========================================================================
                // 2. Earth Numerical RK4 Multi-Body Streamlines
                // =========================================================================
                canvas.setTracingMode(NBodyRenderer.TracingMode.NUMERICAL_RK4);
                canvas.render(state);
                WritableImage imgEarthRK4 = canvas.snapshot(null, null);
                saveSnapshot(imgEarthRK4, "14_earth_rk4_streamlines.png");

                // =========================================================================
                // 3. Jupiter Magnetosphere & Magnetopause Bow Shock
                // =========================================================================
                assertTrue(jupiterIdx >= 0 && state.hasMagneticField(jupiterIdx), "Jupiter must have magnetic field");
                assertEquals(428.0, state.equatorialFieldMicroTesla(jupiterIdx), 1.0, "Jupiter B0 should be ~428 µT");

                canvas.snapFollowTo(jupiterIdx);
                canvas.setTracingMode(NBodyRenderer.TracingMode.PARAMETRIC);
                for (int f = 0; f < 10; f++) {
                    canvas.render(state);
                }
                WritableImage imgJupiter = canvas.snapshot(null, null);
                saveSnapshot(imgJupiter, "15_jupiter_bow_shock.png");

                // =========================================================================
                // 4. Solar Wind Plasma & Parker Spirals (Full Solar System View)
                // =========================================================================
                canvas.setFollowMode(NBodyCanvas.FollowMode.OFF);
                canvas.setSelectedBody(-1);
                canvas.fitToContent();

                // Step simulation forward to generate expansive Parker spiral plasma tracks
                for (int step = 0; step < 20; step++) {
                    solarEngine.step(3600.0 * 24.0); // 1 day per step
                    canvas.render(solarEngine.getState());
                }
                WritableImage imgSolarWind = canvas.snapshot(null, null);
                saveSnapshot(imgSolarWind, "16_solar_wind_parker_spirals.png");

                // =========================================================================
                // 5. Alpha Centauri Binary Wind Contact Plane Deflection
                // =========================================================================
                NBodyConfig alpha = Presets.alphaCentauri();
                NBodyEngine alphaEngine = new NBodyEngine(alpha);
                canvas.clearTrails();
                canvas.render(alphaEngine.getState());
                canvas.fitToContent();

                for (int step = 0; step < 30; step++) {
                    alphaEngine.step(3600.0 * 12.0);
                    canvas.render(alphaEngine.getState());
                }
                WritableImage imgAlphaBinary = canvas.snapshot(null, null);
                saveSnapshot(imgAlphaBinary, "17_alpha_centauri_binary_wind.png");

                // =========================================================================
                // 6. Hierarchy & Hit-Test Precedence Verification
                // =========================================================================
                // Sun (tier 2), Earth (tier 1), Moon (tier 0)
                assertEquals(2, canvas.getRenderer().getBodyHierarchyTier(state, 0), "Sun should be Tier 2");
                assertEquals(1, canvas.getRenderer().getBodyHierarchyTier(state, earthIdx), "Earth should be Tier 1");
                assertEquals(0, canvas.getRenderer().getBodyHierarchyTier(state, moonIdx), "Moon should be Tier 0");

                // =========================================================================
                // 7. Item 1 Verification: Solar Wind Complete Pause Freeze
                // =========================================================================
                canvas.setPaused(true);
                assertTrue(canvas.isPaused(), "Canvas must report paused");
                assertTrue(canvas.getRenderer().isPaused(), "Renderer must report paused");
                assertTrue(canvas.getRenderer().getSolarWindRenderer().isPaused(), "SolarWindRenderer must report paused");

                canvas.render(state);
                SolarWindParticlePool pool = canvas.getRenderer().getSolarWindRenderer().getPool();
                double p0Rad = pool.radius[0];
                double p10Rad = pool.radius[10];

                // Render 5 more frames while paused: positions MUST remain identical
                for (int f = 0; f < 5; f++) {
                    canvas.render(state);
                }
                assertEquals(p0Rad, pool.radius[0], 1e-9, "Particle 0 must freeze completely while paused");
                assertEquals(p10Rad, pool.radius[10], 1e-9, "Particle 10 must freeze completely while paused");

                // Unpause and verify that particles advance
                canvas.setPaused(false);
                canvas.setSpeedMultiplier(100_000.0);
                canvas.render(state);
                assertTrue(pool.radius[0] > p0Rad, "Particle 0 must advance when unpaused");

                // =========================================================================
                // 8. Item 4 Verification: Canvas Viewport Auto-Refit on Sidebar Toggle
                // =========================================================================
                canvas.setFollowMode(NBodyCanvas.FollowMode.OFF);
                canvas.setWidth(1200);
                canvas.setHeight(800);
                canvas.fitToContent();
                double baseScaleBefore = canvas.getCamera().getBaseScale();

                // Simulate sidebar opening: canvas width reduces from 1200 to 500
                canvas.setWidth(500);
                canvas.onViewportResized(1200, 800, 500, 800);
                double baseScaleAfter = canvas.getCamera().getBaseScale();

                assertTrue(baseScaleAfter < baseScaleBefore,
                        "baseScale must shrink when sidebar opens to refit into smaller width (was: "
                                + baseScaleBefore + " -> " + baseScaleAfter + ")");

                // Simulate sidebar closing: canvas width expands back to 1200
                canvas.setWidth(1200);
                canvas.onViewportResized(500, 800, 1200, 800);
                double baseScaleRestored = canvas.getCamera().getBaseScale();
                assertEquals(baseScaleBefore, baseScaleRestored, 1e-9,
                        "baseScale must be restored exactly to previous size when sidebar closes");

                // Verify repeated opening and closing has zero scale drift (Item 3)
                for (int cycle = 0; cycle < 5; cycle++) {
                    canvas.setWidth(500);
                    canvas.onViewportResized(1200, 800, 500, 800);
                    canvas.setWidth(1200);
                    canvas.onViewportResized(500, 800, 1200, 800);
                }
                assertEquals(baseScaleBefore, canvas.getCamera().getBaseScale(), 1e-9,
                        "Repeated sidebar toggles must have zero scale drift");

                // =========================================================================
                // 9. Item 2 Verification: CME Plasma Wave & Shock Front
                // =========================================================================
                SolarWindRenderer swRenderer = canvas.getRenderer().getSolarWindRenderer();
                assertNotNull(swRenderer.getPool(), "Particle pool must exist");
                for (int i = 0; i < SolarWindParticlePool.GLOBAL_PARTICLES; i++) {
                    assertTrue(swRenderer.getPool().cmeIntensity[i] >= 0.0 && swRenderer.getPool().cmeIntensity[i] <= 1.0,
                            "CME intensity must be in [0, 1]");
                }

                // =========================================================================
                // 10. Single-Domain World-Space Planetary Interaction Verification
                // =========================================================================
                canvas.render(state);
                SolarWindParticlePool p = swRenderer.getPool();
                int earthParticles = 0;
                int jupiterParticles = 0;
                for (int i = 0; i < SolarWindParticlePool.PARTICLES_PER_STAR; i++) {
                    if (p.targetPlanet[i] == earthIdx) earthParticles++;
                    if (p.targetPlanet[i] == jupiterIdx) jupiterParticles++;
                }
                assertTrue(earthParticles > 0, "Pool must allocate physical world-space stream particles for Earth");
                assertTrue(jupiterParticles > 0, "Pool must allocate physical world-space stream particles for Jupiter");

                // Verify interaction at wide scale (10^10 m)
                canvas.setFollowMode(NBodyCanvas.FollowMode.OFF);
                canvas.getCamera().setScale(1.0e-10); // Wide 10^10 m scale
                canvas.getCamera().setFollowPoint(state.positionX[earthIdx], state.positionY[earthIdx]);
                for (int f = 0; f < 5; f++) {
                    canvas.render(state);
                }

                boolean anyEarthDeflection = false;
                for (int i = 0; i < SolarWindParticlePool.PARTICLES_PER_STAR; i++) {
                    if (p.targetPlanet[i] == earthIdx && p.inSheath[i]) {
                        anyEarthDeflection = true;
                        break;
                    }
                }
                assertTrue(anyEarthDeflection, "Earth's magnetosphere must actively deflect solar wind particles at wide scale (10^10 m)");

            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "JavaFX snapshot test timed out");
        if (failure[0] != null) {
            if (failure[0] instanceof Exception ex) throw ex;
            throw new RuntimeException(failure[0]);
        }
    }
}
