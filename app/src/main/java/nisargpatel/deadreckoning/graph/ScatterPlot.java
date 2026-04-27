package nisargpatel.deadreckoning.graph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;

public class ScatterPlot {

    private final String seriesName;
    private final ArrayList<Double> xList = new ArrayList<>();
    private final ArrayList<Double> yList = new ArrayList<>();

    public ScatterPlot(String seriesName) {
        this.seriesName = seriesName;
    }

    public View getGraphView(Context context) {
        return new ScatterView(context);
    }

    public void addPoint(double x, double y) {
        xList.add(x);
        yList.add(y);
    }

    public float getLastXPoint() {
        return xList.isEmpty() ? 0f : xList.get(xList.size() - 1).floatValue();
    }

    public float getLastYPoint() {
        return yList.isEmpty() ? 0f : yList.get(yList.size() - 1).floatValue();
    }

    public void clearSet() {
        xList.clear();
        yList.clear();
    }

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
