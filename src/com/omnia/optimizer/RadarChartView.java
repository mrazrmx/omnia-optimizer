package com.omnia.optimizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

public class RadarChartView extends View {
    private static final int AXES = 5;
    private static final int RINGS = 5;
    private static final String[] LABELS = {"Tela", "Repouso", "Rede", "Térmica", "Sistema"};

    private float[] currentValues = new float[]{0.80f, 0.72f, 0.90f, 0.80f, 0.85f};
    private float[] baselineValues = new float[]{0.75f, 0.65f, 0.75f, 0.82f, 0.80f};

    private Paint gridPaint;
    private Paint spokePaint;
    private Paint baseFillPaint;
    private Paint baseStrokePaint;
    private Paint currFillPaint;
    private Paint currGlowPaint;
    private Paint currStrokePaint;
    private Paint dotOuterPaint;
    private Paint dotInnerPaint;
    private Paint labelPaint;

    public RadarChartView(Context context) {
        super(context);
        init();
    }

    public RadarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RadarChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(Color.parseColor("#243B61"));
        gridPaint.setStrokeWidth(1.1f * d);

        spokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        spokePaint.setStyle(Paint.Style.STROKE);
        spokePaint.setColor(Color.parseColor("#1D3152"));
        spokePaint.setStrokeWidth(1.0f * d);

        baseFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseFillPaint.setStyle(Paint.Style.FILL);
        baseFillPaint.setColor(Color.parseColor("#243B82F6"));

        baseStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseStrokePaint.setStyle(Paint.Style.STROKE);
        baseStrokePaint.setColor(Color.parseColor("#3B82F6"));
        baseStrokePaint.setStrokeWidth(2.0f * d);
        baseStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        currFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currFillPaint.setStyle(Paint.Style.FILL);
        currFillPaint.setColor(Color.parseColor("#26F43F5E"));

        currGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currGlowPaint.setStyle(Paint.Style.STROKE);
        currGlowPaint.setColor(Color.parseColor("#55FF4D6D"));
        currGlowPaint.setStrokeWidth(6.5f * d);
        currGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        currStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currStrokePaint.setStyle(Paint.Style.STROKE);
        currStrokePaint.setColor(Color.parseColor("#FF4D6D"));
        currStrokePaint.setStrokeWidth(2.4f * d);
        currStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        dotOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotOuterPaint.setStyle(Paint.Style.FILL);
        dotOuterPaint.setColor(Color.parseColor("#FF4D6D"));

        dotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotInnerPaint.setStyle(Paint.Style.FILL);
        dotInnerPaint.setColor(Color.parseColor("#FFF1F2"));

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.parseColor("#CBD5E1"));
        labelPaint.setTextSize(11.5f * d);
        labelPaint.setTypeface(Typeface.MONOSPACE);
    }

    public void setScores(float[] current, float[] baseline) {
        if (current != null && current.length == AXES) {
            for (int i = 0; i < AXES; i++) {
                currentValues[i] = Math.max(0.12f, Math.min(1.0f, current[i]));
            }
        }
        if (baseline != null && baseline.length == AXES) {
            for (int i = 0; i < AXES; i++) {
                baselineValues[i] = Math.max(0.12f, Math.min(1.0f, baseline[i]));
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
        float cx = w / 2f;
        float cy = h / 2f + 4f * d;
        float radius = Math.min(w, h) * 0.34f;

        Path ringPath = new Path();
        for (int r = 1; r <= RINGS; r++) {
            float rad = radius * ((float) r / RINGS);
            ringPath.reset();
            for (int i = 0; i < AXES; i++) {
                double angle = Math.toRadians(-90 + i * (360.0 / AXES));
                float x = cx + (float) (rad * Math.cos(angle));
                float y = cy + (float) (rad * Math.sin(angle));
                if (i == 0) ringPath.moveTo(x, y);
                else ringPath.lineTo(x, y);
            }
            ringPath.close();
            canvas.drawPath(ringPath, gridPaint);
        }

        for (int i = 0; i < AXES; i++) {
            double angle = Math.toRadians(-90 + i * (360.0 / AXES));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float ex = cx + radius * cos;
            float ey = cy + radius * sin;
            canvas.drawLine(cx, cy, ex, ey, spokePaint);

            float lx = cx + (radius + 18f * d) * cos;
            float ly = cy + (radius + 18f * d) * sin;
            if (Math.abs(cos) < 0.15f) {
                labelPaint.setTextAlign(Paint.Align.CENTER);
                ly -= 2f * d;
            } else if (cos > 0) {
                labelPaint.setTextAlign(Paint.Align.LEFT);
            } else {
                labelPaint.setTextAlign(Paint.Align.RIGHT);
            }
            if (sin > 0.35f) {
                ly += 8f * d;
            }
            canvas.drawText(LABELS[i], lx, ly + 4f * d, labelPaint);
        }

        drawPolygon(canvas, cx, cy, radius, baselineValues, baseFillPaint, baseStrokePaint, null, false, d);
        drawPolygon(canvas, cx, cy, radius, currentValues, currFillPaint, currStrokePaint, currGlowPaint, true, d);
    }

    private void drawPolygon(Canvas canvas, float cx, float cy, float radius, float[] vals,
                             Paint fillPaint, Paint strokePaint, Paint glowPaint, boolean drawDots, float d) {
        Path p = new Path();
        float[] xs = new float[AXES];
        float[] ys = new float[AXES];
        for (int i = 0; i < AXES; i++) {
            double angle = Math.toRadians(-90 + i * (360.0 / AXES));
            float len = radius * vals[i];
            xs[i] = cx + (float) (len * Math.cos(angle));
            ys[i] = cy + (float) (len * Math.sin(angle));
            if (i == 0) p.moveTo(xs[i], ys[i]);
            else p.lineTo(xs[i], ys[i]);
        }
        p.close();
        canvas.drawPath(p, fillPaint);
        if (glowPaint != null) {
            canvas.drawPath(p, glowPaint);
        }
        canvas.drawPath(p, strokePaint);
        if (drawDots) {
            for (int i = 0; i < AXES; i++) {
                canvas.drawCircle(xs[i], ys[i], 3.8f * d, dotOuterPaint);
                canvas.drawCircle(xs[i], ys[i], 1.7f * d, dotInnerPaint);
            }
        }
    }
}
