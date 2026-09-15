package simulation.boids;

import java.util.concurrent.ThreadLocalRandom;

public class Vector2D {
    public float x;
    public float y;

    public Vector2D() {
        this(0, 0);
    }

    public Vector2D(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public Vector2D(Vector2D other) {
        this.x = other.x;
        this.y = other.y;
    }

    // Static random
    public static Vector2D random() {
        return new Vector2D(getRandomFloat(), getRandomFloat());
    }

    private static float getRandomFloat() {
        return ThreadLocalRandom.current().nextFloat();
    }

    // Addition
    public Vector2D add(Vector2D other) {
        return new Vector2D(this.x + other.x, this.y + other.y);
    }

    public Vector2D add(float scalar) {
        return new Vector2D(this.x + scalar, this.y + scalar);
    }

    public Vector2D addInPlace(Vector2D other) {
        this.x += other.x;
        this.y += other.y;
        return this;
    }

    public Vector2D addInPlace(float scalar) {
        this.x += scalar;
        this.y += scalar;
        return this;
    }

    // Subtraction
    public Vector2D sub(Vector2D other) {
        return new Vector2D(this.x - other.x, this.y - other.y);
    }

    public Vector2D sub(float scalar) {
        return new Vector2D(this.x - scalar, this.y - scalar);
    }

    public Vector2D subInPlace(Vector2D other) {
        this.x -= other.x;
        this.y -= other.y;
        return this;
    }

    public Vector2D subInPlace(float scalar) {
        this.x -= scalar;
        this.y -= scalar;
        return this;
    }

    // Multiplication
    public Vector2D mult(Vector2D other) {
        return new Vector2D(this.x * other.x, this.y * other.y);
    }

    public Vector2D mult(float scalar) {
        return new Vector2D(this.x * scalar, this.y * scalar);
    }

    public Vector2D multInPlace(Vector2D other) {
        this.x *= other.x;
        this.y *= other.y;
        return this;
    }

    public Vector2D multInPlace(float scalar) {
        this.x *= scalar;
        this.y *= scalar;
        return this;
    }

    // Division
    public Vector2D div(Vector2D other) {
        return new Vector2D(this.x / other.x, this.y / other.y);
    }

    public Vector2D div(float scalar) {
        return new Vector2D(this.x / scalar, this.y / scalar);
    }

    public Vector2D divInPlace(Vector2D other) {
        if (other.x == 0 || other.y == 0) {
            throw new IllegalArgumentException("Divide by zero.");
        }
        this.x /= other.x;
        this.y /= other.y;
        return this;
    }

    public Vector2D divInPlace(float scalar) {
        if (scalar == 0) {
            throw new IllegalArgumentException("Divide by zero.");
        }
        this.x /= scalar;
        this.y /= scalar;
        return this;
    }

    // Negation
    public Vector2D negate() {
        return new Vector2D(-this.x, -this.y);
    }

    // Distance
    public float distance(Vector2D other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public float distance2(Vector2D other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        return dx * dx + dy * dy;
    }

    public float toroidalDistance2(Vector2D other, float width, float height) {
        float dx = Math.abs(this.x - other.x);
        float dy = Math.abs(this.y - other.y);

        if (dx > width / 2) {
            dx = width - dx;
        }

        if (dy > height / 2) {
            dy = height - dy;
        }

        return dx * dx + dy * dy;
    }

    public float toroidalDistance(Vector2D other, float width, float height) {
        return (float) Math.sqrt(toroidalDistance2(other, width, height));
    }

    // Normalization and limits
    public float norm() {
        return (float) Math.sqrt(this.x * this.x + this.y * this.y);
    }

    public Vector2D normalize() {
        float magnitude = norm();
        if (magnitude != 0) {
            this.x /= magnitude;
            this.y /= magnitude;
        }
        return this;
    }

    public Vector2D limit(float max) {
        float magnitude = norm();
        if (magnitude > max) {
            this.x *= max / magnitude;
            this.y *= max / magnitude;
        }
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Vector2D vector2D = (Vector2D) obj;
        return Float.compare(vector2D.x, x) == 0 && Float.compare(vector2D.y, y) == 0;
    }
}
