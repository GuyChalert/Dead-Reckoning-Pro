package nisargpatel.deadreckoning.gis;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.view.MotionEvent;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * Interactive overlay that lets the user drag a bounding box on the map.
 * Dragging a corner handle resizes the box; dragging inside moves the whole box.
 * Returns true from onTouchEvent only while a drag is active, allowing
 * normal map gestures when the user touches outside the box.
 */
public class BoundingBoxOverlay extends Overlay {

    private static final float HANDLE_RADIUS_DP = 20f;
    private static final float STROKE_WIDTH_DP  = 3f;

    private GeoPoint northWest;
    private GeoPoint southEast;

    private final Paint fillPaint   = new Paint();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Point pNW = new Point();
    private final Point pSE = new Point();

    private enum Drag { NONE, NW, NE, SW, SE, WHOLE }
    private Drag dragMode = Drag.NONE;
    private float lastTouchX, lastTouchY;
    private final float handleRadiusPx;

    /**
     * @param initial Initial geographic bounding box to display.
     * @param density Display density from {@link android.util.DisplayMetrics#density},
     *                used to convert DP sizes to pixels.
     */
    public BoundingBoxOverlay(BoundingBox initial, float density) {
        northWest = new GeoPoint(initial.getLatNorth(), initial.getLonWest());
        southEast = new GeoPoint(initial.getLatSouth(), initial.getLonEast());
        handleRadiusPx = HANDLE_RADIUS_DP * density;

        fillPaint.setColor(Color.argb(50, 33, 150, 243));
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(Color.rgb(33, 150, 243));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(STROKE_WIDTH_DP * density);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);

        handleRing.setColor(Color.rgb(33, 150, 243));
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(STROKE_WIDTH_DP * density);
    }

    /**
     * Returns the current bounding box after any user drag operations.
     * Always normalises so latN ≥ latS and lonE ≥ lonW regardless of corner drag direction.
     *
     * @return Current geographic bounding box in decimal degrees (WGS-84).
     */
    public BoundingBox getBoundingBox() {
        double latN = Math.max(northWest.getLatitude(),  southEast.getLatitude());
        double latS = Math.min(northWest.getLatitude(),  southEast.getLatitude());
        double lonE = Math.max(northWest.getLongitude(), southEast.getLongitude());
        double lonW = Math.min(northWest.getLongitude(), southEast.getLongitude());
        return new BoundingBox(latN, lonE, latS, lonW);
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow) return;
        Projection proj = mapView.getProjection();
        proj.toPixels(northWest, pNW);
        proj.toPixels(southEast, pSE);

        float left   = Math.min(pNW.x, pSE.x);
        float top    = Math.min(pNW.y, pSE.y);
        float right  = Math.max(pNW.x, pSE.x);
        float bottom = Math.max(pNW.y, pSE.y);

        canvas.drawRect(left, top, right, bottom, fillPaint);
        canvas.drawRect(left, top, right, bottom, strokePaint);

        drawHandle(canvas, left,  top);
        drawHandle(canvas, right, top);
        drawHandle(canvas, left,  bottom);
        drawHandle(canvas, right, bottom);
    }

    /**
     * Draws a single corner-resize handle (white circle with blue ring) at screen position.
     *
     * @param c Canvas to draw on.
     * @param x Screen X coordinate in pixels (px).
     * @param y Screen Y coordinate in pixels (px).
     */
    private void drawHandle(Canvas c, float x, float y) {
        c.drawCircle(x, y, handleRadiusPx, handlePaint);
        c.drawCircle(x, y, handleRadiusPx, handleRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event, MapView mapView) {
        float x = event.getX(), y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Projection proj = mapView.getProjection();
                proj.toPixels(northWest, pNW);
                proj.toPixels(southEast, pSE);
                dragMode = hitTest(x, y);
                if (dragMode != Drag.NONE) {
                    lastTouchX = x;
                    lastTouchY = y;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragMode == Drag.NONE) return false;
                applyDrag(x - lastTouchX, y - lastTouchY, mapView.getProjection());
                lastTouchX = x;
                lastTouchY = y;
                mapView.invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean consumed = (dragMode != Drag.NONE);
                dragMode = Drag.NONE;
                return consumed;
            }
        }
        return false;
    }

    /**
     * Determines which drag mode a touch position activates.
     * Corner handles take priority; interior box triggers WHOLE move.
     *
     * @param x Touch X in screen pixels (px).
     * @param y Touch Y in screen pixels (px).
     * @return Drag mode, or {@link Drag#NONE} if the touch is outside the overlay.
     */
    private Drag hitTest(float x, float y) {
        float left   = Math.min(pNW.x, pSE.x);
        float top    = Math.min(pNW.y, pSE.y);
        float right  = Math.max(pNW.x, pSE.x);
        float bottom = Math.max(pNW.y, pSE.y);

        float r = handleRadiusPx * 2f;
        if (near(x, y, left,  top,    r)) return Drag.NW;
        if (near(x, y, right, top,    r)) return Drag.NE;
        if (near(x, y, left,  bottom, r)) return Drag.SW;
        if (near(x, y, right, bottom, r)) return Drag.SE;
        if (x > left && x < right && y > top && y < bottom) return Drag.WHOLE;
        return Drag.NONE;
    }

    /**
     * @return {@code true} if point (ax,ay) is within {@code thresh} pixels of (bx,by).
     */
    private boolean near(float ax, float ay, float bx, float by, float thresh) {
        return Math.abs(ax - bx) < thresh && Math.abs(ay - by) < thresh;
    }

    /**
     * Translates a pixel drag delta to a geographic coordinate change and applies
     * it to the appropriate corner(s) based on {@link #dragMode}.
     * Uses two projection lookups to convert pixels to degrees accurately.
     *
     * @param dx   Horizontal drag delta in screen pixels (px); positive = right.
     * @param dy   Vertical drag delta in screen pixels (px); positive = down.
     * @param proj Current map projection for pixel-to-geo conversion.
     */
    private void applyDrag(float dx, float dy, Projection proj) {
        // Convert a pixel delta to a geo delta via two projection lookups.
        GeoPoint origin = (GeoPoint) proj.fromPixels(0, 0);
        GeoPoint shifted = (GeoPoint) proj.fromPixels((int) dx, (int) dy);
        double dLat = shifted.getLatitude()  - origin.getLatitude();
        double dLon = shifted.getLongitude() - origin.getLongitude();

        double nLat = northWest.getLatitude();
        double nLon = northWest.getLongitude();
        double sLat = southEast.getLatitude();
        double sLon = southEast.getLongitude();

        switch (dragMode) {
            case NW:
                northWest = new GeoPoint(nLat + dLat, nLon + dLon);
                break;
            case NE:
                northWest = new GeoPoint(nLat + dLat, nLon);
                southEast = new GeoPoint(sLat, sLon + dLon);
                break;
            case SW:
                northWest = new GeoPoint(nLat, nLon + dLon);
                southEast = new GeoPoint(sLat + dLat, sLon);
                break;
            case SE:
                southEast = new GeoPoint(sLat + dLat, sLon + dLon);
                break;
            case WHOLE:
                northWest = new GeoPoint(nLat + dLat, nLon + dLon);
                southEast = new GeoPoint(sLat + dLat, sLon + dLon);
                break;
            default:
                break;
        }
    }
}
