package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.Locale;

/** A card drawn in one pass: banner, or icon plus name, or an input name; focus ring on top. */
final class Tile extends View {
    private static final float FOCUS_SCALE = 1.14f;
    private static final float MAGNIFIER_SCALE = 1.30f;

    final Item item;
    final String kind;
    private final CharSequence title;
    private final int cardW, cardH, labelH;
    private final boolean labelAlways, labelOnFocus;
    private final float radius, stroke;
    private final RectF card = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint under = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;

    Tile(Context c, Item item, String kind, CharSequence title, int cardW, int cardH,
            String labels, int accent) {
        super(c);
        this.item = item;
        this.kind = kind;
        this.title = title;
        this.cardW = cardW;
        this.cardH = cardH;
        boolean app = Catalog.APPS.equals(kind);
        labelH = app ? Ui.dp(c, 20) : 0;
        labelAlways = "always".equals(labels);
        labelOnFocus = "focus".equals(labels);
        radius = Ui.dp(c, app ? 8 : 6);
        stroke = Ui.dp(c, 2f);

        fill.setColor(app ? Ui.SURFACE : 0xFF17181B);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(stroke * 4.5f);
        halo.setColor((accent & 0x00FFFFFF) | 0x55000000);
        dot.setColor(0xFF34C759);
        inner.setColor(Ui.TEXT);
        inner.setTypeface(Ui.MEDIUM);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTextSize(Ui.dp(c, app ? 12 : (cardH < Ui.dp(c, 45) ? 12 : 14)));
        under.setColor(Ui.TEXT);
        under.setTypeface(Ui.MEDIUM);
        under.setTextAlign(Paint.Align.CENTER);
        under.setTextSize(Ui.dp(c, 11));

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(title);
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + labelH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        card.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
        sheen.setShader(new LinearGradient(0, 0, 0, cardH * 0.45f,
                0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    void setAppRowFocusState(boolean isFocusedTile, boolean rowHasFocus) {
        if (!Catalog.APPS.equals(kind)) return;
        animate().cancel();
        float targetScale = isFocusedTile ? 1.18f : (rowHasFocus ? 0.92f : 1.0f);
        float targetZ = isFocusedTile ? Ui.dp(getContext(), 14f) : 0f;
        animate().scaleX(targetScale).scaleY(targetScale)
                .translationZ(targetZ)
                .setDuration(190)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .start();
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (Catalog.APPS.equals(kind)) {
            invalidate();
            return;
        }
        animate().cancel();
        float targetScale = FOCUS_SCALE;
        float targetZ = Ui.dp(getContext(), 10f);
        if (focused) {
            setTranslationZ(Ui.dp(getContext(), 8));
            animate().scaleX(targetScale).scaleY(targetScale)
                    .translationZ(targetZ)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                    .start();
        } else {
            animate().scaleX(1f).scaleY(1f)
                    .translationZ(0f)
                    .setDuration(150)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable banner = item.banner, icon = item.icon;
        boolean isApp = Catalog.APPS.equals(kind);
        if (!isApp) {
            boolean connected = isInputConnected();
            int dev = getDeviceType();
            if (connected) {
                if (dev == 1) fill.setColor(0xFF003791); // PlayStation Royal Blue
                else if (dev == 2) fill.setColor(0xFF107C10); // Xbox Forest Green
                else if (dev == 3) fill.setColor(0xFFB81D24); // Nintendo Switch Red
                else if (dev == 4) fill.setColor(0xFF1E293B); // PC Slate
                else fill.setColor(0xFF1B324D); // Active HDMI Electric Blue/Navy
            } else {
                fill.setColor(0x66141518); // Sleek translucent dark glass when disconnected
            }
        }
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(card, fill);
        if (banner != null) {
            banner.setBounds(0, 0, cardW, cardH);
            banner.draw(canvas);
        } else if (icon != null) {
            int size = cardH * 46 / 100;
            int left = (cardW - size) / 2, top = cardH * 12 / 100;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            drawLine(canvas, inner, cardW / 2f, cardH - cardH * 13 / 100f, title);
        } else if (!isApp) {
            drawInputContent(canvas);
        }
        if (moving) canvas.drawColor(0x99000000);
        canvas.restore();

        if ((!isApp && isFocused()) || moving) {
            float half = stroke / 2;
            if (isFocused()) {
                canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius - half, radius - half, halo);
            }
            ring.setPathEffect(moving ? new DashPathEffect(new float[] {stroke * 3, stroke * 2}, 0) : null);
            canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius - half, radius - half, ring);
        }
        if (moving) drawArrows(canvas);
        boolean label = (banner != null) && (labelAlways || labelOnFocus && isFocused());
        if (labelH > 0 && label) {
            CharSequence showTxt = item.contentTitle != null ? item.contentTitle : title;
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 6), showTxt);
        }
    }

    private boolean isInputConnected() {
        return !Catalog.APPS.equals(kind) && (item.state == TvInputManager.INPUT_STATE_CONNECTED || item.state == 1);
    }

    private int getDeviceType() {
        if (Catalog.APPS.equals(kind)) return 0;
        String t = (title != null ? title.toString() : "").toLowerCase(Locale.ROOT);
        if (t.contains("playstation") || t.contains("ps5") || t.contains("ps4") || t.contains("ps3")) return 1;
        if (t.contains("xbox")) return 2;
        if (t.contains("switch") || t.contains("nintendo")) return 3;
        if (t.contains("pc") || t.contains("computer") || t.contains("laptop")) return 4;
        return 0;
    }

