package com.example.intentprobe;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.intentprobe.capture.ProbeProtocol;
import com.example.intentprobe.data.ProbeProvider;
import com.example.intentprobe.data.ProbeRepository;
import com.example.intentprobe.export.RuleExporter;
import com.example.intentprobe.model.AppEntry;
import com.example.intentprobe.model.CaptureEntry;
import com.example.intentprobe.model.ConnectionEntry;
import com.example.intentprobe.model.RouteCandidate;
import com.example.intentprobe.scan.ApkManifestScanner;
import com.example.intentprobe.scan.DexRouteScanner;
import com.example.intentprobe.ui.CaptureAdapter;
import com.example.intentprobe.ui.RouteAdapter;
import com.example.intentprobe.ui.WindowInsetsHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";

    private static final int CAPTURE_LIMIT = 200;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable externalChange = this::onExternalChange;

    private ProbeRepository repository;
    private String pkg;
    private AppEntry app;
    private ConnectionEntry connection;
    private int routeCount;
    private int captureCount;

    private boolean routesTab = true;
    private boolean scanning;

    private RouteAdapter routeAdapter;
    private CaptureAdapter captureAdapter;
    private RecyclerView recycler;
    private View empty;
    private ProgressBar progress;
    private TextView emptyText;

    private TextView tvLabel;
    private TextView tvPkg;
    private TextView tvConnection;
    private TextView tvConnectionDetail;
    private TextView tvHint;
    private SwitchMaterial switchProbe;
    private MaterialButton btnScan;
    private MaterialButton btnLsposed;
    private View routeSearchLayout;
    private TextInputEditText routeSearch;

    private ContentObserver observer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_app_detail);
        WindowInsetsHelper.applyRootInsets(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repository = new ProbeRepository(this);
        tvLabel = findViewById(R.id.tv_app_label);
        tvPkg = findViewById(R.id.tv_app_pkg);
        tvConnection = findViewById(R.id.tv_connection);
        tvConnectionDetail = findViewById(R.id.tv_connection_detail);
        tvHint = findViewById(R.id.tv_hint);
        switchProbe = findViewById(R.id.switch_probe);
        btnScan = findViewById(R.id.btn_scan);
        btnLsposed = findViewById(R.id.btn_lsposed);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.tv_empty);
        MaterialButtonToggleGroup toggleGroup = findViewById(R.id.toggle_group);
        routeSearchLayout = findViewById(R.id.route_search_layout);
        routeSearch = findViewById(R.id.route_search);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        routeAdapter = new RouteAdapter(this::showRouteDialog);
        captureAdapter = new CaptureAdapter(this::showCaptureDialog);
        recycler.setAdapter(routeAdapter);

        routeSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                routeAdapter.setQuery(s == null ? "" : s.toString());
                updateEmptyState();
            }
        });

        switchProbe.setOnCheckedChangeListener((button, checked) -> {
            if (app != null && checked != app.enabled) {
                toggleProbe(checked);
            }
        });
        btnScan.setOnClickListener(v -> scan());
        btnLsposed.setOnClickListener(v -> openLsposed());

        toggleGroup.check(R.id.btn_tab_routes);
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            routesTab = checkedId == R.id.btn_tab_routes;
            routeSearchLayout.setVisibility(routesTab ? View.VISIBLE : View.GONE);
            if (routesTab) {
                recycler.setAdapter(routeAdapter);
                loadRoutes();
            } else {
                routeSearch.setText("");
                recycler.setAdapter(captureAdapter);
                loadCaptures();
            }
            updateHint();
            updateEmptyState();
        });

        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                main.removeCallbacks(externalChange);
                main.postDelayed(externalChange, 300);
            }
        };
        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(ProbeProvider.URI_EVENTS, true, observer);
        resolver.registerContentObserver(ProbeProvider.URI_HELLO, true, observer);

        loadHeader();
        loadRoutes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHeader();
        if (routesTab) {
            loadRoutes();
        } else {
            loadCaptures();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
        }
        executor.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_app_detail, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            loadHeader();
            if (routesTab) loadRoutes();
            else loadCaptures();
            return true;
        }
        if (id == R.id.action_clear_captures) {
            confirmClearCaptures();
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onExternalChange() {
        loadHeader();
        if (!routesTab) loadCaptures();
    }

    private void loadHeader() {
        executor.execute(() -> {
            AppEntry loaded = repository.getApp(pkg);
            ConnectionEntry connectionEntry = repository.getLatestConnection(pkg);
            int routes = repository.getRouteCount(pkg);
            int captures = repository.countCaptures(pkg);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (loaded == null) {
                    Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                app = loaded;
                connection = connectionEntry;
                routeCount = routes;
                captureCount = captures;
                bindHeader();
                updateHint();
                updateEmptyState();
            });
        });
    }

    private void bindHeader() {
        tvLabel.setText(app.label == null ? app.pkg : app.label);
        tvPkg.setText(app.pkg);

        switchProbe.setOnCheckedChangeListener(null);
        switchProbe.setChecked(app.enabled);
        switchProbe.setOnCheckedChangeListener((button, checked) -> {
            if (app != null && checked != app.enabled) {
                toggleProbe(checked);
            }
        });

        long now = System.currentTimeMillis();
        boolean fresh = connection != null
                && now - connection.lastSeen < ProbeProtocol.CONNECTION_FRESH_MS;
        String detail;
        if (connection == null) {
            tvConnection.setText("● " + getString(R.string.connection_none));
            tvConnection.setTextColor(ContextCompat.getColor(this, R.color.status_off));
            detail = app.enabled ? getString(R.string.lsposed_guide)
                    : getString(R.string.probe_off);
        } else if (fresh) {
            tvConnection.setText("● " + getString(R.string.connection_connected));
            tvConnection.setTextColor(ContextCompat.getColor(this, R.color.status_ok));
            detail = connectionDetail(connection);
        } else {
            tvConnection.setText("● " + getString(R.string.connection_stale));
            tvConnection.setTextColor(ContextCompat.getColor(this, R.color.status_warn));
            detail = connectionDetail(connection);
            if (app.enabled) {
                detail += "\n" + getString(R.string.lsposed_guide);
            }
        }
        tvConnectionDetail.setText(detail);

        btnScan.setEnabled(!scanning);
        btnScan.setText(scanning ? R.string.scanning
                : (app.lastScanAt == 0 ? R.string.scan : R.string.rescan));
    }

    private String connectionDetail(ConnectionEntry entry) {
        CharSequence time = DateUtils.getRelativeTimeSpanString(entry.lastSeen,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.connection_detail, time)
                + "\n" + getString(R.string.capture_process,
                entry.process == null ? entry.pkg : entry.process, entry.pid);
    }

    private void updateHint() {
        if (app == null) return;
        if (routesTab) {
            if (scanning) {
                tvHint.setText(R.string.scanning);
                tvHint.setTextColor(ContextCompat.getColor(this, R.color.status_warn));
                return;
            }
            if (app.scanError != null && !app.scanError.isEmpty()) {
                tvHint.setText(getString(R.string.scan_failed, app.scanError));
                tvHint.setTextColor(ContextCompat.getColor(this, R.color.status_error));
                return;
            }
            tvHint.setTextColor(ContextCompat.getColor(this, R.color.status_off));
            if (app.lastScanAt == 0) {
                tvHint.setText(R.string.scan_not_yet);
            } else if (app.lastUpdateTime > app.lastScanAt) {
                tvHint.setText(R.string.scan_needs_update);
            } else {
                CharSequence time = DateUtils.getRelativeTimeSpanString(app.lastScanAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
                tvHint.setText(getString(R.string.routes_count, routeCount)
                        + " · " + getString(R.string.scan_time, time));
            }
        } else {
            tvHint.setTextColor(ContextCompat.getColor(this, R.color.status_off));
            if (captureCount == 0) {
                tvHint.setText(R.string.captures_empty);
            } else {
                String text = getString(R.string.captures_count, captureCount);
                if (captureCount > CAPTURE_LIMIT) {
                    text += " · " + getString(R.string.captures_limited, CAPTURE_LIMIT);
                }
                tvHint.setText(text);
            }
        }
    }

    private void updateEmptyState() {
        if (app == null) return;
        int count = routesTab ? routeAdapter.getItemCount() : captureAdapter.getItemCount();
        if (scanning && routesTab) {
            empty.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.scanning);
            recycler.setVisibility(View.GONE);
            return;
        }
        if (count == 0) {
            empty.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            if (routesTab) {
                boolean hasQuery = routeSearch != null && routeSearch.getText() != null
                        && routeSearch.getText().length() > 0;
                if (hasQuery) {
                    emptyText.setText(R.string.routes_no_match);
                } else {
                    emptyText.setText(app.lastScanAt == 0
                            ? R.string.scan_not_yet : R.string.routes_empty);
                }
            } else {
                emptyText.setText(R.string.captures_empty);
            }
            recycler.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
        }
    }

    private void loadRoutes() {
        executor.execute(() -> {
            List<RouteCandidate> routes = repository.getRoutes(pkg);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                routeAdapter.setItems(routes);
                updateEmptyState();
            });
        });
    }

    private void loadCaptures() {
        executor.execute(() -> {
            List<CaptureEntry> captures = repository.getCaptures(pkg, CAPTURE_LIMIT);
            int count = repository.countCaptures(pkg);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                captureAdapter.setItems(captures);
                captureCount = count;
                updateHint();
                updateEmptyState();
            });
        });
    }

    private void scan() {
        if (scanning || app == null) return;
        final String apkPath = app.apkPath;
        if (apkPath == null || apkPath.isEmpty()) {
            Toast.makeText(this, getString(R.string.scan_failed, "APK 路径未知"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        scanning = true;
        btnScan.setEnabled(false);
        btnScan.setText(R.string.scanning);
        updateHint();
        updateEmptyState();

        executor.execute(() -> {
            try {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvHint.setText(R.string.scanning_manifest);
                });
                ApkManifestScanner.ScanResult manifest =
                        ApkManifestScanner.scanAll(apkPath, pkg);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvHint.setText(R.string.scanning_dex);
                });
                List<RouteCandidate> dexRoutes =
                        DexRouteScanner.scan(apkPath, pkg, manifest.schemes);
                List<RouteCandidate> routes = mergeRoutes(manifest.routes, dexRoutes);
                repository.replaceRoutes(pkg, routes, System.currentTimeMillis());
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    scanning = false;
                    Toast.makeText(this, getString(R.string.scan_done_split,
                            manifest.routes.size(), dexRoutes.size()),
                            Toast.LENGTH_SHORT).show();
                    loadHeader();
                    loadRoutes();
                });
            } catch (Throwable t) {
                String message = t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage();
                repository.markScanError(pkg, message);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    scanning = false;
                    Toast.makeText(this, getString(R.string.scan_failed, message),
                            Toast.LENGTH_LONG).show();
                    loadHeader();
                    updateEmptyState();
                });
            }
        });
    }

    private static List<RouteCandidate> mergeRoutes(List<RouteCandidate> manifest,
            List<RouteCandidate> dex) {
        Set<String> seen = new HashSet<>();
        for (RouteCandidate route : manifest) {
            if (route.uri != null) seen.add(route.uri.toLowerCase(Locale.ROOT));
        }
        List<RouteCandidate> result = new ArrayList<>(manifest);
        for (RouteCandidate route : dex) {
            if (route.uri != null && seen.contains(route.uri.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(route);
        }
        return result;
    }

    private void toggleProbe(boolean enabled) {
        boolean ok = repository.setEnabled(pkg, enabled);
        if (!ok) {
            Toast.makeText(this, R.string.probe_toggle_failed, Toast.LENGTH_SHORT).show();
            loadHeader();
            return;
        }
        if (app != null) app.enabled = enabled;
        Toast.makeText(this, enabled ? R.string.probe_enabled_toast
                : R.string.probe_disabled_toast, Toast.LENGTH_LONG).show();
        loadHeader();
    }

    private void openLsposed() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if (intent == null) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.setPackage("org.lsposed.manager");
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            if (intent.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, R.string.lsposed_missing, Toast.LENGTH_LONG).show();
                return;
            }
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.lsposed_missing, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClearCaptures() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.captures_clear)
                .setMessage(R.string.clear_captures_confirm)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    executor.execute(() -> {
                        repository.clearCaptures(pkg);
                        main.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, R.string.captures_cleared,
                                    Toast.LENGTH_SHORT).show();
                            loadHeader();
                            loadCaptures();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showRouteDialog(RouteCandidate route) {
        boolean dex = "dex".equals(route.origin);
        String component = route.alias != null ? route.alias : route.activity;
        boolean hasComponent = component != null && !component.isEmpty();
        List<String> hints = new ArrayList<>();
        if (dex) hints.add(getString(R.string.route_origin_dex_hint));
        if (!route.exported) hints.add(getString(R.string.route_not_exported_hint));
        if (route.patternOnly) hints.add(getString(R.string.route_pattern_hint));
        if (route.permission != null) {
            hints.add(getString(R.string.route_permission_hint, route.permission));
        }
        boolean schemeAllowed = RuleExporter.isSchemeAllowed(route.uri);
        if (!schemeAllowed) hints.add(getString(R.string.route_scheme_hint));

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (route.exported && schemeAllowed) {
            labels.add(getString(R.string.copy_intent_uri)
                    + getString(R.string.recommended_suffix));
            actions.add(() -> copyRouteIntent(route, false, false));

            if (hasComponent && route.uri != null) {
                labels.add(getString(R.string.copy_intent_locked));
                actions.add(() -> copyRouteIntent(route, true, true));
            }

            if (RuleExporter.canUsePureUri(route.action, route.uri, route.mimeType,
                    route.categories)) {
                labels.add(getString(R.string.copy_uri));
                actions.add(() -> copyPureUri(route));
            }

            if (hasComponent) {
                labels.add(getString(R.string.copy_class_warning));
                actions.add(() -> copyClass(route, component));
            }
        }

        labels.add(getString(R.string.copy_details));
        actions.add(() -> copyDetails(route, component));

        String title = hasComponent ? CaptureAdapter.simpleName(component)
                : (route.uri != null ? route.uri : getString(R.string.route_origin_dex));
        String message = hints.isEmpty() ? null : joinLines(hints);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    Runnable action = actions.get(which);
                    if (action != null) action.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyRouteIntent(RouteCandidate route, boolean locked, boolean confirm) {
        String component = route.alias != null ? route.alias : route.activity;
        boolean useComponent = locked || route.uri == null;
        RuleExporter.Outcome outcome = RuleExporter.buildIntentUri(pkg, route.action,
                route.uri, route.mimeType, route.categories,
                useComponent ? component : null, Collections.emptyList());
        if (!outcome.ok()) {
            Toast.makeText(this, errorText(outcome), Toast.LENGTH_LONG).show();
            return;
        }
        if (confirm) {
            confirmThen(getString(R.string.lock_warning_title),
                    getString(R.string.lock_warning_message),
                    () -> copy(outcome.value));
        } else {
            copy(outcome.value);
        }
    }

    private void copyPureUri(RouteCandidate route) {
        RuleExporter.Outcome outcome = RuleExporter.buildPureUri(route.action, route.uri,
                route.mimeType, route.categories);
        if (!outcome.ok()) {
            Toast.makeText(this, errorText(outcome), Toast.LENGTH_LONG).show();
            return;
        }
        copy(outcome.value);
    }

    private void copyClass(RouteCandidate route, String component) {
        RuleExporter.Outcome outcome = RuleExporter.buildClassName(pkg, component);
        if (!outcome.ok()) {
            Toast.makeText(this, errorText(outcome), Toast.LENGTH_LONG).show();
            return;
        }
        confirmThen(getString(R.string.class_warning_title),
                getString(R.string.class_warning_message),
                () -> copy(outcome.value));
    }

    private void copyDetails(RouteCandidate route, String component) {
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.details_package, route.pkg)).append('\n');
        if (component != null && !component.isEmpty()) {
            builder.append(getString(R.string.details_component, component)).append('\n');
        }
        if (route.origin != null && !"manifest".equals(route.origin)) {
            builder.append(getString(R.string.details_origin, route.origin)).append('\n');
        }
        if (route.alias != null) {
            builder.append(getString(R.string.details_alias, route.activity)).append('\n');
        }
        builder.append(getString(R.string.details_exported,
                route.exported ? "true" : "false"));
        if (route.exportedInferred) builder.append(getString(R.string.details_inferred));
        builder.append('\n');
        if (route.launchMode != null) {
            builder.append(getString(R.string.details_launch_mode, route.launchMode))
                    .append('\n');
        }
        if (route.permission != null) {
            builder.append(getString(R.string.details_permission, route.permission))
                    .append('\n');
        }
        builder.append(getString(R.string.details_action,
                route.action == null ? getString(R.string.details_none) : route.action))
                .append('\n');
        if (route.categories != null) {
            builder.append(getString(R.string.details_categories, route.categories))
                    .append('\n');
        }
        if (route.uri != null) {
            builder.append(getString(R.string.details_data, route.uri)).append('\n');
        }
        if (route.mimeType != null) {
            builder.append(getString(R.string.details_type, route.mimeType)).append('\n');
        }
        if (route.patternOnly) {
            builder.append("pathPattern：").append(route.pathPattern != null
                    ? route.pathPattern : route.pathSuffix).append('\n');
        }
        copy(builder.toString());
    }

    private void showCaptureDialog(CaptureEntry capture) {
        ProbeProtocol.ExtrasInfo info = ProbeProtocol.summarize(capture.extrasJson);
        List<String> hints = new ArrayList<>();
        if (info.hidden > 0) hints.add(getString(R.string.extras_hidden_hint, info.hidden));
        if (info.unsupported > 0) {
            hints.add(getString(R.string.extras_unsupported_hint, info.unsupported));
        }
        if (info.truncated > 0) {
            hints.add(getString(R.string.extras_truncated_hint, info.truncated));
        }
        boolean schemeAllowed = RuleExporter.isSchemeAllowed(capture.data);
        if (!schemeAllowed) hints.add(getString(R.string.route_scheme_hint));

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (schemeAllowed) {
            labels.add(getString(R.string.copy_intent_uri)
                    + getString(R.string.recommended_suffix));
            actions.add(() -> copyCaptureIntent(capture, false, false));

            if (capture.data != null && capture.targetActivity != null) {
                labels.add(getString(R.string.copy_intent_locked));
                actions.add(() -> copyCaptureIntent(capture, true, true));
            }

            if (RuleExporter.canUsePureUri(capture.action, capture.data, capture.type,
                    capture.categories)) {
                labels.add(getString(R.string.copy_uri));
                actions.add(() -> copyPureCapture(capture));
            }
        }

        if (capture.data == null) {
            for (ProbeProtocol.ExtraItem extra : ProbeProtocol.uriExtras(capture.extrasJson)) {
                if (!RuleExporter.isSchemeAllowed(extra.value)) continue;
                labels.add(getString(R.string.copy_extra_uri, extra.key));
                actions.add(() -> copyExtraUri(extra));
            }
        }

        labels.add(getString(R.string.copy_details));
        actions.add(() -> copyCaptureDetails(capture));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.capture_details_title)
                .setMessage(hints.isEmpty() ? null : joinLines(hints))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    Runnable action = actions.get(which);
                    if (action != null) action.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyCaptureIntent(CaptureEntry capture, boolean locked, boolean confirm) {
        List<RuleExporter.Extra> extras = RuleExporter.fromCaptureExtras(capture.extrasJson);
        boolean useComponent = locked || capture.data == null;
        RuleExporter.Outcome outcome = RuleExporter.buildIntentUri(pkg, capture.action,
                capture.data, capture.type, capture.categories,
                useComponent ? capture.targetActivity : null, extras);
        if (!outcome.ok()) {
            Toast.makeText(this, errorText(outcome), Toast.LENGTH_LONG).show();
            return;
        }
        String message = outcome.droppedExtras > 0
                ? getString(R.string.copy_done) + "，另有 " + outcome.droppedExtras
                + " 项 extras 未写入"
                : null;
        if (confirm) {
            confirmThen(getString(R.string.lock_warning_title),
                    getString(R.string.lock_warning_message),
                    () -> copy(outcome.value, message));
        } else {
            copy(outcome.value, message);
        }
    }

    private void copyExtraUri(ProbeProtocol.ExtraItem extra) {
        RuleExporter.Outcome outcome = RuleExporter.buildIntentUri(pkg,
                Intent.ACTION_VIEW, extra.value, null, null, null, Collections.emptyList());
        if (!outcome.ok()) {
            Toast.makeText(this, errorText(outcome), Toast.LENGTH_LONG).show();
            return;
        }
        copy(outcome.value);
    }

    private void copyPureCapture(CaptureEntry capture) {
        RuleExporter.Outcome outcome = RuleExporter.buildPureUri(capture.action, capture.data,
                capture.type, capture.categories);
        if (!outcome.ok()) {
            Toast.makeText(this, errorText(outcome), Toast.LENGTH_LONG).show();
            return;
        }
        copy(outcome.value);
    }

    private void copyCaptureDetails(CaptureEntry capture) {
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.capture_source,
                CaptureAdapter.sourceLabel(this, capture.source))).append('\n');
        builder.append(getString(R.string.capture_time,
                DateUtils.formatDateTime(this, capture.timestamp,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                                | DateUtils.FORMAT_SHOW_YEAR))).append('\n');
        builder.append(getString(R.string.capture_process,
                capture.process == null ? pkg : capture.process, capture.pid)).append('\n');
        if (capture.caller != null) {
            builder.append(getString(R.string.capture_caller, capture.caller)).append('\n');
        }
        builder.append(getString(R.string.capture_target,
                capture.targetActivity == null ? getString(R.string.capture_target_unknown)
                        : capture.targetActivity)).append('\n');
        if (capture.targetPkg != null) {
            builder.append(getString(R.string.capture_target_pkg, capture.targetPkg))
                    .append('\n');
        }
        builder.append(getString(R.string.details_action,
                capture.action == null ? getString(R.string.details_none) : capture.action))
                .append('\n');
        if (capture.data != null) {
            builder.append(getString(R.string.details_data, capture.data)).append('\n');
        }
        if (capture.type != null) {
            builder.append(getString(R.string.details_type, capture.type)).append('\n');
        }
        if (capture.categories != null) {
            builder.append(getString(R.string.details_categories, capture.categories))
                    .append('\n');
        }
        builder.append(getString(R.string.details_flags, capture.flags)).append('\n');

        List<ProbeProtocol.ExtraItem> extras = ProbeProtocol.parseExtras(capture.extrasJson);
        builder.append(getString(R.string.details_extras)).append('\n');
        if (extras.isEmpty()) {
            builder.append("  ").append(getString(R.string.capture_no_extras));
        } else {
            for (ProbeProtocol.ExtraItem item : extras) {
                if ("__truncated__".equals(item.type)) {
                    builder.append("  … +").append(item.value).append(" 项未记录\n");
                    continue;
                }
                if ("__error__".equals(item.type)) {
                    builder.append("  <读取失败：").append(item.value).append(">\n");
                    continue;
                }
                builder.append("  ").append(item.key).append(" (").append(item.type).append(')');
                if (item.value == null) {
                    builder.append(' ').append(getString(R.string.capture_extra_hidden));
                } else {
                    builder.append(" = ").append(item.value);
                    if (item.truncated) {
                        builder.append(getString(R.string.capture_extra_truncated));
                    }
                }
                builder.append('\n');
            }
        }
        copy(builder.toString());
    }

    private String errorText(RuleExporter.Outcome outcome) {
        switch (outcome.error) {
            case TOO_LONG:
                return getString(R.string.copy_too_long);
            case TOO_MANY_EXTRAS:
                return getString(R.string.copy_too_many_extras);
            case BLOCKED_SCHEME:
                return getString(R.string.copy_unsupported_scheme);
            case UNSUPPORTED:
            case INVALID:
            default:
                return getString(R.string.copy_failed,
                        outcome.detail == null ? "无法生成规则" : outcome.detail);
        }
    }

    private void confirmThen(String title, String message, Runnable action) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.copy, (dialog, which) -> action.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copy(String text) {
        copy(text, null);
    }

    private void copy(String text, String extraToast) {
        if (text == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Intentprobe", text));
        }
        String toast = extraToast == null ? getString(R.string.copy_done) : extraToast;
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(line);
        }
        return builder.toString();
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.about_ok, null)
                .show();
    }
}
