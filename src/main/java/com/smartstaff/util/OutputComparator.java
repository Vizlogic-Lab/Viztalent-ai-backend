package com.smartstaff.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Compares program output with a test's expected output: line endings are
 *  normalised, trailing whitespace on each line and trailing blank lines are
 *  ignored. With a float tolerance, whitespace-separated tokens that both
 *  parse as numbers may differ by at most that much. */
public final class OutputComparator {

    private OutputComparator() {}

    public static boolean matches(String expected, String actual, BigDecimal floatTolerance) {
        String e = normalise(expected);
        String a = normalise(actual);
        if (e.equals(a)) return true;
        if (floatTolerance == null) return false;

        String[] et = e.split("\\s+");
        String[] at = a.split("\\s+");
        if (et.length != at.length) return false;
        double tol = floatTolerance.doubleValue();
        for (int i = 0; i < et.length; i++) {
            if (et[i].equals(at[i])) continue;
            Double x = parse(et[i]);
            Double y = parse(at[i]);
            if (x == null || y == null || Double.isNaN(x) || Double.isNaN(y) || Math.abs(x - y) > tol) return false;
        }
        return true;
    }

    public static String normalise(String output) {
        if (output == null) return "";
        String[] lines = output.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) out.add(line.stripTrailing());
        int end = out.size();
        while (end > 0 && out.get(end - 1).isEmpty()) end--;
        return String.join("\n", out.subList(0, end));
    }

    private static Double parse(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
