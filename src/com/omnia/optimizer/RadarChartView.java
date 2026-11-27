package com.omnia.optimizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom 5-axis glowing pentagon radar chart inspired by hardware optimizer HUDs.
 */
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
        Context ctx = getContext();

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(OmniaTheme.color(ctx, R.color.omnia_chart_grid));
        gridPaint.setStrokeWidth(1.1f * d);

        spokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        spokePaint.setStyle(Paint.Style.STROKE);
        spokePaint.setColor(OmniaTheme.color(ctx, R.color.omnia_chart_spoke));
        spokePaint.setStrokeWidth(1.0f * d);

        int primary = OmniaTheme.color(ctx, R.color.omnia_primary);
        int currColor = OmniaTheme.color(ctx, R.color.omnia_chart_on);

        baseFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseFillPaint.setStyle(Paint.Style.FILL);
        baseFillPaint.setColor(OmniaTheme.withAlpha(primary, 0x24));

        baseStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseStrokePaint.setStyle(Paint.Style.STROKE);
        baseStrokePaint.setColor(primary);
        baseStrokePaint.setStrokeWidth(2.0f * d);
        baseStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        currFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currFillPaint.setStyle(Paint.Style.FILL);
        currFillPaint.setColor(OmniaTheme.withAlpha(currColor, 0x26));

        currGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currGlowPaint.setStyle(Paint.Style.STROKE);
        currGlowPaint.setColor(OmniaTheme.withAlpha(currColor, 0x45));
        currGlowPaint.setStrokeWidth(5.5f * d);
        currGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        currStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currStrokePaint.setStyle(Paint.Style.STROKE);
        currStrokePaint.setColor(currColor);
        currStrokePaint.setStrokeWidth(2.4f * d);
        currStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        dotOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotOuterPaint.setStyle(Paint.Style.FILL);
        dotOuterPaint.setColor(currColor);

        dotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotInnerPaint.setStyle(Paint.Style.FILL);
        dotInnerPaint.setColor(OmniaTheme.color(ctx, R.color.omnia_surface));

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(OmniaTheme.color(ctx, R.color.omnia_text_secondary));
        labelPaint.setTextSize(12f * d);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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

        // 1. Draw concentric pentagons
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

        // 2. Draw radial axis spokes and labels
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
            if (sin > 0.5f) {
                ly += 10f * d;
            }
            canvas.drawText(LABELS[i], lx, ly + 4f * d, labelPaint);
        }

        // 3. Draw baseline polygon (Média 7 ciclos)
        Path basePath = buildPolygonPath(cx, cy, radius, baselineValues);
        canvas.drawPath(basePath, baseFillPaint);
        canvas.drawPath(basePath, baseStrokePaint);

        // 4. Draw current polygon (Ciclo Atual) with neon coral glow
        Path currPath = buildPolygonPath(cx, cy, radius, currentValues);
        canvas.drawPath(currPath, currFillPaint);
        canvas.drawPath(currPath, currGlowPaint);
        canvas.drawPath(currPath, currStrokePaint);

        // 5. Draw glowing vertices on current polygon
        for (int i = 0; i < AXES; i++) {
            double angle = Math.toRadians(-90 + i * (360.0 / AXES));
            float rad = radius * currentValues[i];
            float vx = cx + (float) (rad * Math.cos(angle));
            float vy = cy + (float) (rad * Math.sin(angle));
            canvas.drawCircle(vx, vy, 4.5f * d, dotOuterPaint);
            canvas.drawCircle(vx, vy, 2.0f * d, dotInnerPaint);
        }
    }

    private Path buildPolygonPath(float cx, float cy, float radius, float[] vals) {
        Path p = new Path();
        for (int i = 0; i < AXES; i++) {
            double angle = Math.toRadians(-90 + i * (360.0 / AXES));
            float rad = radius * vals[i];
            float x = cx + (float) (rad * Math.cos(angle));
            float y = cy + (float) (rad * Math.sin(angle));
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        return p;
    }
}
