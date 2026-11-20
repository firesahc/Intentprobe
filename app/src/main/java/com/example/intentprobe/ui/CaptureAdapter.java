package com.example.intentprobe.ui;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.intentprobe.R;
import com.example.intentprobe.capture.ProbeProtocol;
import com.example.intentprobe.model.CaptureEntry;

import java.util.ArrayList;
import java.util.List;

public class CaptureAdapter extends RecyclerView.Adapter<CaptureAdapter.Holder> {

    public interface Listener {
        void onClick(CaptureEntry capture);
    }

    private final List<CaptureEntry> items = new ArrayList<>();
    private final Listener listener;

    public CaptureAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<CaptureEntry> captures) {
        items.clear();
        if (captures != null) items.addAll(captures);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_capture, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        CaptureEntry capture = items.get(position);
        Context context = holder.itemView.getContext();

        holder.source.setText(sourceLabel(context, capture.source));
        holder.time.setText(DateUtils.getRelativeTimeSpanString(capture.timestamp,
                System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS));

        String targetText;
        if (capture.targetActivity != null && !capture.targetActivity.isEmpty()) {
            targetText = simpleName(capture.targetActivity);
        } else if (capture.targetPkg != null && !capture.targetPkg.isEmpty()) {
            targetText = capture.targetPkg;
        } else {
            targetText = context.getString(R.string.capture_target_unknown);
        }
        if (ProbeProtocol.SOURCE_OUTGOING.equals(capture.source) && capture.caller != null) {
            targetText = targetText + "  ← " + simpleName(capture.caller);
        }
        holder.target.setText(targetText);

        holder.action.setText(capture.action == null
                ? context.getString(R.string.details_none) : capture.action);

        String dataLine;
        if (capture.data != null) {
            dataLine = capture.data;
        } else {
            ProbeProtocol.ExtraItem uriExtra = ProbeProtocol.firstUriExtra(capture.extrasJson);
            if (uriExtra != null) {
                dataLine = context.getString(R.string.capture_extra_uri_hint,
                        uriExtra.key, uriExtra.value);
            } else if (capture.type != null) {
                dataLine = capture.type;
            } else {
                dataLine = context.getString(R.string.details_none);
            }
        }
        holder.data.setText(dataLine);

        ProbeProtocol.ExtrasInfo info = ProbeProtocol.summarize(capture.extrasJson);
        List<String> parts = new ArrayList<>();
        if (info.total == 0 && info.truncated == 0) {
            parts.add(context.getString(R.string.capture_no_extras));
        } else {
            parts.add(context.getString(R.string.capture_extras, info.total));
            if (info.hidden > 0) parts.add("+" + info.hidden + " 仅类型");
            if (info.unsupported > 0) parts.add("+" + info.unsupported + " 未记录");
            if (info.truncated > 0) parts.add("+" + info.truncated + " 截断");
        }
        holder.extras.setText(join(parts));

        holder.itemView.setOnClickListener(v -> listener.onClick(capture));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static String sourceLabel(Context context, String source) {
        if (ProbeProtocol.SOURCE_ON_CREATE.equals(source)) {
            return context.getString(R.string.capture_source_oncreate);
        }
        if (ProbeProtocol.SOURCE_ON_NEW_INTENT.equals(source)) {
            return context.getString(R.string.capture_source_onnewintent);
        }
        if (ProbeProtocol.SOURCE_OUTGOING.equals(source)) {
            return context.getString(R.string.capture_source_outgoing);
        }
        return source == null ? context.getString(R.string.unknown) : source;
    }

    public static String simpleName(String value) {
        if (value == null || value.isEmpty()) return "";
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(value);
        }
        return builder.toString();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView source;
        final TextView time;
        final TextView target;
        final TextView action;
        final TextView data;
        final TextView extras;

        Holder(View itemView) {
            super(itemView);
            source = itemView.findViewById(R.id.tv_capture_source);
            time = itemView.findViewById(R.id.tv_capture_time);
            target = itemView.findViewById(R.id.tv_capture_target);
            action = itemView.findViewById(R.id.tv_capture_action);
            data = itemView.findViewById(R.id.tv_capture_data);
            extras = itemView.findViewById(R.id.tv_capture_extras);
        }
    }
}
