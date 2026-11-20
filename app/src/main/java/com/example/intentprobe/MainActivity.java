package com.example.intentprobe;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.intentprobe.data.ProbeRepository;
import com.example.intentprobe.model.AppEntry;
import com.example.intentprobe.model.ConnectionEntry;
import com.example.intentprobe.scan.AppListLoader;
import com.example.intentprobe.ui.AppAdapter;
import com.example.intentprobe.ui.WindowInsetsHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ProbeRepository repository;
    private AppAdapter adapter;
    private RecyclerView recycler;
    private View empty;
    private ProgressBar progress;
    private TextView emptyText;
    private TextInputEditText search;

    private volatile boolean loadedOnce;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        WindowInsetsHelper.applyRootInsets(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        repository = new ProbeRepository(this);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.empty_text);
        search = findViewById(R.id.search);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter(new AppAdapter.Listener() {
            @Override
            public void onOpen(AppEntry app) {
                Intent intent = new Intent(MainActivity.this, AppDetailActivity.class);
                intent.putExtra(AppDetailActivity.EXTRA_PKG, app.pkg);
                startActivity(intent);
            }

            @Override
            public void onToggle(AppEntry app, boolean enabled) {
                toggleProbe(app, enabled);
            }
        });
        recycler.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.setQuery(s == null ? "" : s.toString());
                updateEmptyState();
            }
        });

        loadApps(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loadedOnce) {
            reloadFromDb();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            loadApps(true);
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadApps(boolean refresh) {
        if (loading) return;
        loading = true;
        updateEmptyState();
        executor.execute(() -> {
            try {
                List<AppEntry> existing = repository.getApps();
                if (refresh || existing.isEmpty()) {
                    List<AppEntry> scanned = AppListLoader.load(this);
                    repository.upsertApps(scanned);
                }
                List<AppEntry> apps = repository.getApps();
                Map<String, ConnectionEntry> connections = repository.getAllConnections();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    loadedOnce = true;
                    adapter.setItems(apps, connections);
                    updateEmptyState();
                });
            } catch (Throwable t) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    empty.setVisibility(View.VISIBLE);
                    progress.setVisibility(View.GONE);
                    emptyText.setText(getString(R.string.loading_failed,
                            t.getMessage() == null ? t.getClass().getSimpleName()
                                    : t.getMessage()));
                });
            }
        });
    }

    private void reloadFromDb() {
        executor.execute(() -> {
            try {
                List<AppEntry> apps = repository.getApps();
                Map<String, ConnectionEntry> connections = repository.getAllConnections();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    adapter.setItems(apps, connections);
                    updateEmptyState();
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void toggleProbe(AppEntry app, boolean enabled) {
        boolean ok = repository.setEnabled(app.pkg, enabled);
        if (!ok) {
            Toast.makeText(this, R.string.probe_toggle_failed, Toast.LENGTH_SHORT).show();
            reloadFromDb();
            return;
        }
        app.enabled = enabled;
        Toast.makeText(this, enabled ? R.string.probe_enabled_toast
                : R.string.probe_disabled_toast, Toast.LENGTH_LONG).show();
        reloadFromDb();
    }

    private void updateEmptyState() {
        if (loading) {
            empty.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.apps_loading);
            recycler.setVisibility(View.GONE);
            return;
        }
        if (adapter == null || adapter.getItemCount() == 0) {
            empty.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            boolean hasQuery = search != null && search.getText() != null
                    && search.getText().length() > 0;
            emptyText.setText(hasQuery ? R.string.apps_no_match : R.string.apps_empty);
            recycler.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
        }
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.about_ok, null)
                .show();
    }
}
