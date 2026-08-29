package ui.simcore;

import theme.Theme;
import theme.ThemeManager;
import ui.icon.Icons;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A screen's canvas-first layout shell: a collapsible sidebar column and a
 * collapsible graph column, each animated open/closed via a min/pref/max
 * width tween on a real layout column (never {@code setVisible(false)}), so
 * each genuinely shrinks its neighbors rather than overlapping them. Moved
 * out of {@code controller.SimulationController} — see round 1 §11 of the
 * UI restructuring plan. Nothing here is pendulum-specific: any screen with
 * a canvas + collapsible sidebar/graph column can reuse this as-is.
 */
public final class LayoutShell {

    // Chosen to comfortably fit the sidebar's larger fonts/icon tab bar and
    // the graph panel's own axes/labels.
    private static final double SIDEBAR_EXPANDED_WIDTH = 340;
    private static final double GRAPH_EXPANDED_WIDTH    = 460;
    private static final Duration WIDTH_ANIM_DURATION   = Duration.millis(220);

    private final ScrollPane sidebarScroll;
    private final StackPane graphHost;
    private final VBox sidebarToggleHost;

    private boolean sidebarExpanded = false;
    private Icons.IconView sidebarToggleIcon;
    private Timeline sidebarWidthAnim;
    private Timeline graphWidthAnim;

    /**
     * @param sidebarToggleHost a real layout column, a sibling of {@code
     *        sidebarScroll} in the same HBox (see Simulation.fxml) — round
     *        1.5 §1: {@link #buildSidebarToggle} used to add the toggle
     *        button as a fixed-margin overlay on the canvas/graph stack,
     *        which only looked attached to the sidebar by coincidence while
     *        collapsed and visibly floated away from it once expanded,
     *        since the overlay's position never tracked the sidebar's own
     *        animated width. A sibling layout column can't do that — an
     *        HBox never leaves a gap between adjacent children, expanded or
     *        not — so the button now lives here instead.
     */
    public LayoutShell(ScrollPane sidebarScroll, StackPane graphHost, VBox sidebarToggleHost) {
        this.sidebarScroll = sidebarScroll;
        this.graphHost = graphHost;
        this.sidebarToggleHost = sidebarToggleHost;
    }

    /**
     * Builds the sidebar-toggle "tab" — icon above a vertically-set
     * {@code "Sidebar"} label, rotated 90&deg; counter-clockwise so it reads
     * bottom-to-top, the standard vertical-tab convention for something
     * attached to a side panel's edge. The label is wrapped in a {@link
     * Group} rather than rotated in place: {@code Node.setRotate} is a
     * visual-only transform that doesn't itself change layout bounds, so
     * without the wrapper the surrounding {@link VBox} would still reserve
     * the label's original unrotated (wide/short) footprint instead of its
     * rotated (narrow/tall) one, clipping it against the icon above.
     */
    public void buildSidebarToggle() {
        Theme theme = ThemeManager.getInstance().getCurrent();

        sidebarToggleIcon = Icons.create(Icons.Glyph.CHEVRON, 16, Icons.idleColor(theme));
        sidebarToggleIcon.setRotate(sidebarExpanded ? 0 : 180);

        Label label = new Label("Sidebar");
        label.getStyleClass().add("sidebar-toggle-label");
        label.setRotate(-90);
        Group rotatedLabel = new Group(label);

        VBox content = new VBox(8, sidebarToggleIcon, rotatedLabel);
        content.setAlignment(Pos.CENTER);

        Button toggle = new Button();
        toggle.getStyleClass().add("sidebar-toggle-button");
        toggle.setGraphic(content);
        Tooltip.install(toggle, new Tooltip("Toggle sidebar"));
        toggle.setOnAction(e -> setSidebarExpanded(!sidebarExpanded));

        ThemeManager.getInstance().addListener(() ->
                sidebarToggleIcon.setColor(Icons.idleColor(ThemeManager.getInstance().getCurrent())));

        VBox.setMargin(toggle, new Insets(10, 0, 0, 0));
        sidebarToggleHost.getChildren().setAll(toggle);
    }

    /**
     * Animates the sidebar's width between 0 (collapsed) and {@link
     * #SIDEBAR_EXPANDED_WIDTH}. Deliberately a min/pref/max width tween on a
     * real layout column, not {@code setVisible(false)} — the canvas/graph
     * genuinely shrink to make room rather than being overlaid.
     */
    public void setSidebarExpanded(boolean expanded) {
        this.sidebarExpanded = expanded;
        double target = expanded ? SIDEBAR_EXPANDED_WIDTH : 0.0;

        if (sidebarWidthAnim != null) sidebarWidthAnim.stop();

        if (ThemeManager.getInstance().isReducedMotion()) {
            sidebarScroll.setMinWidth(target);
            sidebarScroll.setPrefWidth(target);
            sidebarScroll.setMaxWidth(target);
        } else {
            sidebarWidthAnim = new Timeline(new KeyFrame(WIDTH_ANIM_DURATION,
                    new KeyValue(sidebarScroll.minWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(sidebarScroll.prefWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(sidebarScroll.maxWidthProperty(), target, Interpolator.EASE_BOTH)));
            sidebarWidthAnim.play();
        }

        if (sidebarToggleIcon != null) sidebarToggleIcon.setRotate(expanded ? 0 : 180);
    }

    /**
     * Animates the graph column's width between 0 (hidden) and {@link
     * #GRAPH_EXPANDED_WIDTH}. Entirely independent of the sidebar's own
     * collapse state; both just happen to use the same min/pref/max-width
     * technique.
     */
    public void setGraphVisible(boolean show) {
        double target = show ? GRAPH_EXPANDED_WIDTH : 0.0;

        if (graphWidthAnim != null) graphWidthAnim.stop();

        if (ThemeManager.getInstance().isReducedMotion()) {
            graphHost.setMinWidth(target);
            graphHost.setPrefWidth(target);
            graphHost.setMaxWidth(target);
        } else {
            graphWidthAnim = new Timeline(new KeyFrame(WIDTH_ANIM_DURATION,
                    new KeyValue(graphHost.minWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(graphHost.prefWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(graphHost.maxWidthProperty(), target, Interpolator.EASE_BOTH)));
            graphWidthAnim.play();
        }
    }
}
