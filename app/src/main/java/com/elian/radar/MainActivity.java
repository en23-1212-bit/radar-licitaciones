package com.elian.radar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.InputStream;

public class MainActivity extends Activity {
    static final int REQ_NOTIF = 1;
    static final int REQ_IMPORTAR = 2;

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF14213D);
        Notifier.canales(this);
        Programador.asegurar(this);

        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String s = u.getScheme();
                if ("http".equals(s) || "https".equals(s)) {
                    abrirFuera(u.toString());
                    return true;
                }
                return false;
            }
        });
        web.addJavascriptInterface(new Bridge(this), "Android");
        String pantalla = getIntent().getStringExtra("pantalla");
        web.loadUrl("file:///android_asset/www/index.html" + (pantalla != null ? "#" + pantalla : ""));

        Engine.listener = () -> runOnUiThread(() -> js("window.onRunDone && onRunDone()"));

        // Autovigilancia al abrir: si hay atraso, revisa ya.
        long lastOk = Store.obj(this, "health").optLong("lastOk", 0);
        if (System.currentTimeMillis() - lastOk > 9 * Const.H) {
            new Thread(() -> Engine.correr(getApplicationContext(), "al abrir"), "radar-abrir").start();
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String pantalla = intent.getStringExtra("pantalla");
        if (pantalla != null) js("window.go && go(" + JSONObject.quote(pantalla) + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Programador.asegurar(this);
        js("window.onResume && onResume()");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        js("window.onResume && onResume()");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.atras ? atras() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORTAR || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            String msg;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                JSONObject copia = new JSONObject(Http.readAll(in));
                if (!"radar-licitaciones".equals(copia.optString("app"))) throw new Exception("no es una copia del Radar");
                Store.importar(getApplicationContext(), copia);
                msg = "Copia restaurada";
            } catch (Exception e) {
                msg = "No se pudo restaurar: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> js("toast(" + JSONObject.quote(m) + "); window.onResume && onResume()"));
        }, "radar-importar").start();
    }

    void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    void abrirFuera(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        Engine.listener = null;
        super.onDestroy();
    }
}
