package com.jimmy.agent;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- поднимаем встроенный Python-стек агента в фоне ---
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        final Python py = Python.getInstance();
        final String filesDir = getFilesDir().getAbsolutePath();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    py.getModule("android_main").callAttr("main", filesDir);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "jimmy-agent").start();

        // --- WebView поверх локального сервера ---
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        setContentView(web);

        // ждём, пока сервер ответит на /api/health, затем открываем UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 120; i++) {
                    try {
                        HttpURLConnection c = (HttpURLConnection)
                                new URL("http://127.0.0.1:8000/api/health").openConnection();
                        c.setConnectTimeout(500);
                        c.setReadTimeout(500);
                        c.getInputStream().close();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                web.loadUrl("http://127.0.0.1:8000/");
                            }
                        });
                        return;
                    } catch (Exception e) {
                        try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.loadData(
                            "<body style='background:#212121;color:#ececec;font-family:sans-serif;padding:24px'>"
                            + "<h2>⚡ JimmyAgent</h2><p>Сервер не поднялся за 60 секунд. "
                            + "Закрой приложение и запусти снова.</p></body>",
                            "text/html", "utf-8");
                    }
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
