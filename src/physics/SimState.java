package physics;

public final class SimState {

    public final double   time;
    public final double[] angles;
    public final double[] angularVelocities;
    public final double[] bobX;
    public final double[] bobY;
    public final double   kineticEnergy;
    public final double   potentialEnergy;
    public final double   totalEnergy;

    public SimState(double time, double[] angles, double[] angularVelocities,
                    double[] bobX, double[] bobY,
                    double kineticEnergy, double potentialEnergy) {
        this.time              = time;
        this.angles            = angles.clone();
        this.angularVelocities = angularVelocities.clone();
        this.bobX              = bobX.clone();
        this.bobY              = bobY.clone();
        this.kineticEnergy     = kineticEnergy;
        this.potentialEnergy   = potentialEnergy;
        this.totalEnergy       = kineticEnergy + potentialEnergy;
    }

    public int getN() { return angles.length; }
}