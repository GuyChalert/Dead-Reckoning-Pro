package nisargpatel.deadreckoning.gis;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.osmdroid.views.MapView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nisargpatel.deadreckoning.R;

/**
 * Bottom sheet that shows the current layer stack and allows adding custom WMS/WMTS sources
 * or toggling the pre-configured IGN/BRGM presets.
 */
public class LayerControlSheet extends BottomSheetDialogFragment {

    /** Called when the user requests to start the offline region download UI. */
    public interface DownloadCallback {
        void onDownloadRegionRequested();
    }

    private LayerManager layerManager;
    private MapView mapView;
    private DownloadCallback downloadCallback;
    private LayerAdapter adapter;

    private final ActivityResultLauncher<String[]> pickZip =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importShapefile(uri); });

    private final ActivityResultLauncher<String[]> pickMBTiles =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importMBTiles(uri); });

    private final ActivityResultLauncher<String[]> pickGeoTiff =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importGeoTiff(uri); });

    public static LayerControlSheet newInstance(LayerManager manager, MapView mapView,
                                                DownloadCallback downloadCb) {
        LayerControlSheet sheet = new LayerControlSheet();
        sheet.layerManager      = manager;
        sheet.mapView           = mapView;
        sheet.downloadCallback  = downloadCb;
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_layers, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView rv = view.findViewById(R.id.rvLayers);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        List<MapLayer> layers = layerManager.getLayers();
        adapter = new LayerAdapter(layers, new LayerAdapter.Listener() {
            @Override
            public void onVisibilityChanged(MapLayer layer, boolean visible) {
                layerManager.setVisible(layer.getId(), visible);
            }
            @Override
            public void onAlphaChanged(MapLayer layer, float alpha) {
                layerManager.setAlpha(layer.getId(), alpha);
            }
            @Override
            public void onRemove(MapLayer layer) {
                layerManager.removeLayer(layer.getId());
                int idx = layers.indexOf(layer);
                layers.remove(layer);
                adapter.notifyItemRemoved(idx);
            }
        });
        rv.setAdapter(adapter);

        // --- preset chips
        View chipIgnOrtho  = view.findViewById(R.id.chipIgnOrtho);
        View chipIgnTopo   = view.findViewById(R.id.chipIgnTopo);
        View chipBrgmGeo   = view.findViewById(R.id.chipBrgmGeo);
        if (chipIgnOrtho  != null) chipIgnOrtho .setOnClickListener(v -> addPreset("ign_ortho"));
        if (chipIgnTopo   != null) chipIgnTopo  .setOnClickListener(v -> addPreset("ign_topo"));
        if (chipBrgmGeo   != null) chipBrgmGeo  .setOnClickListener(v -> addPreset("brgm_geo"));

        // --- add custom button
        MaterialButton btnAdd = view.findViewById(R.id.btnAddLayer);
        if (btnAdd != null) btnAdd.setOnClickListener(v -> showAddLayerDialog());

        // --- import shapefile button
        MaterialButton btnShp = view.findViewById(R.id.btnImportShapefile);
        if (btnShp != null) btnShp.setOnClickListener(v ->
                pickZip.launch(new String[]{"application/zip", "application/octet-stream"}));

        // --- import MBTiles button
        MaterialButton btnMbt = view.findViewById(R.id.btnImportMBTiles);
        if (btnMbt != null) btnMbt.setOnClickListener(v ->
                pickMBTiles.launch(new String[]{"application/octet-stream", "*/*"}));

        // --- import GeoTIFF button
        MaterialButton btnTif = view.findViewById(R.id.btnImportGeoTiff);
        if (btnTif != null) btnTif.setOnClickListener(v ->
                pickGeoTiff.launch(new String[]{"image/tiff", "application/octet-stream", "*/*"}));

        // --- download offline region button
        MaterialButton btnDownload = view.findViewById(R.id.btnDownloadRegion);
        if (btnDownload != null) btnDownload.setOnClickListener(v -> {
            dismiss();
            if (downloadCallback != null) downloadCallback.onDownloadRegionRequested();
        });
    }

    // ------------------------------------------------------------------ shapefile import

    private void importShapefile(Uri uri) {
        if (mapView == null) return;
        runInBackground(() -> {
            try {
                ShapefileOverlay.load(requireContext(), uri, mapView);
                mapView.post(() -> Toast.makeText(requireContext(),
                        R.string.shapefile_imported, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                mapView.post(() -> Toast.makeText(requireContext(),
                        getString(R.string.shapefile_error, e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ MBTiles import

    private void importMBTiles(Uri uri) {
        String name = resolveDisplayName(uri, "mbtiles");
        runInBackground(() -> {
            try {
                layerManager.importMBTiles(uri, name);
                if (mapView != null) mapView.post(() -> {
                    List<MapLayer> current = layerManager.getLayers();
                    adapter.notifyItemInserted(current.size() - 1);
                    Toast.makeText(requireContext(),
                            getString(R.string.mbtiles_imported, name), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (mapView != null) mapView.post(() ->
                        Toast.makeText(requireContext(),
                                getString(R.string.mbtiles_error, e.getMessage()),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ GeoTIFF import

    private void importGeoTiff(Uri uri) {
        runInBackground(() -> {
            try {
                layerManager.importGeoTiff(uri);
                if (mapView != null) mapView.post(() ->
                        Toast.makeText(requireContext(),
                                R.string.geotiff_imported, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                if (mapView != null) mapView.post(() ->
                        Toast.makeText(requireContext(),
                                getString(R.string.geotiff_error, e.getMessage()),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ utilities

    private void runInBackground(Runnable r) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(r);
        exec.shutdown();
    }

    private String resolveDisplayName(Uri uri, String fallback) {
        try (android.database.Cursor c = requireContext().getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return fallback;
    }

    // ------------------------------------------------------------------ presets

    private void addPreset(String id) {
        List<MapLayer> presets = LayerManager.getPresets();
        for (MapLayer p : presets) {
            if (p.getId().equals(id)) {
                layerManager.addLayer(p);
                List<MapLayer> layers = layerManager.getLayers();
                adapter.notifyItemInserted(layers.size() - 1);
                Toast.makeText(requireContext(), p.getName() + " added", Toast.LENGTH_SHORT).show();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ add custom

    private void showAddLayerDialog() {
        View dlgView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_layer, null);
        EditText editUrl = dlgView.findViewById(R.id.editServiceUrl);
        RadioGroup rgType = dlgView.findViewById(R.id.rgServiceType);
        RadioButton rbWmts = dlgView.findViewById(R.id.rbWmts);
        RadioButton rbWms  = dlgView.findViewById(R.id.rbWms);
        rbWmts.setChecked(true);

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.layer_add_title)
            .setView(dlgView)
            .setPositiveButton(R.string.layer_fetch, (d, w) -> {
                String url = editUrl.getText() != null
                        ? editUrl.getText().toString().trim() : "";
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.layer_url_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                LayerType hint = rbWmts.isChecked() ? LayerType.WMTS : LayerType.WMS;
                fetchAndPick(url, hint);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    @SuppressWarnings("deprecation")
    private void fetchAndPick(String url, LayerType hint) {
        ProgressDialog prog = new ProgressDialog(requireContext());
        prog.setMessage(getString(R.string.layer_fetching));
        prog.setCancelable(false);
        prog.show();

        WebServiceConnector.fetchCapabilities(url, hint,
            new WebServiceConnector.FetchCallback() {
                @Override
                public void onResult(LayerType serviceType, List<LayerInfo> layers) {
                    prog.dismiss();
                    if (layers.isEmpty()) {
                        Toast.makeText(requireContext(),
                            R.string.layer_no_compatible, Toast.LENGTH_LONG).show();
                        return;
                    }
                    showLayerPickerDialog(url, serviceType, layers);
                }
                @Override
                public void onError(String message) {
                    prog.dismiss();
                    Toast.makeText(requireContext(),
                        getString(R.string.layer_fetch_error, message),
                        Toast.LENGTH_LONG).show();
                }
            });
    }

    private void showLayerPickerDialog(String endpointUrl, LayerType serviceType,
                                       List<LayerInfo> infos) {
        String[] titles = new String[infos.size()];
        for (int i = 0; i < infos.size(); i++) titles[i] = infos.get(i).toString();

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.layer_pick_title)
            .setItems(titles, (d, which) -> {
                LayerInfo info = infos.get(which);
                String fmt  = info.formats.isEmpty() ? "image/png" : info.formats.get(0);
                String id   = "custom_" + info.name.replaceAll("[^a-zA-Z0-9]", "_");
                MapLayer layer = new MapLayer(id, info.toString(),
                        serviceType, endpointUrl,
                        info.name, info.defaultStyle, fmt,
                        serviceType == LayerType.WMTS ? info.matrixSet : "");
                layerManager.addLayer(layer);
                List<MapLayer> current = layerManager.getLayers();
                adapter.notifyItemInserted(current.size() - 1);
                Toast.makeText(requireContext(),
                    getString(R.string.layer_added, info.toString()),
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }
}
