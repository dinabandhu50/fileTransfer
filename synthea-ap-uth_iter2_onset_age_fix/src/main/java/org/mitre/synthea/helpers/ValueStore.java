package org.mitre.synthea.helpers;

public class ValueStore {
    private static int population;
    private static String state;

    public static void setValues(int pop, String st) {
        population = pop;
        state = st;
    }

    public static int getPopulation() {
        return population;
    }

    public static String getState() {
        return state;
    }
}
