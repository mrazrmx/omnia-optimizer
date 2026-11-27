package com.omnia.optimizer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Painel interativo de RAM (MemHogs) em Carvão Técnico.
 * Executa sob demanda (e loop opcional de 2.5s somente quando visível e ativado).
 */
public final class MemHogsViewBinding {

    private static final long LIVE_INTERVAL_MS = 2500L;
    private static final int MAX_VISIBLE_GROUPS = 40;

    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvMemGaugeMain;
    private TextView tvMemGaugeSub;
    private ProgressBar progressMemBar;
    private Button btnMemRefresh;
    private Switch switchMemLive;
    private TextView tvMemStatus;
    private TextView tvMemReclaimBanner;
    private LinearLayout listContainer;

    private boolean panelVisible = false;
    private boolean liveEnabled = false;
    private boolean loading = false;
    private boolean destroyed = false;
    private MemHogsController.MemSnapshot latestSnapshot;
    private final Set<String> expandedOwners = new HashSet<String>();

    private final Runnable liveTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isLiveActive()) return;
            triggerCapture(false);
            mainHandler.removeCallbacks(this);
            mainHandler.postDelayed(this, LIVE_INTERVAL_MS);
        }
    };

    public MemHogsViewBinding(Context context, Handler mainHandler) {
        this.context = context;
        this.mainHandler = mainHandler;
    }

    public void bind(View root) {
        tvMemGaugeMain = (TextView) root.findViewById(R.id.tvMemGaugeMain);
        tvMemGaugeSub = (TextView) root.findViewById(R.id.tvMemGaugeSub);
        progressMemBar = (ProgressBar) root.findViewById(R.id.progressMemBar);
        btnMemRefresh = (Button) root.findViewById(R.id.btnMemRefresh);
        switchMemLive = (Switch) root.findViewById(R.id.switchMemLive);
        tvMemStatus = (TextView) root.findViewById(R.id.tvMemStatus);
        tvMemReclaimBanner = (TextView) root.findViewById(R.id.tvMemReclaimBanner);
        listContainer = (LinearLayout) root.findViewById(R.id.memHogsListContainer);

        btnMemRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OmniaTheme.hapticTap(v);
                triggerCapture(true);
            }
        });

        switchMemLive.setChecked(false);
        switchMemLive.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                OmniaTheme.hapticTap(buttonView);
                liveEnabled = isChecked;
                updateLiveLoop();
            }
        });

        renderGaugeOnly(MemHogsController.readDeviceGauge(context));
    }

    public void setVisible(boolean visible) {
        this.panelVisible = visible;
        if (visible && latestSnapshot == null && !loading) {
            triggerCapture(false);
        } else if (visible) {
            renderGaugeOnly(MemHogsController.readDeviceGauge(context));
        }
        updateLiveLoop();
    }

    public void destroy() {
        destroyed = true;
        panelVisible = false;
        mainHandler.removeCallbacks(liveTickRunnable);
        executor.shutdownNow();
    }

    private boolean isLiveActive() {
        return !destroyed && panelVisible && liveEnabled;
    }

    private void updateLiveLoop() {
        mainHandler.removeCallbacks(liveTickRunnable);
        if (isLiveActive()) {
            mainHandler.post(liveTickRunnable);
        }
    }

    public void triggerCapture(final boolean showBusyLabel) {
        if (loading || destroyed) return;
        loading = true;
        if (btnMemRefresh != null) {
            btnMemRefresh.setEnabled(false);
            if (showBusyLabel) btnMemRefresh.setText("Lendo...");
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final MemHogsController.MemSnapshot snap = MemHogsController.captureSnapshot(context);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (destroyed) return;
                        if (btnMemRefresh != null) {
                            btnMemRefresh.setEnabled(true);
                            btnMemRefresh.setText("Atualizar");
                        }
                        latestSnapshot = snap;
                        renderSnapshot(snap);
                    }
                });
            }
        });
    }

    private void renderGaugeOnly(MemHogsController.MemGauge g) {
        if (tvMemGaugeMain == null || g == null || g.totalBytes <= 0L) return;
        int pct = Math.round(g.usedFraction * 100f);
        tvMemGaugeMain.setText(MemHogsController.formatBytes(g.usedBytes) + " / " + MemHogsController.formatBytes(g.totalBytes) + " (" + pct + "%)");
        if (tvMemGaugeSub != null && latestSnapshot == null) {
            tvMemGaugeSub.setText("Livre: " + MemHogsController.formatBytes(g.availBytes)
                    + (g.lowMemory ? " · Pressão de memória ativa" : " · Memória estável"));
        }
        if (progressMemBar != null) {
            progressMemBar.setMax(1000);
            progressMemBar.setProgress(Math.min(1000, Math.max(0, Math.round(g.usedFraction * 1000f))));
            int barColor = g.usedFraction >= MemHogsController.HOT_DEVICE_USED_FRACTION
                    ? OmniaTheme.color(context, R.color.omnia_charcoal_red)
                    : (g.usedFraction >= 0.70f
                        ? OmniaTheme.color(context, R.color.omnia_charcoal_amber)
                        : OmniaTheme.color(context, R.color.omnia_charcoal_cyan));
            progressMemBar.setProgressTintList(ColorStateList.valueOf(barColor));
        }
    }

    private void renderSnapshot(MemHogsController.MemSnapshot snap) {
        if (snap == null) return;
        renderGaugeOnly(snap.gauge);

        if (snap.totalRamKb > 0L) {
            long usedDisplayKb = snap.usedRamKb > 0L ? snap.usedRamKb : (snap.gauge.usedBytes / 1024L);
            long totalDisplayKb = snap.totalRamKb;
            int pct = totalDisplayKb > 0L ? (int) Math.round((usedDisplayKb * 100.0) / totalDisplayKb) : 0;
            tvMemGaugeMain.setText(MemHogsController.formatKb(usedDisplayKb) + " / " + MemHogsController.formatKb(totalDisplayKb) + " (" + pct + "%)");
            tvMemGaugeSub.setText(String.format(Locale.US, "Livre: %s · %d processos · %d grupos · %s",
                    MemHogsController.formatKb(snap.freeRamKb > 0L ? snap.freeRamKb : (snap.gauge.availBytes / 1024L)),
                    snap.processCount, snap.groups.size(), snap.timestampText));
        }

        if (snap.error != null && !snap.error.isEmpty()) {
            tvMemStatus.setVisibility(View.VISIBLE);
            tvMemStatus.setText(snap.error);
            tvMemStatus.setTextColor(OmniaTheme.color(context, R.color.omnia_charcoal_amber));
        } else {
            tvMemStatus.setVisibility(View.GONE);
        }

        listContainer.removeAllViews();
        List<MemHogsController.MemGroup> groups = snap.groups;
        int limit = Math.min(groups.size(), MAX_VISIBLE_GROUPS);
        for (int i = 0; i < limit; i++) {
            listContainer.addView(buildGroupRow(groups.get(i)));
        }
        if (groups.size() > limit) {
            TextView footer = new TextView(context);
            footer.setText("Mostrando top " + limit + " de " + groups.size() + " grupos (ordenado por PSS).");
            footer.setTextColor(OmniaTheme.color(context, R.color.omnia_charcoal_dim));
            footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            footer.setPadding(dp(4), dp(6), dp(4), dp(2));
            listContainer.addView(footer);
        }
    }

    private View buildGroupRow(final MemHogsController.MemGroup group) {
        final float d = context.getResources().getDisplayMetrics().density;
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_charcoal_cell);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        // Top line: App Name + Memory / Share
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(group.label);
        tvTitle.setTextColor(group.isHot
                ? OmniaTheme.color(context, R.color.omnia_charcoal_red)
                : OmniaTheme.color(context, R.color.omnia_charcoal_text));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setMaxLines(1);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(tvTitle);

        TextView tvPkg = new TextView(context);
        String procTag = group.members.size() + (group.members.size() == 1 ? " processo" : " processos");
        String subtitle = group.isApp ? (group.owner + " · " + procTag) : ("Sistema/Nativo · " + procTag);
        if (group.protectedApp && group.isApp) subtitle += " · Protegido";
        tvPkg.setText(subtitle);
        tvPkg.setTextColor(OmniaTheme.color(context, R.color.omnia_charcoal_dim));
        tvPkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvPkg.setMaxLines(1);
        tvPkg.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(tvPkg);

        topRow.addView(titleCol);

        TextView tvMem = new TextView(context);
        double pctVal = group.shareOfRam * 100.0;
        tvMem.setText(String.format(Locale.US, "%s  %.1f%%", MemHogsController.formatKb(group.pssKb), pctVal));
        tvMem.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvMem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvMem.setTextColor(group.isHot
                ? OmniaTheme.color(context, R.color.omnia_charcoal_red)
                : (pctVal >= 6.0
                    ? OmniaTheme.color(context, R.color.omnia_charcoal_amber)
                    : OmniaTheme.color(context, R.color.omnia_charcoal_cyan)));
        tvMem.setPadding(dp(8), 0, 0, 0);
        topRow.addView(tvMem);

        card.addView(topRow);

        // Proportional usage bar
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        barLp.topMargin = dp(7);
        barLp.bottomMargin = dp(6);
        bar.setLayoutParams(barLp);
        bar.setMax(1000);
        int barProgress = (int) Math.min(1000L, Math.max(8L, Math.round(group.shareOfRam * 1000.0)));
        bar.setProgress(barProgress);
        int tint = group.isHot
                ? OmniaTheme.color(context, R.color.omnia_charcoal_red)
                : (pctVal >= 6.0
                    ? OmniaTheme.color(context, R.color.omnia_charcoal_amber)
                    : OmniaTheme.color(context, R.color.omnia_charcoal_green));
        bar.setProgressTintList(ColorStateList.valueOf(tint));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(OmniaTheme.color(context, R.color.omnia_charcoal_bg)));
        card.addView(bar);

        // Expandable details + action buttons row
        final LinearLayout detailsBox = new LinearLayout(context);
        detailsBox.setOrientation(LinearLayout.VERTICAL);
        boolean isExpanded = expandedOwners.contains(group.owner);
        detailsBox.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

        StringBuilder memSb = new StringBuilder();
        for (int m = 0; m < group.members.size(); m++) {
            MemHogsController.MemMember mem = group.members.get(m);
            if (m > 0) memSb.append("\n");
            memSb.append(String.format(Locale.US, "pid %-6d  %-8s  %s",
                    mem.pid, MemHogsController.formatKb(mem.pssKb), mem.processName));
        }
        TextView tvMembers = new TextView(context);
        tvMembers.setText(memSb.toString());
        tvMembers.setTypeface(Typeface.MONOSPACE);
        tvMembers.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvMembers.setTextColor(OmniaTheme.color(context, R.color.omnia_charcoal_dim));
        tvMembers.setPadding(0, dp(2), 0, dp(6));
        detailsBox.addView(tvMembers);
        card.addView(detailsBox);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OmniaTheme.hapticTap(v);
                boolean open = detailsBox.getVisibility() != View.VISIBLE;
                detailsBox.setVisibility(open ? View.VISIBLE : View.GONE);
                if (open) expandedOwners.add(group.owner);
                else expandedOwners.remove(group.owner);
            }
        });

        if (group.canReclaim) {
            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);

            Button btnKill = makeActionButton("Liberar (Kill BG)",
                    OmniaTheme.color(context, R.color.omnia_charcoal_bg),
                    OmniaTheme.color(context, R.color.omnia_charcoal_cyan),
                    OmniaTheme.color(context, R.color.omnia_charcoal_cyan));
            btnKill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    OmniaTheme.hapticConfirm(v);
                    runReclaim(group, false);
                }
            });
            btnRow.addView(btnKill);

            if (group.canForceStop) {
                Button btnStop = makeActionButton("Forçar Parada",
                        OmniaTheme.color(context, R.color.omnia_charcoal_bg),
                        OmniaTheme.color(context, R.color.omnia_charcoal_red),
                        OmniaTheme.color(context, R.color.omnia_charcoal_red));
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btnStop.getLayoutParams();
                lp.leftMargin = (int) (8f * d);
                btnStop.setLayoutParams(lp);
                btnStop.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        OmniaTheme.hapticConfirm(v);
                        confirmForceStop(group);
                    }
                });
                btnRow.addView(btnStop);
            }

            card.addView(btnRow);
        }

        return card;
    }

    private Button makeActionButton(String text, int fillColor, int strokeColor, int textColor) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setTextColor(textColor);
        btn.setPadding(dp(12), dp(4), dp(12), dp(4));
        btn.setMinHeight(dp(34));
        btn.setMinimumHeight(dp(34));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setStroke(dp(1), strokeColor);
        bg.setCornerRadius(dp(18));
        btn.setBackground(bg);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)));
        return btn;
    }

    private void confirmForceStop(final MemHogsController.MemGroup group) {
        new AlertDialog.Builder(context)
                .setTitle("Forçar parada de " + group.label + "?")
                .setMessage("Isso executará `am force-stop " + group.owner + "`, encerrando todos os processos, jobs e notificações em segundo plano até que o app seja reaberto.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Forçar Parada", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runReclaim(group, true);
                    }
                })
                .show();
    }

    private void runReclaim(final MemHogsController.MemGroup group, final boolean forceStop) {
        if (loading || destroyed) return;
        loading = true;
        showReclaimBanner((forceStop ? "Encerrando " : "Liberando cache de ") + group.label + "...", false, true);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final MemHogsController.ReclaimOutcome outcome = MemHogsController.reclaimPackage(context, group, forceStop);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (destroyed) return;
                        showReclaimBanner(outcome.message, outcome.ok, false);
                        if (outcome.afterSnapshot != null) {
                            latestSnapshot = outcome.afterSnapshot;
                            renderSnapshot(outcome.afterSnapshot);
                        }
                    }
                });
            }
        });
    }

    private void showReclaimBanner(String text, boolean ok, boolean neutral) {
        if (tvMemReclaimBanner == null) return;
        tvMemReclaimBanner.setVisibility(View.VISIBLE);
        tvMemReclaimBanner.setText(text);
        int color = neutral
                ? OmniaTheme.color(context, R.color.omnia_charcoal_cyan)
                : (ok ? OmniaTheme.color(context, R.color.omnia_charcoal_green) : OmniaTheme.color(context, R.color.omnia_charcoal_red));
        tvMemReclaimBanner.setTextColor(color);
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
