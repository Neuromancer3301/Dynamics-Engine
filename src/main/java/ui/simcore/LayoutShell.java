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
     *
     * <p>Round 1.5 tried making this button a real layout column, a sibling
     * of {@code sidebarScroll}, specifically to fix it detaching from the
     * sidebar on expand (see git history for that attempt). That traded one
     * bug for a worse one: a real column, even styled with no
     * background/border of its own, still has to show *something* for
     * whatever fraction of its height the button doesn't cover — and what
     * showed was this screen's own plain background fill (black in dark
     * theme, white in light), a flat rectangle with no relation to the
     * canvas beside it. Round 1.7 §1 reverts to an overlay on {@link
     * #canvasGraphStack} — so the "empty" area around the button is once
     * again the canvas's own live rendering, not a stray colored panel —
     * and fixes the actual round-1.4 bug directly: {@link #buildSidebarToggle}
     * gives the button a right margin of exactly 0. {@code
     * canvasGraphStack}'s own right edge always sits exactly at the
     * sidebar's current left edge (collapsed or expanded) because {@code
     * BorderPane} resizes its center region to fill whatever the right
     * region doesn't claim — a zero right-margin overlay therefore stays
     * flush against the sidebar automatically, with no need to track its
     * animated width at all. The old fixed-10px-on-every-side margin was
     * the actual bug: fine while collapsed (a 10px inset from the window
     * edge reads as intentional breathing room), but that same 10px read as
     * "detached from the sidebar" the moment the sidebar expanded and that
     * edge became the sidebar's own boundary instead of the window's.
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

        StackPane.setAlignment(toggle, Pos.TOP_RIGHT);
        StackPane.setMargin(toggle, new Insets(10, 0, 0, 0));
        canvasGraphStack.getChildren().add(toggle);
    }

    /**
     * Animates the sidebar's width between 0 (collapsed) and {@link
     * #SIDEBAR_EXPANDED_WIDTH}. Deliberately a min/pref/max width tween on a
     * real layout column, not {@code setVisible(false)} — the canvas/graph
     * genuinely shrink to make room rather than being overlaid.
     *
     * <p>Round 1.6 §2: also toggles {@code sidebarScroll}'s vbarPolicy —
     * NEVER while collapsed (or collapsing), AS_NEEDED only once actually
     * expanded — see Simulation.fxml's own comment on the initial value for
     * why a vertical scrollbar must never even attempt to render at 0
     * width. Collapsing flips it back to NEVER immediately, before the
     * width even starts shrinking, so the scrollbar doesn't linger visible
     * while the column animates down to nothing; expanding waits until the
     * width animation actually finishes, so it doesn't try to render
     * against a still-mid-animation, not-yet-wide-enough column.
     */
    public void setSidebarExpanded(boolean expanded) {
        this.sidebarExpanded = expanded;
        double target = expanded ? SIDEBAR_EXPANDED_WIDTH : 0.0;

        if (sidebarWidthAnim != null) sidebarWidthAnim.stop();
        if (!expanded) sidebarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        if (ThemeManager.getInstance().isReducedMotion()) {
            sidebarScroll.setMinWidth(target);
            sidebarScroll.setPrefWidth(target);
            sidebarScroll.setMaxWidth(target);
            if (expanded) sidebarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        } else {
            sidebarWidthAnim = new Timeline(new KeyFrame(WIDTH_ANIM_DURATION,
                    new KeyValue(sidebarScroll.minWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(sidebarScroll.prefWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(sidebarScroll.maxWidthProperty(), target, Interpolator.EASE_BOTH)));
            if (expanded) {
                sidebarWidthAnim.setOnFinished(e -> sidebarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED));
            }
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
