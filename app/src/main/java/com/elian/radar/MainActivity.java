package com.elian.radar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
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
        Notifier.canales(this);
        Programador.asegurar(this);

        // Pantalla de borde a borde con márgenes propios: así la barra de abajo nunca queda
        // debajo de los botones de Android/Samsung ni la de arriba debajo de la hora.
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(0xFFFFFFFF);
        final View arriba = new View(this);
        arriba.setBackgroundColor(0xFF14213D);
        final View abajo = new View(this);
        abajo.setBackgroundColor(0xFFFFFFFF);
        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFFF2F3F6);
        raiz.addView(arriba, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0));
        raiz.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        raiz.addView(abajo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0));
        setContentView(raiz);
        bordeABorde();
        raiz.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = b.top; bottom = Math.max(b.bottom, ime.bottom); left = b.left; right = b.right;
            } else {
                top = legacyTop(insets); bottom = legacyBottom(insets); left = legacyLeft(insets); right = legacyRight(insets);
            }
            arriba.getLayoutParams().height = top;
            abajo.getLayoutParams().height = bottom;
            arriba.requestLayout();
            abajo.requestLayout();
            v.setPadding(left, 0, right, 0);
            return insets;
        });
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
        // Buscar versión nueva al abrir (como máximo cada 6 horas)
        new Thread(() -> {
            Updater.revisarSiToca(getApplicationContext(), false);
            runOnUiThread(() -> js("window.onUpdateInfo && onUpdateInfo()"));
        }, "radar-version").start();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACCION_INSTALAR.equals(intent.getAction())) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmar = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : legacyIntent(intent);
                if (confirmar != null) {
                    confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirmar);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS && status != -999) {
                js("window.toast && toast(" + JSONObject.quote("No se instaló la actualización. Intenta de nuevo.") + ")");
            }
            return;
        }
        String pantalla = intent.getStringExtra("pantalla");
        if (pantalla != null) js("window.go && go(" + JSONObject.quote(pantalla) + ")");
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyIntent(Intent i) {
        return i.getParcelableExtra(Intent.EXTRA_INTENT);
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

    private void bordeABorde() {
        android.view.Window w = getWindow();
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS | WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        } else {
            legacyFlags(w);
        }
    }

    @SuppressWarnings("deprecation")
    private static void legacyFlags(android.view.Window w) {
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    @SuppressWarnings("deprecation") private static int legacyTop(WindowInsets i) { return i.getSystemWindowInsetTop(); }
    @SuppressWarnings("deprecation") private static int legacyBottom(WindowInsets i) { return i.getSystemWindowInsetBottom(); }
    @SuppressWarnings("deprecation") private static int legacyLeft(WindowInsets i) { return i.getSystemWindowInsetLeft(); }
    @SuppressWarnings("deprecation") private static int legacyRight(WindowInsets i) { return i.getSystemWindowInsetRight(); }

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
