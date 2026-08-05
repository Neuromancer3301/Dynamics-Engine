package navigation;

/**
 * Implemented by any FXML controller that needs to trigger navigation or
 * receive screen-lifecycle notifications from the shell.
 *
 * <p>{@link SceneRouter} checks for this interface immediately after loading
 * a screen and wires itself in automatically — controllers never construct
 * or look up the router themselves. That indirection is what keeps a
 * controller like {@code SimulationController} testable in isolation from
 * the windowing system.
 */
public interface Navigable {

    /** Called once, immediately after FXML loading, before the screen is shown. */
    void setRouter(SceneRouter router);

    /**
     * Called every time this screen becomes the visible root, including on
     * return from a pushed screen. Default no-op — override for screens that
     * start background work only while visible (the simulation's physics
     * thread and render loop, for example).
     */
    default void onShow() {}

    /**
     * Called just before this screen stops being the visible root. Default
     * no-op — override to release resources or pause background work
     * started in {@link #onShow()}.
     */
    default void onHide() {}
}
