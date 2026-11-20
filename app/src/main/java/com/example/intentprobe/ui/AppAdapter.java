package com.example.intentprobe.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.intentprobe.R;
import com.example.intentprobe.capture.ProbeProtocol;
import com.example.intentprobe.model.AppEntry;
import com.example.intentprobe.model.ConnectionEntry;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    public interface Listener {
        void onOpen(AppEntry app);

        void onToggle(AppEntry app, boolean enabled);
    }

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> visible = new ArrayList<>();
    private final Listener listener;
    private Map<String, ConnectionEntry> connections = Collections.emptyMap();
    private String query = "";

    public AppAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<AppEntry> items, Map<String, ConnectionEntry> connections) {
        all.clear();
        if (items != null) all.addAll(items);
        this.connections = connections == null ? Collections.emptyMap() : connections;
        applyFilter();
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public int visibleCount() {
        return visible.size();
    }

    private void applyFilter() {
        visible.clear();
        if (query.isEmpty()) {
            visible.addAll(all);
        } else {
            for (AppEntry app : all) {
                String label = app.label == null ? "" : app.label.toLowerCase(Locale.ROOT);
                String pkg = app.pkg == null ? "" : app.pkg.toLowerCase(Locale.ROOT);
                if (label.contains(query) || pkg.contains(query)) {
                    visible.add(app);
                }
            }
        }
        // Enabled probes float to the top; the sort is stable, so each group
        // keeps the repository's label order.
        Collections.sort(visible, (a, b) -> Boolean.compare(b.enabled, a.enabled));
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AppEntry app = visible.get(position);
        Context context = holder.itemView.getContext();
        holder.label.setText(app.label == null ? app.pkg : app.label);
        holder.pkg.setText(app.pkg);
        holder.icon.setText(firstCharacter(app.label == null ? app.pkg : app.label));

        ConnectionEntry connection = connections.get(app.pkg);
        boolean fresh = connection != null
                && System.currentTimeMillis() - connection.lastSeen
                < ProbeProtocol.CONNECTION_FRESH_MS;

        if (!app.enabled) {
            holder.status.setText(R.string.probe_off);
            holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_off));
        } else if (fresh) {
            holder.status.setText(R.string.probe_on_connected);
            holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_ok));
        } else {
            holder.status.setText(R.string.probe_on_waiting);
            holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_warn));
        }

        holder.toggle.setOnCheckedChangeListener(null);
        holder.toggle.setChecked(app.enabled);
        holder.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked != app.enabled) {
                listener.onToggle(app, checked);
            }
        });
        holder.itemView.setOnClickListener(v -> listener.onOpen(app));
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    private static String firstCharacter(String text) {
        if (text == null || text.isEmpty()) return "?";
        int codePoint = text.codePointAt(0);
        return new String(Character.toChars(codePoint)).toUpperCase(Locale.getDefault());
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView icon;
        final TextView label;
        final TextView pkg;
        final TextView status;
        final SwitchMaterial toggle;

        Holder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.tv_icon);
            label = itemView.findViewById(R.id.tv_label);
            pkg = itemView.findViewById(R.id.tv_pkg);
            status = itemView.findViewById(R.id.tv_status);
            toggle = itemView.findViewById(R.id.switch_probe);
        }
    }
}
