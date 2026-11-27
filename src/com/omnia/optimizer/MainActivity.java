package com.omnia.optimizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
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
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_RESTORE_BACKUP = 2001;
    private static final int REQ_NOTIF_PERMISSION = 2002;
    public static final String EXTRA_OPEN_TAB = "open_tab";
    public static final int TAB_HUD = 0;
    public static final int TAB_PERF = 1;
    public static final int TAB_DIAG = 2;
    public static final int TAB_AUDIT = 3;
    public static final int TAB_VAULT = 4;
    private static final int SYS_SUB_CPU = 0;
    private static final int SYS_SUB_RAM = 1;
    private static final int SYS_SUB_SHELL = 2;
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
    private View navHud, navPerf, navDiag, navAudit, navVault;
    private View[] navPills;
    private ImageView[] navIcons;
    private TextView[] navLabels;
    private TextView segSysCpu, segSysRam, segSysShell;
    private View subPerfCpu, subPerfRam, subPerfShell;
    private int currentSysSubTab = SYS_SUB_CPU;
    private MemHogsViewBinding memHogsBinder;
    private LocalShellViewBinding localShellBinder;
    private final View[] accBodies = new View[4];
    private final ImageView[] accChevrons = new ImageView[4];
    private final TextView[] accSummaries = new TextView[4];
    private final boolean[] accExpanded = new boolean[]{true, false, false, false};
    private ImageView btnToggleSentinelDetails;
    private View layoutSentinelDetails;
    private boolean sentinelDetailsOpen = true;

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
    private Button btnCleanDozeWhitelist;
    private Button btnShizukuAutoSetup;
    private Button btnQuickBatteryFixes;
    private Button btnCopyAdbCmd;
    private Button btnGrantUsagePerm;
    private Button btnGrantFilesPerm;
    private Button btnVulkanApply;
    private Button btnVulkanVerify;
    private Button btnVulkanRevert;
    private Button btnOpenAodSettings;
    private Button btnOpenWifi7Labs;
    private TextView tvShizukuStatus;
    private TextView tvVulkanStatus;
    private View layoutSentinelActions;
    private Button btnHuntControl;
    private Button btnHuntUndo;
    private View cardRebootDrift;
    private TextView tvRebootDriftDetails;
    private Button btnFixRebootDrift;
    private Button btnDismissRebootDrift;

    private TextView tvQuickControlsStatus;
    private TextView tvMaintenanceStatus;
    private Button btnToggleLowHeat;
    private Button btnToggleProtectBattery;
    private Button btnCycleAodMode;
    private View layoutExtraDimControls;
    private Button btnToggleExtraDim;
    private SeekBar seekExtraDimLevel;
    private TextView tvExtraDimPct;
    private Button btnToggleNetworkMode;
    private Button btnConfigureRefreshHz;
    private Button btnToggleMcfContinuity;
    private Button btnToggleMosey;
    private Button btnArtCompile;
    private Button btnToggleFreezer;
    private SeekBar seekAnimWindow;
    private SeekBar seekAnimTransition;
    private SeekBar seekAnimAnimator;
    private TextView tvAnimWindowVal;
    private TextView tvAnimTransitionVal;
    private TextView tvAnimAnimatorVal;
    private Button btnAnimPresetIdeal;
    private Button btnAnimPresetFast;
    private Button btnAnimPresetStock;
    private Button btnTrimCaches;

    private boolean isBindingChecklist = false;
    private boolean sentinelLiveShown = false;
    private boolean isBindingExtraDimSeek = false;
    private boolean isBindingAnimSeek = false;
    private volatile List<WakelockSentinel.SuspectApp> lastSentinelSuspects = new java.util.ArrayList<WakelockSentinel.SuspectApp>();
    private volatile SystemTweaksController.SystemState lastSystemState;
    private volatile VulkanController.RendererStatus lastVulkanStatus;
    private boolean isActivityVisible = false;
    private boolean settingsObserverRegistered = false;
    private int currentTab = TAB_HUD;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService perfExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean perfReadInFlight = new AtomicBoolean(false);
    private final ExecutorService shizukuExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean shizukuReadInFlight = new AtomicBoolean(false);
    private volatile boolean shizukuPendingGfx = false;
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
        navHud = findViewById(R.id.navHud);
        navPerf = findViewById(R.id.navPerf);
        navDiag = findViewById(R.id.navDiag);
        navAudit = findViewById(R.id.navAudit);
        navVault = findViewById(R.id.navVault);
        initModernUiExtensions();

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
        btnCleanDozeWhitelist = (Button) findViewById(R.id.btnCleanDozeWhitelist);
        btnShizukuAutoSetup = (Button) findViewById(R.id.btnShizukuAutoSetup);
        btnQuickBatteryFixes = (Button) findViewById(R.id.btnQuickBatteryFixes);
        btnCopyAdbCmd = (Button) findViewById(R.id.btnCopyAdbCmd);
        btnGrantUsagePerm = (Button) findViewById(R.id.btnGrantUsagePerm);
        btnGrantFilesPerm = (Button) findViewById(R.id.btnGrantFilesPerm);
        btnVulkanApply = (Button) findViewById(R.id.btnVulkanApply);
        btnVulkanVerify = (Button) findViewById(R.id.btnVulkanVerify);
        btnVulkanRevert = (Button) findViewById(R.id.btnVulkanRevert);
        btnOpenAodSettings = (Button) findViewById(R.id.btnOpenAodSettings);
        btnOpenWifi7Labs = (Button) findViewById(R.id.btnOpenWifi7Labs);
        tvShizukuStatus = (TextView) findViewById(R.id.tvShizukuStatus);
        tvVulkanStatus = (TextView) findViewById(R.id.tvVulkanStatus);
        layoutSentinelActions = findViewById(R.id.layoutSentinelActions);
        btnHuntControl = (Button) findViewById(R.id.btnHuntControl);
        btnHuntUndo = (Button) findViewById(R.id.btnHuntUndo);
        cardRebootDrift = findViewById(R.id.cardRebootDrift);
        tvRebootDriftDetails = (TextView) findViewById(R.id.tvRebootDriftDetails);
        btnFixRebootDrift = (Button) findViewById(R.id.btnFixRebootDrift);
        btnDismissRebootDrift = (Button) findViewById(R.id.btnDismissRebootDrift);

        tvQuickControlsStatus = (TextView) findViewById(R.id.tvQuickControlsStatus);
        tvMaintenanceStatus = (TextView) findViewById(R.id.tvMaintenanceStatus);
        btnToggleLowHeat = (Button) findViewById(R.id.btnToggleLowHeat);
        btnToggleProtectBattery = (Button) findViewById(R.id.btnToggleProtectBattery);
        btnCycleAodMode = (Button) findViewById(R.id.btnCycleAodMode);
        layoutExtraDimControls = findViewById(R.id.layoutExtraDimControls);
        btnToggleExtraDim = (Button) findViewById(R.id.btnToggleExtraDim);
        seekExtraDimLevel = (SeekBar) findViewById(R.id.seekExtraDimLevel);
        tvExtraDimPct = (TextView) findViewById(R.id.tvExtraDimPct);
        btnToggleNetworkMode = (Button) findViewById(R.id.btnToggleNetworkMode);
        btnConfigureRefreshHz = (Button) findViewById(R.id.btnConfigureRefreshHz);
        btnToggleMcfContinuity = (Button) findViewById(R.id.btnToggleMcfContinuity);
        btnToggleMosey = (Button) findViewById(R.id.btnToggleMosey);
        btnArtCompile = (Button) findViewById(R.id.btnArtCompile);
        btnToggleFreezer = (Button) findViewById(R.id.btnToggleFreezer);
        seekAnimWindow = (SeekBar) findViewById(R.id.seekAnimWindow);
        seekAnimTransition = (SeekBar) findViewById(R.id.seekAnimTransition);
        seekAnimAnimator = (SeekBar) findViewById(R.id.seekAnimAnimator);
        tvAnimWindowVal = (TextView) findViewById(R.id.tvAnimWindowVal);
        tvAnimTransitionVal = (TextView) findViewById(R.id.tvAnimTransitionVal);
        tvAnimAnimatorVal = (TextView) findViewById(R.id.tvAnimAnimatorVal);
        btnAnimPresetIdeal = (Button) findViewById(R.id.btnAnimPresetIdeal);
        btnAnimPresetFast = (Button) findViewById(R.id.btnAnimPresetFast);
        btnAnimPresetStock = (Button) findViewById(R.id.btnAnimPresetStock);
        btnTrimCaches = (Button) findViewById(R.id.btnTrimCaches);

        setupTabs();
        setupShortcuts();
        setupSystemTweaksControls();
        loadChecklist();
        setupChecklistListeners();
        ChargingJobService.ensureArmed(this);
        maybeRequestNotificationsPermission();
        new Thread(new Runnable() {
            @Override
            public void run() {
                VulkanController.restorePendingKeptSettings(MainActivity.this);
            }
        }).start();
        applyOpenTabIntent(getIntent());
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
                        lastSentinelSuspects = rep.suspects;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnHuntWakelocks.setEnabled(true);
                                btnHuntWakelocks.setText("🕵️ CAÇAR LADRÃO AGORA");
                                sentinelLiveShown = true;
                                applySentinelCard(rep.severity, rep.details, true);
                                OmniaWidgetProvider.saveLiveHuntReport(MainActivity.this, rep);
                                updateSentinelActionButtons();
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
                                btnCollectNow.setText("Ler Agora");
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
                        btnDriveBackup.setText("Backup Drive");
                        btnQuickBackup.setText("Backup Drive");
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

    private void initModernUiExtensions() {
        navPills = new View[]{
                findViewById(R.id.navPillHud),
                findViewById(R.id.navPillPerf),
                findViewById(R.id.navPillDiag),
                findViewById(R.id.navPillAudit),
                findViewById(R.id.navPillVault)
        };
        navIcons = new ImageView[]{
                (ImageView) findViewById(R.id.navIconHud),
                (ImageView) findViewById(R.id.navIconPerf),
                (ImageView) findViewById(R.id.navIconDiag),
                (ImageView) findViewById(R.id.navIconAudit),
                (ImageView) findViewById(R.id.navIconVault)
        };
        navLabels = new TextView[]{
                (TextView) findViewById(R.id.navLabelHud),
                (TextView) findViewById(R.id.navLabelPerf),
                (TextView) findViewById(R.id.navLabelDiag),
                (TextView) findViewById(R.id.navLabelAudit),
                (TextView) findViewById(R.id.navLabelVault)
        };

        segSysCpu = (TextView) findViewById(R.id.segSysCpu);
        segSysRam = (TextView) findViewById(R.id.segSysRam);
        segSysShell = (TextView) findViewById(R.id.segSysShell);
        subPerfCpu = findViewById(R.id.subPerfCpu);
        subPerfRam = findViewById(R.id.subPerfRam);
        subPerfShell = findViewById(R.id.subPerfShell);

        if (segSysCpu != null) segSysCpu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectSysSubTab(SYS_SUB_CPU); }
        });
        if (segSysRam != null) segSysRam.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectSysSubTab(SYS_SUB_RAM); }
        });
        if (segSysShell != null) segSysShell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectSysSubTab(SYS_SUB_SHELL); }
        });

        memHogsBinder = new MemHogsViewBinding(this, mainHandler);
        if (subPerfRam != null) memHogsBinder.bind(subPerfRam);

        localShellBinder = new LocalShellViewBinding(this, mainHandler);
        if (subPerfShell != null) localShellBinder.bind(subPerfShell);

        selectSysSubTab(SYS_SUB_CPU);

        btnToggleSentinelDetails = (ImageView) findViewById(R.id.btnToggleSentinelDetails);
        layoutSentinelDetails = findViewById(R.id.layoutSentinelDetails);
        if (btnToggleSentinelDetails != null && layoutSentinelDetails != null) {
            btnToggleSentinelDetails.setColorFilter(OmniaTheme.color(this, R.color.omnia_text_secondary));
            btnToggleSentinelDetails.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    OmniaTheme.hapticTap(v);
                    sentinelDetailsOpen = !sentinelDetailsOpen;
                    layoutSentinelDetails.setVisibility(sentinelDetailsOpen ? View.VISIBLE : View.GONE);
                    btnToggleSentinelDetails.setImageResource(sentinelDetailsOpen ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
                }
            });
        }

        int[] headIds = new int[]{R.id.layoutAccHead1, R.id.layoutAccHead2, R.id.layoutAccHead3, R.id.layoutAccHead4};
        int[] bodyIds = new int[]{R.id.layoutAccBody1, R.id.layoutAccBody2, R.id.layoutAccBody3, R.id.layoutAccBody4};
        int[] chevIds = new int[]{R.id.ivAccChevron1, R.id.ivAccChevron2, R.id.ivAccChevron3, R.id.ivAccChevron4};
        int[] sumIds = new int[]{R.id.tvAccSummary1, R.id.tvAccSummary2, R.id.tvAccSummary3, R.id.tvAccSummary4};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            View head = findViewById(headIds[i]);
            accBodies[i] = findViewById(bodyIds[i]);
            accChevrons[i] = (ImageView) findViewById(chevIds[i]);
            accSummaries[i] = (TextView) findViewById(sumIds[i]);
            if (accChevrons[i] != null) {
                accChevrons[i].setColorFilter(OmniaTheme.color(this, R.color.omnia_text_secondary));
                accChevrons[i].setImageResource(accExpanded[i] ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
            }
            if (accBodies[i] != null) {
                accBodies[i].setVisibility(accExpanded[i] ? View.VISIBLE : View.GONE);
            }
            if (head != null) {
                head.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        OmniaTheme.hapticTap(v);
                        toggleAccordion(idx);
                    }
                });
            }
        }
    }

    private void toggleAccordion(int idx) {
        if (idx < 0 || idx >= accBodies.length || accBodies[idx] == null) return;
        accExpanded[idx] = !accExpanded[idx];
        accBodies[idx].setVisibility(accExpanded[idx] ? View.VISIBLE : View.GONE);
        if (accChevrons[idx] != null) {
            accChevrons[idx].setImageResource(accExpanded[idx] ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
        }
    }

    private void selectSysSubTab(int subTab) {
        currentSysSubTab = subTab;
        if (subPerfCpu != null) subPerfCpu.setVisibility(subTab == SYS_SUB_CPU ? View.VISIBLE : View.GONE);
        if (subPerfRam != null) subPerfRam.setVisibility(subTab == SYS_SUB_RAM ? View.VISIBLE : View.GONE);
        if (subPerfShell != null) subPerfShell.setVisibility(subTab == SYS_SUB_SHELL ? View.VISIBLE : View.GONE);

        styleSegmentTab(segSysCpu, subTab == SYS_SUB_CPU);
        styleSegmentTab(segSysRam, subTab == SYS_SUB_RAM);
        styleSegmentTab(segSysShell, subTab == SYS_SUB_SHELL);

        updatePerfLoopState();
        if (memHogsBinder != null) {
            memHogsBinder.setVisible(isActivityVisible && currentTab == TAB_PERF && currentSysSubTab == SYS_SUB_RAM);
        }
    }

    private void styleSegmentTab(TextView tv, boolean active) {
        if (tv == null) return;
        if (active) {
            tv.setBackgroundResource(R.drawable.bg_segmented_active);
            tv.setTextColor(OmniaTheme.color(this, R.color.omnia_on_primary_container));
        } else {
            tv.setBackgroundColor(Color.TRANSPARENT);
            tv.setTextColor(OmniaTheme.color(this, R.color.omnia_text_secondary));
        }
    }

    private void updateAccordionSummaries(SystemTweaksController.SystemState st, int scorePct, VulkanController.RendererStatus vk) {
        if (accSummaries[0] != null && st != null) {
            String light = st.lowHeatActive ? "Leve ON" : "Leve OFF";
            String bat = st.protectBatteryMode > 0 ? "Carga 80%" : "Carga 100%";
            String net = st.networkHas5gNr ? "5G Auto" : "4G/LTE";
            String mcf = ("1".equals(st.mcfContinuity) || "1".equals(st.mcfMyDevice)) ? "MCF ON" : "MCF OFF";
            accSummaries[0].setText(light + " · " + bat + " · " + net + " · " + mcf);
        }
        if (accSummaries[1] != null && st != null) {
            String hz = st.peakRefreshHz > 0f ? (Math.round(st.peakRefreshHz) + "Hz") : "Auto";
            String dim = st.extraDimActive ? ("Brilho- " + st.extraDimLevel + "%") : "Brilho- OFF";
            String anim = String.format(Locale.US, "%.1f/%.1f/%.1fx",
                    st.windowScale, st.transitionScale, st.animatorScale);
            accSummaries[1].setText("Hz " + hz + " · " + dim + " · Anim " + anim);
        }
        if (accSummaries[2] != null && st != null) {
            String frz = "enabled".equals(st.cachedAppsFreezer) ? "Freezer ON" : "Freezer Padrão";
            accSummaries[2].setText(frz + " · ART speed-profile & Caches sob demanda");
        }
        if (accSummaries[3] != null) {
            String vkTxt = (vk != null && vk.propVulkan) ? "Vulkan skiavk ativo" : "OpenGL padrão";
            accSummaries[3].setText(vkTxt + " · Auditor Score " + scorePct + "%");
        }
    }

    private void setupTabs() {
        navHud.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectTab(TAB_HUD); } });
        navPerf.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectTab(TAB_PERF); } });
        navDiag.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectTab(TAB_DIAG); } });
        navAudit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectTab(TAB_AUDIT); } });
        navVault.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { OmniaTheme.hapticTap(v); selectTab(TAB_VAULT); } });
        selectTab(TAB_HUD);
    }

    private void selectTab(int index) {
        currentTab = index;
        tabHud.setVisibility(index == TAB_HUD ? View.VISIBLE : View.GONE);
        tabPerf.setVisibility(index == TAB_PERF ? View.VISIBLE : View.GONE);
        tabDiag.setVisibility(index == TAB_DIAG ? View.VISIBLE : View.GONE);
        tabAudit.setVisibility(index == TAB_AUDIT ? View.VISIBLE : View.GONE);
        tabVault.setVisibility(index == TAB_VAULT ? View.VISIBLE : View.GONE);

        styleNavSlot(0, index == TAB_HUD);
        styleNavSlot(1, index == TAB_PERF);
        styleNavSlot(2, index == TAB_DIAG);
        styleNavSlot(3, index == TAB_AUDIT);
        styleNavSlot(4, index == TAB_VAULT);

        mainScroll.scrollTo(0, 0);
        updatePerfLoopState();
        if (memHogsBinder != null) {
            memHogsBinder.setVisible(isActivityVisible && index == TAB_PERF && currentSysSubTab == SYS_SUB_RAM);
        }
        if (index == TAB_AUDIT && tvShizukuStatus != null) {
            refreshShizukuAndVulkanAsync(true);
        }
    }

    private void styleNavSlot(int index, boolean active) {
        if (navPills != null && index < navPills.length && navPills[index] != null) {
            if (active) navPills[index].setBackgroundResource(R.drawable.bg_nav_active);
            else navPills[index].setBackgroundColor(Color.TRANSPARENT);
        }
        if (navIcons != null && index < navIcons.length && navIcons[index] != null) {
            navIcons[index].setColorFilter(OmniaTheme.color(this,
                    active ? R.color.omnia_on_primary_container : R.color.omnia_text_secondary));
        }
        if (navLabels != null && index < navLabels.length && navLabels[index] != null) {
            navLabels[index].setTextColor(OmniaTheme.color(this,
                    active ? R.color.omnia_text_primary : R.color.omnia_text_secondary));
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
                            btnRestoreBackup.setText("Restaurar");
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
                        "dumpsys deviceidle whitelist +" + pkg + "; " +
                        "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true";
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

        btnShizukuAutoSetup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runShizukuAutoSetup();
            }
        });

        btnQuickBatteryFixes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmQuickBatteryFixes();
            }
        });

        btnVulkanApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVulkanApplyDialog(true);
            }
        });

        btnVulkanVerify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnVulkanVerify.setEnabled(false);
                refreshShizukuAndVulkanAsync(true);
            }
        });

        btnVulkanRevert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVulkanApplyDialog(false);
            }
        });

        btnOpenAodSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent samsungAod = new Intent();
                samsungAod.setClassName("com.android.settings", "com.android.settings.Settings$LockscreenSettingsActivity");
                openSettingsSafe(samsungAod, new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            }
        });

        btnOpenWifi7Labs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final ShizukuBridge.ExecResult res = TweaksAudit.openWifi7Labs(MainActivity.this);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (res.isOk()) {
                                    Toast.makeText(MainActivity.this, "Wi-Fi 7 Labs aberto!", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(MainActivity.this,
                                            "Atalho Samsung Wi-Fi 7 Labs requer Shizuku/Root nesta ROM: "
                                                    + (res.error != null ? res.error : res.stderr),
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private void runShizukuAutoSetup() {
        btnShizukuAutoSetup.setEnabled(false);
        btnShizukuAutoSetup.setText("⏳ CONECTANDO...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ShizukuBridge.ShizukuStatus st = ShizukuBridge.getStatus(MainActivity.this);
                if (!st.serviceRunning) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            btnShizukuAutoSetup.setEnabled(true);
                            btnShizukuAutoSetup.setText("🔗 SHIZUKU AUTO-ATIVAR");
                            updateShizukuText(st);
                            Toast.makeText(MainActivity.this, st.detail, Toast.LENGTH_LONG).show();
                            if (st.managerInstalled) {
                                try {
                                    Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE);
                                    if (i != null) startActivity(i);
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
                    return;
                }
                if (!st.permissionGranted) {
                    ShizukuBridge.requestPermission(MainActivity.this, new ShizukuBridge.PermissionCallback() {
                        @Override
                        public void onResult(final boolean allowed) {
                            if (allowed) {
                                completeShizukuAutoGrant();
                            } else {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        btnShizukuAutoSetup.setEnabled(true);
                                        btnShizukuAutoSetup.setText("🔗 SHIZUKU AUTO-ATIVAR");
                                        Toast.makeText(MainActivity.this, "Autorização Shizuku negada.", Toast.LENGTH_SHORT).show();
                                        refreshShizukuAndVulkanAsync(false);
                                    }
                                });
                            }
                        }
                    });
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            btnShizukuAutoSetup.setEnabled(true);
                            btnShizukuAutoSetup.setText("🔗 SHIZUKU AUTO-ATIVAR");
                        }
                    });
                    return;
                }
                completeShizukuAutoGrant();
            }
        }).start();
    }

    private void completeShizukuAutoGrant() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ShizukuBridge.ExecResult gRes = ShizukuBridge.grantOmniaPermissions(MainActivity.this);
                final ShizukuBridge.ChemicalBatteryCache chem = ShizukuBridge.refreshChemicalHealthAndOverlays(MainActivity.this);
                final VulkanController.RendererStatus vkSt = VulkanController.inspectStatus(MainActivity.this, true);
                lastVulkanStatus = vkSt;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnShizukuAutoSetup.setEnabled(true);
                        btnShizukuAutoSetup.setText("🔗 SHIZUKU AUTO-ATIVAR");
                        refreshDashboard();
                        refreshShizukuAndVulkanAsync(true);
                        StringBuilder msg = new StringBuilder();
                        msg.append(gRes.isOk()
                                ? "✅ Permissões do Omnia liberadas sem PC!"
                                : "⚠️ Aviso Shizuku: " + (gRes.error != null ? gRes.error : gRes.stderr));
                        if (chem != null && chem.asoc > 0) {
                            msg.append(String.format(Locale.US, "\nASOC %.0f%%", chem.asoc));
                            if (chem.cycles > 0) msg.append(String.format(Locale.US, " · %.1f ciclos", chem.cycles));
                        }
                        Toast.makeText(MainActivity.this, msg.toString(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void confirmQuickBatteryFixes() {
        new AlertDialog.Builder(this)
                .setTitle("Aplicar Ajustes Auditados (Reversíveis)")
                .setMessage("Via Shizuku, ajusta somente varreduras ociosas e tempo de tela:\n"
                        + "• Wi-Fi / BLE em 2º plano = 0\n"
                        + "• Busca de aparelhos próximos = 0\n"
                        + "• Tempo limite da tela <= 60s (se maior)\n\n"
                        + "Não altera 120Hz, resolução nativa, modo Leve nem apps em uso.")
                .setPositiveButton("Aplicar agora", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final List<String> changes = ShizukuBridge.applyRecommendedBatteryTweaks(MainActivity.this);
                                RebootDriftTracker.recordBaselineNow(MainActivity.this);
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        refreshDashboard();
                                        String txt = changes.isEmpty()
                                                ? "✅ Todos esses ajustes já estavam otimizados!"
                                                : android.text.TextUtils.join("\n", changes);
                                        Toast.makeText(MainActivity.this, txt, Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showVulkanApplyDialog(final boolean enableVulkan) {
        if (enableVulkan) {
            final VulkanController.RendererStatus snap = lastVulkanStatus;
            int behindCount = (snap != null && snap.behindScanned) ? snap.behindOpenPackages.size() : -1;
            String behindOption = behindCount > 0
                    ? String.format(Locale.US, "🔄 Só apps abertos em OpenGL (%d atrasados)", behindCount)
                    : "🔄 Só apps abertos atrasados (via gfxinfo)";
            String[] items = new String[]{
                    "⚡ Leve · System UI + Home + Teclado (trava tela 1-2s)",
                    "🌙 Leve sem travar tela agora (Home + Teclado)",
                    behindOption,
                    "🚀 Completo · Todos apps + restaura widgets/acessib."
            };
            new AlertDialog.Builder(this)
                    .setTitle("Ativar API Vulkan (HWUI)")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            VulkanController.ApplyMode mode;
                            if (which == 0) mode = VulkanController.ApplyMode.LIGHT_WITH_SYSTEM_UI;
                            else if (which == 1) mode = VulkanController.ApplyMode.LIGHT_DEFER_SYSTEM_UI;
                            else if (which == 2) mode = VulkanController.ApplyMode.OPEN_APPS_BEHIND;
                            else mode = VulkanController.ApplyMode.FULL_WITH_SYSTEM_UI;
                            executeVulkanMode(mode, true);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        } else {
            String[] items = new String[]{
                    "↩️ Reverter c/ System UI (volta tudo p/ OpenGL)",
                    "🌙 Reverter sem travar tela agora (prop + Home + Teclado)"
            };
            new AlertDialog.Builder(this)
                    .setTitle("Reverter Renderizador para OpenGL Padrão")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            executeVulkanMode(
                                    which == 0 ? VulkanController.ApplyMode.REVERT_WITH_SYSTEM_UI
                                               : VulkanController.ApplyMode.REVERT_DEFER_SYSTEM_UI,
                                    false);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }
    }

    private void executeVulkanMode(final VulkanController.ApplyMode mode, final boolean enabling) {
        btnVulkanApply.setEnabled(false);
        btnVulkanRevert.setEnabled(false);
        btnVulkanVerify.setEnabled(false);
        tvVulkanStatus.setText(enabling ? "⚡ Aplicando API Vulkan..." : "↩️ Revertendo para padrão OpenGL...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final VulkanController.ApplyResult res = VulkanController.applyMode(MainActivity.this, mode);
                if (res.status != null) lastVulkanStatus = res.status;
                if (res.ok) RebootDriftTracker.recordBaselineNow(MainActivity.this);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnVulkanApply.setEnabled(true);
                        btnVulkanRevert.setEnabled(true);
                        btnVulkanVerify.setEnabled(true);
                        refreshDashboard();
                        if (res.status != null) updateVulkanText(res.status);
                        Toast.makeText(MainActivity.this, res.summary, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private interface TweakTask {
        SystemTweaksController.ActionResult run();
    }

    private void runShizukuTweak(final TweakTask task) {
        shizukuExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final SystemTweaksController.ActionResult res = task.run();
                if (res.ok) RebootDriftTracker.recordBaselineNow(MainActivity.this);
                final SystemTweaksController.SystemState st = SystemTweaksController.readLocalState(MainActivity.this);
                lastSystemState = st;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshDashboard();
                        updateSystemControlsUi(st);
                        Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void setupSystemTweaksControls() {
        btnFixRebootDrift.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnFixRebootDrift.setEnabled(false);
                btnFixRebootDrift.setText("⏳ REAPLICANDO...");
                shizukuExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final SystemTweaksController.ActionResult res = RebootDriftTracker.applyPendingDriftFixes(MainActivity.this);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnFixRebootDrift.setEnabled(true);
                                btnFixRebootDrift.setText("⚡ REAPLICAR VIA SHIZUKU");
                                refreshDashboard();
                                refreshShizukuAndVulkanAsync(true);
                                Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });
        btnDismissRebootDrift.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RebootDriftTracker.dismissDrift(MainActivity.this);
                refreshDashboard();
                Toast.makeText(MainActivity.this, "Alerta de drift dispensado para este boot.", Toast.LENGTH_SHORT).show();
            }
        });
        btnCleanDozeWhitelist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDozeWhitelistDialog();
            }
        });
        btnHuntControl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHuntControlDialog();
            }
        });
        btnHuntUndo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmUndoAllRestrictions();
            }
        });

        btnToggleLowHeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runShizukuTweak(new TweakTask() {
                    @Override public SystemTweaksController.ActionResult run() {
                        return SystemTweaksController.toggleLowHeatMode(MainActivity.this);
                    }
                });
            }
        });

        btnToggleProtectBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runShizukuTweak(new TweakTask() {
                    @Override public SystemTweaksController.ActionResult run() {
                        return SystemTweaksController.toggleProtectBattery(MainActivity.this);
                    }
                });
            }
        });

        btnCycleAodMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAodDialog();
            }
        });

        btnToggleExtraDim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runShizukuTweak(new TweakTask() {
                    @Override public SystemTweaksController.ActionResult run() {
                        return SystemTweaksController.toggleExtraDim(MainActivity.this);
                    }
                });
            }
        });

        seekExtraDimLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvExtraDimPct.setText((progress + 5) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
                if (isBindingExtraDimSeek) return;
                final int pct = seekBar.getProgress() + 5;
                runShizukuTweak(new TweakTask() {
                    @Override public SystemTweaksController.ActionResult run() {
                        return SystemTweaksController.setExtraDimLevel(MainActivity.this, pct);
                    }
                });
            }
        });

        btnToggleNetworkMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNetworkDialog();
            }
        });

        btnConfigureRefreshHz.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRefreshHzDialog();
            }
        });

        btnToggleMcfContinuity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runShizukuTweak(new TweakTask() {
                    @Override public SystemTweaksController.ActionResult run() {
                        return SystemTweaksController.toggleMcfContinuity(MainActivity.this);
                    }
                });
            }
        });

        btnToggleMosey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoseyDialog();
            }
        });

        btnArtCompile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmArtCompile();
            }
        });

        btnToggleFreezer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runShizukuTweak(new TweakTask() {
                    @Override public SystemTweaksController.ActionResult run() {
                        return SystemTweaksController.toggleCachedAppsFreezer(MainActivity.this);
                    }
                });
            }
        });

        bindAnimationSeekBar(seekAnimWindow, tvAnimWindowVal, "window_animation_scale", "Escala de Janela");
        bindAnimationSeekBar(seekAnimTransition, tvAnimTransitionVal, "transition_animation_scale", "Escala de Transição");
        bindAnimationSeekBar(seekAnimAnimator, tvAnimAnimatorVal, "animator_duration_scale", "Duração do Animator");

        btnAnimPresetIdeal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyAnimPreset(0.7f, 0.5f, 0.5f); }
        });
        btnAnimPresetFast.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyAnimPreset(0.5f, 0.5f, 0.5f); }
        });
        btnAnimPresetStock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyAnimPreset(1.0f, 1.0f, 1.0f); }
        });

        btnTrimCaches.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTrimCaches.setEnabled(false);
                shizukuExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final SystemTweaksController.ActionResult res = SystemTweaksController.trimAppCaches(MainActivity.this);
                        final SystemTweaksController.SystemState st = SystemTweaksController.readLocalState(MainActivity.this);
                        lastSystemState = st;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnTrimCaches.setEnabled(true);
                                refreshDashboard();
                                updateSystemControlsUi(st);
                                Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });
    }

    private void showAodDialog() {
        String[] opts = new String[]{
                "👆 Toque para exibir (~18,6 mAh/ciclo)",
                "🌙 Desativado (0 mAh)",
                "⏱️ Sempre ativo (maior gasto OLED)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Always-On Display (Shizuku)")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, final int which) {
                        final int preset = which == 0 ? 1 : (which == 1 ? 0 : 2);
                        runShizukuTweak(new TweakTask() {
                            @Override public SystemTweaksController.ActionResult run() {
                                return SystemTweaksController.setAodPreset(MainActivity.this, preset);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showNetworkDialog() {
        SystemTweaksController.SystemState st = lastSystemState != null
                ? lastSystemState : SystemTweaksController.readLocalState(this);
        if (!st.hasNetworkMode) {
            openSettingsSafe(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                    new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            return;
        }
        String[] opts = new String[]{
                "🟢 4G / LTE Econômico (evita 5G em sinal fraco)",
                "📡 5G / Auto (NR + LTE)",
                "↩️ Restaurar Modo Original" + (st.origNetworkMode.isEmpty() ? "" : " (" + st.origNetworkMode + ")"),
                "📲 Abrir Tela Redes Móveis do Android"
        };
        new AlertDialog.Builder(this)
                .setTitle("Rede Móvel · Atual: " + st.networkModeRaw)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, final int which) {
                        if (which == 3) {
                            openSettingsSafe(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                                    new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                            return;
                        }
                        shizukuExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                final SystemTweaksController.ActionResult res =
                                        SystemTweaksController.setNetworkPreferenceMode(MainActivity.this, which);
                                final SystemTweaksController.SystemState nextSt =
                                        SystemTweaksController.readLocalState(MainActivity.this);
                                lastSystemState = nextSt;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        refreshDashboard();
                                        updateSystemControlsUi(nextSt);
                                        if (res.recommendMobileNetworkScreen) {
                                            new AlertDialog.Builder(MainActivity.this)
                                                    .setTitle("Preferência de Rede")
                                                    .setMessage(res.message)
                                                    .setPositiveButton("Redes Móveis", new DialogInterface.OnClickListener() {
                                                        @Override public void onClick(DialogInterface d, int w) {
                                                            openSettingsSafe(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                                                                    new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                                                        }
                                                    })
                                                    .setNegativeButton("OK", null)
                                                    .show();
                                        } else {
                                            Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                                        }
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showMoseyDialog() {
        SystemTweaksController.SystemState st = lastSystemState != null
                ? lastSystemState : SystemTweaksController.readLocalState(this);
        if (!st.moseyInstalled) {
            Toast.makeText(this, "Mosey / AirDrop não instalado neste aparelho.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = new String[]{
                "⏸️ Pausar sob demanda (disable-user + force-stop)",
                "⏹️ Só encerrar processo agora (force-stop)",
                "▶️ Reativar Mosey (pm enable)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Controle Mosey / AirDrop (" + (st.moseyDisabled ? "Pausado" : "Ativo") + ")")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, final int which) {
                        final int action = which == 0 ? 0 : (which == 1 ? 2 : 1);
                        runShizukuTweak(new TweakTask() {
                            @Override public SystemTweaksController.ActionResult run() {
                                return SystemTweaksController.controlMosey(MainActivity.this, action);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmArtCompile() {
        SystemTweaksController.BatterySafetySnapshot bat = SystemTweaksController.readBatterySafety(this);
        String msg = String.format(Locale.US,
                "Compila via Shizuku (cmd package compile -m speed-profile) apenas os 10 apps mais usados que ainda estejam em verify/interpret.\n\n"
                + "• Bateria atual: %d%% (%s) · %.1f °C\n"
                + "• Travas: recusa <20%% fora do cabo ou >=39 °C.\n"
                + "• Zero risco a dados/logins e zero execução em 2º plano.",
                bat.level, bat.plugged ? "no carregador" : "na bateria", bat.tempC);
        new AlertDialog.Builder(this)
                .setTitle("Otimizador ART (speed-profile)")
                .setMessage(msg)
                .setPositiveButton("Compilar Top Apps", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        btnArtCompile.setEnabled(false);
                        btnArtCompile.setText("⏳ COMPILANDO ART (AGUARDE)...");
                        shizukuExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                final SystemTweaksController.ActionResult res =
                                        SystemTweaksController.compileTopAppsSpeedProfile(MainActivity.this);
                                final SystemTweaksController.SystemState st =
                                        SystemTweaksController.readLocalState(MainActivity.this);
                                lastSystemState = st;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        btnArtCompile.setEnabled(true);
                                        btnArtCompile.setText("⚙️ COMPILAR ART TOP APPS (SPEED-PROFILE)");
                                        updateSystemControlsUi(st);
                                        new AlertDialog.Builder(MainActivity.this)
                                                .setTitle("Resultado ART")
                                                .setMessage(res.message)
                                                .setPositiveButton("OK", null)
                                                .show();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showHuntControlDialog() {
        final List<WakelockSentinel.SuspectApp> list = lastSentinelSuspects;
        if (list == null || list.isEmpty()) {
            Toast.makeText(this, "Rode 'Caçar Ladrão' primeiro para listar apps ativos.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int limit = Math.min(10, list.size());
        String[] labels = new String[limit];
        for (int i = 0; i < limit; i++) {
            WakelockSentinel.SuspectApp s = list.get(i);
            String prefix = s.protectedApp ? "🔒 [PROTEGIDO] " : "⚠️ ";
            labels[i] = prefix + s.appName + "\n   " + s.reason;
        }
        new AlertDialog.Builder(this)
                .setTitle("Controlar App Detectado (Shizuku)")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final WakelockSentinel.SuspectApp sel = list.get(which);
                        if (sel.protectedApp) {
                            Toast.makeText(MainActivity.this,
                                    "🔒 " + sel.appName + " é protegido pelo Omnia (mensageria/banco/sistema/mídia).",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        final List<String> actionLabels = new ArrayList<String>();
                        final List<Integer> actionModes = new ArrayList<Integer>();
                        actionLabels.add("🛑 Force-Stop agora (mais leve)"); actionModes.add(0);
                        actionLabels.add("🌙 Restringir 2º plano (appops ignore)"); actionModes.add(1);
                        actionLabels.add("📦 Standby Bucket Restricted"); actionModes.add(2);
                        actionLabels.add("🔒 Restringir tudo + Doze + Force-Stop"); actionModes.add(3);
                        if (sel.inDozeUserWhitelist) {
                            actionLabels.add("💤 Remover só da Whitelist Doze"); actionModes.add(5);
                        }
                        actionLabels.add("↩️ Restaurar padrão deste app"); actionModes.add(4);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(sel.appName + " (" + sel.packageName + ")")
                                .setItems(actionLabels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, final int actionIdx) {
                                        final int mode = actionModes.get(actionIdx);
                                        runShizukuTweak(new TweakTask() {
                                            @Override public SystemTweaksController.ActionResult run() {
                                                return SystemTweaksController.applyAppControlAction(
                                                        MainActivity.this, sel.packageName, sel.appName, mode);
                                            }
                                        });
                                    }
                                })
                                .setNegativeButton("Voltar", null)
                                .show();
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void confirmUndoAllRestrictions() {
        final List<SystemTweaksController.UndoEntry> entries = SystemTweaksController.loadUndoEntries(this);
        if (entries.isEmpty()) {
            updateSentinelActionButtons();
            return;
        }
        StringBuilder sb = new StringBuilder("Serão restaurados ao estado original anterior:\n");
        for (SystemTweaksController.UndoEntry e : entries) {
            sb.append("\n• ").append(e.appLabel)
                    .append(" (op=").append(e.prevRunAnyBg)
                    .append(", bucket=").append(e.prevBucket);
            if (e.prevDozeMode != null && !e.prevDozeMode.isEmpty()) {
                sb.append(", doze=+").append(e.prevDozeMode);
            }
            sb.append(")");
        }
        new AlertDialog.Builder(this)
                .setTitle("Desfazer Restrições (" + entries.size() + ")")
                .setMessage(sb.toString())
                .setPositiveButton("Restaurar Tudo", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runShizukuTweak(new TweakTask() {
                            @Override public SystemTweaksController.ActionResult run() {
                                return SystemTweaksController.undoAllRestrictions(MainActivity.this);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateSentinelActionButtons() {
        if (layoutSentinelActions == null) return;
        int undoCount = SystemTweaksController.loadUndoEntries(this).size();
        boolean hasSuspects = lastSentinelSuspects != null && !lastSentinelSuspects.isEmpty();
        if (!hasSuspects && undoCount == 0) {
            layoutSentinelActions.setVisibility(View.GONE);
            return;
        }
        layoutSentinelActions.setVisibility(View.VISIBLE);
        btnHuntControl.setVisibility(hasSuspects ? View.VISIBLE : View.GONE);
        if (undoCount > 0) {
            btnHuntUndo.setVisibility(View.VISIBLE);
            btnHuntUndo.setText("↩️ DESFAZER (" + undoCount + ")");
        } else {
            btnHuntUndo.setVisibility(View.GONE);
        }
    }

    private void updateSystemControlsUi(SystemTweaksController.SystemState st) {
        if (st == null || tvQuickControlsStatus == null) return;

        String aodDesc = !st.hasAod ? "Indisp." : (!st.aodEnabled ? "Desligado"
                : (st.aodTapToShow ? "Toque (18,6 mAh)" : "Sempre Ativo"));
        String netDesc = !st.hasNetworkMode ? "Manual" : (st.networkHas5gNr ? "5G NR (" + st.networkModeRaw + ")"
                : "LTE/4G (" + st.networkModeRaw + ")");
        boolean mcfOn = "1".equals(st.mcfContinuity) || "1".equals(st.mcfMyDevice)
                || (!st.mcfFamilyShare.isEmpty() && !"0".equals(st.mcfFamilyShare));
        String mcfDesc = !st.hasMcfContinuity ? "Ausente" : (mcfOn ? "Ativa" : "Desligada");
        String moseyDesc = !st.moseyInstalled ? "Não instalado" : (st.moseyDisabled ? "Pausado" : "Ativo");
        String hzFloorDesc = st.minRefreshHz <= 0.1f ? "Auto/0" : String.format(Locale.US, "%.0f Hz", st.minRefreshHz);
        String hzDesc = !st.hasRefreshControl ? "N/D"
                : String.format(Locale.US, "%s → %.0f Hz (%s)", hzFloorDesc, st.peakRefreshHz,
                        st.refreshRateMode == 0 ? "Fixo" : "Adapt.");

        tvQuickControlsStatus.setText(String.format(Locale.US,
                "• Leve: %s  |  Carga Máx: %s\n• AOD: %s  |  Brilho-: %s\n• Rede: %s  |  MCF: %s\n• Hz Tela: %s  |  Mosey: %s",
                !st.hasLowHeat ? "N/D" : (st.lowHeatActive ? "ON" : "OFF"),
                !st.hasProtectBattery ? "N/D" : (st.protectBatteryMode > 0 ? "80% ON" : "OFF (100%)"),
                aodDesc,
                !st.hasExtraDim ? "N/D" : (st.extraDimActive ? "ON (" + st.extraDimLevel + "%)" : "OFF (" + st.extraDimLevel + "%)"),
                netDesc, mcfDesc, hzDesc, moseyDesc));

        btnToggleLowHeat.setVisibility(st.hasLowHeat ? View.VISIBLE : View.GONE);
        if (st.hasLowHeat) {
            btnToggleLowHeat.setText(st.lowHeatActive ? "🍃 Leve: ON" : "🍃 Leve: OFF");
            btnToggleLowHeat.setBackgroundResource(st.lowHeatActive ? R.drawable.bg_pill_green : R.drawable.bg_pill_dark);
        }

        btnToggleProtectBattery.setVisibility(st.hasProtectBattery ? View.VISIBLE : View.GONE);
        if (st.hasProtectBattery) {
            btnToggleProtectBattery.setText(st.protectBatteryMode > 0 ? "🔋 80%: ON" : "🔋 80%: OFF");
            btnToggleProtectBattery.setBackgroundResource(st.protectBatteryMode > 0 ? R.drawable.bg_pill_blue : R.drawable.bg_pill_dark);
        }

        btnCycleAodMode.setVisibility(st.hasAod ? View.VISIBLE : View.GONE);
        if (st.hasAod) {
            btnCycleAodMode.setText(!st.aodEnabled ? "🌙 AOD: OFF" : (st.aodTapToShow ? "👆 AOD: Toque" : "⏱️ AOD: Cont."));
        }

        layoutExtraDimControls.setVisibility(st.hasExtraDim ? View.VISIBLE : View.GONE);
        if (st.hasExtraDim) {
            btnToggleExtraDim.setText(st.extraDimActive ? "🕶️ Brilho-: ON" : "🕶️ Brilho-: OFF");
            btnToggleExtraDim.setBackgroundResource(st.extraDimActive ? R.drawable.bg_pill_blue : R.drawable.bg_pill_dark);
            isBindingExtraDimSeek = true;
            seekExtraDimLevel.setProgress(Math.max(0, Math.min(90, st.extraDimLevel - 5)));
            tvExtraDimPct.setText(st.extraDimLevel + "%");
            isBindingExtraDimSeek = false;
        }

        btnToggleNetworkMode.setText(!st.hasNetworkMode ? "📶 Redes" : (st.networkHas5gNr ? "📡 Rede: 5G" : "🟢 Rede: LTE"));
        btnToggleNetworkMode.setBackgroundResource(!st.networkHas5gNr && st.hasNetworkMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_blue);

        btnConfigureRefreshHz.setVisibility(st.hasRefreshControl ? View.VISIBLE : View.GONE);
        if (st.hasRefreshControl) {
            boolean customHz = st.peakRefreshHz < st.maxHardwareHz - 1.0f || st.minRefreshHz > 0.5f;
            btnConfigureRefreshHz.setText("⚡ Hz Adaptativo: " + hzFloorDesc + " → "
                    + String.format(Locale.US, "%.0f Hz", st.peakRefreshHz));
            btnConfigureRefreshHz.setBackgroundResource(customHz ? R.drawable.bg_pill_green : R.drawable.bg_pill_dark);
        }

        btnToggleMcfContinuity.setVisibility(st.hasMcfContinuity ? View.VISIBLE : View.GONE);
        if (st.hasMcfContinuity) {
            btnToggleMcfContinuity.setText(mcfOn ? "🔗 MCF: ON" : "✂️ MCF: OFF");
            btnToggleMcfContinuity.setBackgroundResource(mcfOn ? R.drawable.bg_pill_dark : R.drawable.bg_pill_green);
        }

        btnToggleMosey.setVisibility(st.moseyInstalled ? View.VISIBLE : View.GONE);
        if (st.moseyInstalled) {
            btnToggleMosey.setText(st.moseyDisabled ? "⏸️ Mosey: OFF" : " Mosey: ON");
            btnToggleMosey.setBackgroundResource(st.moseyDisabled ? R.drawable.bg_pill_green : R.drawable.bg_pill_dark);
        }

        if (tvMaintenanceStatus != null) {
            StringBuilder msb = new StringBuilder();
            msb.append("• Freezer: ").append(st.cachedAppsFreezer)
               .append(String.format(Locale.US, "  |  Anim: %.1f/%.1f/%.1fx",
                       st.windowScale, st.transitionScale, st.animatorScale));
            msb.append(String.format(Locale.US, "\n• UFS Livre: %.1f GB", st.freeStorageGb));
            if (!st.kernelFreezerNote.isEmpty()) {
                msb.append("\n• Kernel Freezer: ").append(st.kernelFreezerNote);
            }
            if (!st.lastArtReport.isEmpty()) {
                msb.append("\n• Último ART: ").append(st.lastArtReport);
            }
            tvMaintenanceStatus.setText(msb.toString());

            btnToggleFreezer.setVisibility(st.hasFreezer ? View.VISIBLE : View.GONE);
            btnToggleFreezer.setText("enabled".equals(st.cachedAppsFreezer) ? "❄️ Freezer: ON" : "❄️ Freezer: Def.");

            isBindingAnimSeek = true;
            bindAnimSeekValue(seekAnimWindow, tvAnimWindowVal, st.windowScale);
            bindAnimSeekValue(seekAnimTransition, tvAnimTransitionVal, st.transitionScale);
            bindAnimSeekValue(seekAnimAnimator, tvAnimAnimatorVal, st.animatorScale);
            isBindingAnimSeek = false;

            boolean isIdeal = approxScale(st.windowScale, 0.7f) && approxScale(st.transitionScale, 0.5f) && approxScale(st.animatorScale, 0.5f);
            boolean isFast = approxScale(st.windowScale, 0.5f) && approxScale(st.transitionScale, 0.5f) && approxScale(st.animatorScale, 0.5f);
            boolean isStock = approxScale(st.windowScale, 1.0f) && approxScale(st.transitionScale, 1.0f) && approxScale(st.animatorScale, 1.0f);
            btnAnimPresetIdeal.setBackgroundResource(isIdeal ? R.drawable.bg_pill_blue : R.drawable.bg_pill_dark);
            btnAnimPresetFast.setBackgroundResource(isFast ? R.drawable.bg_pill_blue : R.drawable.bg_pill_dark);
            btnAnimPresetStock.setBackgroundResource(isStock ? R.drawable.bg_pill_blue : R.drawable.bg_pill_dark);
        }
        updateSentinelActionButtons();
    }

    private void bindAnimationSeekBar(SeekBar seek, final TextView tvVal, final String globalKey, final String label) {
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvVal.setText(String.format(Locale.US, "%.1fx", progress / 10f));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
                if (isBindingAnimSeek) return;
                final float val = seekBar.getProgress() / 10f;
                runShizukuTweak(new TweakTask() {
                    @Override public SystemTweaksController.ActionResult run() {
                        return SystemTweaksController.setSingleAnimationScale(MainActivity.this, globalKey, label, val);
                    }
                });
            }
        });
    }

    private void applyAnimPreset(final float w, final float t, final float a) {
        runShizukuTweak(new TweakTask() {
            @Override public SystemTweaksController.ActionResult run() {
                return SystemTweaksController.setAnimationScales(MainActivity.this, w, t, a);
            }
        });
    }

    private static void bindAnimSeekValue(SeekBar seek, TextView tvVal, float scale) {
        int step = Math.max(0, Math.min(10, Math.round(scale * 10f)));
        seek.setProgress(step);
        tvVal.setText(String.format(Locale.US, "%.1fx", scale));
    }

    private static boolean approxScale(float actual, float target) {
        return Math.abs(actual - target) < 0.05f;
    }

    private void showRefreshHzDialog() {
        final SystemTweaksController.SystemState st = SystemTweaksController.readLocalState(this);
        if (!st.hasRefreshControl) {
            Toast.makeText(this, "Painel não reportou múltiplas taxas de atualização.", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> labels = new ArrayList<String>();
        final List<Float> targetPeak = new ArrayList<Float>();
        final List<Integer> actionKinds = new ArrayList<Integer>();

        for (Integer hz : st.supportedHzDesc) {
            if (hz < 60) continue;
            String tag = hz == st.maxHardwareHz ? " (Máximo nativo)" : (hz == 60 ? " (Econômico LTPO)" : " (Intermediário)");
            boolean active = Math.abs(st.peakRefreshHz - hz) < 1.5f;
            labels.add((active ? "✅ " : "• ") + "Teto " + hz + " Hz" + tag);
            targetPeak.add((float) hz);
            actionKinds.add(0);
        }
        labels.add("⚓ Definir Piso Mínimo LTPO (atual: "
                + (st.minRefreshHz <= 0.1f ? "Auto/0" : String.format(Locale.US, "%.0f Hz", st.minRefreshHz)) + ")");
        targetPeak.add(0f);
        actionKinds.add(1);

        labels.add("↩️ Restaurar Padrão ROM (0 → " + st.maxHardwareHz + " Hz)");
        targetPeak.add((float) st.maxHardwareHz);
        actionKinds.add(2);

        String modesSummary = st.supportedHzDesc.isEmpty() ? "padrão"
                : android.text.TextUtils.join(", ", st.supportedHzDesc) + " Hz";
        new AlertDialog.Builder(this)
                .setTitle("Hz Adaptativo (Hardware: " + modesSummary + ")")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int kind = actionKinds.get(which);
                        if (kind == 1) {
                            showRefreshFloorDialog(st);
                        } else if (kind == 2) {
                            runShizukuTweak(new TweakTask() {
                                @Override public SystemTweaksController.ActionResult run() {
                                    return SystemTweaksController.restoreDefaultRefreshBounds(MainActivity.this);
                                }
                            });
                        } else {
                            final float peak = targetPeak.get(which);
                            final float floor = Math.min(st.minRefreshHz, peak);
                            runShizukuTweak(new TweakTask() {
                                @Override public SystemTweaksController.ActionResult run() {
                                    return SystemTweaksController.setAdaptiveRefreshBounds(MainActivity.this, floor, peak);
                                }
                            });
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void showRefreshFloorDialog(final SystemTweaksController.SystemState st) {
        final List<String> floorLabels = new ArrayList<String>();
        final List<Float> floorValues = new ArrayList<Float>();
        floorLabels.add((st.minRefreshHz <= 0.1f ? "✅ " : "• ") + "Auto / 0 Hz (LTPO irrestrito — recomendado)");
        floorValues.add(0.0f);

        List<Integer> asc = new ArrayList<Integer>(st.supportedHzDesc);
        Collections.sort(asc);
        for (Integer hz : asc) {
            if (hz > st.peakRefreshHz + 0.5f) continue;
            boolean active = Math.abs(st.minRefreshHz - hz) < 1.5f;
            floorLabels.add((active ? "✅ " : "• ") + "Piso " + hz + " Hz");
            floorValues.add((float) hz);
        }
        new AlertDialog.Builder(this)
                .setTitle("Piso Mínimo (Teto atual: " + String.format(Locale.US, "%.0f Hz", st.peakRefreshHz) + ")")
                .setItems(floorLabels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final float newFloor = floorValues.get(which);
                        runShizukuTweak(new TweakTask() {
                            @Override public SystemTweaksController.ActionResult run() {
                                return SystemTweaksController.setAdaptiveRefreshBounds(MainActivity.this, newFloor, st.peakRefreshHz);
                            }
                        });
                    }
                })
                .setNegativeButton("Voltar", null)
                .show();
    }

    private void showDozeWhitelistDialog() {
        btnCleanDozeWhitelist.setEnabled(false);
        btnCleanDozeWhitelist.setText("⏳ LENDO DOZE...");
        shizukuExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final List<SystemTweaksController.DozeWhitelistEntry> all =
                        SystemTweaksController.inspectUserDozeWhitelist(MainActivity.this);
                final List<SystemTweaksController.DozeWhitelistEntry> removable =
                        new ArrayList<SystemTweaksController.DozeWhitelistEntry>();
                int protCount = 0;
                for (SystemTweaksController.DozeWhitelistEntry e : all) {
                    if (e.protectedApp) protCount++;
                    else removable.add(e);
                }
                final int protectedCount = protCount;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnCleanDozeWhitelist.setEnabled(true);
                        btnCleanDozeWhitelist.setText("💤 DOZE WHITELIST");
                        if (all.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "Permissão DUMP ou Shizuku necessária para inspecionar whitelist do Doze.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (removable.isEmpty()) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Whitelist Doze Limpa")
                                    .setMessage("Nenhum app não protegido está isento do Doze (" + protectedCount + " apps protegidos preservados).")
                                    .setPositiveButton("OK", null)
                                    .show();
                            return;
                        }
                        String[] itemLabels = new String[removable.size()];
                        final boolean[] checked = new boolean[removable.size()];
                        for (int i = 0; i < removable.size(); i++) {
                            SystemTweaksController.DozeWhitelistEntry e = removable.get(i);
                            itemLabels[i] = e.appLabel + "\n" + e.packageName;
                            checked[i] = !e.packageName.startsWith("com.samsung.") && !e.packageName.startsWith("com.sec.");
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Limpar Whitelist Doze (" + removable.size() + " não protegidos)")
                                .setMultiChoiceItems(itemLabels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                        checked[which] = isChecked;
                                    }
                                })
                                .setPositiveButton("Remover Selecionados", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        final List<SystemTweaksController.DozeWhitelistEntry> sel =
                                                new ArrayList<SystemTweaksController.DozeWhitelistEntry>();
                                        for (int i = 0; i < removable.size(); i++) {
                                            if (checked[i]) sel.add(removable.get(i));
                                        }
                                        runShizukuTweak(new TweakTask() {
                                            @Override public SystemTweaksController.ActionResult run() {
                                                return SystemTweaksController.removePackagesFromDozeWhitelist(MainActivity.this, sel);
                                            }
                                        });
                                    }
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                });
            }
        });
    }

    private void refreshShizukuAndVulkanAsync(final boolean queryGfx) {
        if (queryGfx) shizukuPendingGfx = true;
        if (!shizukuReadInFlight.compareAndSet(false, true)) {
            return;
        }
        shizukuExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean wantGfx = queryGfx || shizukuPendingGfx;
                    shizukuPendingGfx = false;
                    final ShizukuBridge.ShizukuStatus st = ShizukuBridge.getStatus(MainActivity.this);
                    final VulkanController.RendererStatus vk = VulkanController.inspectStatus(
                            MainActivity.this, wantGfx && st.serviceRunning && st.permissionGranted);
                    final SystemTweaksController.SystemState sysSt = (wantGfx && st.permissionGranted)
                            ? SystemTweaksController.readWithShizukuDetails(MainActivity.this)
                            : SystemTweaksController.readLocalState(MainActivity.this);
                    lastVulkanStatus = vk;
                    lastSystemState = sysSt;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (btnVulkanVerify != null) btnVulkanVerify.setEnabled(true);
                            updateShizukuText(st);
                            updateVulkanText(vk);
                            updateSystemControlsUi(sysSt);
                        }
                    });
                } finally {
                    shizukuReadInFlight.set(false);
                }
            }
        });
    }

    private void updateShizukuText(ShizukuBridge.ShizukuStatus st) {
        if (tvShizukuStatus == null || st == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(st.permissionGranted ? "✅ Shizuku: " : (st.serviceRunning ? "⚠️ Shizuku: " : "❌ Shizuku: "))
          .append(st.detail);
        ShizukuBridge.ChemicalBatteryCache chem = ShizukuBridge.loadCachedChemicalHealth(this);
        if (chem != null && chem.asoc > 0) {
            sb.append(String.format(Locale.US, "\n🔋 Química: ASOC %.0f%%", chem.asoc));
            if (chem.cycles > 0) sb.append(String.format(Locale.US, " · %.1f ciclos", chem.cycles));
            if (chem.bsoh > 0) sb.append(String.format(Locale.US, " · BSOH %.0f%%", chem.bsoh));
        }
        tvShizukuStatus.setText(sb.toString());
    }

    private void updateVulkanText(VulkanController.RendererStatus st) {
        if (tvVulkanStatus == null || st == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Prop HWUI: ").append(st.propVulkan ? "✅ skiavk (Vulkan ativo p/ novos apps)" : "⚙️ " + (st.propValue.isEmpty() ? "Padrão (OpenGL pós-boot)" : st.propValue));
        sb.append("\n• System UI: ").append(VulkanController.describePipeline(st.systemUi));
        sb.append("\n• Launcher: ").append(VulkanController.describePipeline(st.launcher));
        sb.append("\n• Teclado: ").append(VulkanController.describePipeline(st.keyboard));
        if (st.behindScanned) {
            if (st.behindOpenPackages.isEmpty()) {
                sb.append("\n✅ Todos os apps abertos estão no alvo");
            } else {
                int max = Math.min(4, st.behindOpenLabels.size());
                String preview = android.text.TextUtils.join(", ", st.behindOpenLabels.subList(0, max));
                if (st.behindOpenLabels.size() > max) {
                    preview += " +" + (st.behindOpenLabels.size() - max);
                }
                sb.append(String.format(Locale.US, "\n⚠️ %d apps abertos atrasados (%s)",
                        st.behindOpenPackages.size(), preview));
            }
        }
        tvVulkanStatus.setText(sb.toString());
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyOpenTabIntent(intent);
    }

    private void applyOpenTabIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_OPEN_TAB)) {
            return;
        }
        int tab = intent.getIntExtra(EXTRA_OPEN_TAB, TAB_HUD);
        if (tab >= TAB_HUD && tab <= TAB_VAULT) {
            selectTab(tab);
        }
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
        if (memHogsBinder != null) {
            memHogsBinder.setVisible(currentTab == TAB_PERF && currentSysSubTab == SYS_SUB_RAM);
        }
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
        if (memHogsBinder != null) {
            memHogsBinder.setVisible(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityVisible = false;
        unregisterSettingsObserver();
        mainHandler.removeCallbacks(perfTickRunnable);
        mainHandler.removeCallbacks(debouncedSettingsRefresh);
        perfExecutor.shutdownNow();
        shizukuExecutor.shutdownNow();
        if (memHogsBinder != null) memHogsBinder.destroy();
        if (localShellBinder != null) localShellBinder.destroy();
    }

    private boolean isPerfActive() {
        return isActivityVisible && currentTab == TAB_PERF && currentSysSubTab == SYS_SUB_CPU && !isFinishing() && !isDestroyed();
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
                Settings.Global.getUriFor("cached_apps_freezer"),
                Settings.Global.getUriFor("window_animation_scale"),
                Settings.Global.getUriFor("transition_animation_scale"),
                Settings.Global.getUriFor("animator_duration_scale"),
                Settings.System.getUriFor("screen_off_timeout"),
                Settings.System.getUriFor("aod_mode"),
                Settings.System.getUriFor("aod_show_for_new_noti"),
                Settings.System.getUriFor("aod_tap_to_show_mode"),
                Settings.System.getUriFor("nearby_scanning_enabled"),
                Settings.System.getUriFor("mcf_continuity"),
                Settings.System.getUriFor("mcf_mydevice_activated"),
                Settings.System.getUriFor("mcf_family_device_share_enabled"),
                Settings.Secure.getUriFor("refresh_rate_mode"),
                Settings.System.getUriFor("peak_refresh_rate"),
                Settings.System.getUriFor("min_refresh_rate"),
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
                badgeRenderer.setTextColor(OmniaTheme.color(this, R.color.omnia_ok_text));
            } else {
                badgeRenderer.setBackgroundResource(R.drawable.bg_pill_dark);
                badgeRenderer.setTextColor(OmniaTheme.color(this, R.color.omnia_text_secondary));
            }
        } else {
            badgeRenderer.setText("HWUI · " + jobPart);
            badgeRenderer.setBackgroundResource(R.drawable.bg_pill_dark);
            badgeRenderer.setTextColor(OmniaTheme.color(this, R.color.omnia_text_secondary));
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
        tvAuditTitle.setText("Auditor do Sistema · Score " + res.settingsScorePct + "%");
        tvAudit.setText(res.auditReport);
        tvPermStatus.setText(res.permissionStatusText);
        tvHistoryList.setText(res.historySummary);
        tvStorageUsage.setText(StorageManager.getStorageUsageSummary());
        if (cardRebootDrift != null && tvRebootDriftDetails != null) {
            if (res.driftReport != null && res.driftReport.hasDrift()) {
                cardRebootDrift.setVisibility(View.VISIBLE);
                tvRebootDriftDetails.setText("O sistema resetou " + res.driftReport.items.size()
                        + " ajuste(s) desde o último ciclo:\n" + res.driftReport.formatSummary());
            } else {
                cardRebootDrift.setVisibility(View.GONE);
            }
        }

        VulkanController.RendererStatus vkNow = lastVulkanStatus != null ? lastVulkanStatus : VulkanController.inspectStatus(this, false);
        updateVulkanText(vkNow);
        SystemTweaksController.SystemState localSys = SystemTweaksController.readLocalState(this);
        lastSystemState = localSys;
        updateSystemControlsUi(localSys);
        updateAccordionSummaries(localSys, res.settingsScorePct, vkNow);
        refreshShizukuAndVulkanAsync(currentTab == TAB_AUDIT);

        if (!sentinelLiveShown) {
            String summary = (res.sentinelSummary != null && !res.sentinelSummary.isEmpty())
                    ? res.sentinelSummary
                    : "Último ciclo sem alertas. Toque acima para varredura instantânea (0% em 2º plano).";
            applySentinelCard(res.sentinelSeverity, summary, false);
        }
        updateSentinelActionButtons();

        if (etUserContext.getText().toString().trim().isEmpty() && res.latestUserNote != null && !res.latestUserNote.isEmpty()) {
            etUserContext.setText(res.latestUserNote);
        }

        OmniaWidgetProvider.requestWidgetUpdate(this);
    }

    private void applySentinelCard(String severity, String text, boolean live) {
        String prefix = live ? "Sentinela Anti-Wakelock · Ao Vivo" : "Sentinela Anti-Wakelock · Último Ciclo";
        if ("CRITICO".equals(severity)) {
            tvSentinelTitle.setText(prefix + " [Crítico]");
            tvSentinelTitle.setTextColor(OmniaTheme.color(this, R.color.omnia_alert_text));
        } else if ("ATENCAO".equals(severity)) {
            tvSentinelTitle.setText(prefix + " [Atenção]");
            tvSentinelTitle.setTextColor(OmniaTheme.color(this, R.color.omnia_warn_text));
        } else {
            tvSentinelTitle.setText(prefix + " [Limpo]");
            tvSentinelTitle.setTextColor(OmniaTheme.color(this, R.color.omnia_ok_text));
        }
        tvSentinelReport.setText(text);
    }

    private void applyStatusPill(TextView pill, String activeText, String inactiveText, boolean active) {
        if (active) {
            pill.setText(activeText);
            pill.setBackgroundResource(R.drawable.bg_pill_blue);
            pill.setTextColor(OmniaTheme.color(this, R.color.omnia_info_text));
        } else {
            pill.setText(inactiveText);
            pill.setBackgroundResource(R.drawable.bg_pill_red);
            pill.setTextColor(OmniaTheme.color(this, R.color.omnia_alert_text));
        }
    }
}
