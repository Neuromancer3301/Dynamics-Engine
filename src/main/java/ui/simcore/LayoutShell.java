package ui.simcore;

import theme.ThemeManager;
import ui.icon.Icons;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
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
    private final StackPane canvasGraphStack;

    private boolean sidebarExpanded = false;
    private Icons.IconView sidebarToggleIcon;
    private Timeline sidebarWidthAnim;
    private Timeline graphWidthAnim;

    public LayoutShell(ScrollPane sidebarScroll, StackPane graphHost, StackPane canvasGraphStack) {
        this.sidebarScroll = sidebarScroll;
        this.graphHost = graphHost;
        this.canvasGraphStack = canvasGraphStack;
    }

    /** Builds the overlay chevron button (top-right of the canvas/graph area) that toggles the sidebar. */
    public void buildSidebarToggle() {
        Button toggle = new Button();
        toggle.getStyleClass().add("canvas-overlay-button");
        // A fixed, theme-independent tint, not Icons.hoverColor (near-black
        // in light theme) — this button's chip is always dark regardless of
        // app theme, so the icon has to ignore the app theme the same way,
        // or it goes invisible in light mode.
        sidebarToggleIcon = Icons.create(Icons.Glyph.CHEVRON, 18, Icons.onDarkOverlayColor());
        sidebarToggleIcon.setRotate(sidebarExpanded ? 0 : 180);
        toggle.setGraphic(sidebarToggleIcon);
        Tooltip.install(toggle, new Tooltip("Toggle sidebar"));
        toggle.setOnAction(e -> setSidebarExpanded(!sidebarExpanded));

        StackPane.setAlignment(toggle, Pos.TOP_RIGHT);
        StackPane.setMargin(toggle, new Insets(10));
        canvasGraphStack.getChildren().add(toggle);
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