    private void drawInputContent(Canvas canvas) {
        boolean connected = isInputConnected();
        int dev = getDeviceType();
        float midY = cardH / 2f;
        float iconSize = Ui.dp(getContext(), 13f);
        float iconX = Ui.dp(getContext(), 12f);

        inner.setColor(connected ? 0xFFFFFFFF : 0x88F2F2F3);
        inner.setTextAlign(Paint.Align.LEFT);

        if (dev == 1) { // PlayStation
            drawPlayStationIcon(canvas, iconX + iconSize / 2f, midY, iconSize);
            float textX = iconX + iconSize + Ui.dp(getContext(), 7f);
            drawTextClipped(canvas, inner, textX, midY - (inner.ascent() + inner.descent()) / 2, title, cardW - textX - Ui.dp(getContext(), 16f));
        } else if (dev == 2) { // Xbox
            drawXboxIcon(canvas, iconX + iconSize / 2f, midY, iconSize);
            float textX = iconX + iconSize + Ui.dp(getContext(), 7f);
            drawTextClipped(canvas, inner, textX, midY - (inner.ascent() + inner.descent()) / 2, title, cardW - textX - Ui.dp(getContext(), 16f));
        } else if (dev == 3) { // Switch
            drawSwitchIcon(canvas, iconX + iconSize / 2f, midY, iconSize);
            float textX = iconX + iconSize + Ui.dp(getContext(), 7f);
            drawTextClipped(canvas, inner, textX, midY - (inner.ascent() + inner.descent()) / 2, title, cardW - textX - Ui.dp(getContext(), 16f));
        } else {
            if (connected) {
                drawHdmiIcon(canvas, iconX + iconSize / 2f, midY, iconSize);
                float textX = iconX + iconSize + Ui.dp(getContext(), 7f);
                drawTextClipped(canvas, inner, textX, midY - (inner.ascent() + inner.descent()) / 2, title, cardW - textX - Ui.dp(getContext(), 16f));
            } else {
                inner.setTextAlign(Paint.Align.CENTER);
                drawLine(canvas, inner, cardW / 2f, midY - (inner.ascent() + inner.descent()) / 2, title);
            }
        }

        if (connected) {
            float r = Ui.dp(getContext(), 3.5f);
            dot.setColor(0xFF00E676);
            canvas.drawCircle(cardW - Ui.dp(getContext(), 9f), midY, r, dot);
        }
    }

    private void drawPlayStationIcon(Canvas canvas, float cx, float cy, float size) {
        float r = size * 0.40f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 1.3f));
        float d = size * 0.28f;

        // Top: Triangle △ (Cyan)
        p.setColor(0xFF00E5FF);
        Path tri = new Path();
        tri.moveTo(cx, cy - d - r * 0.45f);
        tri.lineTo(cx - r * 0.38f, cy - d + r * 0.32f);
        tri.lineTo(cx + r * 0.38f, cy - d + r * 0.32f);
        tri.close();
        canvas.drawPath(tri, p);

        // Right: Circle ○ (Red)
        p.setColor(0xFFFF3B30);
        canvas.drawCircle(cx + d, cy, r * 0.32f, p);

        // Bottom: Cross ✕ (Blue)
        p.setColor(0xFF2979FF);
        float s = r * 0.28f;
        canvas.drawLine(cx - s, cy + d - s, cx + s, cy + d + s, p);
        canvas.drawLine(cx + s, cy + d - s, cx - s, cy + d + s, p);

        // Left: Square ▢ (Pink)
        p.setColor(0xFFFF4081);
        canvas.drawRect(cx - d - s, cy - s, cx - d + s, cy + s, p);
    }

    private void drawXboxIcon(Canvas canvas, float cx, float cy, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF107C10);
        p.setStyle(Paint.Style.FILL);
        float r = size * 0.45f;
        canvas.drawCircle(cx, cy, r, p);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 1.5f));
        float s = r * 0.55f;
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, p);
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, p);
    }

    private void drawSwitchIcon(Canvas canvas, float cx, float cy, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float w = size * 0.32f, h = size * 0.75f;
        p.setColor(0xFF00C3E3); // Blue joycon
        canvas.drawRoundRect(new RectF(cx - w - 2, cy - h / 2, cx - 2, cy + h / 2), 4, 4, p);
        p.setColor(0xFFFF4554); // Red joycon
        canvas.drawRoundRect(new RectF(cx + 2, cy - h / 2, cx + w + 2, cy + h / 2), 4, 4, p);
    }

    private void drawHdmiIcon(Canvas canvas, float cx, float cy, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF00E5FF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 1.3f));
        float w = size * 0.55f, h = size * 0.38f;
        canvas.drawRoundRect(new RectF(cx - w, cy - h, cx + w, cy + h), 3, 3, p);
    }

    private void drawTextClipped(Canvas canvas, TextPaint paint, float x, float baseline, CharSequence text, float maxW) {
        CharSequence s = TextUtils.ellipsize(text, paint, Math.max(Ui.dp(getContext(), 24f), maxW), TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, paint);
    }

    private void drawLine(Canvas canvas, TextPaint paint, float x, float baseline, CharSequence text) {
        float room = cardW - Ui.dp(getContext(), 16);
        CharSequence s = TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, paint);
    }

    private void drawArrows(Canvas canvas) {
        float h = cardH / 6f, w = h * 0.7f, mid = cardH / 2f, pad = h * 0.8f;
        inner.setStyle(Paint.Style.FILL);
        arrow.reset();
        arrow.moveTo(pad, mid);
        arrow.lineTo(pad + w, mid - h / 2);
        arrow.lineTo(pad + w, mid + h / 2);
        arrow.close();
        arrow.moveTo(cardW - pad, mid);
        arrow.lineTo(cardW - pad - w, mid - h / 2);
        arrow.lineTo(cardW - pad - w, mid + h / 2);
        arrow.close();
        canvas.drawPath(arrow, inner);
    }
}
