package com.jimmy.agent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.system.Os;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    // ---------- константы ----------
    private static final String TERMUX_USR = "/data/data/com.termux/files/usr";
    private static final int BG = 0xFF212121, PANEL = 0xFF2F2F2F, LINE = 0x22FFFFFF,
            TXT = 0xFFECECEC, DIM = 0xFF9B9B9B, DIM2 = 0xFF6E6E6E,
            AMBER = 0xFFFFB020, USER_BG = 0xFF2F2F2F;
    private static final String VERSION = "0.3.0";
    private static final int MAX_ATTEMPTS = 3;      // скрытые ретраи при пустом ответе
    private static final long IDLE_TIMEOUT_MS = 120000; // сторожок "зависшего" ответа

    private File filesDir, usr, home, jimmyDir, codexBin;
    private boolean hasSession = false;
    private boolean setupDone = false;
    private volatile boolean agentBusy = false;
    private volatile Process currentProc = null;
    private volatile boolean stopRequested = false;
    private volatile long lastEventAt = 0;

    // ---------- загрузчик ----------
    private LinearLayout loadingRoot;
    private TextView statusTxt, consoleTxt;
    private ScrollView consoleScroll;

    // ---------- чат ----------
    private LinearLayout chatRoot;
    private ListView listView;
    private EditText input;
    private TextView sendBtn;
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

    @Override
    public void onBackPressed() {
        if (agentBusy) { // сначала останавливаем генерацию, а не выходим
            onSend();
            return;
        }
        moveTaskToBack(true); // сворачиваемся, чтобы прокси дожил в фоне
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
            File bash = new File(usr, "bin/bash");
            Os.chmod(bash.getAbsolutePath(), 0755);
            logconsole("bash: exists=" + bash.exists() + " size=" + bash.length()
                    + " canExecute=" + bash.canExecute());
            String out = runAndWait(new String[]{bash.getAbsolutePath(), "-c",
                    "echo bash-ok && uname -m && ls / >/dev/null && echo io-ok"}, baseEnv(), null);
            out = out.trim();
            logconsole(out.isEmpty() ? "(bash ничего не вывел)" : out);
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
                TextView retry = new TextView(this);
                retry.setText("ПОПРОБОВАТЬ СНОВА");
                retry.setTextColor(0xFF212121);
                retry.setTextSize(15);
                retry.setTypeface(Typeface.DEFAULT_BOLD);
                retry.setGravity(Gravity.CENTER);
                GradientDrawable g = new GradientDrawable();
                g.setColor(AMBER);
                g.setCornerRadius(dp(12));
                retry.setBackground(g);
                retry.setPadding(0, dp(14), 0, dp(14));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(16), 0, 0);
                loadingRoot.addView(retry, lp);
                retry.setOnClickListener(v -> {
                    loadingRoot.removeView(retry);
                    new Thread(this::performSetup, "setup").start();
                });
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
    // Формат строки SYMLINKS.txt:  СОДЕРЖИМОЕ←ГДЕ_СОЗДАТЬ
    //   parts[1] = путь ссылки ОТНОСИТЕЛЬНО usr/ (напр. ./lib/libevent.so)
    //   parts[0] = содержимое ссылки: относительное к папке ссылки
    //              ИЛИ абсолютное с префиксом /data/data/com.termux/files/usr
    private void applySymlinks() throws Exception {
        if (symlinksCache == null) return;
        String txt = new String(symlinksCache, "UTF-8");
        String[] lines = txt.split("\n");
        int ok = 0, skip = 0;
        for (String line : lines) {
            line = line.trim();
            if (!line.contains("←")) continue;
            String[] parts = line.split("←");
            if (parts.length != 2) { skip++; continue; }
            String content = parts[0].trim();
            String linkRel = parts[1].trim();
            if (content.startsWith(TERMUX_USR))
                content = usr.getAbsolutePath() + content.substring(TERMUX_USR.length());
            try {
                File link = new File(usr, linkRel);
                File parent = link.getParentFile();
                if (parent != null) parent.mkdirs();
                link.delete(); // идемпотентно: retry / повторная установка
                Os.symlink(content, link.getAbsolutePath());
                ok++;
            } catch (Throwable t) {
                skip++;
                if (skip <= 5) logconsole("symlink skip: " + linkRel + " (" + t + ")");
            }
        }
        logconsole("создано ссылок: " + ok + (skip > 0 ? ", пропущено: " + skip : ""));
        if (ok < 1000) throw new RuntimeException("слишком мало симлинков (" + ok + ") — проверь парсер");
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
    // ЧАТ (нативный, в стиле ChatGPT)
    // =====================================================================
    private void showChat() {
        chatRoot = new LinearLayout(this);
        chatRoot.setOrientation(LinearLayout.VERTICAL);
        chatRoot.setBackgroundColor(BG);

        // ---------- header ----------
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(8), dp(10));
        head.setBackgroundColor(0xFF171717);
        TextView title = tv("⚡ JimmyAgent", 17, TXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView newChat = tv("✎", 20, TXT, false);
        newChat.setGravity(Gravity.CENTER);
        GradientDrawable nc = new GradientDrawable();
        nc.setColor(0x00000000);
        nc.setCornerRadius(dp(18));
        newChat.setBackground(nc);
        newChat.setPadding(dp(8), dp(2), dp(8), dp(2));
        newChat.setOnClickListener(v -> onNewChat());
        head.addView(newChat, new LinearLayout.LayoutParams(dp(36), dp(36)));

        chatRoot.addView(head);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        chatRoot.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        // ---------- список сообщений ----------
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        listView.setStackFromBottom(true);
        listView.setClipToPadding(false);
        listView.setBackgroundColor(BG);
        chatRoot.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));

        View div2 = new View(this);
        div2.setBackgroundColor(LINE);
        chatRoot.addView(div2, new LinearLayout.LayoutParams(-1, 1));

        // ---------- композер (как у ChatGPT: скруглённая капсула) ----------
        LinearLayout composerWrap = new LinearLayout(this);
        composerWrap.setOrientation(LinearLayout.HORIZONTAL);
        composerWrap.setPadding(dp(12), dp(10), dp(12), dp(12));
        composerWrap.setBackgroundColor(BG);

        LinearLayout capsule = new LinearLayout(this);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.BOTTOM);
        GradientDrawable cap = new GradientDrawable();
        cap.setColor(PANEL);
        cap.setCornerRadius(dp(24));
        capsule.setBackground(cap);
        capsule.setPadding(dp(16), dp(4), dp(6), dp(4));

        input = new EditText(this);
        input.setHint("Сообщение");
        input.setHintTextColor(DIM2);
        input.setTextColor(TXT);
        input.setTextSize(15.5f);
        input.setBackgroundColor(0x00000000);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(1);
        input.setMaxLines(6);
        input.setPadding(0, dp(8), dp(8), dp(8));
        capsule.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendBtn = new TextView(this);
        sendBtn.setText("↑");
        sendBtn.setTextSize(18);
        sendBtn.setTextColor(0xFF212121);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setOnClickListener(v -> onSend());
        applySendStyle();
        capsule.addView(sendBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        composerWrap.addView(capsule, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatRoot.addView(composerWrap);

        setContentView(chatRoot);
        if (msgs.isEmpty())
            addNote("чат подключён к Codex CLI (ChatJimmy, llama3.1-8B, ~17K tok/s). Всё локально.\n✎ — новый чат · долгий тап по сообщению — копировать");
    }

    // круглая кнопка отправки: янтарная в idle, серая ■ занято
    private void applySendStyle() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(agentBusy ? 0xFF555555 : AMBER);
        sendBtn.setBackground(g);
        sendBtn.setText(agentBusy ? "■" : "↑");
        sendBtn.setTextSize(agentBusy ? 14 : 18);
    }

    private void addNote(String t) {
        msgs.add(new Msg(t, 2));
        adapter.notifyDataSetChanged();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("JimmyAgent", text));
            Toast.makeText(this, "скопировано", Toast.LENGTH_SHORT).show();
        }
    }

    private void onNewChat() {
        if (agentBusy) {
            Toast.makeText(this, "grug занят — сначала останови (■)", Toast.LENGTH_SHORT).show();
            return;
        }
        msgs.clear();
        hasSession = false;
        adapter.notifyDataSetChanged();
        addNote("новый чат. grug готов 🦴");
        input.requestFocus();
    }

    private void onRegenerate() {
        if (agentBusy) return;
        String lastUser = null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).who == 0) { lastUser = msgs.get(i).text; break; }
        }
        if (lastUser == null) {
            Toast.makeText(this, "нечего повторять — нет твоих сообщений", Toast.LENGTH_SHORT).show();
            return;
        }
        addNote("↻ grug печатает ещё раз");
        final Msg live = new Msg("", 1);
        msgs.add(live);
        adapter.notifyDataSetChanged();
        setBusy(true);
        final String text = lastUser;
        new Thread(() -> runCodex(text, live), "codex-run").start();
    }

    private void onSend() {
        if (agentBusy) { // кнопка превращается в «стоп»
            stopRequested = true;
            Process p = currentProc;
            if (p != null) p.destroy();
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        msgs.add(new Msg(text, 0));
        final Msg live = new Msg("", 1);
        msgs.add(live);
        adapter.notifyDataSetChanged();
        setBusy(true);
        new Thread(() -> runCodex(text, live), "codex-run").start();
    }

    private void setBusy(boolean b) {
        agentBusy = b;
        if (b) stopRequested = false;
        runOnUiThread(() -> {
            applySendStyle();
            input.setHint(agentBusy ? "grug печатает…" : "Сообщение");
            adapter.notifyDataSetChanged();
        });
    }

    // =====================================================================
    // ЗАПУСК CODEX (со скрытыми ретраями)
    // =====================================================================
    private void runCodex(String userMsg, Msg live) {
        // пустой ответ — норма для 8B: молча пробуем ещё раз, не дёргая юзера
        boolean answered = false;
        String err = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !answered && !stopRequested; attempt++) {
            try {
                answered = runCodexOnce(userMsg, live);
            } catch (Throwable t) {
                err = String.valueOf(t);
            }
            if (stopRequested) break;
            if (!answered && attempt < MAX_ATTEMPTS) {
                // тихий ретрай: без уведомлений — просто снова «думает»
                try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            }
        }
        setBusy(false);
        if (stopRequested) {
            appendLive(live, "⏹ остановлено");
        } else if (!answered) {
            appendLive(live, err != null
                    ? "💥 ошибка: " + err
                    : "(grug так и не ответил за " + MAX_ATTEMPTS
                    + " попытки — переформулируй или жми ↻ ещё раз)");
        }
    }

    // одна попытка. true — есть осмысленный ответ
    private boolean runCodexOnce(String userMsg, Msg live) throws Exception {
        boolean gotContent = false;
        // каждая попытка пишет в пузырь заново (ретраи незаметны)
        runOnUiThread(() -> {
            live.text = "grug думает… 🦴\n";
            adapter.notifyDataSetChanged();
        });

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
        currentProc = p;
        lastEventAt = System.currentTimeMillis();

        // сторожок: нет событий N секунд → убиваем попытку
        Thread wd = new Thread(() -> {
            try {
                while (true) {
                    try { p.exitValue(); break; } catch (IllegalThreadStateException alive) { /* ещё живёт */ }
                    if (System.currentTimeMillis() - lastEventAt > IDLE_TIMEOUT_MS) {
                        p.destroy();
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (Throwable ignored) {}
        });
        wd.setDaemon(true);
        wd.start();

        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        boolean sawThreadStarted = false;
        while ((line = r.readLine()) != null) {
            lastEventAt = System.currentTimeMillis();
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("{")) continue;
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
                    if (!t.isEmpty()) {
                        appendLive(live, "🦴 " + oneLine(t, 320) + "\n");
                        gotContent = true;
                    }
                } else if ("command_execution".equals(it)) {
                    String c = item.optString("command", "").trim();
                    if (!c.isEmpty()) appendLive(live, "⚙ " + oneLine(c, 200) + "\n");
                    String out = item.optString("aggregated_output", "").trim();
                    int exit = item.optInt("exit_code", 0);
                    if (!out.isEmpty())
                        appendLive(live, dim(out, 500) + (exit != 0 ? " [exit " + exit + "]" : "") + "\n");
                } else if ("agent_message".equals(it)) {
                    String t = item.optString("text", "").trim();
                    if (!t.isEmpty()) {
                        appendLive(live, t + "\n");
                        gotContent = true;
                    }
                }
            } catch (Throwable ignore) { }
        }
        int code = p.waitFor();
        currentProc = null;
        if (sawThreadStarted) hasSession = true;
        // контент = reasoning или agent_message; одни лишь команды без ответа считаем провалом
        return gotContent && !stopRequested;
    }

    private String oneLine(String s, int max) {
        s = s.replace("\n", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String dim(String s, int max) {
        if (s.length() > max) return s.substring(s.length() - max);
        return s;
    }

    private void appendLive(Msg live, String add) {
        runOnUiThread(() -> {
            live.text += add;
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

        private int lastAssistantIndex() {
            for (int i = msgs.size() - 1; i >= 0; i--)
                if (msgs.get(i).who == 1) return i;
            return -1;
        }

        public View getView(final int pos, View cv, ViewGroup parent) {
            final Msg m = msgs.get(pos);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(5), dp(12), dp(5));

            final TextView t = new TextView(MainActivity.this);
            t.setTextSize(15.5f);
            t.setTextColor(TXT);
            t.setLineSpacing(3, 1.0f);
            t.setTextIsSelectable(true);
            t.setText(m.text.isEmpty() ? "🦴…" : m.text);
            // долгий тап — копировать любое сообщение
            t.setOnLongClickListener(v -> {
                if (!m.text.isEmpty()) copyToClipboard(m.text);
                return true;
            });

            GradientDrawable g = new GradientDrawable();
            if (m.who == 0) {
                // пользователь — пузырь справа
                g.setColor(USER_BG);
                g.setCornerRadius(dp(18));
                t.setBackground(g);
                int padH = dp(14), padV = dp(10);
                t.setPadding(padH, padV, padH, padV);
                LinearLayout wrap = hwrap(Gravity.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(64), 0, 0, 0);
                wrap.addView(t, lp);
                row.addView(wrap);
            } else if (m.who == 2) {
                // системная заметка — по центру, тускло
                t.setTextColor(DIM2);
                t.setTextSize(12.5f);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(8), dp(2), dp(8), dp(2));
                row.addView(t);
            } else {
                // ассистент — на всю ширину, без пузыря (как у ChatGPT)
                t.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                t.setTextSize(13.5f);
                t.setPadding(dp(4), dp(2), dp(4), dp(2));
                row.addView(t);

                // панель действий: ⧉ копировать · ↻ ещё раз (у последнего)
                if (!m.text.isEmpty() && !agentBusy) {
                    LinearLayout actions = new LinearLayout(MainActivity.this);
                    actions.setOrientation(LinearLayout.HORIZONTAL);
                    actions.setPadding(dp(4), 0, dp(4), dp(2));
                    actions.addView(actionChip("⧉ копировать", v -> copyToClipboard(m.text)));
                    if (pos == lastAssistantIndex()) {
                        actions.addView(actionChip("↻ ещё раз", v -> onRegenerate()));
                    }
                    row.addView(actions);
                }
            }
            return row;
        }

        private LinearLayout hwrap(int gravity) {
            LinearLayout w = new LinearLayout(MainActivity.this);
            w.setOrientation(LinearLayout.HORIZONTAL);
            w.setGravity(gravity);
            return w;
        }

        private TextView actionChip(String label, View.OnClickListener l) {
            TextView c = new TextView(MainActivity.this);
            c.setText(label);
            c.setTextSize(12.5f);
            c.setTextColor(DIM);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x14FFFFFF);
            bg.setCornerRadius(dp(10));
            c.setBackground(bg);
            c.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            c.setLayoutParams(lp);
            c.setOnClickListener(l);
            return c;
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
