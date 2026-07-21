package com.jimmy.agent;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.system.Os;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView; // unused, kept minimal
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    // ---------- константы ----------
    private static final String TERMUX_USR = "/data/data/com.termux/files/usr";
    private static final int BG = 0xFF212121, PANEL = 0xFF2A2A2A, LINE = 0x22FFFFFF,
            TXT = 0xFFECECEC, DIM = 0xFF9B9B9B, DIM2 = 0xFF6E6E6E,
            AMBER = 0xFFFFB020, USER_BG = 0xFF2F2F2F, GRUG = 0xFFB9A5FF;
    private static final String VERSION = "0.2";

    private File filesDir, usr, home, jimmyDir, codexBin;
    private boolean hasSession = false;
    private boolean setupDone = false;
    private volatile boolean agentBusy = false;

    // ---------- загрузчик ----------
    private LinearLayout loadingRoot;
    private TextView statusTxt, consoleTxt;
    private ScrollView consoleScroll;

    // ---------- чат ----------
    private LinearLayout chatRoot;
    private ListView listView;
    private EditText input;
    private Button sendBtn;
    private final ArrayList<Msg> msgs = new ArrayList<>();
    private ChatAdapter adapter;

    static class Msg {
        String text;
        int who; // 0 user, 1 agent, 2 system-note
        Msg(String t, int w) { text = t; who = w; }
    }

    // =====================================================================
    // lifecycle
    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        filesDir = getFilesDir();
        usr = new File(filesDir, "usr");
        home = new File(filesDir, "home");
        jimmyDir = new File(home, "jimmy");
        codexBin = new File(filesDir, "codex/codex");
        setupDone = new File(filesDir, ".setup_done").exists();

        adapter = new ChatAdapter();
        if (setupDone) {
            startProxy();
            showChat();
        } else {
            showLoading();
            new Thread(this::performSetup, "setup").start();
        }
    }

    // =====================================================================
    // ЭКРАН ЗАГРУЗКИ
    // =====================================================================
    private void showLoading() {
        loadingRoot = new LinearLayout(this);
        loadingRoot.setOrientation(LinearLayout.VERTICAL);
        loadingRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        loadingRoot.setBackgroundColor(BG);
        loadingRoot.setPadding(dp(28), dp(48), dp(28), dp(24));

        TextView bolt = tv("⚡", 64, AMBER, true);
        bolt.setGravity(Gravity.CENTER);
        loadingRoot.addView(bolt);

        TextView title = tv("JimmyAgent", 26, TXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(6), 0, 0);
        loadingRoot.addView(title);

        TextView sub = tv("встроенный Termux + Codex CLI + ChatJimmy", 13, DIM, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(28));
        loadingRoot.addView(sub);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        loadingRoot.addView(pb, new LinearLayout.LayoutParams(dp(48), dp(48)));

        statusTxt = tv("подготовка…", 15, TXT, false);
        statusTxt.setGravity(Gravity.CENTER);
        statusTxt.setPadding(0, dp(20), 0, dp(10));
        loadingRoot.addView(statusTxt);

        consoleScroll = new ScrollView(this);
        consoleTxt = tv("", 11, DIM2, false);
        consoleTxt.setTypeface(Typeface.MONOSPACE);
        consoleTxt.setMovementMethod(new ScrollingMovementMethod());
        consoleScroll.addView(consoleTxt);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        consoleScroll.setLayoutParams(lp);
        consoleTxt.setPadding(dp(8), dp(6), dp(8), dp(6));
        loadingRoot.addView(consoleScroll);

        setContentView(loadingRoot);
    }

    private void status(final String s) {
        logconsole("▸ " + s);
        runOnUiThread(() -> statusTxt.setText(s));
    }

    private void logconsole(final String s) {
        runOnUiThread(() -> {
            consoleTxt.append(s + "\n");
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // =====================================================================
    // УСТАНОВКА ОКРУЖЕНИЯ
    // =====================================================================
    private void performSetup() {
        try {
            status("распаковываю Termux-окружение…");
            unzipAsset("bootstrap-aarch64.zip", usr);
            logconsole("файлы на месте");

            status("создаю симлинки…");
            applySymlinks();
            logconsole("симлинки готовы");

            status("выставляю права на исполнение…");
            home.mkdirs();
            new File(usr, "tmp").mkdirs();
            chmodTree(usr);
            logconsole("chmod ✔");

            status("распаковываю Codex CLI…");
            untarGzAsset("codex-musl.bin", new File(filesDir, "codex"));
            File rawCodex = new File(filesDir, "codex/codex-aarch64-unknown-linux-musl");
            if (rawCodex.exists() && !rawCodex.equals(codexBin)) rawCodex.renameTo(codexBin);
            Os.chmod(codexBin.getAbsolutePath(), 0755);
            logconsole("codex ✔ (" + codexBin.length() / 1024 / 1024 + " МБ)");

            status("раскладываю файлы агента…");
            jimmyDir.mkdirs();
            new File(home, ".codex").mkdirs();
            copyAsset("jimmy/AGENTS.md", new File(jimmyDir, "AGENTS.md"));
            copyAsset("jimmy/config.toml", new File(home, ".codex/config.toml"));
            logconsole("AGENTS.md + config.toml ✔");

            status("проверяю bash…");
            String out = runAndWait(new String[]{usr + "/bin/bash", "-c",
                    "echo bash-ok && uname -m"}, baseEnv(), null);
            logconsole(out.trim());
            if (!out.contains("bash-ok")) throw new RuntimeException("bash не запустился: " + out);

            new File(filesDir, ".setup_done").createNewFile();
            status("готово!");
            Thread.sleep(400);

            runOnUiThread(() -> {
                startProxy();
                showChat();
                addNote("🦴 окружение готово. grug доволен. complexity very, very bad.");
            });
        } catch (final Throwable t) {
            status("ОШИБКА УСТАНОВКИ");
            logconsole("💥 " + t);
            runOnUiThread(() -> {
                Button retry = new Button(this);
                retry.setText("Попробовать снова");
                retry.setOnClickListener(v -> {
                    loadingRoot.removeView(retry);
                    new Thread(this::performSetup, "setup").start();
                });
                loadingRoot.addView(retry);
            });
        }
    }

    // ---------- zip ----------
    private void unzipAsset(String asset, File dest) throws Exception {
        byte[] symlinks = null;
        InputStream in = getAssets().open(asset);
        ZipInputStream zin = new ZipInputStream(in);
        byte[] buf = new byte[65536];
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            File out = new File(dest, name);
            if (name.equals("SYMLINKS.txt") || name.endsWith("/SYMLINKS.txt")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                symlinks = bos.toByteArray();
                continue;
            }
            if (e.isDirectory() || name.endsWith("/")) {
                out.mkdirs();
                continue;
            }
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            int n;
            while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
        }
        zin.close();
        symlinksCache = symlinks;
    }

    private byte[] symlinksCache;

    // ---------- symlinks ----------
    private void applySymlinks() throws Exception {
        if (symlinksCache == null) return;
        String txt = new String(symlinksCache, "UTF-8");
        String[] lines = txt.split("\n");
        int ok = 0;
        for (String line : lines) {
            if (!line.contains("←")) continue;
            String[] parts = line.split("←");
            String target = parts[0];
            String src = parts[1];
            target = target.replace(TERMUX_USR, usr.getAbsolutePath());
            try {
                new File(target).getParentFile().mkdirs();
                Os.symlink(src, target);
                ok++;
            } catch (Throwable t) {
                logconsole("symlink skip: " + target);
            }
        }
        logconsole("создано ссылок: " + ok);
    }

    // ---------- chmod ----------
    private void chmodTree(File root) throws Exception {
        chmodWalk(root);
    }

    private void chmodWalk(File f) throws Exception {
        if (f.isDirectory()) {
            Os.chmod(f.getAbsolutePath(), 0755);
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) chmodWalk(k);
        } else {
            String p = f.getAbsolutePath();
            boolean exe = p.contains("/bin/") || p.contains("/libexec/")
                    || p.contains("/lib/apt/") || p.endsWith(".sh");
            Os.chmod(p, exe ? 0755 : 0644);
        }
    }

    // ---------- tar.gz ----------
    private void untarGzAsset(String asset, File dest) throws Exception {
        dest.mkdirs();
        InputStream in = new GZIPInputStream(getAssets().open(asset), 65536);
        byte[] block = new byte[512];
        while (true) {
            int got = readFullyOrPartial(in, block);
            if (got < 512 || isZeroBlock(block)) break;
            String name = tarStr(block, 0, 100);
            String prefix = tarStr(block, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = Long.parseLong(tarStr(block, 124, 12).trim(), 8);
            char type = (char) block[156];
            if (type == '5') {
                new File(dest, name).mkdirs();
            } else if (type == '0' || type == '\0') {
                File out = new File(dest, name);
                out.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                long remain = size;
                byte[] data = new byte[512];
                while (remain > 0) {
                    readFully(in, data, 512);
                    int w = (int) Math.min(512, remain);
                    fos.write(data, 0, w);
                    remain -= w;
                }
                fos.close();
            } else {
                // skip payload
                long remain = size;
                byte[] data = new byte[512];
                while (remain > 0) { readFully(in, data, 512); remain -= 512; }
            }
        }
        in.close();
    }

    private static String tarStr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off).trim();
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static int readFullyOrPartial(InputStream in, byte[] b) throws Exception {
        int total = 0;
        while (total < b.length) {
            int n = in.read(b, total, b.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void readFully(InputStream in, byte[] b, int need) throws Exception {
        int total = 0;
        while (total < need) {
            int n = in.read(b, total, need - total);
            if (n < 0) throw new Exception("EOF in tar");
            total += n;
        }
    }

    // ---------- copy ----------
    private void copyAsset(String asset, File out) throws Exception {
        InputStream in = getAssets().open(asset);
        out.getParentFile().mkdirs();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
    }

    // =====================================================================
    // ОКРУЖЕНИЕ ПРОЦЕССОВ
    // =====================================================================
    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("PREFIX", usr.getAbsolutePath());
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", usr.getAbsolutePath() + "/tmp");
        env.put("PATH", usr.getAbsolutePath() + "/bin:" + usr.getAbsolutePath()
                + "/bin/applets:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", usr.getAbsolutePath() + "/lib");
        env.put("LANG", "C.UTF-8");
        env.put("CHATJIMMY_API_KEY", "dummy");
        env.put("CODEX_HOME", home.getAbsolutePath() + "/.codex");
        return env;
    }

    private void applyEnv(ProcessBuilder pb, Map<String, String> over) {
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(over);
    }

    private String runAndWait(String[] cmd, Map<String, String> env, File cwd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        applyEnv(pb, env);
        if (cwd != null) pb.directory(cwd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        p.waitFor();
        return sb.toString();
    }

    // =====================================================================
    // ПРОКСИ (Chaquopy python → proxy.py)
    // =====================================================================
    private void startProxy() {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
        final Python py = Python.getInstance();
        final String fd = filesDir.getAbsolutePath();
        new Thread(() -> {
            try {
                py.getModule("android_main").callAttr("main", fd);
            } catch (Throwable t) {
                logconsole("proxy error: " + t);
            }
        }, "proxy").start();
    }

    // =====================================================================
    // ЧАТ (нативный)
    // =====================================================================
    private void showChat() {
        chatRoot = new LinearLayout(this);
        chatRoot.setOrientation(LinearLayout.VERTICAL);
        chatRoot.setBackgroundColor(BG);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(8), dp(14), dp(8));
        head.setBackgroundColor(0xFF171717);
        TextView title = tv("⚡ JimmyAgent", 16, TXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView ver = tv("codex · chatjimmy 🦴", 12, DIM, false);
        head.addView(ver);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        chatRoot.addView(head);
        chatRoot.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        listView.setBackgroundColor(BG);
        chatRoot.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // input bar
        View div2 = new View(this);
        div2.setBackgroundColor(LINE);
        chatRoot.addView(div2, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(PANEL);
        input = new EditText(this);
        input.setHint("сообщение grug…");
        input.setHintTextColor(DIM2);
        input.setTextColor(TXT);
        input.setBackgroundColor(PANEL);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setTextSize(15);
        input.setMinLines(1);
        input.setMaxLines(4);
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = new Button(this);
        sendBtn.setText("⚡");
        sendBtn.setOnClickListener(v -> onSend());
        bar.addView(sendBtn);
        chatRoot.addView(bar);

        setContentView(chatRoot);
        addNote("чат подключён к Codex CLI (ChatJimmy, llama3.1-8B, ~17K tok/s). Всё локально.");
    }

    private void addNote(String t) {
        msgs.add(new Msg(t, 2));
        adapter.notifyDataSetChanged();
    }

    private void onSend() {
        if (agentBusy) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        msgs.add(new Msg(text, 0));
        final Msg live = new Msg("", 1);
        msgs.add(live);
        adapter.notifyDataSetChanged();
        agentBusy = true;
        sendBtn.setEnabled(false);
        new Thread(() -> runCodex(text, live), "codex-run").start();
    }

    // =====================================================================
    // ЗАПУСК CODEX
    // =====================================================================
    private void runCodex(String userMsg, Msg live) {
        StringBuilder acc = new StringBuilder();
        try {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(codexBin.getAbsolutePath());
            cmd.add("exec");
            if (hasSession) {
                cmd.add("resume");
                cmd.add("--last");
            }
            cmd.add("--json");
            cmd.add("--skip-git-repo-check");
            cmd.add("-s");
            cmd.add("workspace-write");
            cmd.add(userMsg);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            applyEnv(pb, baseEnv());
            pb.directory(jimmyDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            appendLive(live, acc, "grug думает… 🦴\n");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            boolean sawThreadStarted = false;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!line.startsWith("{")) {
                    // не-JSON строки (шапка codex exec) — пропускаем
                    continue;
                }
                try {
                    JSONObject ev = new JSONObject(line);
                    String type = ev.optString("type", "");
                    if ("thread.started".equals(type)) { sawThreadStarted = true; continue; }
                    if (!"item.completed".equals(type)) continue;
                    JSONObject item = ev.optJSONObject("item");
                    if (item == null) continue;
                    String it = item.optString("type", "");
                    if ("reasoning".equals(it)) {
                        String t = item.optString("text", "").trim();
                        if (!t.isEmpty()) appendLive(live, acc, "🦴 " + oneLine(t, 320) + "\n");
                    } else if ("command_execution".equals(it)) {
                        String c = item.optString("command", "").trim();
                        if (!c.isEmpty()) {
                            appendLive(live, acc, "⚙ " + oneLine(c, 200) + "\n");
                        }
                        String out = item.optString("aggregated_output", "").trim();
                        int exit = item.optInt("exit_code", 0);
                        if (!out.isEmpty()) {
                            appendLive(live, acc, dim(out, 500) + (exit != 0 ? " [exit " + exit + "]" : "") + "\n");
                        }
                    } else if ("agent_message".equals(it)) {
                        String t = item.optString("text", "").trim();
                        if (!t.isEmpty()) appendLive(live, acc, t + "\n");
                    }
                } catch (Throwable ignore) { }
            }
            int code = p.waitFor();
            if (sawThreadStarted) hasSession = true;
            if (acc.length() == 0 || acc.toString().trim().equals("grug думает… 🦴")) {
                appendLive(live, acc, "(модель вернула пустой ответ — попробуй ещё раз, grug иногда залипает) exit=" + code);
            }
        } catch (Throwable t) {
            appendLive(live, acc, "💥 ошибка: " + t);
        } finally {
            agentBusy = false;
            runOnUiThread(() -> {
                sendBtn.setEnabled(true);
                adapter.notifyDataSetChanged();
            });
        }
    }

    private String oneLine(String s, int max) {
        s = s.replace("\n", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String dim(String s, int max) {
        if (s.length() > max) return s.substring(s.length() - max);
        return s;
    }

    private void appendLive(Msg live, StringBuilder acc, String add) {
        acc.append(add);
        runOnUiThread(() -> {
            live.text = acc.toString();
            adapter.notifyDataSetChanged();
        });
    }

    // =====================================================================
    // АДАПТЕР ЧАТА
    // =====================================================================
    class ChatAdapter extends BaseAdapter {
        public int getCount() { return msgs.size(); }
        public Object getItem(int i) { return msgs.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View cv, ViewGroup parent) {
            Msg m = msgs.get(pos);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(4), dp(12), dp(4));
            TextView t = new TextView(MainActivity.this);
            t.setTextSize(15);
            t.setTextColor(TXT);
            t.setLineSpacing(2, 1.0f);
            t.setTextIsSelectable(true);
            t.setText(m.text.isEmpty() ? "…" : m.text);
            int padH = dp(13), padV = dp(9);
            t.setPadding(padH, padV, padH, padV);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            if (m.who == 0) {
                g.setColor(USER_BG);
                row.setGravity(Gravity.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(64), 0, 0, 0);
                row.addView(t, lp);
            } else if (m.who == 2) {
                g.setColor(0x00000000);
                t.setTextColor(DIM2);
                t.setTextSize(12.5f);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                t.setPadding(0, dp(2), 0, dp(2));
                row.addView(t);
            } else {
                g.setColor(0x10FFFFFF);
                row.setGravity(Gravity.START);
                t.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                t.setTextSize(13.5f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, dp(32), 0);
                row.addView(t, lp);
            }
            t.setBackground(g);
            return row;
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================
    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
