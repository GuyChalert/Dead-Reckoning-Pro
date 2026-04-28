package nisargpatel.deadreckoning.graph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;

/**
 * 2-D scatter plot backed by a custom {@link android.view.View}.
 * Axes are auto-scaled to the next round-100 boundary above the maximum absolute value.
 */
public class ScatterPlot {

    private final String seriesName;
    private final ArrayList<Double> xList = new ArrayList<>();
    private final ArrayList<Double> yList = new ArrayList<>();

    /**
     * @param seriesName Title displayed at the top of the plot canvas.
     */
    public ScatterPlot(String seriesName) {
        this.seriesName = seriesName;
    }

    /** @return A {@link View} that renders this scatter plot; add it to a container layout. */
    public View getGraphView(Context context) {
        return new ScatterView(context);
    }

    /**
     * Appends a data point to the series.
     *
     * @param x Horizontal axis value.
     * @param y Vertical axis value.
     */
    public void addPoint(double x, double y) {
        xList.add(x);
        yList.add(y);
    }

    /** @return X value of the most recently added point, or 0 if the series is empty. */
    public float getLastXPoint() {
        return xList.isEmpty() ? 0f : xList.get(xList.size() - 1).floatValue();
    }

    /** @return Y value of the most recently added point, or 0 if the series is empty. */
    public float getLastYPoint() {
        return yList.isEmpty() ? 0f : yList.get(yList.size() - 1).floatValue();
    }

    /** Removes all data points from the series. */
    public void clearSet() {
        xList.clear();
        yList.clear();
    }

    /** Computes the axis bound as the next multiple of 100 above the maximum absolute data value. */
    private double getMaxBound() {
        double max = 1;
        for (double v : xList) if (Math.abs(v) > max) max = Math.abs(v);
        for (double v : yList) if (Math.abs(v) > max) max = Math.abs(v);
        return (Math.floor(max / 100) + 1) * 100;
    }

    private class ScatterView extends View {
        private final Paint dotPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ScatterView(Context ctx) {
            super(ctx);
            dotPaint.setColor(0xff0099ff);
            dotPaint.setStyle(Paint.Style.FILL);
            axisPaint.setColor(Color.DKGRAY);
            axisPaint.setStrokeWidth(2f);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(40f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float scale = (float)(Math.min(w, h) / 2.0 / getMaxBound());

            // axes
            canvas.drawLine(0, cy, w, cy, axisPaint);
            canvas.drawLine(cx, 0, cx, h, axisPaint);

            // title
            canvas.drawText(seriesName, cx, textPaint.getTextSize() + 10, textPaint);

            // points
            for (int i = 0; i < xList.size(); i++) {
                float px = cx + xList.get(i).floatValue() * scale;
                float py = cy - yList.get(i).floatValue() * scale;
                canvas.drawCircle(px, py, 10f, dotPaint);
            }
        }
    }
}
