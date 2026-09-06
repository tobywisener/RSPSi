package com.rspsi.misc;

import java.util.Random;

public class IntUtils {

    private static Random random = new Random();

    public static int randInt(int min, int max) {
        return random.nextInt((max - min) + 1) + min;
    }

    public static int percentageOf(int value, int percent) {
        return (value / 100) * percent;
    }

}
