package nisargpatel.deadreckoning.gis;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import nisargpatel.deadreckoning.R;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.VH> {

    public interface Listener {
        void onVisibilityChanged(MapLayer layer, boolean visible);
        void onAlphaChanged(MapLayer layer, float alpha);
        void onRemove(MapLayer layer);
    }

    private final List<MapLayer> layers;
    private final Listener listener;

    public LayerAdapter(List<MapLayer> layers, Listener listener) {
        this.layers   = layers;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_layer, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MapLayer layer = layers.get(position);
        h.textName.setText(layer.getName());
        h.switchVisible.setOnCheckedChangeListener(null);
        h.switchVisible.setChecked(layer.isVisible());
        h.switchVisible.setOnCheckedChangeListener((btn, checked) ->
                listener.onVisibilityChanged(layer, checked));
        h.seekAlpha.setOnSeekBarChangeListener(null);
        h.seekAlpha.setProgress((int) (layer.getAlpha() * 100));
        h.seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean user) {
                if (user) listener.onAlphaChanged(layer, progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        h.btnRemove.setOnClickListener(v -> listener.onRemove(layer));
    }

    @Override
    public int getItemCount() { return layers.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView textName;
        SwitchCompat switchVisible;
        SeekBar seekAlpha;
        ImageButton btnRemove;

        VH(View v) {
            super(v);
            textName     = v.findViewById(R.id.textLayerName);
            switchVisible= v.findViewById(R.id.switchLayerVisible);
            seekAlpha    = v.findViewById(R.id.seekLayerAlpha);
            btnRemove    = v.findViewById(R.id.btnLayerRemove);
        }
    }
}
