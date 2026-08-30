package dev.sushiomsky.agenticbrowser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_ACTION = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";

    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String CDP_URL = "http://127.0.0.1:9222/json/version";

    private static final String BOOTSTRAP =
            "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
            "if grep -qE '^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=' ~/.termux/termux.properties; then " +
            "sed -i -E 's/^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=.*$/allow-external-apps = true/' ~/.termux/termux.properties; " +
            "else printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties; fi; " +
            "termux-reload-settings; echo 'Termux external commands enabled.'";

    private static final String INSTALL_TIER1 =
            "set -e; export DEBIAN_FRONTEND=noninteractive; " +
            "pkg update -y; pkg install -y git curl x11-repo chromium which; " +
            "cd ~; " +
            "if [ -d termux-agent-browser/.git ]; then cd termux-agent-browser && git pull --ff-only; " +
            "else git clone https://github.com/pjy010218/termux-agent-browser.git && cd termux-agent-browser; fi; " +
            "chmod +x scripts/setup-chromium.sh; ./scripts/setup-chromium.sh; " +
            "echo; echo '=== CDP ==='; curl -fsS http://127.0.0.1:9222/json/version || true";

    private static final String INSTALL_TIER2 =
            "set -e; export DEBIAN_FRONTEND=noninteractive; " +
            "pkg update -y; pkg install -y git curl x11-repo chromium rust nodejs-lts which; " +
            "cd ~; " +
            "if [ -d termux-agent-browser/.git ]; then cd termux-agent-browser && git pull --ff-only; " +
            "else git clone https://github.com/pjy010218/termux-agent-browser.git && cd termux-agent-browser; fi; " +
            "chmod +x scripts/build-agent-browser.sh scripts/setup-chromium.sh; " +
            "CARGO_PROFILE_RELEASE_LTO=false ./scripts/build-agent-browser.sh; ./scripts/setup-chromium.sh; " +
            "echo; agent-browser --version; curl -fsS http://127.0.0.1:9222/json/version || true";

    private static final String RESTART_BROWSER =
            "set -e; " +
            "if command -v sv >/dev/null 2>&1; then sv force-restart chromium-headless || true; fi; " +
            "sleep 2; curl -fsS http://127.0.0.1:9222/json/version";

    private static final String DIAGNOSTICS =
            "echo '=== Android/Termux ==='; termux-info 2>/dev/null || true; " +
            "echo; echo '=== Memory ==='; free -h 2>/dev/null || true; " +
            "echo; echo '=== Chromium ==='; chromium-browser --version 2>/dev/null || chromium --version 2>/dev/null || true; " +
            "echo; echo '=== Service ==='; sv status chromium-headless 2>/dev/null || true; " +
            "echo; echo '=== Port 9222 ==='; (ss -tlnp 2>/dev/null || netstat -tln 2>/dev/null) | grep 9222 || true; " +
            "echo; echo '=== CDP ==='; curl -sS http://127.0.0.1:9222/json/version 2>&1 || true; " +
            "echo; echo '=== Hermes ==='; hermes doctor 2>&1 || true";

    private static final String INSTALL_HERMES =
            "set -e; pkg update -y; pkg install -y curl; " +
            "curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash; " +
            "export PATH=\"$HOME/.local/bin:$PATH\"; hash -r; " +
            "echo; echo '=== Hermes ==='; hermes --version || true";

    private static final String HERMES_MODEL_SETUP =
            "export PATH=\"$HOME/.local/bin:$PATH\"; " +
            "if ! command -v hermes >/dev/null 2>&1; then echo 'Install Hermes first.'; exit 3; fi; " +
            "hermes model";

    private static final String HERMES_CONFIG =
            "set -e; " +
            "if ! command -v hermes >/dev/null 2>&1; then echo 'Hermes is not installed in this Termux environment.'; exit 3; fi; " +
            "hermes config set browser.cdp_url http://127.0.0.1:9222; " +
            "hermes config set browser.dialog_policy must_respond; " +
            "hermes config set browser.dialog_timeout_s 300; " +
            "hermes config check || true; " +
            "echo 'Hermes configured for Android CDP.'";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView termuxStatus;
    private TextView permissionStatus;
    private TextView cdpStatus;
    private TextView log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        scroll.addView(root);

        TextView title = text("Agentic Browser Setup", 25, true);
        root.addView(title);
        TextView subtitle = text("Termux + Chromium/CDP + Hermes setup assistant", 15, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        root.addView(section("Status"));
        termuxStatus = text("Termux: checking…", 15, false);
        permissionStatus = text("RUN_COMMAND: checking…", 15, false);
        cdpStatus = text("CDP :9222: checking…", 15, false);
        root.addView(termuxStatus);
        root.addView(permissionStatus);
        root.addView(cdpStatus);
        root.addView(button("Refresh status", v -> refreshStatus()));

        root.addView(section("1 · Termux bootstrap"));
        root.addView(text("Install Termux first. Then enable external commands once inside Termux. The bootstrap command is copied automatically.", 14, false));
        root.addView(button("Open Termux / download page", v -> openTermux()));
        root.addView(button("Copy bootstrap + open Termux", v -> bootstrapTermux()));
        root.addView(button("Grant RUN_COMMAND permission", v -> requestTermuxPermission()));
        root.addView(button("Open this app's permission settings", v -> openAppSettings()));

        root.addView(section("2 · Browser backend"));
        root.addView(button("Install Tier 1 · Chromium/CDP", v -> runVisible(INSTALL_TIER1, "Install Chromium/CDP")));
        root.addView(button("Install Tier 2 · agent-browser", v -> runVisible(INSTALL_TIER2, "Build agent-browser")));
        root.addView(button("Restart Chromium", v -> runBackground(RESTART_BROWSER, "Restart Chromium")));
        root.addView(button("Test CDP endpoint", v -> checkCdp()));

        root.addView(section("3 · Hermes"));
        root.addView(button("Install / update Hermes Agent", v -> runVisible(INSTALL_HERMES, "Install Hermes Agent")));
        root.addView(button("Open Hermes model setup", v -> runVisible(HERMES_MODEL_SETUP, "Hermes model setup")));
        root.addView(button("Apply Hermes CDP configuration", v -> runVisible(HERMES_CONFIG, "Configure Hermes")));
        root.addView(button("Copy Hermes commands", v -> copyText(
                "hermes config set browser.cdp_url http://127.0.0.1:9222\n" +
                "hermes config set browser.dialog_policy must_respond\n" +
                "hermes config set browser.dialog_timeout_s 300\n" +
                "hermes config check", "Hermes commands copied")));

        root.addView(section("Diagnostics"));
        root.addView(button("Run full diagnostics", v -> runVisible(DIAGNOSTICS, "Agentic browser diagnostics")));
        root.addView(button("Copy Tier 1 install command", v -> copyText(INSTALL_TIER1, "Install command copied")));

        log = text("Ready.", 13, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(0, dp(14), 0, 0);
        root.addView(log);
        return scroll;
    }

    private TextView section(String value) {
        TextView t = text(value, 18, true);
        t.setPadding(0, dp(22), 0, dp(8));
        return t;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshStatus() {
        boolean termux = isPackageInstalled(TERMUX_PACKAGE);
        boolean permission = checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        termuxStatus.setText("Termux: " + (termux ? "✓ installed" : "✗ not installed"));
        permissionStatus.setText("RUN_COMMAND: " + (permission ? "✓ granted" : "✗ not granted"));
        checkCdp();
    }

    private boolean isPackageInstalled(String name) {
        try {
            getPackageManager().getPackageInfo(name, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        openUrl("https://f-droid.org/packages/com.termux/");
    }

    private void bootstrapTermux() {
        copyText(BOOTSTRAP, "Bootstrap copied — paste it in Termux");
        openTermux();
    }

    private void requestTermuxPermission() {
        if (!isPackageInstalled(TERMUX_PACKAGE)) {
            toast("Install Termux first.");
            openTermux();
            return;
        }
        try {
            requestPermissions(new String[]{TERMUX_PERMISSION}, 1001);
        } catch (RuntimeException e) {
            log("Permission request failed: " + e.getMessage());
            openAppSettings();
        }
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void checkCdp() {
        cdpStatus.setText("CDP :9222: checking…");
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(CDP_URL).openConnection();
                c.setConnectTimeout(1800);
                c.setReadTimeout(1800);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String first = r.readLine();
                main.post(() -> {
                    cdpStatus.setText("CDP :9222: ✓ online (HTTP " + code + ")");
                    log("CDP online: " + (first == null ? "response received" : first));
                });
            } catch (Exception e) {
                main.post(() -> cdpStatus.setText("CDP :9222: ✗ offline"));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void runVisible(String command, String label) {
        runTermux(command, label, false);
    }

    private void runBackground(String command, String label) {
        runTermux(command, label, true);
    }

    private void runTermux(String command, String label, boolean background) {
        if (!isPackageInstalled(TERMUX_PACKAGE)) {
            toast("Termux is not installed.");
            openTermux();
            return;
        }
        if (checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            toast("Grant RUN_COMMAND first.");
            requestTermuxPermission();
            return;
        }

        Intent intent = new Intent(TERMUX_ACTION);
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE));
        intent.putExtra(EXTRA_PATH, BASH);
        intent.putExtra(EXTRA_ARGS, new String[]{"-lc", command});
        intent.putExtra(EXTRA_WORKDIR, HOME);
        intent.putExtra(EXTRA_BACKGROUND, background);
        intent.putExtra(EXTRA_LABEL, label);
        try {
            startService(intent);
            log(label + " sent to Termux." + (background ? " Running in background." : " Follow progress in Termux."));
            if (!background) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
                if (launch != null) startActivity(launch);
            } else {
                main.postDelayed(this::checkCdp, 2500);
            }
        } catch (Exception e) {
            log("Termux command failed to start: " + e.getMessage() +
                    "\nMake sure allow-external-apps=true is enabled and reload Termux settings.");
        }
    }

    private void copyText(String value, String message) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Agentic Browser Setup", value));
        toast(message);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            log("Could not open URL: " + url);
        }
    }

    private void log(String value) {
        if (log != null) log.setText(value);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}
