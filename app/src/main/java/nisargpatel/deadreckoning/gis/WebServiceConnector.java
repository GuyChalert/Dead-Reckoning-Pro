package nisargpatel.deadreckoning.gis;

import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches and parses WMS/WMTS GetCapabilities on a background thread,
 * delivering the result on the main thread.
 */
public class WebServiceConnector {

    /** Callback for GetCapabilities results; always delivered on the main thread. */
    public interface FetchCallback {
        /**
         * @param serviceType Detected service type.
         * @param layers      Parsed layers supporting Web Mercator.
         */
        void onResult(LayerType serviceType, List<LayerInfo> layers);
        /** @param message Human-readable error description. */
        void onError(String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Fetches and parses a WMS or WMTS GetCapabilities document on a background thread,
     * then delivers the result on the main thread via {@code callback}.
     *
     * @param endpointUrl Base URL of the service (without query string).
     * @param hint        Expected service type; determines which GetCapabilities parameters to append.
     * @param callback    Result/error receiver called on the main thread.
     */
    public static void fetchCapabilities(String endpointUrl, LayerType hint,
                                         FetchCallback callback) {
        EXECUTOR.execute(() -> {
            String capUrl = buildCapabilitiesUrl(endpointUrl, hint);
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(capUrl).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);
                conn.setRequestProperty("Accept",
                        "application/xml, text/xml, */*");
                int code = conn.getResponseCode();
                if (code != 200) {
                    postError(callback, "HTTP " + code + " from " + capUrl);
                    return;
                }
                try (InputStream is = conn.getInputStream()) {
                    CapabilitiesParser.parse(is, new CapabilitiesParser.ParseCallback() {
                        @Override
                        public void onSuccess(LayerType type, List<LayerInfo> layers) {
                            MAIN.post(() -> callback.onResult(type, layers));
                        }
                        @Override
                        public void onError(String message) {
                            postError(callback, message);
                        }
                    });
                }
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * Appends standard GetCapabilities query parameters to the base URL.
     * Uses WMTS parameters for {@link LayerType#WMTS}, WMS parameters otherwise.
     */
    private static String buildCapabilitiesUrl(String base, LayerType hint) {
        String url = base.contains("?") ? base + "&" : base + "?";
        if (hint == LayerType.WMTS) {
            return url + "SERVICE=WMTS&REQUEST=GetCapabilities&VERSION=1.0.0";
        }
        return url + "SERVICE=WMS&REQUEST=GetCapabilities";
    }

    /** Posts an error to the callback on the main thread. */
    private static void postError(FetchCallback cb, String msg) {
        MAIN.post(() -> cb.onError(msg));
    }
}
