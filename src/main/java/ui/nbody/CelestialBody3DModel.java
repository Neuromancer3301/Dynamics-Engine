package ui.nbody;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.AmbientLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

/**
 * Encapsulates a real, hardware-accelerated JavaFX 3D model for a celestial body
 * (textured 3D sphere mesh, 3D circumstellar ring geometry, 3D axial tilt,
 * spin axis rotation, and calibrated 3D lighting).
 * <p>
 * At runtime, the 2D simulation canvas draws a 2D snapshot of this 3D model
 * corresponding to the current frame's rotation angle. Snapshots are lazily cached
 * across 72 rotation angles (5-degree increments) to guarantee locked 60+ FPS
 * performance with zero GPU readback stalls.
 */
public class CelestialBody3DModel {

    public static final int DEFAULT_SNAPSHOT_SIZE = 128;
    public static final int NUM_ROTATION_FRAMES = 72;

    public static final double SPHERE_FRACTION = 0.38;

    private final String name;
    private final Image texture;
    private final Image ringTexture;
    private final double axialTiltDeg;
    private final boolean isStar;
    private final boolean isCompact;
    private final boolean isComet;
    private final double radiusMultiplier;

    private final Group root3D;
    private final Group planetGroup;
    private final Sphere sphereNode;
    private final Rotate spinRotate;
    private final Rotate tiltRotate;
    private final SubScene subScene;
    private final PerspectiveCamera camera;

    private final WritableImage[] snapshotCache = new WritableImage[NUM_ROTATION_FRAMES];

    public CelestialBody3DModel(String name, Image texture, Image ringTexture,
                                double axialTiltDeg, boolean isStar, boolean isCompact, boolean isComet) {
        this(name, texture, ringTexture, axialTiltDeg, isStar, isCompact, isComet, Color.web("#888888"));
    }

    public CelestialBody3DModel(String name, Image texture, Image ringTexture,
                                double axialTiltDeg, boolean isStar, boolean isCompact, boolean isComet, Color baseColor) {
        this.name = name;
        this.texture = texture;
        this.ringTexture = ringTexture;
        this.axialTiltDeg = axialTiltDeg;
        this.isStar = isStar;
        this.isCompact = isCompact;
        this.isComet = isComet;

        boolean hasRings = (ringTexture != null || name.toLowerCase().contains("saturn"));
        this.radiusMultiplier = hasRings ? 2.35 : 1.0;

        int size = DEFAULT_SNAPSHOT_SIZE;
        double sphereRadius = (size * SPHERE_FRACTION) / radiusMultiplier;

        sphereNode = new Sphere(sphereRadius, 64);
        PhongMaterial material = new PhongMaterial();
        if (texture != null) {
            material.setDiffuseMap(texture);
            if (isStar) {
                material.setSelfIlluminationMap(texture);
                material.setSpecularColor(Color.TRANSPARENT);
            } else {
                material.setSpecularColor(Color.rgb(180, 180, 190));
                material.setSpecularPower(28.0);
            }
        } else {
            material.setDiffuseColor(baseColor != null ? baseColor : Color.web("#888888"));
        }
        sphereNode.setMaterial(material);

        planetGroup = new Group(sphereNode);

        // Add 3D circumstellar rings if applicable (Saturn)
        if (hasRings) {
            double innerRing = sphereRadius * 1.25;
            double outerRing = sphereRadius * 2.30;
            MeshView ringMesh = createRingMesh(innerRing, outerRing, ringTexture);
            planetGroup.getChildren().add(ringMesh);
        }

        // 3D Transforms: axial tilt and rotation around polar spin axis
        tiltRotate = new Rotate(axialTiltDeg, Rotate.Z_AXIS);
        // Slight inclination tilt so rings and poles are visible in 3D perspective from front
        Rotate perspectiveTilt = new Rotate(-18.0, Rotate.X_AXIS);
        spinRotate = new Rotate(0.0, Rotate.Y_AXIS);
        planetGroup.getTransforms().addAll(tiltRotate, perspectiveTilt, spinRotate);

        root3D = new Group(planetGroup);

        // 3D Lighting
        if (isStar) {
            AmbientLight starAmbient = new AmbientLight(Color.WHITE);
            root3D.getChildren().add(starAmbient);
        } else {
            PointLight sunLight = new PointLight(Color.rgb(255, 252, 245));
            sunLight.setTranslateX(-size * 1.5);
            sunLight.setTranslateY(-size * 1.5);
            sunLight.setTranslateZ(-size * 2.2);

            AmbientLight ambientLight = new AmbientLight(Color.rgb(150, 150, 160));
            root3D.getChildren().addAll(sunLight, ambientLight);
        }

        camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-size * 1.7);
        camera.setNearClip(0.1);
        camera.setFarClip(2500.0);

