package dev.campuspocket.app;

final class TrainingVisualControls {
    static PageModel.Bounds taskCardNext(TrainingMarkers.Pixels pixels, int width, int height) {
        int start = -1;
        int step = Math.max(1, width / 450);
        for (int y = scaled(height, .65); y < scaled(height, .91); y += step) {
            // An accessibility floating bubble can cover the right dark margin
            // without covering the card's blue Next button (actual screenshot).
            // Require the left dark backdrop and both white inner margins instead.
            boolean button = dark(sample(pixels, width, .028, y))
                    && paper(sample(pixels, width, .21, y)) && paper(sample(pixels, width, .79, y))
                    && blue(sample(pixels, width, .335, y)) && blue(sample(pixels, width, .385, y))
                    && blue(sample(pixels, width, .615, y)) && blue(sample(pixels, width, .665, y));
            if (button) {
                if (start < 0) start = y;
            } else if (start >= 0) {
                if (y - start >= width * .045 && y - start <= width * .16) {
                    return new PageModel.Bounds(scaled(width, .33), start, scaled(width, .67), y);
                }
                start = -1;
            }
        }
        return null;
    }

    /** Tutorial overlay from the supplied Step 1 screenshot: small cyan-rimmed
     * blue OK button over a dimmed page, to the left below the large green tip. */
    static PageModel.Bounds tutorialOkay(TrainingMarkers.Pixels pixels, int width, int height) {
        int left = scaled(width, .15), right = scaled(width, .42);
        int top = scaled(height, .739), bottom = scaled(height, .794);
        if (top < 0 || bottom >= height || !dark(sample(pixels, width, .03, scaled(height, .76)))
                || !dark(sample(pixels, width, .90, scaled(height, .76)))) return null;
        int blueCount = 0, rimCount = 0;
        int step = Math.max(2, width / 190);
        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {
                int rgb = pixels.rgb(x, y);
                int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                if (b > 200 && b - r > 60 && b - g > 20 && g > 80) blueCount++;
                if (g > 165 && b > 130 && g - r > 40 && b - r > 30) rimCount++;
            }
        }
        if (blueCount < 40 || rimCount < 20) return null;
        return new PageModel.Bounds(scaled(width, .18), scaled(height, .744),
                scaled(width, .38), scaled(height, .785));
    }

    static PageModel.Bounds pressToTalk(TrainingMarkers.Pixels pixels, int width, int height) {
        int start = -1, longestStart = 0, longestEnd = 0;
        int step = Math.max(1, width / 500);
        for (int y = scaled(height, .75); y < scaled(height, .985); y += step) {
            boolean rim = side(pixels, width, y, .029, .083) && side(pixels, width, y, .917, .971)
                    && paper(sample(pixels, width, .29, y)) && paper(sample(pixels, width, .71, y));
            if (rim) {
                if (start < 0) start = y;
            } else if (start >= 0) {
                if (y - start > longestEnd - longestStart) {
                    longestStart = start;
                    longestEnd = y;
                }
                start = -1;
            }
        }
        if (longestEnd - longestStart < width * .052 || longestEnd - longestStart > width * .20) return null;
        int center = (longestEnd + longestStart) / 2;
        int reach = scaled(width, .099);
        return new PageModel.Bounds(scaled(width, .13), Math.max(0, center - reach),
                scaled(width, .87), Math.min(height, center + reach));
    }

    private static boolean side(TrainingMarkers.Pixels pixels, int width, int y, double start, double finish) {
        for (int x = scaled(width, start); x <= scaled(width, finish); x++) {
            int rgb = pixels.rgb(x, y);
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            if (b > 200 && b - r > 35 && b - g > 26) return true;
        }
        return false;
    }

    private static int sample(TrainingMarkers.Pixels pixels, int width, double x, int y) {
        return pixels.rgb(scaled(width, x), y);
    }

    private static int scaled(int size, double ratio) { return (int) Math.round(size * ratio); }

    private static boolean dark(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        return r >= 35 && r <= 125 && g <= 135 && b <= 150 && Math.abs(b - r) <= 44;
    }

    private static boolean paper(int rgb) {
        return (rgb >> 16 & 255) > 222 && (rgb >> 8 & 255) > 222 && (rgb & 255) > 222;
    }

    private static boolean blue(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        return b > 205 && b - r > 75 && b - g > 48 && g > 66;
    }
}
