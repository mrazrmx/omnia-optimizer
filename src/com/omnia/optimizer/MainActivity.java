package com.omnia.optimizer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_RESTORE_BACKUP = 2001;
    private static final int REQ_NOTIF_PERMISSION = 2002;
    private static final int TAB_HUD = 0;
    private static final int TAB_PERF = 1;
    private static final int TAB_DIAG = 2;
    private static final int TAB_AUDIT = 3;
    private static final int TAB_VAULT = 4;
    private static final long PERF_INTERVAL_MS = 1000L;

    private TextView tvDeviceSubtitle;
    private TextView badgeProfile;
    private TextView badgeExtraDim;
    private TextView badgeRenderer;
    private RadarChartView radarChart;
    private TrendChartView trendChart;
    private TextView tvHardwareTileLeft;
    private TextView tvHardwareTileRight;
    private TextView tvHardwareSummary;
    private TextView tvCurrentSummary;
    private TextView tvScreenComparison;
    private TextView tvRateComparison;
    private TextView tvCausalDiagnosis;
    private TextView tvAuditTitle;
    private TextView tvAudit;
    private TextView tvPermStatus;
    private TextView tvHistoryList;
    private TextView tvStorageUsage;
    private TextView tvPerfStatus;
    private TextView tvPerfCpu;
    private TextView tvPerfThermals;
    private TextView tvPerfMemory;
    private TextView tvPerfGpuPower;
    private TextView tvSentinelTitle;
    private TextView tvSentinelReport;
    private EditText etUserContext;

    private ScrollView mainScroll;
    private View tabHud, tabPerf, tabDiag, tabAudit, tabVault;
    private Button navHud, navPerf, navDiag, navAudit, navVault;

    private CheckBox cbRoutineWifiCalling;
    private CheckBox cbRoutineLookStay;
    private CheckBox cbRoutineDeepSleepApps;
    private CheckBox cbBaselineDisplay;

    private Button btnSaveContext;
    private Button btnDriveBackup;
    private Button btnQuickBackup;
    private Button btnRestoreBackup;
    private Button btnCleanStorage;
    private Button btnCollectNow;
    private Button btnHuntWakelocks;
    private Button btnCopyAdbCmd;
    private Button btnGrantUsagePerm;
    private Button btnGrantFilesPerm;

    private boolean isBindingChecklist = false;
    private boolean sentinelLiveShown = false;
    private boolean isActivityVisible = false;
    private boolean settingsObserverRegistered = false;
    private int currentTab = TAB_HUD;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService perfExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean perfReadInFlight = new AtomicBoolean(false);
    private ContentObserver settingsObserver;

    private final Runnable debouncedSettingsRefresh = new Runnable() {
        @Override
        public void run() {
            if (isActivityVisible) {
                refreshDashboard();
            }
        }
    };

    private final Runnable perfTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPerfActive()) {
                return;
            }
            if (!perfReadInFlight.compareAndSet(false, true)) {
                mainHandler.postDelayed(this, PERF_INTERVAL_MS);
                return;
            }
            perfExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final PerfMonitor.PerfSnapshot snap = PerfMonitor.readSnapshot(MainActivity.this);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!isPerfActive()) return;
                                tvPerfStatus.setText("● ATIVA APENAS NESTA ABA (0% 2º PLANO) · " + snap.timestampText);
                                tvPerfCpu.setText(snap.cpuText);
                                tvPerfThermals.setText(snap.thermalsText);
                                tvPerfMemory.setText(snap.memoryText);
                                tvPerfGpuPower.setText(snap.gpuPowerText);
                            }
                        });
                    } finally {
                        perfReadInFlight.set(false);
                        if (isPerfActive()) {
                            mainHandler.removeCallbacks(perfTickRunnable);
                            mainHandler.postDelayed(perfTickRunnable, PERF_INTERVAL_MS);
                        }
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#060911"));
        getWindow().setNavigationBarColor(Color.parseColor("#080E1A"));
        setContentView(R.layout.activity_main);

        tvDeviceSubtitle = (TextView) findViewById(R.id.tvDeviceSubtitle);
        tvDeviceSubtitle.setText(AnalyticsEngine.getDeviceHeaderSubtitle());

        badgeProfile = (TextView) findViewById(R.id.badgeProfile);
        badgeExtraDim = (TextView) findViewById(R.id.badgeExtraDim);
        badgeRenderer = (TextView) findViewById(R.id.badgeRenderer);
        radarChart = (RadarChartView) findViewById(R.id.radarChart);
        trendChart = (TrendChartView) findViewById(R.id.trendChart);
        tvHardwareTileLeft = (TextView) findViewById(R.id.tvHardwareTileLeft);
        tvHardwareTileRight = (TextView) findViewById(R.id.tvHardwareTileRight);
        tvHardwareSummary = (TextView) findViewById(R.id.tvHardwareSummary);
        tvCurrentSummary = (TextView) findViewById(R.id.tvCurrentSummary);
        tvScreenComparison = (TextView) findViewById(R.id.tvScreenComparison);
        tvRateComparison = (TextView) findViewById(R.id.tvRateComparison);
        tvCausalDiagnosis = (TextView) findViewById(R.id.tvCausalDiagnosis);
        tvAuditTitle = (TextView) findViewById(R.id.tvAuditTitle);
        tvAudit = (TextView) findViewById(R.id.tvAudit);
        tvPermStatus = (TextView) findViewById(R.id.tvPermStatus);
        tvHistoryList = (TextView) findViewById(R.id.tvHistoryList);
        tvStorageUsage = (TextView) findViewById(R.id.tvStorageUsage);
        tvPerfStatus = (TextView) findViewById(R.id.tvPerfStatus);
        tvPerfCpu = (TextView) findViewById(R.id.tvPerfCpu);
        tvPerfThermals = (TextView) findViewById(R.id.tvPerfThermals);
        tvPerfMemory = (TextView) findViewById(R.id.tvPerfMemory);
        tvPerfGpuPower = (TextView) findViewById(R.id.tvPerfGpuPower);
        tvSentinelTitle = (TextView) findViewById(R.id.tvSentinelTitle);
        tvSentinelReport = (TextView) findViewById(R.id.tvSentinelReport);
        etUserContext = (EditText) findViewById(R.id.etUserContext);

        mainScroll = (ScrollView) findViewById(R.id.mainScroll);
        tabHud = findViewById(R.id.tabHud);
        tabPerf = findViewById(R.id.tabPerf);
        tabDiag = findViewById(R.id.tabDiag);
        tabAudit = findViewById(R.id.tabAudit);
        tabVault = findViewById(R.id.tabVault);
        navHud = (Button) findViewById(R.id.navHud);
        navPerf = (Button) findViewById(R.id.navPerf);
        navDiag = (Button) findViewById(R.id.navDiag);
        navAudit = (Button) findViewById(R.id.navAudit);
        navVault = (Button) findViewById(R.id.navVault);

        cbRoutineWifiCalling = (CheckBox) findViewById(R.id.cbRoutineWifiCalling);
        cbRoutineLookStay = (CheckBox) findViewById(R.id.cbRoutineLookStay);
        cbRoutineDeepSleepApps = (CheckBox) findViewById(R.id.cbRoutineDeepSleepApps);
        cbBaselineDisplay = (CheckBox) findViewById(R.id.cbBaselineDisplay);

        btnSaveContext = (Button) findViewById(R.id.btnSaveContext);
        btnDriveBackup = (Button) findViewById(R.id.btnDriveBackup);
        btnQuickBackup = (Button) findViewById(R.id.btnQuickBackup);
        btnRestoreBackup = (Button) findViewById(R.id.btnRestoreBackup);
        btnCleanStorage = (Button) findViewById(R.id.btnCleanStorage);
        btnCollectNow = (Button) findViewById(R.id.btnCollectNow);
        btnHuntWakelocks = (Button) findViewById(R.id.btnHuntWakelocks);
        btnCopyAdbCmd = (Button) findViewById(R.id.btnCopyAdbCmd);
        btnGrantUsagePerm = (Button) findViewById(R.id.btnGrantUsagePerm);
        btnGrantFilesPerm = (Button) findViewById(R.id.btnGrantFilesPerm);

        setupTabs();
        setupShortcuts();
        loadChecklist();
        setupChecklistListeners();
        ChargingJobService.ensureArmed(this);
        maybeRequestNotificationsPermission();
        refreshDashboard();

        btnHuntWakelocks.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnHuntWakelocks.setEnabled(false);
                btnHuntWakelocks.setText("🕵️ VARRENDO POWER & BATTERYSTATS...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final WakelockSentinel.SentinelReport rep = WakelockSentinel.scanLiveNow(MainActivity.this);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnHuntWakelocks.setEnabled(true);
                                btnHuntWakelocks.setText("🕵️ CAÇAR LADRÃO DE BATERIA AGORA");
                                sentinelLiveShown = true;
                                applySentinelCard(rep.severity, rep.details, true);
                            }
                        });
                    }
                }).start();
            }
        });

        btnCollectNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCollectNow.setEnabled(false);
                btnCollectNow.setText("LENDO...");
                final String note = etUserContext.getText().toString();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        DumpHelper.performCollection(MainActivity.this, note);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnCollectNow.setEnabled(true);
                                btnCollectNow.setText("⚡ LER AGORA");
                                sentinelLiveShown = false;
                                refreshDashboard();
                                Toast.makeText(MainActivity.this, "Ciclo registrado com sucesso!", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });

        btnSaveContext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String note = etUserContext.getText().toString().trim();
                List<CycleData> list = CycleData.loadAllCycles();
                if (!list.isEmpty()) {
                    CycleData latest = list.get(0);
                    latest.userContext = note;
                    CycleData.saveCycle(latest);
                    refreshDashboard();
                    Toast.makeText(MainActivity.this, "Nota salva no ciclo atual!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Nenhum ciclo ativo para associar nota.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        View.OnClickListener driveClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerDriveBackup();
            }
        };
        btnDriveBackup.setOnClickListener(driveClick);
        btnQuickBackup.setOnClickListener(driveClick);

        btnRestoreBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRestorePicker();
            }
        });

        btnCleanStorage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCleanStorage.setEnabled(false);
                btnCleanStorage.setText("ARQUIVANDO...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        StorageManager.createMonthlyZipBackup();
                        StorageManager.cleanupOldRawDumps(7);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnCleanStorage.setEnabled(true);
                                btnCleanStorage.setText("ARQUIVAR ZIP E LIMPAR BRUTOS (> 7 DIAS)");
                                refreshDashboard();
                                Toast.makeText(MainActivity.this, "Dumps brutos antigos arquivados em ZIP!", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private void maybeRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_PERMISSION);
                } catch (Exception ignored) {}
            }
        }
    }

    private void triggerDriveBackup() {
        btnDriveBackup.setEnabled(false);
        btnQuickBackup.setEnabled(false);
        btnDriveBackup.setText("ZIPANDO...");
        btnQuickBackup.setText("ZIPANDO...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final File zip = StorageManager.createMonthlyZipBackup();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnDriveBackup.setEnabled(true);
                        btnQuickBackup.setEnabled(true);
                        btnDriveBackup.setText("☁️ BACKUP DRIVE");
                        btnQuickBackup.setText("☁️ BACKUP DRIVE");
                        if (zip != null) {
                            Intent share = StorageManager.getGoogleDriveBackupIntent(MainActivity.this, zip);
                            if (share != null) {
                                try {
                                    startActivity(share);
                                } catch (Exception e) {
                                    try {
                                        Intent fallback = StorageManager.getFallbackShareIntent(zip);
                                        if (fallback != null) startActivity(fallback);
                                    } catch (Exception ex) {
                                        Toast.makeText(MainActivity.this, "Erro ao abrir Google Drive: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(MainActivity.this, "Erro ao criar arquivo ZIP de backup.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void setupTabs() {
        navHud.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectTab(TAB_HUD); } });
        navPerf.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectTab(TAB_PERF); } });
        navDiag.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectTab(TAB_DIAG); } });
        navAudit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectTab(TAB_AUDIT); } });
        navVault.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectTab(TAB_VAULT); } });
        selectTab(TAB_HUD);
    }

    private void selectTab(int index) {
        currentTab = index;
        tabHud.setVisibility(index == TAB_HUD ? View.VISIBLE : View.GONE);
        tabPerf.setVisibility(index == TAB_PERF ? View.VISIBLE : View.GONE);
        tabDiag.setVisibility(index == TAB_DIAG ? View.VISIBLE : View.GONE);
        tabAudit.setVisibility(index == TAB_AUDIT ? View.VISIBLE : View.GONE);
        tabVault.setVisibility(index == TAB_VAULT ? View.VISIBLE : View.GONE);

        styleNavButton(navHud, index == TAB_HUD);
        styleNavButton(navPerf, index == TAB_PERF);
        styleNavButton(navDiag, index == TAB_DIAG);
        styleNavButton(navAudit, index == TAB_AUDIT);
        styleNavButton(navVault, index == TAB_VAULT);

        mainScroll.scrollTo(0, 0);
        updatePerfLoopState();
    }

    private void styleNavButton(Button btn, boolean active) {
        if (active) {
            btn.setBackgroundResource(R.drawable.bg_nav_active);
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setBackgroundColor(Color.TRANSPARENT);
            btn.setTextColor(Color.parseColor("#94A3B8"));
        }
    }

    private void openRestorePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/json", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_RESTORE_BACKUP);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                startActivityForResult(Intent.createChooser(fallback, "Escolher Backup (.zip ou .json)"), REQ_RESTORE_BACKUP);
            } catch (Exception ex) {
                Toast.makeText(this, "Nenhum seletor de arquivos disponível.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESTORE_BACKUP && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) return;

            btnRestoreBackup.setEnabled(false);
            btnRestoreBackup.setText("RESTAURANDO...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final StorageManager.RestoreResult res = StorageManager.restoreBackupFromUri(MainActivity.this, uri);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            btnRestoreBackup.setEnabled(true);
                            btnRestoreBackup.setText("📥 RESTAURAR");
                            if (res.checklistRestored) loadChecklist();
                            refreshDashboard();
                            Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }).start();
        }
    }

    private void setupShortcuts() {
        final String pkg = getPackageName();
        btnCopyAdbCmd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String cmd = "pm grant " + pkg + " android.permission.DUMP && " +
                        "pm grant " + pkg + " android.permission.BATTERY_STATS && " +
                        "pm grant " + pkg + " android.permission.PACKAGE_USAGE_STATS && " +
                        "cmd appops set " + pkg + " GET_USAGE_STATS allow && " +
                        "cmd appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow && " +
                        "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow && " +
                        "dumpsys deviceidle whitelist +" + pkg;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Omnia ADB Setup", cmd));
                    Toast.makeText(MainActivity.this, "Comando copiado! Cole no aShell (c/ Shizuku) ou adb shell no PC.", Toast.LENGTH_LONG).show();
                }
            }
        });
        btnGrantUsagePerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsSafe(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
            }
        });
        btnGrantFilesPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent filesIntent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                filesIntent.setData(Uri.parse("package:" + pkg));
                openSettingsSafe(filesIntent, new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        });

        findViewById(R.id.btnOpenDisplay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsSafe(new Intent(Settings.ACTION_DISPLAY_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
            }
        });
        findViewById(R.id.btnOpenBattery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsSafe(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            }
        });
        findViewById(R.id.btnOpenConnections).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsSafe(new Intent(Settings.ACTION_WIRELESS_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
            }
        });
        findViewById(R.id.btnOpenAppInfo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent appIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                appIntent.setData(Uri.parse("package:" + pkg));
                openSettingsSafe(appIntent, new Intent(Settings.ACTION_SETTINGS));
            }
        });
    }

    private void openSettingsSafe(Intent primary, Intent fallback) {
        try {
            startActivity(primary);
        } catch (Exception e) {
            try {
                startActivity(fallback);
            } catch (Exception ex) {
                Toast.makeText(this, "Não foi possível abrir esta configuração.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupChecklistListeners() {
        CompoundButton.OnCheckedChangeListener listener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isBindingChecklist) saveChecklist();
            }
        };
        cbRoutineWifiCalling.setOnCheckedChangeListener(listener);
        cbRoutineLookStay.setOnCheckedChangeListener(listener);
        cbRoutineDeepSleepApps.setOnCheckedChangeListener(listener);
        cbBaselineDisplay.setOnCheckedChangeListener(listener);
    }

    private void loadChecklist() {
        isBindingChecklist = true;
        try {
            File f = StorageManager.getChecklistFile();
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                cbRoutineWifiCalling.setChecked(json.optBoolean("wifiCalling", false));
                cbRoutineLookStay.setChecked(json.optBoolean("lookStay", false));
                cbRoutineDeepSleepApps.setChecked(json.optBoolean("deepSleepApps", false));
                cbBaselineDisplay.setChecked(json.optBoolean("baselineDisplay", true));
            } else {
                cbBaselineDisplay.setChecked(true);
            }
        } catch (Exception ignored) {}
        isBindingChecklist = false;
    }

    private void saveChecklist() {
        try {
            JSONObject json = new JSONObject();
            json.put("wifiCalling", cbRoutineWifiCalling.isChecked());
            json.put("lookStay", cbRoutineLookStay.isChecked());
            json.put("deepSleepApps", cbRoutineDeepSleepApps.isChecked());
            json.put("baselineDisplay", cbBaselineDisplay.isChecked());
            FileWriter fw = new FileWriter(StorageManager.getChecklistFile());
            fw.write(json.toString(2));
            fw.flush();
            fw.close();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onStart() {
        super.onStart();
        isActivityVisible = true;
        registerSettingsObserver();
        ChargingJobService.ensureArmed(this);
        refreshDashboard();
        updatePerfLoopState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityVisible = true;
        registerSettingsObserver();
        ChargingJobService.ensureArmed(this);
        refreshDashboard();
        updatePerfLoopState();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isActivityVisible) refreshDashboard();
    }

    @Override
    protected void onStop() {
        super.onStop();
        isActivityVisible = false;
        unregisterSettingsObserver();
        mainHandler.removeCallbacks(debouncedSettingsRefresh);
        updatePerfLoopState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityVisible = false;
        unregisterSettingsObserver();
        mainHandler.removeCallbacks(perfTickRunnable);
        mainHandler.removeCallbacks(debouncedSettingsRefresh);
        perfExecutor.shutdownNow();
    }

    private boolean isPerfActive() {
        return isActivityVisible && currentTab == TAB_PERF && !isFinishing() && !isDestroyed();
    }

    private void updatePerfLoopState() {
        mainHandler.removeCallbacks(perfTickRunnable);
        if (isPerfActive()) mainHandler.post(perfTickRunnable);
    }

    private void scheduleInstantSettingsRefresh() {
        if (!isActivityVisible) return;
        mainHandler.removeCallbacks(debouncedSettingsRefresh);
        mainHandler.postDelayed(debouncedSettingsRefresh, 30L);
    }

    private void registerSettingsObserver() {
        if (settingsObserverRegistered) return;
        if (settingsObserver == null) {
            settingsObserver = new ContentObserver(mainHandler) {
                @Override public void onChange(boolean selfChange) { scheduleInstantSettingsRefresh(); }
                @Override public void onChange(boolean selfChange, Uri uri) { scheduleInstantSettingsRefresh(); }
            };
        }

        Uri[] observedUris = new Uri[]{
                Settings.Global.getUriFor("sem_low_heat_mode"),
                Settings.Global.getUriFor("low_power"),
                Settings.Global.getUriFor("wifi_scan_always_enabled"),
                Settings.Global.getUriFor("ble_scan_always_enabled"),
                Settings.Global.getUriFor("protect_battery"),
                Settings.Global.getUriFor("ram_expand_size"),
                Settings.Global.getUriFor("preferred_network_mode"),
                Settings.System.getUriFor("screen_off_timeout"),
                Settings.System.getUriFor("aod_mode"),
                Settings.System.getUriFor("aod_show_for_new_noti"),
                Settings.System.getUriFor("nearby_scanning_enabled"),
                Settings.Secure.getUriFor("refresh_rate_mode"),
                Settings.Secure.getUriFor("reduce_bright_colors_activated"),
                Settings.Secure.getUriFor("reduce_bright_colors_level")
        };

        ContentResolver cr = getContentResolver();
        for (Uri uri : observedUris) {
            if (uri != null) {
                try { cr.registerContentObserver(uri, false, settingsObserver); } catch (Exception ignored) {}
            }
        }
        settingsObserverRegistered = true;
    }

    private void unregisterSettingsObserver() {
        if (!settingsObserverRegistered || settingsObserver == null) return;
        try { getContentResolver().unregisterContentObserver(settingsObserver); } catch (Exception ignored) {}
        settingsObserverRegistered = false;
    }

    private void refreshDashboard() {
        AnalyticsEngine.AnalysisResult res = AnalyticsEngine.analyze(this);

        if (res.hasLowHeatSetting) {
            badgeProfile.setVisibility(View.VISIBLE);
            applyStatusPill(badgeProfile, "LEVE: ON", "LEVE: OFF", res.lowHeatActive);
        } else {
            badgeProfile.setVisibility(View.GONE);
        }

        if (res.hasExtraDimSetting) {
            badgeExtraDim.setVisibility(View.VISIBLE);
            String dimOnText = res.extraDimLevel >= 0 ? "BRILHO-: " + res.extraDimLevel + "%" : "BRILHO-: ON";
            applyStatusPill(badgeExtraDim, dimOnText, "BRILHO-: OFF", res.extraDimActive);
        } else {
            badgeExtraDim.setVisibility(View.GONE);
        }

        String jobPart = res.autoJobArmed ? "AUTO ON" : "AUTO PEND.";
        if (res.vulkanActive) {
            badgeRenderer.setText("VULKAN · " + jobPart);
            if (res.autoJobArmed) {
                badgeRenderer.setBackgroundResource(R.drawable.bg_pill_green);
                badgeRenderer.setTextColor(Color.parseColor("#6EE7B7"));
            } else {
                badgeRenderer.setBackgroundResource(R.drawable.bg_pill_dark);
                badgeRenderer.setTextColor(Color.parseColor("#CBD5E1"));
            }
        } else {
            badgeRenderer.setText("HWUI · " + jobPart);
            badgeRenderer.setBackgroundResource(R.drawable.bg_pill_dark);
            badgeRenderer.setTextColor(Color.parseColor("#CBD5E1"));
        }

        radarChart.setScores(res.radarCurrent, res.radarBaseline);
        trendChart.setCycles(res.trendCycles);

        tvHardwareTileLeft.setText(res.hardwareTileLeft);
        tvHardwareTileRight.setText(res.hardwareTileRight);
        tvHardwareSummary.setText(res.hardwareSummary);
        tvCurrentSummary.setText(res.currentSummary);
        tvScreenComparison.setText(res.screenTimeComparison);
        tvRateComparison.setText(res.rateComparison);
        tvCausalDiagnosis.setText(res.causalDiagnosis);
        tvAuditTitle.setText("AUDITOR DO SISTEMA · SCORE " + res.settingsScorePct + "%");
        tvAudit.setText(res.auditReport);
        tvPermStatus.setText(res.permissionStatusText);
        tvHistoryList.setText(res.historySummary);
        tvStorageUsage.setText(StorageManager.getStorageUsageSummary());

        if (!sentinelLiveShown) {
            String summary = (res.sentinelSummary != null && !res.sentinelSummary.isEmpty())
                    ? res.sentinelSummary
                    : "Último ciclo sem alertas. Toque acima para varredura instantânea (0% em 2º plano).";
            applySentinelCard(res.sentinelSeverity, summary, false);
        }

        if (etUserContext.getText().toString().trim().isEmpty() && res.latestUserNote != null && !res.latestUserNote.isEmpty()) {
            etUserContext.setText(res.latestUserNote);
        }
    }

    private void applySentinelCard(String severity, String text, boolean live) {
        String prefix = live ? "SENTINELA ANTI-WAKELOCK · AO VIVO" : "SENTINELA ANTI-WAKELOCK · ÚLTIMO CICLO";
        if ("CRITICO".equals(severity)) {
            tvSentinelTitle.setText(prefix + " [CRÍTICO]");
            tvSentinelTitle.setTextColor(Color.parseColor("#FDA4AF"));
        } else if ("ATENCAO".equals(severity)) {
            tvSentinelTitle.setText(prefix + " [ATENÇÃO]");
            tvSentinelTitle.setTextColor(Color.parseColor("#FBBF24"));
        } else {
            tvSentinelTitle.setText(prefix + " [LIMPO]");
            tvSentinelTitle.setTextColor(Color.parseColor("#6EE7B7"));
        }
        tvSentinelReport.setText(text);
    }

    private void applyStatusPill(TextView pill, String activeText, String inactiveText, boolean active) {
        if (active) {
            pill.setText(activeText);
            pill.setBackgroundResource(R.drawable.bg_pill_blue);
            pill.setTextColor(Color.parseColor("#93C5FD"));
        } else {
            pill.setText(inactiveText);
            pill.setBackgroundResource(R.drawable.bg_pill_red);
            pill.setTextColor(Color.parseColor("#FDA4AF"));
        }
    }
}
