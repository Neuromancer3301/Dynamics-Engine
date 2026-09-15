package simulation.boids;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

public class Flock {
    private final List<Boid> boids;

    public Flock() {
        this.boids = new ArrayList<>();
    }

    public Flock(Flock other) {
        this.boids = new ArrayList<>(other.boids.size());
        for (Boid b : other.boids) {
            this.boids.add(new Boid(b));
        }
    }

    public Boid get(int i) {
        return boids.get(i);
    }

    public void add(Boid boid) {
        boids.add(boid);
    }

    public void clear() {
        boids.clear();
    }

    public void removeLast() {
        if (!boids.isEmpty()) {
            boids.remove(boids.size() - 1);
        }
    }

    public void update(float windowWidth, float windowHeight, int numThreads) {
        KDTree tree = new KDTree();
        for (Boid b : boids) {
            tree.insert(b);
        }

        // We use an array of lists to store the search results
        @SuppressWarnings("unchecked")
        List<Boid>[] searchResults = new List[boids.size()];

        if (numThreads > 1) {
            ForkJoinPool customThreadPool = null;
            try {
                // If a specific number of threads is requested, use a custom ForkJoinPool
                customThreadPool = new ForkJoinPool(numThreads);
                customThreadPool.submit(() -> {
                    IntStream.range(0, boids.size()).parallel().forEach(i -> {
                        Boid b = boids.get(i);
                        searchResults[i] = tree.search(b, b.perception);
                    });

                    IntStream.range(0, boids.size()).parallel().forEach(i -> {
                        boids.get(i).update(searchResults[i]);
                    });
                }).get(); // wait for completion
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (customThreadPool != null) {
                    customThreadPool.shutdown();
                }
            }
        } else if (numThreads == 1) {
            // Sequential execution
            for (int i = 0; i < boids.size(); ++i) {
                Boid b = boids.get(i);
                searchResults[i] = tree.search(b, b.perception);
            }
            for (int i = 0; i < boids.size(); ++i) {
                boids.get(i).update(searchResults[i]);
            }
        } else {
            // Default parallel stream behavior (common pool)
            IntStream.range(0, boids.size()).parallel().forEach(i -> {
                Boid b = boids.get(i);
                searchResults[i] = tree.search(b, b.perception);
            });

            IntStream.range(0, boids.size()).parallel().forEach(i -> {
                boids.get(i).update(searchResults[i]);
            });
        }
    }

    public int size() {
        return boids.size();
    }
}
