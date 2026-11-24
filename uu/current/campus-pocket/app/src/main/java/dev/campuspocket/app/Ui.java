package dev.campuspocket.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xfff8f8fc, WHITE = 0xffffffff, INK = 0xff1b1832, SUB = 0xff5c5976;
    static final int ACCENT = 0xff4c3fc8, PALE = 0xffeeeaff, BORDER = 0xffe8e5f1;
    static final int DARK = 0xff252044, LILAC = 0xffd8d0ff, GREEN = 0xff23664e, MINT = 0xffe3f5eb;
    static final int APRICOT = 0xfffcecdf, AMBER = 0xff83421f;

    static int dp(Context c, float x) {
        return Math.round(x * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context c, int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(c, radius));
        if (border != 0) drawable.setStroke(dp(c, 1), border);
        return drawable;
    }

    static RippleDrawable ripple(Context c, int fill, int round, int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x221b1832), shape(c, fill, round, border),
                shape(c, WHITE, round, 0));
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(c, 3), 1);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView button(Context c, String name, boolean primary, Runnable action) {
        TextView v = text(c, name, 14, primary ? WHITE : ACCENT, true);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(c, 48));
        v.setPadding(dp(c, 13), dp(c, 10), dp(c, 13), dp(c, 10));
        v.setBackground(ripple(c, primary ? ACCENT : PALE, 14, 0));
        v.setFocusable(true);
        v.setOnClickListener(w -> action.run());
        return v;
    }

    static void pad(View v, int h, int top, int bottom) {
        Context c = v.getContext();
        v.setPadding(dp(c, h), dp(c, top), dp(c, h), dp(c, bottom));
    }

    static void gap(LinearLayout l, int h) {
        l.addView(new View(l.getContext()), new LinearLayout.LayoutParams(1, dp(l.getContext(), h)));
    }

    static void spaceX(LinearLayout l, int w) {
        l.addView(new View(l.getContext()), new LinearLayout.LayoutParams(dp(l.getContext(), w), 1));
    }

    static void stretch(LinearLayout l, View v) {
        l.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    }

    static void rule(LinearLayout l) {
        View line = new View(l.getContext());
        line.setBackgroundColor(BORDER);
        l.addView(line, new LinearLayout.LayoutParams(-1, dp(l.getContext(), 1)));
    }

    static LinearLayout card(Context c) {
        LinearLayout v = column(c);
        v.setBackground(shape(c, WHITE, 21, BORDER));
        pad(v, 16, 17, 17);
        return v;
    }

    static EditText field(Context c, String hint, String value, boolean multiline) {
        EditText input = new EditText(c);
        input.setTextColor(INK);
        input.setHintTextColor(SUB);
        input.setHint(hint);
        input.setText(value);
        input.setTextSize(15);
        input.setBackground(shape(c, BG, 13, BORDER));
        if (multiline) {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(3);
            input.setMaxLines(10);
        } else {
            input.setSingleLine(true);
        }
        input.setMinHeight(dp(c, 48));
        pad(input, 13, 11, 11);
        return input;
    }

    static LinearLayout iconBox(Context c, String type, int fg, int bg, int size) {
        LinearLayout box = row(c);
        box.setGravity(Gravity.CENTER);
        box.setBackground(shape(c, bg, 12, 0));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
        box.addView(new Icon(c, type, fg), new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        return box;
    }

    static final class Icon extends View {
        private final String type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Context c, String type, int color) {
            super(c);
            this.type = type;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            c.scale(getWidth() / 24f, getHeight() / 24f);
            switch (type) {
                case "home":
                    path(c, 3,11, 12,3, 21,11);
                    path(c, 5,10, 5,21, 10,21, 10,15, 14,15, 14,21, 19,21, 19,10);
                    break;
                case "book":
                    path(c, 12,6, 8,4, 3,5, 3,18, 8,17, 12,20, 16,17, 21,18, 21,5, 16,4, 12,6, 12,20);
                    break;
                case "headphones":
                    c.drawArc(3, 3, 21, 21, 180, 180, false, paint);
                    c.drawRoundRect(3, 12, 7, 20, 2, 2, paint);
                    c.drawRoundRect(17, 12, 21, 20, 2, 2, paint);
                    break;
                case "mic":
                    c.drawRoundRect(9, 3, 15, 15, 3, 3, paint);
                    c.drawArc(5.5f, 7, 18.5f, 19.5f, 0, 180, false, paint);
                    path(c, 12,20, 12,22);
                    path(c, 9,22, 15,22);
                    break;
                case "list":
                    path(c, 3,6, 5,8, 8,4);
                    path(c, 11,6, 21,6);
                    path(c, 3,13, 5,15, 8,11);
                    path(c, 11,13, 21,13);
                    path(c, 11,20, 21,20);
                    break;
                case "wave":
                    path(c, 3,12, 6,12, 8,7, 11,17, 14,5, 17,14, 20,12, 22,12);
                    break;
                case "tune":
                    path(c, 3,6, 7,6); path(c, 11,6, 21,6);
                    path(c, 3,12, 13,12); path(c, 17,12, 21,12);
                    path(c, 3,18, 8,18); path(c, 12,18, 21,18);
                    c.drawCircle(9, 6, 2, paint); c.drawCircle(15, 12, 2, paint); c.drawCircle(10, 18, 2, paint);
                    break;
                case "arrow":
                    path(c, 6,12, 18,12); path(c, 13,7, 18,12, 13,17);
                    break;
                case "spark":
                    path(c, 12,2, 14.5f,9, 22,12, 14.5f,14.5f, 12,22, 9.5f,14.5f, 2,12, 9.5f,9, 12,2);
                    break;
                default:
                    c.drawCircle(12, 12, 8, paint);
            }
        }

        private void path(Canvas canvas, float... xy) {
            Path p = new Path();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            canvas.drawPath(p, paint);
        }
    }
}
