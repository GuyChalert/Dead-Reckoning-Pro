package nisargpatel.deadreckoning.gis;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * Draws a georeferenced bitmap as a ground overlay on the osmdroid MapView.
 * The bitmap is stretched to cover the given bounding box at all zoom levels.
 */
public class RasterOverlay extends Overlay {

    private final Bitmap bitmap;
    private final BoundingBox bounds;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Point reuse = new Point();

    public RasterOverlay(Bitmap bitmap, BoundingBox bounds) {
        this.bitmap = bitmap;
        this.bounds = bounds;
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || bitmap == null || bitmap.isRecycled()) return;

        Projection proj = mapView.getProjection();

        proj.toPixels(new GeoPoint(bounds.getLatNorth(), bounds.getLonWest()), reuse);
        float left = reuse.x;
        float top  = reuse.y;

        proj.toPixels(new GeoPoint(bounds.getLatSouth(), bounds.getLonEast()), reuse);
        float right  = reuse.x;
        float bottom = reuse.y;

        canvas.drawBitmap(bitmap,
                new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                new RectF(left, top, right, bottom),
                paint);
    }

    public void recycle() {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
