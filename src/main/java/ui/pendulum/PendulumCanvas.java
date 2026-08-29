package ui.pendulum;

import physics.SimState;
import ui.simcore.SimCanvas;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * JavaFX Canvas that renders the N-pendulum chain and owns direct-manipulation
 * (grab / drag / fling) of its bobs.
 *
 * <p>As of round 1 of the UI restructuring, this class is a thin composition
 * shell over {@code ui.simcore.SimCanvas} (background/waiting-message/
 * scale-bar/pan-zoom camera), {@link PendulumInteraction} (hit-testing,
 * drag/fling, the gravity handle, pan), and {@link PendulumChainRenderer}
 * (everything actually drawn). Every public method that existed before the
 * split is kept with an unchanged signature, as a one-line passthrough to
 * whichever of the three actually owns that behavior now — see each
 * collaborator's own javadoc for the real logic.
 *
 * <p>Interaction: register a {@link DragListener} via {@link #setDragListener}
 * to be notified as the user grabs, drags, and releases a bob. The listener
 * only ever sees link indices and angles — no coordinate-geometry of its
 * own — so it can stay a thin translation to {@code
 * simulation.command.SimCommand}s.
 *
 * <p>Hovering (or dragging) a bob also shows a live inspector overlay —
 * that link's angle, angular velocity, mass, and rod length — entirely
 * internal to this class' collaborators; nothing outside needs to know a
 * bob is being inspected.
 */
public final class PendulumCanvas extends SimCanvas {

    /** Per-link trail: which links (if any) leave a fading path. Cycled via {@link #cycleTrailMode}. */
    public enum TrailMode { OFF, TIP_ONLY, ALL_LINKS }

    /** Notified as the user grabs, drags, and releases a bob. See the class javadoc. */
    public interface DragListener {
        /** A bob was pressed. Return {@code true} to begin dragging it, {@code false} to ignore the press. */
        boolean onGrab(int linkIndex);

        /** The pointer moved while {@code linkIndex} was grabbed; {@code angle} is its new value in radians. */
        void onDrag(int linkIndex, double angle);

        /** The pointer was released; {@code angularVelocity} (rad/s) is estimated from the motion just before release. */
        void onRelease(int linkIndex, double angle, double angularVelocity);
    }

    /** Notified as the user drags the gravity-direction handle — "painting" the field. */
    public interface GravityListener {
        /** The handle was dragged to a new direction; {@code angle} follows the same convention as a link's {@code theta} (0 = straight down). */
        void onGravityAngleChanged(double angle);
    }

    /**
     * Notified when a bob becomes selected — a completed left-button click
     * or click-drag-release (§7.1 of the UI overhaul spec). Selection is a
     * persistent state that outlives the gesture; this class only reports
     * the event, it doesn't own the state itself — see {@link #setSelectedLink}.
     */
    public interface SelectionListener {
        void onLinkSelected(int linkIndex);
    }

    /**
     * Notified during a right-button ("length") drag on a bob (§7.3). Length
     * is a structural parameter — {@link #onLengthPreview} fires
     * continuously as a visual-only preview (no engine mutation), and
     * {@link #onLengthCommit} fires once on release. {@code newLength} is
     * already positive and finite (clamped) by the time either method is
     * called.
     */
    public interface LengthDragListener {
        void onLengthPreview(int linkIndex, double newLength);
        void onLengthCommit(int linkIndex, double newLength);
    }

    /** Notified when a bob is double-clicked (not part of a drag) — opens the per-link parameter dialog (§7.4). */
    public interface DoubleClickListener {
        void onDoubleClick(int linkIndex);
    }

    /**
     * Notified on a clean right-click-and-release on a bob (round 4 §2) —
     * i.e. a right-click that never armed the length-drag, since
     * length-editing and delete are mutually-exclusive right-click gestures
     * gated by the same flag ({@link #setDragEditingEnabled}). Fires once
     * the gesture completes, not on press, so a modal doesn't pop up
     * before the mouse button is even lifted — the same reasoning as
     * {@link DoubleClickListener}.
     */
    public interface RightClickListener {
        void onRightClick(int linkIndex);
    }

    private final PendulumInteraction interaction;
    private final PendulumChainRenderer renderer;

    // Not final: a structural edit (per-link editor, runtime N) changes the
    // arm's total length after construction — see setTotalLength.
    private double totalLength;

    private SimState lastState;

    // ---- Selection (§7.1) ----
    // Stays here — set externally (by controller.SimulationController via
    // setSelectedLink), read by rendering; never interaction state, since
    // it outlives any single gesture.
    private int selectedLink = -1;

    public PendulumCanvas(double width, double height, double totalLength) {
        super(width, height);
        this.totalLength = totalLength;
        this.renderer = new PendulumChainRenderer(camera);
        this.interaction = new PendulumInteraction(this, camera);
    }

    @Override
    protected double contentExtent() {
        return totalLength;
    }

    @Override
    protected boolean hasContent() {
        return lastState != null;
    }

    @Override
    protected void drawContent(GraphicsContext gc, double w, double h) {
        renderer.draw(gc, lastState, w, h, interaction.inspectedLink(), selectedLink,
                interaction.previewLink(), interaction.previewScreenX(), interaction.previewScreenY(),
                interaction.gravityAngle(), interaction.isDraggingGravity());
    }

    /** Full render call with no ensemble or A/B compare — invoked every frame from the AnimationTimer when neither is active. */
    public void render(SimState state) {
        render(state, null, null);
    }

    /** Full render call, optionally drawing a butterfly-effect ensemble, with no A/B compare chain. */
    public void render(SimState state, List<SimState> ensembleStates) {
        render(state, ensembleStates, null);
    }

    /**
     * Full render call, optionally drawing a butterfly-effect ensemble as
     * faint ghost chains behind the primary, and/or a single bold "B"
     * chain for A/B compare. {@code ensembleStates} may be {@code null} or
     * empty when the ensemble isn't active; {@code compareState} is
     * {@code null} when A/B compare isn't active.
     */
    public void render(SimState state, List<SimState> ensembleStates, SimState compareState) {
        this.lastState = state;
        renderer.setGhosts(ensembleStates);
        renderer.setCompare(compareState);
        renderFrame();
    }

    /** Registers the listener notified of grab/drag/release. {@code null} disables interaction. */
    public void setDragListener(DragListener listener) { interaction.setDragListener(listener); }

    /** Registers the listener notified when the gravity handle is dragged. {@code null} disables that interaction. */
    public void setGravityListener(GravityListener listener) { interaction.setGravityListener(listener); }

    /** Registers the listener notified when a bob becomes selected. {@code null} disables that notification (selection can still be set programmatically). */
    public void setSelectionListener(SelectionListener listener) { interaction.setSelectionListener(listener); }

    /** Registers the listener notified during/after a right-click length drag. {@code null} disables that interaction. */
    public void setLengthDragListener(LengthDragListener listener) { interaction.setLengthDragListener(listener); }

    /** Registers the listener notified on a double-click. {@code null} disables that interaction. */
    public void setDoubleClickListener(DoubleClickListener listener) { interaction.setDoubleClickListener(listener); }

    /** Registers the listener notified on a clean right-click-and-release (round 4 §2). {@code null} disables that notification. */
    public void setRightClickListener(RightClickListener listener) { interaction.setRightClickListener(listener); }

    /** Enables or disables left/right-drag posing of bobs (§4-e's Add-tool no-op) without touching selection, which stays tool-agnostic. On by default. */
    public void setDragEditingEnabled(boolean dragEditingEnabled) { interaction.setDragEditingEnabled(dragEditingEnabled); }

    /**
     * Sets the currently-selected link, drawn with a pulsing halo and
     * reflected in the top-left HUD. {@code -1} clears the selection. This
     * is the single source of truth for selection state — {@code
     * controller.SimulationController} owns the pause/resume policy (§7.1)
     * and calls this to reflect it visually; this class never pauses
     * anything itself.
     */
    public void setSelectedLink(int link) { this.selectedLink = link; }

    public int getSelectedLink() { return selectedLink; }

    /** Toggles 15°-angle / 0.25m-length drag snapping (§6.3). Off by default. */
    public void setSnapEnabled(boolean snapEnabled) { interaction.setSnapEnabled(snapEnabled); }

    /** Updates where the gravity handle is drawn without implying a drag happened — for keeping the visual in sync after an external change. */
    public void setGravityAngleVisual(double angle) { interaction.setGravityAngleVisual(angle); }

    /** Updates the arm length used to compute the render scale. Call after any structural rebuild. */
    public void setTotalLength(double totalLength) { this.totalLength = totalLength; }

    /** Cycles TIP_ONLY -> ALL_LINKS -> OFF -> TIP_ONLY. Clears existing trails on entering OFF, so nothing lingers. */
    public void cycleTrailMode() {
        TrailMode next = switch (renderer.getTrailMode()) {
            case TIP_ONLY  -> TrailMode.ALL_LINKS;
            case ALL_LINKS -> TrailMode.OFF;
            case OFF       -> TrailMode.TIP_ONLY;
        };
        renderer.setTrailMode(next);
    }

    /** Current trail mode — read by the sidebar to keep its button label in sync. */
    public TrailMode getTrailMode() { return renderer.getTrailMode(); }

    /** Enables colouring trail segments by speed instead of by link. */
    public void setVelocityTint(boolean on) { renderer.setVelocityTint(on); }

    /** Whether velocity tinting is active. */
    public boolean isVelocityTint() { return renderer.isVelocityTint(); }

    /** Swaps the per-bob color palette for a colour-blind-safe one. */
    public void setColorBlindSafe(boolean colorBlindSafe) { renderer.setColorBlindSafe(colorBlindSafe); }

    /** Disables trail recording and the glow/highlight flourishes in the renderer. */
    public void setReducedMotion(boolean reducedMotion) { renderer.setReducedMotion(reducedMotion); }

    /** Erases every link's trail history. Called on reset and after a structural rebuild, where old points describe a chain that no longer exists. */
    public void clearTrail() { renderer.clearTrail(); }

    /** Ends a right-click length-drag preview — the next render() draws the chain from real state again. */
    public void clearLengthPreview() { interaction.clearLengthPreview(); }

    /**
     * Saves the current frame — pendulum, trails, ghosts, everything already
     * drawn on this canvas — as a PNG. "Trace art": running with a long
     * trail for a while and exporting captures exactly the kind of
     * chaotic-attractor-shaped image this canvas already renders live, with
     * no separate rendering path to keep in sync.
     */
    public void exportSnapshot(Path path) throws IOException {
        WritableImage image = this.snapshot(new SnapshotParameters(), null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", path.toFile());
    }

    /** The most recently rendered state, or {@code null} before the first frame — package-private, read by {@link PendulumInteraction}. */
    SimState lastState() { return lastState; }

    /** Package-private, read by {@link PendulumInteraction} for its bob-radius-dependent hit-testing. */
    PendulumChainRenderer renderer() { return renderer; }
}
