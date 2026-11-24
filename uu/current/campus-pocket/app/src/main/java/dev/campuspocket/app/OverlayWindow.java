package dev.campuspocket.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import java.util.EnumMap;
import java.util.Map;

final class OverlayWindow {
    enum Mode {
        CHIP("chip", 1f, .5f),
        PANEL("panel", .5f, 1f),
        PREVIEW(null, .5f, 1f);

        final String key;
        final float initialX, initialY;

        Mode(String key, float initialX, float initialY) {
            this.key = key;
            this.initialX = initialX;
            this.initialY = initialY;
        }
    }

    private final Context context;
    private final WindowManager manager;
    private final SharedPreferences preferences;
    private final Map<Mode, PointF> positions = new EnumMap<>(Mode.class);
    private FrameLayout frame;
    private WindowManager.LayoutParams layout;
    private Mode mode;
    private boolean dirty;
    private int marginX, marginY, maxX, maxY;

    OverlayWindow(Context context, WindowManager manager, SharedPreferences preferences) {
        this.context = context;
        this.manager = manager;
        this.preferences = preferences;
        for (Mode item : Mode.values()) {
            positions.put(item, new PointF(item.key == null ? item.initialX : preference(item, "x", item.initialX),
                    item.key == null ? item.initialY : preference(item, "y", item.initialY)));
        }
    }

    boolean showing() { return frame != null; }

    boolean covers(int x, int y) {
        if (frame == null) return false;
        android.graphics.Rect bounds = new android.graphics.Rect();
        return frame.getGlobalVisibleRect(bounds) && bounds.contains(x, y);
    }

    void show(View body, Mode newMode, boolean keepScreenOn) {
        savePosition();
        mode = newMode;
        Point space = availableSpace();
        int inset = Ui.dp(context, 10);
        marginX = Math.min(inset, Math.max(0, (space.x - 1) / 2));
        marginY = Math.min(inset, Math.max(0, (space.y - 1) / 2));
        int availableWidth = Math.max(1, space.x - 2 * marginX);
        int availableHeight = Math.max(1, space.y - 2 * marginY);
        if (mode != Mode.CHIP) {
            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
            body = scroll;
        }
        int widthSpec = mode == Mode.CHIP
                ? View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(Math.min(Ui.dp(context, 500), availableWidth), View.MeasureSpec.EXACTLY);
        body.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
        int width = Math.min(availableWidth, Math.max(1, body.getMeasuredWidth()));
        int height = Math.min(availableHeight, Math.max(1, body.getMeasuredHeight()));
        maxX = space.x - marginX - width;
        maxY = space.y - marginY - height;

        WindowManager.LayoutParams next = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        if (keepScreenOn) next.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        next.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            next.setFitInsetsTypes(barsAndCutout());
            next.setFitInsetsIgnoringVisibility(true);
        }
        PointF position = positions.get(mode);
        next.x = marginX + Math.round((maxX - marginX) * position.x);
        next.y = marginY + Math.round((maxY - marginY) * position.y);

        boolean add = frame == null;
        if (add) frame = new FrameLayout(context);
        layout = next;
        frame.removeAllViews();
        frame.addView(body, new FrameLayout.LayoutParams(-1, -1));
        try {
            if (add) manager.addView(frame, layout);
            else manager.updateViewLayout(frame, layout);
        } catch (RuntimeException e) {
            dismiss();
            throw e;
        }
    }

    void dismiss() {
        savePosition();
        if (frame == null) return;
        FrameLayout old = frame;
        frame = null;
        layout = null;
        try {
            manager.removeViewImmediate(old);
        } catch (IllegalArgumentException ignored) {
            // The window may already have been detached by Android.
        }
    }

    void dragHandle(View handle, boolean clickOnTap) {
        dragHandle(handle, clickOnTap, false);
    }

    void dragHandle(View handle, boolean clickOnTap, boolean snap) {
        int threshold = ViewConfiguration.get(context).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            private WindowManager.LayoutParams startWindow;
            private float downX, downY;
            private int startX, startY;
            private boolean moved, interrupted;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (layout == null || !view.isAttachedToWindow()) return false;
                    startWindow = layout;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = layout.x;
                    startY = layout.y;
                    moved = false;
                    interrupted = false;
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.setPressed(clickOnTap);
                    return true;
                }
                if (startWindow == null) return false;
                if (layout != startWindow) {
                    end(view);
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        interrupted = true;
                        view.setPressed(false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_UP:
                        if (!interrupted) {
                            float dx = event.getRawX() - downX;
                            float dy = event.getRawY() - downY;
                            if (moved || dx * dx + dy * dy >= threshold * threshold) {
                                moved = true;
                                view.setPressed(false);
                                moveTo(startX + Math.round(dx), startY + Math.round(dy));
                            }
                        }
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            boolean tap = clickOnTap && !moved && !interrupted;
                            if (snap && moved && !interrupted && layout != null) {
                                int center = marginX + (maxX - marginX) / 2;
                                moveTo(layout.x <= center ? marginX : maxX, layout.y);
                            }
                            end(view);
                            if (tap) view.performClick();
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        end(view);
                        break;
                    default:
                        break;
                }
                return true;
            }

            private void end(View view) {
                startWindow = null;
                view.setPressed(false);
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                savePosition();
            }
        });
    }

    private void moveTo(int x, int y) {
        if (frame == null || layout == null) return;
        x = Math.max(marginX, Math.min(maxX, x));
        y = Math.max(marginY, Math.min(maxY, y));
        if (layout.x == x && layout.y == y) return;
        layout.x = x;
        layout.y = y;
        try {
            manager.updateViewLayout(frame, layout);
        } catch (IllegalArgumentException e) {
            dismiss();
            return;
        }
        PointF anchor = positions.get(mode);
        if (maxX > marginX) anchor.x = (float) (x - marginX) / (maxX - marginX);
        if (maxY > marginY) anchor.y = (float) (y - marginY) / (maxY - marginY);
        dirty = true;
    }

    private float preference(Mode item, String axis, float fallback) {
        float value = preferences.getFloat("overlay_" + item.key + "_" + axis, fallback);
        return Float.isNaN(value) ? fallback : Math.max(0f, Math.min(1f, value));
    }

    private void savePosition() {
        if (!dirty) return;
        dirty = false;
        if (mode.key == null) return;
        PointF anchor = positions.get(mode);
        preferences.edit().putFloat("overlay_" + mode.key + "_x", anchor.x)
                .putFloat("overlay_" + mode.key + "_y", anchor.y).apply();
    }

    private Point availableSpace() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = manager.getCurrentWindowMetrics();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(barsAndCutout());
            int width = metrics.getBounds().width() - insets.left - insets.right;
            int height = metrics.getBounds().height() - insets.top - insets.bottom;
            if (width > 0 && height > 0) return new Point(width, height);
        }
        Point size = new Point();
        manager.getDefaultDisplay().getSize(size);
        return size;
    }

    private int barsAndCutout() {
        return WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
    }
}
