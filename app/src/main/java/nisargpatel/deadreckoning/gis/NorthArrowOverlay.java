package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.MotionEvent;

import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * Compass-style north arrow in the top-right corner.
 * Rotates counter to map orientation so the red tip always points geographic north.
 * Single tap resets map orientation to 0 (north-up).
 */
public class NorthArrowOverlay extends Overlay {

    private static final int SIZE_DP   = 56;
    private static final int MARGIN_DP = 16;

    private final int sizePx;
    private final int marginPx;
    private final MapView mapView;

    private final Paint circlePaint;
    private final Paint ringPaint;
    private final Paint shadowPaint;
    private final Paint redPaint;
    private final Paint whitePaint;
    private final Paint centerPaint;
    private final Paint textPaint;
    private final Path  arrowPath = new Path();

    private int cx, cy;

    /**
     * @param context Android context used to resolve display density.
     * @param mapView The parent map view; used to read current map orientation (°).
     */
    public NorthArrowOverlay(Context context, MapView mapView) {
        this.mapView = mapView;
        float d = context.getResources().getDisplayMetrics().density;
        sizePx   = (int) (SIZE_DP   * d);
        marginPx = (int) (MARGIN_DP * d);

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.argb(200, 18, 18, 28));
        circlePaint.setStyle(Paint.Style.FILL);

        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setColor(Color.argb(160, 255, 255, 255));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.2f * d);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.argb(100, 0, 0, 0));
        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStrokeWidth(1.5f * d);

        redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        redPaint.setColor(Color.rgb(230, 30, 30));
        redPaint.setStyle(Paint.Style.FILL);

        whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        whitePaint.setColor(Color.rgb(230, 230, 230));
        whitePaint.setStyle(Paint.Style.FILL);

        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setColor(Color.argb(220, 255, 255, 255));
        centerPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(10 * d);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Draws the north-arrow compass widget in the bottom-left corner of the map.
     * Undoes osmdroid's canvas pre-rotation so the widget stays in a fixed screen
     * position, then counter-rotates the arrow so the red tip always points north.
     */
    @Override
    public void draw(Canvas canvas, Projection projection) {
        float rotation = mapView.getMapOrientation();
        float halfW = projection.getScreenCenterX();
        float halfH = projection.getScreenCenterY();

        int r = sizePx / 2;
        cx = marginPx + r;
        cy = projection.getHeight() - marginPx - r;

        // osmdroid pre-rotates canvas by -rotation around screen center before calling draw().
        // Un-rotate to draw in fixed screen-space position.
        canvas.save();
        canvas.rotate(rotation, halfW, halfH);

        canvas.drawCircle(cx, cy, r, circlePaint);
        canvas.drawCircle(cx, cy, r - 1, ringPaint);

        // Arrow: counter-rotate so the red tip always points to geographic north
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(-rotation);

        int ar = (int) (r * 0.60f);
        float hw = ar * 0.32f;

        // Red (north) half
        arrowPath.reset();
        arrowPath.moveTo(0, -ar);
        arrowPath.lineTo(-hw, 0);
        arrowPath.lineTo(hw, 0);
        arrowPath.close();
        canvas.drawPath(arrowPath, redPaint);
        canvas.drawPath(arrowPath, shadowPaint);

        // White (south) half
        arrowPath.reset();
        arrowPath.moveTo(0, ar);
        arrowPath.lineTo(-hw, 0);
        arrowPath.lineTo(hw, 0);
        arrowPath.close();
        canvas.drawPath(arrowPath, whitePaint);
        canvas.drawPath(arrowPath, shadowPaint);

        // Center dot
        canvas.drawCircle(0, 0, hw * 0.45f, centerPaint);

        // "N" label above red tip
        canvas.drawText("N", 0, -ar - textPaint.getTextSize() * 0.15f, textPaint);

        canvas.restore();
        canvas.restore();
    }

    /**
     * Resets map orientation to 0° (north-up) when the user taps inside the arrow widget.
     *
     * @return {@code true} if the tap was inside the widget and consumed; {@code false} otherwise.
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {
        float dx = e.getX() - cx;
        float dy = e.getY() - cy;
        if (dx * dx + dy * dy <= (sizePx / 2f) * (sizePx / 2f)) {
            mapView.setMapOrientation(0f, true);
            return true;
        }
        return false;
    }
}
