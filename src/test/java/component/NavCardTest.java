package component;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ui.icon.Icons;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NavCardTest {

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // JavaFX toolkit already initialized
        }
    }

    private NavCardController loadNavCard() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<NavCardController> controllerRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/component/NavCard.fxml"));
                VBox node = loader.load();
                assertNotNull(node);
                controllerRef.set(loader.getController());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX loading timed out");
        if (errorRef.get() != null) {
            throw new RuntimeException(errorRef.get());
        }
        return controllerRef.get();
    }

    @Test
    void testPreviewImageLoading() throws Exception {
        NavCardController controller = loadNavCard();
        assertNotNull(controller);
        assertNotNull(controller.getPreviewImageView());
        assertNotNull(controller.getVideoPlaceholderLabel());
        assertNotNull(controller.getVideoBox());

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.configure(
                    "01", Icons.Glyph.PENDULUM, "N-Pendulum Chain",
                    "Description", "Detail",
                    "/reference/n-link-pendulum.png",
                    () -> {});

            assertNotNull(controller.getPreviewImageView().getImage(), "Image should be loaded");
            assertFalse(controller.getPreviewImageView().getImage().isError(), "Image should not have load error");
            assertTrue(controller.getPreviewImageView().isVisible(), "Preview ImageView should be visible");
            assertTrue(controller.getPreviewImageView().isManaged(), "Preview ImageView should be managed");
            assertFalse(controller.getVideoPlaceholderLabel().isVisible(), "Placeholder label should be hidden");
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testAllThreeSimulationScreenshotsExist() throws Exception {
        String[] paths = {
                "/reference/n-link-pendulum.png",
                "/reference/n-body-gravitational.png",
                "/reference/boid-simulation.png"
        };

        for (String path : paths) {
            NavCardController controller = loadNavCard();
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                controller.configure(
                        "01", Icons.Glyph.MOTION, "Title",
                        "Description", "Detail",
                        path,
                        () -> {});

                assertNotNull(controller.getPreviewImageView().getImage(), "Image must exist for " + path);
                assertFalse(controller.getPreviewImageView().getImage().isError(), "Image load error for " + path);
                assertTrue(controller.getPreviewImageView().getImage().getWidth() > 0, "Image width must be > 0");
                assertTrue(controller.getPreviewImageView().getImage().getHeight() > 0, "Image height must be > 0");
                latch.countDown();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void testFallbackWhenNoImageProvided() throws Exception {
        NavCardController controller = loadNavCard();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.configure(
                    "01", Icons.Glyph.PENDULUM, "N-Pendulum Chain",
                    "Description", "Detail",
                    (String) null,
                    () -> {});

            assertNull(controller.getPreviewImageView().getImage());
            assertFalse(controller.getPreviewImageView().isVisible());
            assertTrue(controller.getVideoPlaceholderLabel().isVisible());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
