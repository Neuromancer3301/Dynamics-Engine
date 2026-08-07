package ui;

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
 * The simulation sidebar's tabbed shell (§9 of the UI overhaul spec): an
 * icon bar across the top, an always-visible "Live Status" block pinned
 * above it, and exactly one settings group's controls visible below at a
 * time — replacing the previous single long stacked list of every {@link
 * ControlPanel}/{@link LinkEditorPanel} section concatenated together.
 *
 * <p>{@code SidebarTabs} owns the visible arrangement; {@link ControlPanel}
 * remains the builder of the actual controls (see its own javadoc) and
 * {@link LinkEditorPanel} is used directly as the {@link Group#LINKS}
 * content, unmodified.
 */
public final class SidebarTabs extends VBox {

    /** The six sidebar groups, in tab-bar order. */
    public enum Group {
        MOTION("Motion", Icons.Glyph.MOTION),
        CHAOS("Chaos & Compare", Icons.Glyph.CHAOS),
        GRAPHS("Graphs", Icons.Glyph.GRAPHS),
        HISTORY("History", Icons.Glyph.HISTORY),
        LINKS("Links", Icons.Glyph.LINKS),
        DISPLAY("Display", Icons.Glyph.DISPLAY);

        final String label;
        final Icons.Glyph glyph;

        Group(String label, Icons.Glyph glyph) {
            this.label = label;
            this.glyph = glyph;
        }
    }

    private final HBox tabBar = new HBox();
    private final StackPane contentArea = new StackPane();
    private final Map<Group, Node> groupContent = new LinkedHashMap<>();
    private final Map<Group, Button> tabButtons = new LinkedHashMap<>();
    private final Map<Group, Icons.IconView> tabIcons = new LinkedHashMap<>();
    private Group active = Group.MOTION;

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
     * Assembles the sidebar: {@code status} (typically {@link
     * ControlPanel#getStatusBlock}) is pinned above the tab content and
     * always visible; the six group nodes become each {@link Group}'s
     * content, in enum order. Call once, after every group's controls have
     * been built.
     */
    public void build(Node status,
                       Node motion, Node chaos, Node graphs, Node history, Node links, Node display) {
        Map<Group, Node> content = new LinkedHashMap<>();
        content.put(Group.MOTION, motion);
        content.put(Group.CHAOS, chaos);
        content.put(Group.GRAPHS, graphs);
        content.put(Group.HISTORY, history);
        content.put(Group.LINKS, links);
        content.put(Group.DISPLAY, display);

        for (Group g : Group.values()) {
            groupContent.put(g, content.get(g));

            Button tab = new Button();
            tab.getStyleClass().add("sidebar-tab-button");
            HBox.setHgrow(tab, Priority.ALWAYS);
            tab.setMaxWidth(Double.MAX_VALUE);

            Icons.IconView icon = Icons.create(g.glyph, 18, Icons.idleColor(ThemeManager.getInstance().getCurrent()));
            tab.setGraphic(icon);
            Tooltip.install(tab, new Tooltip(g.label));
            tab.setOnAction(e -> selectGroup(g));

            tabButtons.put(g, tab);
            tabIcons.put(g, icon);
            tabBar.getChildren().add(tab);
        }

        VBox statusWrapper = new VBox(status);
        statusWrapper.getStyleClass().add("sidebar-status-wrapper");

        getChildren().setAll(tabBar, statusWrapper, contentArea);
        selectGroup(active);
    }

    /** Switches the visible group — instant if reduced motion is on, a quick cross-fade otherwise (§9.2). */
    public void selectGroup(Group group) {
        this.active = group;
        Node node = groupContent.get(group);
        if (node == null) return;
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
        for (Map.Entry<Group, Button> e : tabButtons.entrySet()) {
            boolean isActive = e.getKey() == active;
            e.getValue().getStyleClass().remove("sidebar-tab-button-active");
            if (isActive) e.getValue().getStyleClass().add("sidebar-tab-button-active");
            Icons.IconView icon = tabIcons.get(e.getKey());
            if (icon != null) icon.setColor(isActive ? Icons.activeColor(theme) : Icons.idleColor(theme));
        }
    }
}