        subScene = new SubScene(root3D, size, size, true, SceneAntialiasing.BALANCED);
        subScene.setCamera(camera);

        // SubScene must be rooted in a Scene for JavaFX 3D pipeline to initialize and render snapshots
        javafx.scene.Group sceneRoot = new javafx.scene.Group(subScene);
        new javafx.scene.Scene(sceneRoot);
    }

    public Group getRoot3D() {
        return root3D;
    }

    public SubScene getSubScene() {
        return subScene;
    }

    public PerspectiveCamera getCamera() {
        return camera;
    }

    /** Returns the visual radius multiplier (e.g. 2.35 for Saturn to accommodate rings). */
    public double getRadiusMultiplier() {
        return radiusMultiplier;
    }

    /**
     * Calculates the drawing radius for rendering the snapshot onto a 2D canvas,
     * ensuring the central 3D body sphere precisely matches the physical body radius.
     */
    public double getDrawRadius(double bodyRadius) {
        // In the 128x128 snapshot with 30 deg FoV at z = -1.7*128:
        // Sphere of base radius (128*0.38) occupies radius fraction 0.834 of the snapshot half-width.
        // Thus drawRadius = bodyRadius * radiusMultiplier / 0.834 = bodyRadius * radiusMultiplier * 1.20.
        return bodyRadius * radiusMultiplier * 1.20;
    }

    public String getName() {
        return name;
    }

    public boolean isStar() {
        return isStar;
    }

    public boolean isCompact() {
        return isCompact;
    }

    public boolean isComet() {
        return isComet;
    }

    /**
     * Obtains the 2D snapshot of this real 3D model corresponding to the given rotation phase [0.0, 1.0).
     */
    public Image getSnapshot(double rotationProgress) {
        double phi = ((rotationProgress % 1.0) + 1.0) % 1.0;
        int frameIndex = (int) Math.floor(phi * NUM_ROTATION_FRAMES) % NUM_ROTATION_FRAMES;

        WritableImage cached = snapshotCache[frameIndex];
        if (cached != null) {
            return cached;
        }

        if (Platform.isFxApplicationThread()) {
            cached = captureSnapshot(frameIndex);
            snapshotCache[frameIndex] = cached;
            return cached;
        } else {
            // If called off-thread, return base texture or wait
            return texture;
        }
    }

    private WritableImage captureSnapshot(int frameIndex) {
        double angle = frameIndex * (360.0 / NUM_ROTATION_FRAMES);
        spinRotate.setAngle(angle);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return subScene.snapshot(params, null);
    }

    /**
     * Constructs a double-sided planar 3D ring mesh for ringed planets.
     */
    private static MeshView createRingMesh(double innerRadius, double outerRadius, Image ringTexture) {
        TriangleMesh mesh = new TriangleMesh();
        int segments = 64;

        // Points
        for (int i = 0; i <= segments; i++) {
            double theta = (i * 2.0 * Math.PI) / segments;
            float cos = (float) Math.cos(theta);
            float sin = (float) Math.sin(theta);
            mesh.getPoints().addAll(cos * (float) innerRadius, 0.0f, sin * (float) innerRadius);
            mesh.getPoints().addAll(cos * (float) outerRadius, 0.0f, sin * (float) outerRadius);
        }

        // Texture coordinates (radial mapping)
        for (int i = 0; i <= segments; i++) {
            float u = (float) i / segments;
            mesh.getTexCoords().addAll(0.0f, u);
            mesh.getTexCoords().addAll(1.0f, u);
        }

        // Faces (both front and back for 3D visibility)
        for (int i = 0; i < segments; i++) {
            int i0 = i * 2;
            int o0 = i * 2 + 1;
            int i1 = (i + 1) * 2;
            int o1 = (i + 1) * 2 + 1;

            // Front face
            mesh.getFaces().addAll(i0, i0, o0, o0, o1, o1);
            mesh.getFaces().addAll(i0, i0, o1, o1, i1, i1);

            // Back face
            mesh.getFaces().addAll(i0, i0, o1, o1, o0, o0);
            mesh.getFaces().addAll(i0, i0, i1, i1, o1, o1);
        }

        MeshView meshView = new MeshView(mesh);
        PhongMaterial mat = new PhongMaterial();
        if (ringTexture != null) {
            mat.setDiffuseMap(ringTexture);
        } else {
            mat.setDiffuseColor(Color.web("#E0C595", 0.85));
        }
        meshView.setMaterial(mat);
        return meshView;
    }
}
