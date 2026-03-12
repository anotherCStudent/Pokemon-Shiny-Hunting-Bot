package core;

public enum Generation {
    GEN_3("Generation 3 (FRLG / RSE)");

    private final String label;

    Generation(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}