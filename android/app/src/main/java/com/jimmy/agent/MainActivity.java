package com.jimmy.agent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    // ---------- константы ----------
    private static final String TERMUX_USR = "/data/data/com.termux/files/usr";
    private static final int BG = 0xFF171717, HEAD = 0xFF101010, PANEL = 0xFF101010,
            LINE = 0x1FFFFFFF, TXT = 0xFFECECEC, DIM = 0xFF9B9B9B, DIM2 = 0xFF6E6E6E,
            AMBER = 0xFFFFB020, USER_BG = 0xFF2F2F2F, GRUG = 0xFFB9A5FF,
            CODE_BG = 0xFF0D0D0D, CODE_TXT = 0xFFD8DEE4;
    // Версия берётся из manifest'а (versionName/versionCode), чтобы обновление
    // ассетов срабатывало на КАЖДОМ новом APK без ручного бампа константы —
    // ручной бамп уже один раз протёк (0.5.2/0.5.3 уехали со старым config.toml).
    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + "/" + pi.versionCode;
        } catch (Throwable t) {
            return "unknown";
        }
    }
    private static final int MAX_ATTEMPTS = 3;
    private static final long IDLE_TIMEOUT_MS = 120000; // сторожок «зависшего» ответа

    // маркеры форматирования внутри потока ответа (не для пользователя)
    private static final String T_OPEN = "⟦T⟧", T_CLOSE = "⟦/T⟧";
    private static final String C_OPEN = "⟦C⟧", C_CLOSE = "⟦/C⟧";
    // ловим и «кривой» вариант <\think>, который иногда выдаёт 8B
    private static final Pattern THINK_RX = Pattern.compile("(?is)<\\\\?think>\\s*(.*?)(?:</\\\\?think>|$)");

    private File filesDir, usr, home, jimmyDir, codexBin;
    private boolean hasSession = false;
    private boolean setupDone = false;
    private volatile boolean agentBusy = false;
    private volatile boolean stopRequested = false;
    private volatile long lastEventAt = 0;
    private Process currentProc = null;
    private java.net.HttpURLConnection currentConn = null;

    // ---------- режимы: 💬 чистый чат / 🛠 codex-агент ----------
    private boolean chatMode = true;
    private TextView modeBtn, modeSub;
    private final ArrayList<String[]> chatHistory = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

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
    private boolean showingFiles = false;
    private boolean editingPrompt = false;

    // ---------- файлы ----------
    private File filesCurDir;
    private final ArrayList<File> fileRows = new ArrayList<>();
    private FilesAdapter filesAdapter;

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
        filesAdapter = new FilesAdapter();
        if (setupDone) {
            refreshAssetsIfNeeded();
            startProxy();
            showChat();
            startSmokeOnce();
        } else {
            showLoading();
            new Thread(this::performSetup, "setup").start();
        }
    }

    @Override
    public void onBackPressed() {
        if (agentBusy) {          // сначала останавливаем генерацию, а не выходим
            stopAgent();
            return;
        }
        if (editingPrompt || showingFiles) {
            backToChat();
            return;
        }
        moveTaskToBack(true);     // сворачиваемся: прокси доживает в фоне
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
        runOnUiThread(() -> { if (statusTxt != null) statusTxt.setText(s); });
    }

    private void logconsole(final String s) {
        runOnUiThread(() -> {
            if (consoleTxt == null) return;
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
            copyAsset("jimmy/AGENTS.md", new File(jimmyDir, ".AGENTS.md.default"));
            copyAsset("jimmy/SYSTEM.md", new File(jimmyDir, "SYSTEM.md"));
            copyAsset("jimmy/config.toml", new File(home, ".codex/config.toml"));
            logconsole("AGENTS.md + SYSTEM.md + config.toml ✔");
            refreshAssetsIfNeeded(); // отметить версию ассетов

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
                addNote("✔ окружение готово — можно писать.");
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

    // при обновлении APK поверх старого: подтягиваем свежие AGENTS.md/config.toml
    // (полный setup не запускается, а они могли измениться в новой версии).
    // Системный промпт НЕ затираем, если пользователь его редактировал:
    // сравниваем с ранее записанной «теневой» копией дефолта.
    private void refreshAssetsIfNeeded() {
        try {
            File marker = new File(filesDir, ".assets_v");
            String cur = "";
            if (marker.exists()) {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(marker)));
                String l = br.readLine();
                br.close();
                if (l != null) cur = l.trim();
            }
            // Технические файлы обновляем БЕЗУСЛОВНО при каждом запуске —
            // копеечный IO, зато старый config.toml невозможен в принципе.
            new File(home, ".codex").mkdirs();
            copyAsset("jimmy/SYSTEM.md", new File(jimmyDir, "SYSTEM.md"));
            copyAsset("jimmy/config.toml", new File(home, ".codex/config.toml"));
            if (!appVersion().equals(cur)) {
                jimmyDir.mkdirs();
                File agents = new File(jimmyDir, "AGENTS.md");
                File shadow = new File(jimmyDir, ".AGENTS.md.default");
                boolean userCustom = false;
                if (agents.exists() && shadow.exists()) {
                    String a = readFileText(agents);
                    String p = readFileText(shadow);
                    userCustom = !a.equals(p);
                }
                if (!userCustom) {
                    copyAsset("jimmy/AGENTS.md", agents);
                    copyAsset("jimmy/AGENTS.md", shadow);
                } // пользовательский промпт — святыня, не трогаем
                FileOutputStream fos = new FileOutputStream(marker);
                fos.write((appVersion() + "\n").getBytes("UTF-8"));
                fos.close();
            }
        } catch (Throwable ignore) { }
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
        // codex выбирает шелл: $SHELL → кандидаты в PATH → хардкод /bin/bash.
        // На Android без SHELL он падает в fallback на несуществующие пути,
        // и КАЖДАЯ команда умирает с Io(NotFound). Указываем наш bash явно.
        env.put("SHELL", usr.getAbsolutePath() + "/bin/bash");
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

    // Одноразовый смок-тест окружения при старте: показываем пузырём,
    // линкуется ли bash при запуске из Java с нашим env и цел ли
    // libandroid-support.so. Без этого мы гадаем вслепую по скринам.
    private void startSmokeOnce() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                File bash = new File(usr, "bin/bash");
                File lib = new File(usr, "lib/libandroid-support.so");
                sb.append("🧪 smoke: bash=").append(bash.exists() ? bash.length() + "B" : "НЕТ")
                  .append(", libandroid-support.so=").append(lib.exists() ? lib.length() + "B" : "НЕТ").append("\n");
                String out = runAndWait(new String[]{ bash.getAbsolutePath(), "-c",
                        "echo SMOKE_OK; echo LDP=<$LD_LIBRARY_PATH>; ls -1 $PREFIX/lib/libandroid* 2>&1 | head -3" },
                        baseEnv(), null);
                sb.append(out.trim());
            } catch (Throwable t) {
                sb.append("FAIL: ").append(String.valueOf(t));
            }
            final String res = sb.toString();
            try {
                FileOutputStream fos = new FileOutputStream(new File(filesDir, "smoke.txt"));
                fos.write(res.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignore) { }
            runOnUiThread(() -> addNote(res));
        }, "smoke").start();
    }

    // =====================================================================
    // ЧАТ (нативный, в стиле ChatGPT)
    // =====================================================================
    private void showChat() {
        chatRoot = new LinearLayout(this);
        chatRoot.setOrientation(LinearLayout.VERTICAL);
        chatRoot.setBackgroundColor(BG);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(10), dp(6), dp(10));
        head.setBackgroundColor(HEAD);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv("⚡ JimmyAgent", 17, TXT, true);
        titles.addView(title);
        modeSub = tv("💬 чат · chatjimmy", 11, DIM2, false);
        titles.addView(modeSub);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        modeBtn = hdrBtn("💬");
        modeBtn.setOnClickListener(v -> toggleMode());
        head.addView(modeBtn);
        TextView promptBtn = hdrBtn("⚙");
        promptBtn.setOnClickListener(v -> showPromptEditor());
        head.addView(promptBtn);
        TextView filesBtn = hdrBtn("📁");
        filesBtn.setOnClickListener(v -> showFiles());
        head.addView(filesBtn);
        TextView newBtn = hdrBtn("✎");
        newBtn.setOnClickListener(v -> newChat());
        head.addView(newBtn);

        chatRoot.addView(head);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        chatRoot.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        listView.setBackgroundColor(BG);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(6), 0, dp(6));
        chatRoot.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // input bar
        View div2 = new View(this);
        div2.setBackgroundColor(LINE);
        chatRoot.addView(div2, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(dp(10), dp(8), dp(10), dp(10));
        bar.setBackgroundColor(PANEL);

        input = new EditText(this);
        input.setHint("Сообщение…");
        input.setHintTextColor(DIM2);
        input.setTextColor(TXT);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(USER_BG);
        pill.setCornerRadius(dp(22));
        pill.setStroke(1, LINE);
        input.setBackground(pill);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setTextSize(15.5f);
        input.setMinLines(1);
        input.setMaxLines(5);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.setMargins(0, 0, dp(8), 0);
        bar.addView(input, ilp);

        sendBtn = new Button(this);
        setSendIdle();
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(AMBER);
        sendBtn.setBackground(circle);
        sendBtn.setTextColor(0xFF1A1A1A);
        sendBtn.setOnClickListener(v -> {
            if (agentBusy) stopAgent(); else onSend();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(42), dp(42));
        bar.addView(sendBtn, slp);
        chatRoot.addView(bar);

        setContentView(chatRoot);
        showingFiles = false;
        if (msgs.isEmpty())
            addNote("💬 режим чата — чистый разговор без codex-промпта (переключатель 💬/🛠 в шапке). "
                    + "ChatJimmy, llama3.1-8B, ~17K tok/s, всё локально.");
    }

    private void setSendIdle() {
        sendBtn.setText("↑");
        sendBtn.setTextSize(19);
        sendBtn.setEnabled(true);
        if (input != null) input.setHint("Сообщение…");
    }

    private void setSendBusy() {
        sendBtn.setText("■");
        sendBtn.setTextSize(14);
        sendBtn.setEnabled(true);
        if (input != null) input.setHint("печатает ответ…");
    }

    private TextView hdrBtn(String s) {
        TextView b = tv(s, 19, DIM, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        return b;
    }

    private void addNote(String t) {
        msgs.add(new Msg(t, 2));
        adapter.notifyDataSetChanged();
    }

    private void toggleMode() {
        if (agentBusy) stopAgent();
        chatMode = !chatMode;
        modeBtn.setText(chatMode ? "💬" : "🛠");
        modeSub.setText(chatMode ? "💬 чат · chatjimmy" : "🛠 агент · codex · chatjimmy");
        addNote(chatMode
                ? "💬 режим чата: чистый разговор напрямую с моделью — без codex-инструкций и инструментов. Системный промпт — только твой (⚙)."
                : "🛠 режим агента: codex с инструментами — пишет файлы, запускает команды в песочнице.");
    }

    private void newChat() {
        if (agentBusy) stopAgent();
        msgs.clear();
        hasSession = false;
        chatHistory.clear();
        stopRequested = false;
        adapter.notifyDataSetChanged();
        addNote("✎ новый диалог. " + (chatMode
                ? "история чата очищена."
                : "файлы в песочнице остались на месте."));
    }

    private void onSend() {
        if (agentBusy) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignore) { }
        msgs.add(new Msg(text, 0));
        startAgent(text);
    }

    private void onRegenerate() {
        if (agentBusy) return;
        String lastUser = null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).who == 0) { lastUser = msgs.get(i).text; break; }
        }
        if (lastUser == null) return;
        startAgent(lastUser);
    }

    private void startAgent(String prompt) {
        final Msg live = new Msg("", 1);
        msgs.add(live);
        adapter.notifyDataSetChanged();
        agentBusy = true;
        stopRequested = false;
        setSendBusy();
        adapter.notifyDataSetChanged();
        if (chatMode) {
            new Thread(() -> runChatJimmy(prompt, live), "chat-run").start();
        } else {
            new Thread(() -> runCodex(prompt, live), "codex-run").start();
        }
    }

    private void stopAgent() {
        stopRequested = true;
        Process p = currentProc;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignore) { }
        }
        java.net.HttpURLConnection c = currentConn;
        if (c != null) {
            try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    // =====================================================================
    // 💬 РЕЖИМ ЧАТА: напрямую с ChatJimmy через прокси — БЕЗ промпта codex
    //    системный промпт = только пользовательский ~/jimmy/AGENTS.md
    // =====================================================================
    private void trimChatHistory() {
        while (chatHistory.size() > 20) chatHistory.remove(0);
    }

    private void runChatJimmy(String userMsg, Msg live) {
        // последний ход пользователя если ещё не добавлен — добавим
        chatHistory.add(new String[]{"user", userMsg});
        trimChatHistory();

        boolean answered = false;
        String err = null;
        final StringBuilder diag = new StringBuilder();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !answered && !stopRequested; attempt++) {
            final StringBuilder acc = new StringBuilder();
            ui.post(() -> {
                live.text = "";
                adapter.notifyDataSetChanged();
            });
            appendLive(live, acc, T_OPEN + "💭 думаю…\n" + T_CLOSE);
            String reply = null;
            try {
                if (attempt > 1) Thread.sleep(700);
                ensureProxyUp();
                reply = chatCompletion(diag);
            } catch (Throwable t) {
                err = String.valueOf(t);
                if (diag.length() < 400)
                    diag.append("исключение: ").append(err.length() > 160 ? err.substring(0, 160) : err).append('\n');
            }
            if (reply != null && !reply.trim().isEmpty()) {
                answered = true;
                final StringBuilder acc2 = new StringBuilder();
                ui.post(() -> {
                    live.text = "";
                    adapter.notifyDataSetChanged();
                });
                renderAgentText(reply, live, acc2);
                chatHistory.add(new String[]{"assistant", reply});
                trimChatHistory();
            }
        }
        agentBusy = false;
        currentConn = null;
        ui.post(() -> {
            setSendIdle();
            adapter.notifyDataSetChanged();
        });
        if (stopRequested) {
            ui.post(() -> {
                live.text = stripMarkers(live.text) + "\n⏹ остановлено";
                adapter.notifyDataSetChanged();
            });
            // отменённый ход пользователя из истории убираем — чтоб redo не дублировал
            if (!chatHistory.isEmpty()) chatHistory.remove(chatHistory.size() - 1);
        } else if (!answered) {
            // пусто — убираем неотвеченный ход пользователя из истории
            if (!chatHistory.isEmpty()) chatHistory.remove(chatHistory.size() - 1);
            String base = err != null
                    ? "💥 ошибка: " + err
                    : "(модель не ответила за " + MAX_ATTEMPTS
                    + " попытки — переформулируй или жми ↻ ещё раз)";
            String d = diag.toString().trim();
            final String msg = d.isEmpty() ? base
                    : base + "\n\n🔍 детали (пришли скриншотом):\n" + d;
            ui.post(() -> {
                live.text = msg;
                adapter.notifyDataSetChanged();
            });
        }
    }

    // один запрос к прокси; вернёт текст ответа или null (детали → diag)
    private String chatCompletion(StringBuilder diag) throws Exception {
        String system;
        File af = new File(jimmyDir, "AGENTS.md");
        system = af.exists() ? readFileText(af) : null;
        if (system == null || system.startsWith("(")) system = readAssetText("jimmy/AGENTS.md");

        JSONObject req = new JSONObject();
        req.put("model", "llama3.1-8B");
        req.put("stream", false);
        JSONArray arr = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", system);
        arr.put(sys);
        synchronized (chatHistory) {
            for (String[] h : chatHistory) {
                JSONObject m = new JSONObject();
                m.put("role", h[0]);
                m.put("content", h[1]);
                arr.put(m);
            }
        }
        req.put("messages", arr);

        byte[] body = req.toString().getBytes("UTF-8");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:4100/v1/chat/completions").openConnection();
        currentConn = c;
        c.setConnectTimeout(8000);
        c.setReadTimeout(120000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.getOutputStream().write(body);
        int code = c.getResponseCode();
        InputStream is = (code == 200) ? c.getInputStream() : c.getErrorStream();
        BufferedReader rd = new BufferedReader(new InputStreamReader(
                is == null ? new java.io.ByteArrayInputStream(new byte[0]) : is));
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = rd.readLine()) != null) sb.append(ln);
        if (code != 200) {
            String b = sb.toString();
            if (diag.length() < 500)
                diag.append("HTTP ").append(code).append(": ")
                        .append(b.length() > 160 ? b.substring(0, 160) : b).append('\n');
            return null;
        }
        JSONObject resp = new JSONObject(sb.toString());
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            diag.append("пустые choices\n");
            return null;
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) {
            diag.append("нет message\n");
            return null;
        }
        return msg.optString("content", "");
    }

    // =====================================================================
    // ЗАПУСК CODEX (пустой ответ 8B — норма: молча пробуем ещё)
    // =====================================================================
    private void runCodex(String userMsg, Msg live) {
        boolean answered = false;
        String err = null;
        final StringBuilder diag = new StringBuilder();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !answered && !stopRequested; attempt++) {
            // каждая попытка рисует пузырь заново — ретраи для юзера незаметны
            final StringBuilder acc = new StringBuilder();
            ui.post(() -> {
                live.text = "";
                adapter.notifyDataSetChanged();
            });
            appendLive(live, acc, T_OPEN + "💭 думаю…\n" + T_CLOSE);

            String prompt = userMsg;
            if (attempt > 1 && hasSession) {
                prompt = "Ответь кратко на предыдущий запрос пользователя, по-русски.";
            }
            StringBuilder agentText = new StringBuilder();
            boolean[] gotThread = new boolean[1];
            try {
                if (attempt > 1) Thread.sleep(700);
                ensureProxyUp();
                runCodexOnce(prompt, live, acc, agentText, gotThread, diag);
            } catch (Throwable t) {
                err = String.valueOf(t);
                if (diag.length() < 400) diag.append("исключение: ").append(err.length() > 160 ? err.substring(0, 160) : err).append('\n');
            }
            if (gotThread[0]) hasSession = true;
            answered = agentText.toString().trim().length() > 0;
        }
        agentBusy = false;
        currentProc = null;
        ui.post(() -> {
            setSendIdle();
            adapter.notifyDataSetChanged();
        });
        if (stopRequested) {
            ui.post(() -> { live.text = stripMarkers(live.text) + "\n⏹ остановлено"; adapter.notifyDataSetChanged(); });
        } else if (!answered) {
            String base = err != null
                    ? "💥 ошибка: " + err
                    : "(модель не ответила за " + MAX_ATTEMPTS
                    + " попытки — переформулируй или жми ↻ ещё раз)";
            String d = diag.toString().trim();
            final String msg = d.isEmpty() ? base
                    : base + "\n\n🔍 детали (пришли мне скриншотом):\n" + d;
            ui.post(() -> {
                live.text = msg;
                adapter.notifyDataSetChanged();
            });
        }
    }

    // ---------- прокси: ждём поднятия, при падении перезапускаем ----------
    private volatile boolean proxyStarting = false;

    private boolean proxyUp() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 4100), 600);
            s.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // вызывается из фонового потока перед запуском codex
    private void ensureProxyUp() throws Exception {
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            if (proxyUp()) return;
            startProxyOnce();       // второй python просто не забиндится и тихо умрёт
            Thread.sleep(700);
        }
        throw new Exception("прокси 127.0.0.1:4100 не поднялся за 12 сек");
    }

    private void startProxyOnce() {
        if (proxyStarting) return;
        proxyStarting = true;
        new Thread(() -> {
            try {
                startProxy();
            } finally {
                proxyStarting = false;
            }
        }, "proxy-starter").start();
    }

    // один заход codex; agentOut накапливает текст ответа, diag — диагностику сбоев
    private int runCodexOnce(String prompt, Msg live, StringBuilder acc,
                             StringBuilder agentOut, boolean[] gotThread, StringBuilder diag) {
        int exit = -1;
        try {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(codexBin.getAbsolutePath());
            cmd.add("exec");
            // ВАЖНО: флаги идут ДО "resume --last" — иначе clap отвергает --json
            // ("unexpected argument") и ответ пустой. Так было сломано с v0.2.
            cmd.add("--json");
            cmd.add("--skip-git-repo-check");
            cmd.add("-s");
            // ВАЖНО: workspace-write на Android-ядре убивает шелл сигналом SIGSYS
            // (seccomp-песочница codex несовместима с Android) — только full-access.
            cmd.add("danger-full-access");
            if (hasSession) {
                cmd.add("resume");
                cmd.add("--last");
            }
            cmd.add(prompt);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            applyEnv(pb, baseEnv());
            pb.directory(jimmyDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            currentProc = p;
            lastEventAt = System.currentTimeMillis();

            // сторожок: нет событий IDLE_TIMEOUT_MS → убиваем попытку
            Thread wd = new Thread(() -> {
                try {
                    while (true) {
                        try { p.exitValue(); break; } catch (IllegalThreadStateException alive) { }
                        if (System.currentTimeMillis() - lastEventAt > IDLE_TIMEOUT_MS) {
                            p.destroy();
                            break;
                        }
                        Thread.sleep(1000);
                    }
                } catch (Throwable ignored) { }
            });
            wd.setDaemon(true);
            wd.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int rawLogged = 0, itemCount = 0;
            while ((line = r.readLine()) != null) {
                lastEventAt = System.currentTimeMillis();
                if (stopRequested) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!line.startsWith("{")) {
                    // не-JSON строки — обычно ошибки clap/рантайма: собираем в диагностику
                    if (rawLogged < 2 && diag.length() < 500) {
                        diag.append("codex: ").append(line.length() > 140 ? line.substring(0, 140) : line).append('\n');
                        rawLogged++;
                    }
                    continue;
                }
                try {
                    JSONObject ev = new JSONObject(line);
                    String type = ev.optString("type", "");
                    if ("thread.started".equals(type)) { gotThread[0] = true; continue; }
                    if ("error".equals(type)) {
                        String em = ev.optString("message", "");
                        if (!em.isEmpty() && diag.length() < 500)
                            diag.append("codex error: ").append(em.length() > 160 ? em.substring(0, 160) : em).append('\n');
                        continue;
                    }
                    if (!"item.completed".equals(type)) continue;
                    itemCount++;
                    JSONObject item = ev.optJSONObject("item");
                    if (item == null) continue;
                    String it = item.optString("type", "");
                    if ("reasoning".equals(it)) {
                        String t = item.optString("text", "").trim();
                        if (!t.isEmpty()) appendLive(live, acc, T_OPEN + "💭 " + oneLine(t, 320) + "\n" + T_CLOSE);
                    } else if ("command_execution".equals(it)) {
                        String c = item.optString("command", "").trim();
                        StringBuilder codeBlock = new StringBuilder();
                        if (!c.isEmpty()) codeBlock.append("⚙ ").append(oneLine(c, 200)).append("\n");
                        String out = item.optString("aggregated_output", "").trim();
                        int ec = item.optInt("exit_code", 0);
                        if (!out.isEmpty()) {
                            codeBlock.append(dim(out, 500));
                            if (ec != 0) codeBlock.append(" [exit ").append(ec).append("]");
                        }
                        if (codeBlock.length() > 0)
                            appendLive(live, acc, C_OPEN + codeBlock + C_CLOSE + "\n");
                    } else if ("agent_message".equals(it)) {
                        String t = item.optString("text", "").trim();
                        if (!t.isEmpty()) {
                            agentOut.append(t);
                            renderAgentText(t, live, acc);
                        }
                    }
                } catch (Throwable ignore) { }
            }
            exit = p.waitFor();
            if (exit != 0 && diag.length() < 500) diag.append("codex exit=").append(exit).append('\n');
            if (itemCount == 0 && exit == 0 && diag.length() < 500) diag.append("codex: 0 items, exit=0 (пустой ответ upstream)\n");
        } catch (Throwable t) {
            appendLive(live, acc, T_OPEN + "💥 ошибка запуска: " + t + T_CLOSE);
            if (diag.length() < 500) diag.append("spawn: ").append(String.valueOf(t)).append('\n');
        } finally {
            currentProc = null;
        }
        return exit;
    }

    // режем ответ модели на мысли (<think>) и обычный текст — раскрашиваем
    private void renderAgentText(String t, Msg live, StringBuilder acc) {
        Matcher m = THINK_RX.matcher(t);
        int last = 0;
        while (m.find()) {
            appendPlain(t.substring(last, m.start()), live, acc);
            String think = m.group(1) == null ? "" : m.group(1).trim();
            if (!think.isEmpty())
                appendLive(live, acc, T_OPEN + "💭 " + think + "\n" + T_CLOSE);
            last = m.end();
        }
        appendPlain(t.substring(last), live, acc);
    }

    private void appendPlain(String s, Msg live, StringBuilder acc) {
        s = s.trim();
        if (!s.isEmpty()) appendLive(live, acc, s + "\n");
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
        ui.post(() -> {
            live.text = acc.toString();
            adapter.notifyDataSetChanged();
        });
    }

    // =====================================================================
    // РАСКРАСКА МАРКЕРОВ (мысли/код) и копирование
    // =====================================================================
    static class RoundBgSpan implements LineBackgroundSpan {
        private final int color;
        RoundBgSpan(int c) { color = c; }
        public void drawBackground(Canvas c, Paint p, int left, int right, int top,
                                   int baseline, int bottom, CharSequence text,
                                   int start, int end, int lineNumber) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setAntiAlias(true);
            c.drawRoundRect(left, top, right, bottom, 14, 14, paint);
        }
    }

    private CharSequence buildSpanned(String raw) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int tOpen = raw.indexOf(T_OPEN, i);
            int cOpen = raw.indexOf(C_OPEN, i);
            int nextOpen;
            boolean isThink;
            if (tOpen < 0 && cOpen < 0) { nextOpen = -1; isThink = false; }
            else if (tOpen < 0) { nextOpen = cOpen; isThink = false; }
            else if (cOpen < 0) { nextOpen = tOpen; isThink = true; }
            else { nextOpen = Math.min(tOpen, cOpen); isThink = tOpen < cOpen; }

            if (nextOpen < 0) {
                out.append(raw.substring(i));
                break;
            }
            if (nextOpen > i) out.append(raw.substring(i, nextOpen));
            String open = isThink ? T_OPEN : C_OPEN;
            String close = isThink ? T_CLOSE : C_CLOSE;
            int contentStart = nextOpen + open.length();
            int closeAt = raw.indexOf(close, contentStart);
            if (closeAt < 0) closeAt = raw.length();
            String seg = raw.substring(contentStart, closeAt);
            int s = out.length();
            out.append(seg);
            int e = out.length();
            if (e > s) {
                if (isThink) {
                    out.setSpan(new ForegroundColorSpan(GRUG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.85f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    out.setSpan(new ForegroundColorSpan(CODE_TXT), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.83f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RoundBgSpan(CODE_BG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            i = closeAt + (closeAt < raw.length() ? close.length() : 0);
        }
        return out;
    }

    private String stripMarkers(String raw) {
        return raw.replace(T_OPEN, "").replace(T_CLOSE, "")
                .replace(C_OPEN, "").replace(C_CLOSE, "");
    }

    private void copyText(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, stripMarkers(text)));
            Toast.makeText(this, "скопировано", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "ошибка копирования: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================================
    // АДАПТЕР ЧАТА
    // =====================================================================
    class ChatAdapter extends BaseAdapter {
        public int getCount() { return msgs.size(); }
        public Object getItem(int i) { return msgs.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View cv, ViewGroup parent) {
            final Msg m = msgs.get(pos);
            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), dp(4), dp(12), dp(4));

            TextView t = new TextView(MainActivity.this);
            t.setTextIsSelectable(true);
            int padH = dp(13), padV = dp(9);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(18));

            if (m.who == 0) {
                // пользователь: серая плашка справа
                t.setText(m.text.isEmpty() ? "…" : m.text);
                t.setTextSize(15.5f);
                t.setTextColor(TXT);
                t.setLineSpacing(2, 1.0f);
                g.setColor(USER_BG);
                t.setPadding(padH, padV, padH, padV);
                t.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(64), 0, 0, 0);
                lp.gravity = Gravity.END;
                col.addView(t, lp);
                col.addView(actionsRow(m, Gravity.END, false));
            } else if (m.who == 2) {
                // системная заметка: по центру, блеклая
                t.setText(m.text);
                t.setTextSize(12.5f);
                t.setTextColor(DIM2);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, dp(2), 0, dp(2));
                col.addView(t);
            } else {
                // агент: во всю ширину, без плашки — как в ChatGPT
                CharSequence sp = m.text.isEmpty() ? "…" : buildSpanned(m.text);
                t.setText(sp);
                t.setTextSize(15.5f);
                t.setTextColor(TXT);
                t.setLineSpacing(3, 1.0f);
                t.setPadding(0, padV, 0, padV);
                col.addView(t);
                boolean lastAndIdle = (pos == msgs.size() - 1) && !agentBusy;
                col.addView(actionsRow(m, Gravity.START, lastAndIdle));
            }
            return col;
        }

        // ряд действий под сообщением: ⧉ копировать · ↻ ещё раз (только последний ответ)
        private View actionsRow(final Msg m, int grav, boolean withRegen) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(grav);
            row.addView(chip("⧉ копировать", v -> copyText("jimmyagent", m.text)));
            if (withRegen) row.addView(chip("↻ ещё раз", v -> onRegenerate()));
            return row;
        }

        private TextView chip(String s, View.OnClickListener l) {
            TextView b = tv(s, 11.5f, DIM2, false);
            b.setPadding(dp(10), dp(3), dp(10), dp(3));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setStroke(1, LINE);
            b.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), dp(6), dp(4));
            b.setLayoutParams(lp);
            b.setOnClickListener(l);
            return b;
        }
    }

    // =====================================================================
    // ПАНЕЛЬ ФАЙЛОВ ПЕСОЧНИЦЫ
    // =====================================================================
    private void showFiles() {
        jimmyDir.mkdirs();
        if (filesCurDir == null || !filesCurDir.exists() || !isInside(filesCurDir, jimmyDir))
            filesCurDir = jimmyDir;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(10), dp(14), dp(10));
        head.setBackgroundColor(HEAD);
        TextView back = hdrBtn("←");
        back.setOnClickListener(v -> backToChat());
        head.addView(back);
        TextView title = tv("📦 песочница (~/jimmy)", 15, TXT, true);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        View div = new View(this);
        div.setBackgroundColor(LINE);
        root.addView(div, new LinearLayout.LayoutParams(-1, 1));

        final TextView pathLabel = tv("", 11.5f, DIM2, false);
        pathLabel.setPadding(dp(14), dp(6), dp(14), dp(6));
        root.addView(pathLabel);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setBackgroundColor(BG);
        lv.setAdapter(filesAdapter);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));

        lv.setOnItemClickListener((parent, view, pos, id) -> {
            File f = fileRows.get(pos);
            if (f == null) { // «..»
                File p = filesCurDir.getParentFile();
                if (p != null && isInside(p, jimmyDir)) filesCurDir = p;
                refillFiles(pathLabel);
            } else if (f.isDirectory()) {
                filesCurDir = f;
                refillFiles(pathLabel);
            } else {
                viewFile(f);
            }
        });

        showingFiles = true;
        setContentView(root);
        refillFiles(pathLabel);
    }

    private void refillFiles(TextView pathLabel) {
        fileRows.clear();
        String rel = jimmyDir.toURI().relativize(filesCurDir.toURI()).getPath();
        pathLabel.setText(rel.isEmpty() ? "~/jimmy/" : "~/jimmy/" + rel);
        if (!filesCurDir.equals(jimmyDir)) fileRows.add(null); // строка «..»
        File[] kids = filesCurDir.listFiles();
        if (kids != null) {
            ArrayList<File> dirs = new ArrayList<>(), files = new ArrayList<>();
            for (File k : kids) (k.isDirectory() ? dirs : files).add(k);
            Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
            Collections.sort(dirs, byName);
            Collections.sort(files, byName);
            fileRows.addAll(dirs);
            fileRows.addAll(files);
        }
        filesAdapter.notifyDataSetChanged();
    }

    private boolean isInside(File f, File root) {
        try {
            return f.getCanonicalPath().startsWith(root.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private void backToChat() {
        showingFiles = false;
        editingPrompt = false;
        setContentView(chatRoot);
    }

    class FilesAdapter extends BaseAdapter {
        public int getCount() { return fileRows.size(); }
        public Object getItem(int i) { return fileRows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View cv, ViewGroup parent) {
            File f = fileRows.get(pos);
            TextView t = tv("", 14.5f, TXT, false);
            t.setPadding(dp(16), dp(11), dp(16), dp(11));
            if (f == null) {
                t.setText("‹ ..");
                t.setTextColor(DIM);
            } else if (f.isDirectory()) {
                t.setText("📁 " + f.getName() + "/");
            } else {
                t.setText("📄 " + f.getName() + "  ·  " + fmtSize(f.length()));
            }
            return t;
        }
    }

    private String fmtSize(long n) {
        if (n < 1024) return n + " Б";
        if (n < 1024 * 1024) return String.format("%.1f КБ", n / 1024.0);
        return String.format("%.1f МБ", n / 1024.0 / 1024.0);
    }

    // просмотр одного файла
    private void viewFile(final File f) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(10), dp(6), dp(10));
        head.setBackgroundColor(HEAD);
        TextView back = hdrBtn("←");
        back.setOnClickListener(v -> showFiles());
        head.addView(back);
        String rel = jimmyDir.toURI().relativize(f.toURI()).getPath();
        TextView title = tv(rel, 12.5f, DIM, false);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = hdrBtn("⧉");
        final String[] contentHolder = new String[1];
        copy.setOnClickListener(v -> {
            if (contentHolder[0] != null) copyText(f.getName(), contentHolder[0]);
        });
        head.addView(copy);
        root.addView(head);

        View div = new View(this);
        div.setBackgroundColor(LINE);
        root.addView(div, new LinearLayout.LayoutParams(-1, 1));

        ScrollView sv = new ScrollView(this);
        TextView body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(12.5f);
        body.setTextColor(TXT);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(12), dp(14), dp(24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        contentHolder[0] = readFileText(f);
        body.setText(contentHolder[0]);
        setContentView(root);
    }

    private String readFileText(File f) {
        final int CAP = 200_000;
        try {
            long len = f.length();
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            while (total < CAP) {
                int n = in.read(buf, 0, Math.min(buf.length, CAP - total));
                if (n < 0) break;
                bos.write(buf, 0, n);
                total += n;
            }
            in.close();
            byte[] data = bos.toByteArray();
            for (int i = 0; i < Math.min(data.length, 4096); i++) {
                if (data[i] == 0) return "(бинарный файл · " + fmtSize(len) + " — не показываю)";
            }
            String s = new String(data, "UTF-8");
            if (len > CAP) s += "\n\n… (показаны первые " + CAP + " символов из " + fmtSize(len) + ")";
            return s;
        } catch (Throwable t) {
            return "(не удалось прочитать: " + t + ")";
        }
    }

    // =====================================================================
    // РЕДАКТОР СИСТЕМНОГО ПРОМПТА (~/jimmy/AGENTS.md)
    // =====================================================================
    private void showPromptEditor() {
        jimmyDir.mkdirs();
        final File agentsFile = new File(jimmyDir, "AGENTS.md");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // шапка
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(10), dp(6), dp(10));
        head.setBackgroundColor(HEAD);
        TextView back = hdrBtn("←");
        back.setOnClickListener(v -> backToChat());
        head.addView(back);
        TextView title = tv("⚙ системный промпт", 15, TXT, true);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView reset = hdrBtn("↺");
        TextView save = hdrBtn("💾");
        head.addView(reset);
        head.addView(save);
        root.addView(head);

        View div = new View(this);
        div.setBackgroundColor(LINE);
        root.addView(div, new LinearLayout.LayoutParams(-1, 1));

        TextView hint = tv("хранится в ~/jimmy/AGENTS.md — Codex читает его перед КАЖДЫМ "
                + "запросом: изменения применяются со следующего сообщения. "
                + "↺ — вернуть стандартный текст (ещё не сохраняет). "
                + "Техническая часть промпта лежит отдельно в SYSTEM.md и обновляется с приложением.", 11.5f, DIM2, false);
        hint.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.addView(hint);

        final EditText editor = new EditText(this);
        editor.setTextColor(TXT);
        editor.setTextSize(12.5f);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setBackgroundColor(BG);
        editor.setPadding(dp(14), dp(8), dp(14), dp(24));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(10);
        editor.setHorizontallyScrolling(false);
        String cur2 = agentsFile.exists() ? readFileText(agentsFile) : readAssetText("jimmy/AGENTS.md");
        editor.setText(cur2);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1f));

        reset.setOnClickListener(v -> {
            editor.setText(readAssetText("jimmy/AGENTS.md"));
            editor.setSelection(0);
            Toast.makeText(this, "загружен стандартный — нажми 💾 чтобы сохранить", Toast.LENGTH_SHORT).show();
        });
        save.setOnClickListener(v -> {
            try {
                writeTextFile(agentsFile, editor.getText().toString());
                Toast.makeText(this, "промпт сохранён — действует со следующего сообщения", Toast.LENGTH_SHORT).show();
                backToChat();
            } catch (Throwable t) {
                Toast.makeText(this, "ошибка сохранения: " + t, Toast.LENGTH_LONG).show();
            }
        });

        editingPrompt = true;
        setContentView(root);
    }

    private String readAssetText(String asset) {
        try {
            InputStream in = getAssets().open(asset);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "(не удалось прочитать asset " + asset + ": " + t + ")";
        }
    }

    private void writeTextFile(File out, String text) throws Exception {
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(text.getBytes("UTF-8"));
        fos.close();
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
