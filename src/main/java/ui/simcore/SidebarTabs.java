package ui.simcore;

import javafx.animation.FadeTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import theme.Theme;
import theme.ThemeManager;
import ui.icon.Icons;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A simulation sidebar's tabbed shell (§9 of the UI overhaul spec): an icon
 * bar across the top, an always-visible "status" block pinned above it, and
 * exactly one tab's content visible below at a time — replacing a single
 * long stacked list of every section concatenated together.
 *
 * <p>Round 1.4 §1: simulation-agnostic, alongside this package's other
 * reusable base classes ({@link SimCanvas}, {@link ChartCanvas}, {@link
 * ActionRailBuilder}, {@link LayoutShell}) — moved out of {@code ui} (and,
 * before that round, genericized) since nothing in this class's own body
 * ever depended on the pendulum simulation specifically: it always just
 * arranged whatever {@link Node}s a caller handed it. What <em>was</em>
 * pendulum-specific was the old six-parameter {@code build(status, motion,
 * chaos, graphs, history, links, display)} signature, hardcoded to exactly
 * the pendulum sidebar's own tab set — a future simulation with a different
 * number or arrangement of tabs couldn't have reused it without editing this
 * class. {@link #build(Node, Tab...)} replaces that with an arbitrary,
 * caller-supplied list of {@link Tab}s instead.
 */
public final class SidebarTabs extends VBox {

    /** One tab: its label/icon for the tab bar, and the content it shows when selected. */
    public record Tab(String label, Icons.Glyph glyph, Node content) {}

    private final HBox tabBar = new HBox();
    private final StackPane contentArea = new StackPane();
    private final Map<Tab, Button> tabButtons = new LinkedHashMap<>();
    private final Map<Tab, Icons.IconView> tabIcons = new LinkedHashMap<>();
    private Tab active;

    public SidebarTabs() {
        super(0);
        getStyleClass().add("sidebar-panel");
        setFillWidth(true);
        tabBar.getStyleClass().add("sidebar-tabbar");
        contentArea.getStyleClass().add("sidebar-tab-content");
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        ThemeManager.getInstance().addListener(this::refreshTabColors);
    }

    /**
     * Assembles the sidebar: {@code status} is pinned above the tab content
     * and always visible; {@code tabs} become the tab bar, in the given
     * order, with the first one starting selected. Call once, after every
     * tab's own content has been built.
     */
    public void build(Node status, Tab... tabs) {
        for (Tab t : tabs) {
            Button tabButton = new Button();
            tabButton.getStyleClass().add("sidebar-tab-button");
            HBox.setHgrow(tabButton, Priority.ALWAYS);
            tabButton.setMaxWidth(Double.MAX_VALUE);

            Icons.IconView icon = Icons.create(t.glyph(), 18, Icons.idleColor(ThemeManager.getInstance().getCurrent()));
            tabButton.setGraphic(icon);
            Tooltip.install(tabButton, new Tooltip(t.label()));
            tabButton.setOnAction(e -> selectTab(t));

            tabButtons.put(t, tabButton);
            tabIcons.put(t, icon);
            tabBar.getChildren().add(tabButton);
        }

        VBox statusWrapper = new VBox(status);
        statusWrapper.getStyleClass().add("sidebar-status-wrapper");

        getChildren().setAll(tabBar, statusWrapper, contentArea);
        if (tabs.length > 0) selectTab(tabs[0]);
    }

    /** Switches the visible tab — instant if reduced motion is on, a quick cross-fade otherwise (§9.2). */
    public void selectTab(Tab tab) {
        this.active = tab;
        Node node = tab.content();
        contentArea.getChildren().setAll(node);

        if (ThemeManager.getInstance().isReducedMotion()) {
            node.setOpacity(1.0);
        } else {
            node.setOpacity(0.0);
            FadeTransition fade = new FadeTransition(Duration.millis(140), node);
            fade.setToValue(1.0);
            fade.play();
        }
        refreshTabColors();
    }

    private void refreshTabColors() {
        Theme theme = ThemeManager.getInstance().getCurrent();
        for (Map.Entry<Tab, Button> e : tabButtons.entrySet()) {
            boolean isActive = e.getKey() == active;
            e.getValue().getStyleClass().remove("sidebar-tab-button-active");
            if (isActive) e.getValue().getStyleClass().add("sidebar-tab-button-active");
            Icons.IconView icon = tabIcons.get(e.getKey());
            if (icon != null) icon.setColor(isActive ? Icons.activeColor(theme) : Icons.idleColor(theme));
        }
    }
}
