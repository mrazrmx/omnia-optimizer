package com.omnia.optimizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Gráfico nativo em barras comparando a taxa ligada vs. apagada (mAh/h) ao longo dos ciclos.
 */
public class TrendChartView extends View {

    public static final class TrendPoint {
        public final String label;
        public final double rateOn;
        public final double rateOff;
        public final boolean mixed;

        public TrendPoint(String label, double rateOn, double rateOff, boolean mixed) {
            this.label = label;
            this.rateOn = rateOn;
            this.rateOff = rateOff;
            this.mixed = mixed;
        }
    }

    private final List<TrendPoint> points = new ArrayList<TrendPoint>();
    private Paint gridPaint;
    private Paint axisPaint;
    private Paint onBarPaint;
    private Paint onGlowPaint;
    private Paint offBarPaint;
    private Paint offGlowPaint;
    private Paint valuePaint;
    private Paint labelPaint;
    private Paint mixedPaint;

    public TrendChartView(Context context) {
        super(context);
        init();
    }

    public TrendChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrendChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        Context ctx = getContext();
        int onColor = OmniaTheme.color(ctx, R.color.omnia_chart_on);
        int offColor = OmniaTheme.color(ctx, R.color.omnia_chart_off);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(OmniaTheme.color(ctx, R.color.omnia_chart_grid));
        gridPaint.setStrokeWidth(1.0f * d);

        axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisPaint.setColor(OmniaTheme.color(ctx, R.color.omnia_text_muted));
        axisPaint.setTextSize(9.5f * d);
        axisPaint.setTypeface(Typeface.MONOSPACE);

        onGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        onGlowPaint.setColor(OmniaTheme.withAlpha(onColor, 0x2E));

        onBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        onBarPaint.setColor(onColor);

        offGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        offGlowPaint.setColor(OmniaTheme.withAlpha(offColor, 0x2E));

        offBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        offBarPaint.setColor(offColor);

        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setColor(OmniaTheme.color(ctx, R.color.omnia_text_primary));
        valuePaint.setTextSize(8.5f * d);
        valuePaint.setTypeface(Typeface.MONOSPACE);
        valuePaint.setTextAlign(Paint.Align.CENTER);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(OmniaTheme.color(ctx, R.color.omnia_text_secondary));
        labelPaint.setTextSize(10f * d);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        mixedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mixedPaint.setColor(OmniaTheme.color(ctx, R.color.omnia_warn_text));
        mixedPaint.setTextSize(10f * d);
        mixedPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mixedPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setCycles(List<CycleData> cyclesNewestFirst) {
        points.clear();
        if (cyclesNewestFirst != null) {
            int maxItems = Math.min(7, cyclesNewestFirst.size());
            SimpleDateFormat fmt = new SimpleDateFormat("dd/MM", Locale.US);
            for (int i = maxItems - 1; i >= 0; i--) {
                CycleData c = cyclesNewestFirst.get(i);
                String lbl = fmt.format(new Date(c.timestamp));
                points.add(new TrendPoint(lbl, Math.max(0, c.rateOnMahH), Math.max(0, c.rateOffMahH), c.isMixedWindow()));
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float d = getResources().getDisplayMetrics().density;
        float leftPad = 34f * d;
        float rightPad = 8f * d;
        float topPad = 18f * d;
        float bottomPad = 24f * d;

        float chartW = w - leftPad - rightPad;
        float chartH = h - topPad - bottomPad;
        if (chartW <= 0 || chartH <= 0) return;

        if (points.isEmpty()) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Sem ciclos para exibir tendência.", w / 2f, h / 2f, labelPaint);
            return;
        }

        double maxVal = 700.0;
        for (TrendPoint p : points) {
            maxVal = Math.max(maxVal, Math.max(p.rateOn, p.rateOff));
        }
        double scaleMax = Math.ceil(maxVal / 200.0) * 200.0;

        int steps = 4;
        for (int s = 0; s <= steps; s++) {
            float ratio = (float) s / steps;
            float y = topPad + chartH * (1f - ratio);
            canvas.drawLine(leftPad, y, w - rightPad, y, gridPaint);
            int val = (int) Math.round(scaleMax * ratio);
            canvas.drawText(String.valueOf(val), 2f * d, y + 3f * d, axisPaint);
        }

        int count = points.size();
        float slotW = chartW / count;
        float barW = Math.min(13f * d, slotW * 0.28f);
        float gap = 3f * d;
        float radius = 2.5f * d;
        float baseY = topPad + chartH;

        for (int i = 0; i < count; i++) {
            TrendPoint p = points.get(i);
            float cx = leftPad + slotW * (i + 0.5f);

            float onH = (float) Math.min(chartH, (p.rateOn / scaleMax) * chartH);
            float offH = (float) Math.min(chartH, (p.rateOff / scaleMax) * chartH);

            float onLeft = cx - barW - (gap / 2f);
            float offLeft = cx + (gap / 2f);

            if (onH > 1f) {
                RectF rOnGlow = new RectF(onLeft - 1.5f * d, baseY - onH - 1.5f * d, onLeft + barW + 1.5f * d, baseY);
                canvas.drawRoundRect(rOnGlow, radius, radius, onGlowPaint);
                RectF rOn = new RectF(onLeft, baseY - onH, onLeft + barW, baseY);
                canvas.drawRoundRect(rOn, radius, radius, onBarPaint);
                canvas.drawText(String.valueOf(Math.round(p.rateOn)), onLeft + barW / 2f, baseY - onH - 4f * d, valuePaint);
            }

            if (offH > 1f) {
                float visOffH = Math.max(3f * d, offH);
                RectF rOffGlow = new RectF(offLeft - 1.5f * d, baseY - visOffH - 1.5f * d, offLeft + barW + 1.5f * d, baseY);
                canvas.drawRoundRect(rOffGlow, radius, radius, offGlowPaint);
                RectF rOff = new RectF(offLeft, baseY - visOffH, offLeft + barW, baseY);
                canvas.drawRoundRect(rOff, radius, radius, offBarPaint);
                canvas.drawText(String.valueOf(Math.round(p.rateOff)), offLeft + barW / 2f, baseY - visOffH - 4f * d, valuePaint);
            }

            String dateLbl = p.mixed ? p.label + "*" : p.label;
            canvas.drawText(dateLbl, cx, h - 6f * d, p.mixed ? mixedPaint : labelPaint);
        }
    }
}
