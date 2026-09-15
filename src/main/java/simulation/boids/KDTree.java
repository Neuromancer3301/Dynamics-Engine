package simulation.boids;

import java.util.ArrayList;
import java.util.List;

public class KDTree {
    
    public static class Node {
        public Node left;
        public Node right;
        public Boid boid;
        public boolean vertical;

        public Node(Boid boid, boolean vertical, Node left, Node right) {
            this.boid = boid;
            this.vertical = vertical;
            this.left = left;
            this.right = right;
        }

        public boolean isLeaf() {
            return this.left == null && this.right == null;
        }
    }

    private Node root;

    public KDTree() {
        this.root = null;
    }

    private Node insert(Node node, Boid boid, boolean vertical) {
        if (node == null) {
            return new Node(boid, vertical, null, null);
        }

        if ((node.vertical && boid.position.x < node.boid.position.x) ||
            (!node.vertical && boid.position.y < node.boid.position.y)) {
            node.left = insert(node.left, boid, !node.vertical);
        } else {
            node.right = insert(node.right, boid, !node.vertical);
        }

        return node;
    }

    public void insert(Boid boid) {
        this.root = insert(this.root, boid, true);
    }

    private void search(Boid query, double radius, Node node, List<Boid> results) {
        if (node == null) return;

        double w = query.position.distance2(node.boid.position);

        if (node.isLeaf()) {
            if (w < radius * radius) {
                results.add(node.boid);
            }
            return;
        }

        if (w < radius * radius) {
            results.add(node.boid);
        }

        double qx = query.position.x;
        double nx = node.boid.position.x;
        double qy = query.position.y;
        double ny = node.boid.position.y;

        if (node.vertical) {
            if (qx < nx) {
                if (qx - radius <= nx && node.left != null) {
                    search(query, radius, node.left, results);
                }
                if (qx + radius > nx && node.right != null) {
                    search(query, radius, node.right, results);
                    return;
                }
            } else {
                if (qx + radius > nx && node.right != null) {
                    search(query, radius, node.right, results);
                }
                if (qx - radius <= nx && node.left != null) {
                    search(query, radius, node.left, results);
                    return;
                }
            }
        } else {
            if (qy < ny) {
                if (qy - radius <= ny && node.left != null) {
                    search(query, radius, node.left, results);
                }
                if (qy + radius > ny && node.right != null) {
                    search(query, radius, node.right, results);
                    return;
                }
            } else {
                if (qy + radius > ny && node.right != null) {
                    search(query, radius, node.right, results);
                }
                if (qy - radius <= ny && node.left != null) {
                    search(query, radius, node.left, results);
                    return;
                }
            }
        }
    }

    public List<Boid> search(Boid query, double radius) {
        List<Boid> results = new ArrayList<>();
        search(query, radius, root, results);
        return results;
    }
}
