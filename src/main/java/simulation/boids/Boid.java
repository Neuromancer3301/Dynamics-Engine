package simulation.boids;

import java.util.List;

public class Boid {
    private static final float PREDATOR_ESCAPE_FACTOR = 10000000f;
    private static final float PREDATOR_SPEED_BOOST = 1.8f;
    private static final float PREDATOR_PERCEPTION_BOOST = 1.5f;
    private static final float PREDATOR_ACCELERATION_BOOST = 1.4f;

    public Vector2D position;
    public Vector2D velocity;
    public Vector2D acceleration;

    public float maxWidth;
    public float maxHeight;
    public float maxSpeed;
    public float maxForce;
    public float accelerationScale;
    public float cohesionWeight;
    public float alignmentWeight;
    public float separationWeight;
    public float perception;
    public float separationDistance;
    public float noiseScale;
    public boolean isPredator;

    public Boid(float x, float y, float maxWidth, float maxHeight, float maxSpeed, float maxForce,
                float accelerationScale, float cohesionWeight, float alignmentWeight, float separationWeight,
                float perception, float separationDistance, float noiseScale, boolean isPredator) {
        
        this.position = new Vector2D(x, y);
        this.velocity = Vector2D.random().subInPlace(0.5f).multInPlace(maxSpeed * 2);
        this.acceleration = new Vector2D(0, 0);

        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.maxSpeed = maxSpeed;
        this.maxForce = maxForce;
        this.accelerationScale = accelerationScale;
        this.cohesionWeight = cohesionWeight;
        this.alignmentWeight = alignmentWeight;
        this.separationWeight = separationWeight;
        this.perception = perception;
        this.separationDistance = separationDistance;
        this.noiseScale = noiseScale;
        this.isPredator = isPredator;

        if (isPredator) {
            this.maxSpeed *= PREDATOR_SPEED_BOOST;
            this.perception *= PREDATOR_PERCEPTION_BOOST;
        }
    }

    public Boid(Boid other) {
        this.position = new Vector2D(other.position);
        this.velocity = new Vector2D(other.velocity);
        this.acceleration = new Vector2D(other.acceleration);
        this.maxWidth = other.maxWidth;
        this.maxHeight = other.maxHeight;
        this.maxSpeed = other.maxSpeed;
        this.maxForce = other.maxForce;
        this.accelerationScale = other.accelerationScale;
        this.cohesionWeight = other.cohesionWeight;
        this.alignmentWeight = other.alignmentWeight;
        this.separationWeight = other.separationWeight;
        this.perception = other.perception;
        this.separationDistance = other.separationDistance;
        this.noiseScale = other.noiseScale;
        this.isPredator = other.isPredator;
    }

    public Vector2D alignment(List<Boid> boids) {
        Vector2D perceivedVelocity = new Vector2D();
        int n = 0;

        for (Boid b : boids) {
            if (this != b) {
                if (b.isPredator) {
                    return new Vector2D();
                }
                perceivedVelocity.addInPlace(b.velocity);
                n++;
            }
        }

        if (n == 0) return new Vector2D();

        perceivedVelocity.divInPlace(n);
        Vector2D steer = perceivedVelocity.subInPlace(velocity);
        return steer.normalize();
    }

    public Vector2D cohesion(List<Boid> boids) {
        Vector2D perceivedCenter = new Vector2D();
        int n = 0;

        for (Boid b : boids) {
            if (this != b) {
                if (b.isPredator) {
                    return new Vector2D();
                }
                perceivedCenter.addInPlace(b.position);
                n++;
            }
        }

        if (n == 0) return new Vector2D();

        perceivedCenter.divInPlace(n);
        Vector2D steer = perceivedCenter.subInPlace(position);
        return steer.normalize();
    }

    public Vector2D separation(List<Boid> boids) {
        Vector2D c = new Vector2D();

        for (Boid b : boids) {
            if (this != b) {
                if (!isPredator && b.isPredator) {
                    return b.position.sub(position).normalize().multInPlace(-PREDATOR_ESCAPE_FACTOR);
                } else if (isPredator == b.isPredator &&
                           position.distance2(b.position) < separationDistance * separationDistance) {
                    c.subInPlace(b.position.sub(position));
                }
            }
        }

        return c.normalize();
    }

    public void update(List<Boid> boids) {
        Vector2D alignmentUpdate = alignment(boids).multInPlace(alignmentWeight);
        Vector2D cohesionUpdate = cohesion(boids).multInPlace(cohesionWeight);
        Vector2D separationUpdate = separation(boids).multInPlace(separationWeight);

        acceleration.addInPlace(alignmentUpdate).addInPlace(cohesionUpdate).addInPlace(separationUpdate);

        if (isPredator) {
            acceleration.multInPlace(PREDATOR_ACCELERATION_BOOST);
        }
        acceleration.multInPlace(accelerationScale);
        acceleration.limit(maxForce);

        velocity.addInPlace(acceleration);

        if (noiseScale != 0) {
            velocity.addInPlace(Vector2D.random().subInPlace(0.5f).multInPlace(noiseScale));
        }

        velocity.limit(maxSpeed);
        
        // Prevent permanent freeze if forces are 0 but maxSpeed is restored
        if (velocity.x == 0 && velocity.y == 0 && maxSpeed > 0) {
            velocity.addInPlace(Vector2D.random().subInPlace(0.5f).multInPlace(maxSpeed * 0.1f));
        }
        
        position.addInPlace(velocity);

        // Reset acceleration
        acceleration.multInPlace(0);

        // Bounce off screen edges
        if (position.x < 0) {
            position.x = 0;
            velocity.x *= -1;
        } else if (position.x >= maxWidth) {
            position.x = maxWidth - 1;
            velocity.x *= -1;
        }

        if (position.y < 0) {
            position.y = 0;
            velocity.y *= -1;
        } else if (position.y >= maxHeight) {
            position.y = maxHeight - 1;
            velocity.y *= -1;
        }
    }

    public float angle() {
        return (float) (Math.atan2(velocity.x, -velocity.y) * 180 / Math.PI);
    }
}
