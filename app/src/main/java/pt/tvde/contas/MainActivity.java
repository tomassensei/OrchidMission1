package pt.tvde.contas;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/** O ecrã da app: as contas (Hoje, Oferta, Fecho, Objetivo, Semana) num WebView, com ligação ao leitor. */
public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(0xFF0B1A12);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        getWindow().setStatusBarColor(0xFF0B1F15);
        getWindow().setNavigationBarColor(0xFF0B1F15);
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) { tts.setLanguage(new Locale("pt", "PT")); ttsReady = true; }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onNativeResume&&window.onNativeResume()", null);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    boolean readerEnabled() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        ComponentName me = new ComponentName(this, OfferReaderService.class);
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(on);
        while (sp.hasNext()) {
            ComponentName c = ComponentName.unflattenFromString(sp.next());
            if (me.equals(c)) return true;
        }
        return false;
    }

    /** Nomes técnicos das apps Uber/Bolt instaladas, para confirmar que a leitura procura as apps certas. */
    String appsUberBolt() {
        StringBuilder sb = new StringBuilder("Apps no telemóvel: ");
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String label = String.valueOf(r.loadLabel(pm));
                String l = label.toLowerCase(Locale.ROOT);
                if (l.contains("uber") || l.contains("bolt"))
                    sb.append(label).append(" = ").append(r.activityInfo.packageName).append("; ");
            }
        } catch (Exception e) { sb.append("(não deu para ver)"); }
        return sb.toString();
    }

    /** Funções que a página pode chamar com window.Android.… */
    final class Bridge {
        final Context c = MainActivity.this;

        @JavascriptInterface public void setMinimos(float minKm, float minH, float costKm) {
            Prefs.put(c, "minKm", minKm);
            Prefs.put(c, "minH", minH);
            Prefs.put(c, "costKm", costKm);
        }
        @JavascriptInterface public void setReaderPrefs(boolean card, boolean voice, float secs) {
            Prefs.put(c, "readerCard", card);
            Prefs.put(c, "voice", voice);
            Prefs.put(c, "cardSecs", secs);
        }
        @JavascriptInterface public String lastOffer() { return Prefs.s(c, "lastOffer"); }
        @JavascriptInterface public String lastRaw() { return Prefs.s(c, "lastRaw"); }
        @JavascriptInterface public String diag() {
            String v = "?";
            try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
            return "TVDE Contas " + v + "\n" + appsUberBolt() + "\n" + Prefs.s(c, "diag");
        }
        @JavascriptInterface public void clearDiag() {
            Prefs.get(c).edit().remove("diag").remove("lastRaw").apply();
            OfferReaderService s = OfferReaderService.instance;
            if (s != null) s.clearDiag();
        }
        @JavascriptInterface public boolean testCard() {
            OfferReaderService s = OfferReaderService.instance;
            if (s == null) return false;
            s.showTest();
            return true;
        }
        @JavascriptInterface public boolean readerOn() { return readerEnabled(); }
        @JavascriptInterface public void openAccessibility() {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
        @JavascriptInterface public void openAppInfo() {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
        @JavascriptInterface public void speak(String t) {
            if (ttsReady) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "app");
        }
        @JavascriptInterface public void keepScreen(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }
}
