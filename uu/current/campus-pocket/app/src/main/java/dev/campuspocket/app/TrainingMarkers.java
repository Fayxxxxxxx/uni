package dev.campuspocket.app;

final class TrainingMarkers {
    enum Status { DONE, READY, LOCKED, UNKNOWN }

    interface Pixels { int rgb(int x, int y); }

    private static final double[][] TICK = {
            {-.51, .01}, {-.35, .16}, {-.19, .31}, {-.02, .15}, {.16, -.04}, {.33, -.23}, {.49, -.40}
    };
    private static final float[] SOLID_RADII = {.017f, .020f, .023f};
    private static final float[] RING_RADII = {.023f, .026f, .029f};

    static Status inRow(Pixels pixels, int width, int height, int rowY) {
        if (width < 250 || rowY < width / 16 || rowY >= height - width / 16) return Status.UNKNOWN;
        int left = Math.round(width * .060f), right = Math.round(width * .122f);
        int tolerance = Math.round(width * .021f), step = Math.max(1, width / 470);
        boolean hollow = false, grey = false, tick = false;
        for (int y = rowY - tolerance; y <= rowY + tolerance; y += step) {
            for (int x = left; x <= right; x += step) {
                int center = pixels.rgb(x, y);
                if (green(center)) {
                    for (float size : RING_RADII) {
                        double radius = width * size;
                        if (greenRing(pixels, x, y, radius * .87) >= 19
                                && lightRing(pixels, x, y, radius * .59) >= 19) hollow = true;
                    }
                }
                for (float size : SOLID_RADII) {
                    double radius = width * size;
                    if (greenRing(pixels, x, y, radius * .71) >= 19
                            && hasTick(pixels, x, y, radius)) tick = true;
                    if (!grey && muted(center) && mutedRing(pixels, x, y, radius * .62) >= 21
                            && lightRing(pixels, x, y, radius * 1.42) >= 18) grey = true;
                }
            }
        }
        return hollow ? Status.READY : tick ? Status.DONE : grey ? Status.LOCKED : Status.UNKNOWN;
    }

    private static boolean hasTick(Pixels pixels, int x, int y, double radius) {
        int whites = 0, lower = 0, upper = 0;
        for (int i = 0; i < TICK.length; i++) {
            int tx = x + (int) Math.round(TICK[i][0] * radius);
            int ty = y + (int) Math.round(TICK[i][1] * radius);
            int halo = Math.max(1, (int) Math.round(radius * .065));
            boolean lit = tickLight(pixels.rgb(tx, ty)) || tickLight(pixels.rgb(tx, ty + halo))
                    || tickLight(pixels.rgb(tx, ty - halo));
            if (lit) {
                whites++;
                if (i <= 2) lower++;
                if (i >= 4) upper++;
            }
        }
        return whites >= 6 && lower >= 2 && upper >= 2;
    }

    private static int greenRing(Pixels p, int x, int y, double r) { return ring(p, x, y, r, 0); }
    private static int lightRing(Pixels p, int x, int y, double r) { return ring(p, x, y, r, 1); }
    private static int mutedRing(Pixels p, int x, int y, double r) { return ring(p, x, y, r, 2); }

    private static final double[] X = new double[24], Y = new double[24];
    static {
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI / 12;
            X[i] = Math.cos(angle);
            Y[i] = Math.sin(angle);
        }
    }

    private static int ring(Pixels pixels, int x, int y, double radius, int type) {
        int total = 0;
        for (int i = 0; i < 24; i++) {
            int rgb = pixels.rgb(x + (int) Math.round(X[i] * radius), y + (int) Math.round(Y[i] * radius));
            if (type == 0 ? green(rgb) : type == 1 ? light(rgb) : muted(rgb)) total++;
        }
        return total;
    }

    private static boolean green(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        return g > 125 && g - r > 42 && g - b > 18;
    }

    private static boolean tickLight(int rgb) {
        return (rgb >> 16 & 255) > 179 && (rgb >> 8 & 255) > 215 && (rgb & 255) > 194;
    }

    private static boolean light(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        return Math.min(r, Math.min(g, b)) > 215 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 35;
    }

    private static boolean muted(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        return r >= 190 && r <= 232 && g - r >= 2 && g - r <= 18 && b - g >= 3 && b - g <= 18;
    }
}
