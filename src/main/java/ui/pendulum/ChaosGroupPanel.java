package ui.pendulum;

import simulation.SimulationLoop;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The sidebar's "Chaos & Compare" group: the butterfly-effect ensemble
 * toggle, perturb, sonify, and A/B compare. Moved out of {@code
 * ui.ControlPanel} — see round 1 §10 of the UI restructuring plan.
 */
public final class ChaosGroupPanel extends VBox {

    // Small enough to be imperceptible against typical swing speeds
    // (order 1-3 rad/s), large enough to seed a real chaotic divergence.
    private static final double PERTURB_MAGNITUDE = 1.0e-6;

    // Kept as fields so controller.SimulationController#applyStructuralEdit
    // can reset their visual state without the caller needing to know
    // they're ToggleButtons.
    private final ToggleButton btnEnsemble = new ToggleButton("🦋  Butterfly Effect");
    private final ToggleButton btnCompare2 = new ToggleButton("🆚  A/B Compare");

    // Kept as a field so controller.SimulationController#onHide can force
    // this off (and reflect that in the button) when leaving the screen.
    private final ToggleButton btnSonify = new ToggleButton("🔊  Sonify: Off");

    private Consumer<Boolean> onEnsembleToggle;
    private Consumer<Boolean> onSonifyToggle;
    private BiConsumer<Boolean, Double> onCompareToggle;

    public ChaosGroupPanel(SimulationLoop simLoop) {
        super(10);

        Label lEnsemble = SidebarControlFactory.sectionLabel("Chaos");
        SidebarControlFactory.styleButton(btnEnsemble);
        btnEnsemble.setOnAction(e -> {
            if (onEnsembleToggle != null) onEnsembleToggle.accept(btnEnsemble.isSelected());
        });
        Label ensembleHint = SidebarControlFactory.hintLabel("Spawns 50 near-identical copies from right now — watch them fan apart.");

        Button btnPerturb = new Button("⚡  Perturb");
        SidebarControlFactory.styleButton(btnPerturb);
        btnPerturb.setOnAction(e -> simLoop.perturb(PERTURB_MAGNITUDE));
        Label perturbHint = SidebarControlFactory.hintLabel("Nudges every link's velocity by a microscopic random amount.");

        SidebarControlFactory.styleButton(btnSonify);
        btnSonify.setOnAction(e -> {
            boolean on = btnSonify.isSelected();
            if (onSonifyToggle != null) onSonifyToggle.accept(on);
            btnSonify.setText(on ? "🔊  Sonify: On" : "🔊  Sonify: Off");
        });
        Label sonifyHint = SidebarControlFactory.hintLabel("Tip bob's speed as a live tone — hear two nearly-identical runs drift apart.");

        // ---- A/B compare ----
        Label lCompare = SidebarControlFactory.sectionLabel("A/B Compare");
        Slider sCompareOffset = SidebarControlFactory.slider(-1.0, 1.0, 0.1);
        sCompareOffset.setAccessibleText("B's initial angle offset from A, in radians, from -1 to 1");
        Label compareOffsetLabel = SidebarControlFactory.hintLabel("Δθ₁ = 0.10 rad");
        sCompareOffset.valueProperty().addListener((o, ov, nv) ->
                compareOffsetLabel.setText(String.format("Δθ₁ = %.2f rad", nv.doubleValue())));
        SidebarControlFactory.styleButton(btnCompare2);
        btnCompare2.setOnAction(e -> {
            boolean on = btnCompare2.isSelected();
            if (onCompareToggle != null) onCompareToggle.accept(on, sCompareOffset.getValue());
            setCompareVisual(on);
        });
        Label compareHint2 = SidebarControlFactory.hintLabel(
            "Spawns one deliberately different \"B\" chain from right now, offset by Δθ₁ — "
          + "unlike Butterfly Effect's 50 near-identical copies, this is one clear side-by-side comparison.");

        getChildren().setAll(
            lEnsemble, btnEnsemble, ensembleHint, btnPerturb, perturbHint, btnSonify, sonifyHint,
            SidebarControlFactory.sep(), lCompare, SidebarControlFactory.hRow(sCompareOffset, compareOffsetLabel), btnCompare2, compareHint2
        );
    }

    /** Called with {@code true}/{@code false} when the butterfly-effect toggle is clicked. */
    public void setOnEnsembleToggle(Consumer<Boolean> callback) { this.onEnsembleToggle = callback; }
    /** Called with {@code true}/{@code false} when the sonify toggle is clicked. */
    public void setOnSonifyToggle(Consumer<Boolean> callback) { this.onSonifyToggle = callback; }
    /** Called with (enabled, Δθ₁ offset) when the A/B compare toggle is clicked — two values, hence {@code BiConsumer}. */
    public void setOnCompareToggle(BiConsumer<Boolean, Double> callback) { this.onCompareToggle = callback; }

    /** Resets the ensemble toggle to off without firing the callback — used when a structural edit invalidates it. */
    public void setEnsembleVisual(boolean active) { btnEnsemble.setSelected(active); }

    /** Resets the A/B compare button's visual state — used when a structural edit drops an active compare. */
    public void setCompareVisual(boolean active) {
        btnCompare2.setSelected(active);
        btnCompare2.setText(active ? "🆚  A/B Compare: On" : "🆚  A/B Compare");
    }

    /** Keeps the sonify button's visual state in sync when it's force-stopped from outside (leaving the screen). */
    public void setSonifyVisual(boolean on) {
        btnSonify.setSelected(on);
        btnSonify.setText(on ? "🔊  Sonify: On" : "🔊  Sonify: Off");
    }
}
