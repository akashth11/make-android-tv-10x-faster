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
        radius = Ui.dp(c, app ? 8 : 5);
        stroke = Ui.dp(c, 2f);

        fill.setColor(app ? Ui.SURFACE : 0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(stroke * 4.5f);
        halo.setColor((accent & 0x00FFFFFF) | 0x55000000);
        dot.setColor(0xFF00E676);
        inner.setColor(app ? Ui.TEXT : 0xFF111111);
        inner.setTypeface(Ui.MEDIUM);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTextSize(Ui.dp(c, app ? 12 : 11));
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

    void setRowFocusState(boolean isFocusedTile, boolean rowHasFocus) {
        animate().cancel();
        boolean isApp = Catalog.APPS.equals(kind);
        float zoomScale = isApp ? 1.18f : 1.15f;
        float shrinkScale = isApp ? 0.92f : 0.94f;
        float targetScale = isFocusedTile ? zoomScale : (rowHasFocus ? shrinkScale : 1.0f);
        float targetZ = isFocusedTile ? Ui.dp(getContext(), isApp ? 14f : 10f) : 0f;
        animate().scaleX(targetScale).scaleY(targetScale)
                .translationZ(targetZ)
                .setDuration(190)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .start();
        invalidate();
    }

    void setAppRowFocusState(boolean isFocusedTile, boolean rowHasFocus) {
        setRowFocusState(isFocusedTile, rowHasFocus);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable banner = item.banner, icon = item.icon;
        boolean isApp = Catalog.APPS.equals(kind);
        boolean connected = !isApp && isInputConnected();

        if (!isApp) {
            // Simple white box background
            fill.setColor(0xFFFFFFFF);
        }

        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(card, fill);
        if (isApp) {
            if (banner != null) {
                banner.setBounds(0, 0, cardW, cardH);
                banner.draw(canvas);
            } else if (icon != null) {
                int size = cardH * 46 / 100;
                int left = (cardW - size) / 2, top = cardH * 12 / 100;
                icon.setBounds(left, top, left + size, top + size);
                icon.draw(canvas);
                drawLine(canvas, inner, cardW / 2f, cardH - cardH * 13 / 100f, title);
            }
        } else {
            // Crisp black font text centered inside clean white box
            inner.setColor(0xFF111111);
            inner.setTextAlign(Paint.Align.CENTER);
            float textY = cardH / 2f - (inner.ascent() + inner.descent()) / 2f;
            drawLine(canvas, inner, cardW / 2f, textY, title);
        }
        if (moving) canvas.drawColor(0x99000000);
        canvas.restore();

        // Subtle ambient neon/emerald glow when an active device is connected
        if (connected) {
            float glowW = Ui.dp(getContext(), 2.2f);
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setStyle(Paint.Style.STROKE);
            // Ambient soft halo
            glow.setStrokeWidth(glowW * 1.6f);
            glow.setColor(0x4000E676);
            canvas.drawRoundRect(0, 0, cardW, cardH, radius, radius, glow);
            // Crisp edge glow
            glow.setStrokeWidth(glowW);
            glow.setColor(0xA000E676);
            float halfG = glowW / 2f;
            canvas.drawRoundRect(halfG, halfG, cardW - halfG, cardH - halfG, radius, radius, glow);

            // Subtle green active indicator dot on the right edge
            float r = Ui.dp(getContext(), 2.2f);
            dot.setColor(0xFF00E676);
            canvas.drawCircle(cardW - Ui.dp(getContext(), 7f), cardH / 2f, r, dot);
        }

        // NO hover boundary: only draw dashed ring when moving/reordering tiles in edit mode
        if (moving) {
            float half = stroke / 2;
            ring.setPathEffect(new DashPathEffect(new float[] {stroke * 3, stroke * 2}, 0));
            canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius - half, radius - half, ring);
            drawArrows(canvas);
        }

        boolean label = (banner != null) && (labelAlways || labelOnFocus && isFocused());
        if (labelH > 0 && label) {
            CharSequence showTxt = item.contentTitle != null ? item.contentTitle : title;
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 6), showTxt);
        }
    }

    private boolean isInputConnected() {
        return !Catalog.APPS.equals(kind) && item != null && item.state == TvInputManager.INPUT_STATE_CONNECTED;
    }

    private void drawLine(Canvas canvas, TextPaint paint, float x, float baseline, CharSequence text) {
        float room = cardW - Ui.dp(getContext(), 10);
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
