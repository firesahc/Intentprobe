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
import com.example.intentprobe.model.RouteCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RouteAdapter extends RecyclerView.Adapter<RouteAdapter.Holder> {

    public interface Listener {
        void onClick(RouteCandidate route);
    }

    private final List<RouteCandidate> all = new ArrayList<>();
    private final List<RouteCandidate> items = new ArrayList<>();
    private final Listener listener;
    private String query = "";

    public RouteAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<RouteCandidate> routes) {
        all.clear();
        if (routes != null) all.addAll(routes);
        applyFilter();
    }

    public void setQuery(String value) {
        query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        items.clear();
        if (query.isEmpty()) {
            items.addAll(all);
        } else {
            for (RouteCandidate route : all) {
                if (matches(route, query)) items.add(route);
            }
        }
        notifyDataSetChanged();
    }

    private static boolean matches(RouteCandidate route, String query) {
        return contains(route.activity, query)
                || contains(route.alias, query)
                || contains(route.uri, query)
                || contains(route.action, query)
                || contains(route.host, query)
                || contains(route.path, query)
                || contains(route.scheme, query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_route, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        RouteCandidate route = items.get(position);
        Context context = holder.itemView.getContext();
        boolean dex = "dex".equals(route.origin);

        String component = route.alias != null ? route.alias : route.activity;
        if (component == null || component.isEmpty()) {
            holder.title.setText(route.uri != null ? route.uri
                    : context.getString(R.string.route_no_uri));
        } else {
            holder.title.setText(simpleName(component));
        }

        String badge;
        int badgeColor;
        if (dex) {
            badge = context.getString(R.string.route_origin_dex);
            badgeColor = R.color.status_warn;
        } else {
            badge = route.exported
                    ? context.getString(R.string.route_exported)
                    : context.getString(R.string.route_not_exported);
            if (route.exportedInferred) {
                badge += " · " + context.getString(R.string.route_inferred);
            }
            badgeColor = route.exported ? R.color.status_ok : R.color.status_error;
        }
        holder.badge.setText(badge);
        holder.badge.setTextColor(ContextCompat.getColor(context, badgeColor));
        holder.title.setTextColor(onSurfaceColor(context));

        String uriLine;
        if (route.uri != null) {
            uriLine = route.uri;
            if (route.patternOnly) {
                uriLine += "  · " + context.getString(R.string.route_pattern);
            }
        } else if (route.action != null) {
            uriLine = route.action;
        } else {
            uriLine = context.getString(R.string.route_no_uri);
        }
        holder.uri.setText(uriLine);

        List<String> meta = new ArrayList<>();
        if (dex) {
            meta.add(context.getString(R.string.route_origin_dex_hint));
        }
        if (route.uri != null && route.action != null) {
            meta.add(shortName(route.action));
        }
        if (route.categories != null && !route.categories.isEmpty()) {
            meta.add(shortCategories(route.categories));
        }
        if (route.mimeType != null) {
            meta.add(route.mimeType);
        }
        if (route.launchMode != null) {
            meta.add(context.getString(R.string.route_launch_mode, route.launchMode));
        }
        if (route.permission != null) {
            meta.add(context.getString(R.string.route_permission, route.permission));
        }
        if (route.alias != null) {
            meta.add(context.getString(R.string.route_alias, simpleName(route.activity)));
        }
        holder.meta.setText(meta.isEmpty() ? context.getString(R.string.details_none)
                : join(meta));

        holder.itemView.setOnClickListener(v -> listener.onClick(route));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String simpleName(String value) {
        if (value == null) return "";
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static String shortName(String value) {
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static String shortCategories(String categories) {
        StringBuilder builder = new StringBuilder();
        for (String category : categories.split(",")) {
            String trimmed = category.trim();
            if (trimmed.isEmpty()) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(shortName(trimmed));
        }
        return builder.toString();
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(value);
        }
        return builder.toString();
    }

    private static int onSurfaceColor(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, value, true)) {
            return value.data;
        }
        return ContextCompat.getColor(context, R.color.status_off);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView badge;
        final TextView uri;
        final TextView meta;

        Holder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_route_title);
            badge = itemView.findViewById(R.id.tv_route_badge);
            uri = itemView.findViewById(R.id.tv_route_uri);
            meta = itemView.findViewById(R.id.tv_route_meta);
        }
    }
}
