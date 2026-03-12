package core;

public enum HuntMethodType {
    SOFT_RESET("Soft Reset / Legendary"),
    SPIN_METHOD("Spin in Place / Re-encounter"),
    EGG_HATCH("Egg Hatch / Breeding"),
    STARTER_RSE("Starter Pokémon (RSE)"),
    STARTER_FRLG("Starter Pokémon (FRLG)");

    private final String label;

    HuntMethodType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}