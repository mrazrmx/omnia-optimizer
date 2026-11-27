package com.omnia.optimizer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Console ADB Local interativo inspirado no aShell You em Carvão Técnico.
 * Executa via Shizuku IPC sob demanda com streaming linha-a-linha e botão Parar.
 */
public final class LocalShellViewBinding {

    private static final int MAX_BUFFER_CHARS = 85000;

    private final Context context;
    private final Handler mainHandler;

    private TextView tvShellCwd;
    private EditText etShellInput;
    private Button btnShellRunStop;
    private LinearLayout chipsContainer;
    private TextView tvShellStatus;
    private ScrollView scrollOutput;
    private TextView tvShellOutput;
    private Button btnShellCopy;
    private Button btnShellClear;
    private Button btnShellSave;

    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private ShizukuBridge.ShellSession activeSession;
    private boolean running = false;
    private long startedAtMs = 0L;

    public LocalShellViewBinding(Context context, Handler mainHandler) {
        this.context = context;
        this.mainHandler = mainHandler;
    }

    public void bind(View root) {
        tvShellCwd = (TextView) root.findViewById(R.id.tvShellCwd);
        etShellInput = (EditText) root.findViewById(R.id.etShellInput);
        btnShellRunStop = (Button) root.findViewById(R.id.btnShellRunStop);
        chipsContainer = (LinearLayout) root.findViewById(R.id.layoutShellChips);
        tvShellStatus = (TextView) root.findViewById(R.id.tvShellStatus);
        scrollOutput = (ScrollView) root.findViewById(R.id.scrollShellOutput);
        tvShellOutput = (TextView) root.findViewById(R.id.tvShellOutput);
        btnShellCopy = (Button) root.findViewById(R.id.btnShellCopy);
        btnShellClear = (Button) root.findViewById(R.id.btnShellClear);
        btnShellSave = (Button) root.findViewById(R.id.btnShellSave);

        updateWorkingDirLabel();
        rebuildChips();

        if (scrollOutput != null) {
            scrollOutput.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                    }
                    return false;
                }
            });
        }

        btnShellRunStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OmniaTheme.hapticConfirm(v);
                if (running) {
                    stopRunningCommand();
                } else {
                    executeInputCommand();
                }
            }
        });

        etShellInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    if (!running) {
                        OmniaTheme.hapticConfirm(v);
                        executeInputCommand();
                    }
                    return true;
                }
                return false;
            }
        });

        btnShellCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OmniaTheme.hapticTap(v);
                String text = buffer.toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(context, "Console vazio.", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Omnia ADB Shell", text));
                    Toast.makeText(context, "Saída copiada!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnShellClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OmniaTheme.hapticTap(v);
                clearConsole();
            }
        });

        btnShellSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OmniaTheme.hapticTap(v);
                String text = buffer.toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(context, "Nada para salvar.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String path = LocalShellController.saveOutputToFile(context, text);
                if (path != null) {
                    Toast.makeText(context, "Salvo em " + path, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, "Erro ao gravar arquivo .txt.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public void destroy() {
        stopRunningCommand();
    }

    private void executeInputCommand() {
        String raw = etShellInput.getText() != null ? etShellInput.getText().toString() : "";
        final String cmd = LocalShellController.normalizeCommand(raw);
        if (cmd.isEmpty()) {
            Toast.makeText(context, "Digite um comando shell.", Toast.LENGTH_SHORT).show();
            return;
        }
        etShellInput.setText(cmd);
        etShellInput.setSelection(cmd.length());

        if (LocalShellController.isClearCommand(cmd)) {
            clearConsole();
            etShellInput.setText("");
            return;
        }

        LocalShellController.recordHistory(context, cmd);
        rebuildChips();

        appendColoredLine("$ " + cmd, OmniaTheme.color(context, R.color.omnia_charcoal_amber));

        if (!ShizukuBridge.ensureAttached(context) || !ShizukuBridge.hasPermission(context)) {
            appendColoredLine("[Shizuku offline ou não autorizado — libere na aba Ajustes]",
                    OmniaTheme.color(context, R.color.omnia_charcoal_red));
            tvShellStatus.setText("Shizuku desconectado");
            return;
        }

        if ("cd".equals(cmd) || cmd.startsWith("cd ")) {
            if (!cmd.contains("&&") && !cmd.contains(";")) {
                setRunningState(true, "Alterando diretório...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final LocalShellController.CdOutcome cd = LocalShellController.tryHandleDirectoryChange(context, cmd);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                setRunningState(false, cd.ok ? "Diretório atualizado" : "Falha no cd");
                                updateWorkingDirLabel();
                                appendColoredLine(cd.message, OmniaTheme.color(context,
                                        cd.ok ? R.color.omnia_charcoal_green : R.color.omnia_charcoal_red));
                            }
                        });
                    }
                }).start();
                return;
            }
        }

        startedAtMs = System.currentTimeMillis();
        setRunningState(true, "Executando via Shizuku...");
        String wrapped = LocalShellController.wrapWithWorkingDir(context, cmd);

        activeSession = ShizukuBridge.execStreaming(context, wrapped, new ShizukuBridge.ShellStreamListener() {
            @Override
            public void onLine(final String line, final boolean stderr) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        int col = stderr
                                ? OmniaTheme.color(context, R.color.omnia_charcoal_red)
                                : OmniaTheme.color(context, R.color.omnia_charcoal_text);
                        appendColoredLine(line, col);
                    }
                });
            }

            @Override
            public void onCompleted(final int exitCode, final String error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        activeSession = null;
                        long elapsed = Math.max(1L, System.currentTimeMillis() - startedAtMs);
                        String statusText = (error != null && !error.isEmpty())
                                ? (error + " · " + elapsed + " ms")
                                : String.format(Locale.US, "Concluído (exit=%d) · %d ms", exitCode, elapsed);
                        setRunningState(false, statusText);
                        if (error != null && !error.isEmpty()) {
                            appendColoredLine("[" + error + "]", OmniaTheme.color(context, R.color.omnia_charcoal_amber));
                        }
                        appendColoredLine("", OmniaTheme.color(context, R.color.omnia_charcoal_dim));
                    }
                });
            }
        });
    }

    private void stopRunningCommand() {
        ShizukuBridge.ShellSession s = activeSession;
        if (s != null) {
            s.cancel();
            activeSession = null;
        }
        setRunningState(false, "Cancelado");
    }

    private void setRunningState(boolean isRunning, String status) {
        running = isRunning;
        if (tvShellStatus != null && status != null) {
            tvShellStatus.setText(status);
        }
        if (btnShellRunStop != null) {
            btnShellRunStop.setText(isRunning ? "Parar" : "Executar");
            btnShellRunStop.setTextColor(OmniaTheme.color(context,
                    isRunning ? R.color.omnia_charcoal_red : R.color.omnia_charcoal_cyan));
        }
    }

    private void clearConsole() {
        buffer.clear();
        if (tvShellOutput != null) {
            tvShellOutput.setText("");
        }
        if (tvShellStatus != null) {
            tvShellStatus.setText("Pronto · 0% em segundo plano");
        }
    }

    private void appendColoredLine(String text, int color) {
        if (tvShellOutput == null) return;
        int start = buffer.length();
        buffer.append(text).append('\n');
        int end = buffer.length();
        buffer.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (buffer.length() > MAX_BUFFER_CHARS) {
            buffer.delete(0, buffer.length() - MAX_BUFFER_CHARS);
        }

        tvShellOutput.setText(buffer);
        if (scrollOutput != null) {
            scrollOutput.post(new Runnable() {
                @Override
                public void run() {
                    scrollOutput.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void updateWorkingDirLabel() {
        if (tvShellCwd != null) {
            tvShellCwd.setText("cwd: " + LocalShellController.getWorkingDir(context));
        }
    }

    private void rebuildChips() {
        if (chipsContainer == null) return;
        chipsContainer.removeAllViews();

        Set<String> merged = new LinkedHashSet<String>();
        List<String> history = LocalShellController.getHistory(context);
        for (int i = 0; i < Math.min(history.size(), 6); i++) {
            merged.add(history.get(i));
        }
        for (String snippet : LocalShellController.QUICK_SNIPPETS) {
            merged.add(snippet);
        }

        for (final String cmd : merged) {
            TextView chip = new TextView(context);
            chip.setText(cmd);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            chip.setTextColor(OmniaTheme.color(context, R.color.omnia_charcoal_cyan));
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(OmniaTheme.color(context, R.color.omnia_charcoal_cell));
            bg.setStroke(dp(1), OmniaTheme.color(context, R.color.omnia_charcoal_stroke));
            bg.setCornerRadius(dp(16));
            chip.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    OmniaTheme.hapticTap(v);
                    etShellInput.setText(cmd);
                    etShellInput.setSelection(cmd.length());
                }
            });
            chipsContainer.addView(chip);
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
