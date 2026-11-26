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
    private WebView printing;

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

    static final int REQ_TREE = 11, REQ_OPEN = 12;

    private void js(String fn, String arg) {
        String a = org.json.JSONObject.quote(arg == null ? "" : arg);
        runOnUiThread(() -> web.evaluateJavascript("window." + fn + "&&window." + fn + "(" + a + ")", null));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri u = data.getData();
        if (req == REQ_TREE) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
            Prefs.put(this, "bkTree", u.toString());
            js("onBackupFolder", folderName());
        } else if (req == REQ_OPEN) {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(getContentResolver().openInputStream(u), "UTF-8"))) {
                String l; while ((l = br.readLine()) != null) sb.append(l).append('\n');
            } catch (Exception e) { js("onRestore", ""); return; }
            js("onRestore", sb.toString());
        }
    }

    String folderName() {
        String t = Prefs.s(this, "bkTree");
        if (t.isEmpty()) return "";
        String d = android.net.Uri.decode(t);
        int i = d.lastIndexOf(':');
        return i >= 0 ? d.substring(i + 1) : d;
    }

    /** Escreve a cópia na pasta escolhida e guarda só as 30 mais recentes. */
    String writeBackup(String json) {
        String t = Prefs.s(this, "bkTree");
        if (t.isEmpty()) return "sem pasta";
        try {
            android.net.Uri tree = android.net.Uri.parse(t);
            android.net.Uri dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
            String name = "TVDEContas_copia_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new java.util.Date()) + ".json";
            android.net.Uri f = android.provider.DocumentsContract.createDocument(getContentResolver(), dir, "application/json", name);
            if (f == null) return "não deu para criar o ficheiro";
            try (java.io.OutputStream os = getContentResolver().openOutputStream(f)) { os.write(json.getBytes("UTF-8")); }
            // guarda as últimas 30
            android.net.Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
            java.util.List<String[]> list = new java.util.ArrayList<>();
            try (android.database.Cursor c = getContentResolver().query(kids, new String[]{
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext()) if (c.getString(1).startsWith("TVDEContas_copia_")) list.add(new String[]{c.getString(0), c.getString(1)});
            }
            list.sort((a, b) -> a[1].compareTo(b[1]));
            for (int i = 0; i < list.size() - 30; i++)
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, list.get(i)[0]));
            return "ok";
        } catch (Exception e) { return "erro: " + e.getMessage(); }
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
        @JavascriptInterface public void backupPickFolder() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        }
        @JavascriptInterface public String backupFolder() { return folderName(); }
        @JavascriptInterface public String backupWrite(String json) { return writeBackup(json); }
        @JavascriptInterface public void backupRestore() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_OPEN);
        }
        @JavascriptInterface public void restoreOffers(String json) { Prefs.put(c, "offers", json); }
        /** Lê o QR de uma fatura com o leitor da Google (não precisa de permissão da câmara). */
        @JavascriptInterface public void scanQr() {
            runOnUiThread(() -> {
                try {
                    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(MainActivity.this)
                            .startScan()
                            .addOnSuccessListener(b -> js("onQr", b.getRawValue()))
                            .addOnFailureListener(e -> js("onQr", ""))
                            .addOnCanceledListener(() -> js("onQr", ""));
                } catch (Throwable t) { js("onQr", ""); }
            });
        }
        @JavascriptInterface public void setBubble(boolean on, float size) { Prefs.put(c, "bubble", on); Prefs.put(c, "bubbleSize", size); }
        @JavascriptInterface public void setWorking(boolean on) { Prefs.put(c, "working", on); }
        @JavascriptInterface public void setPrints(boolean on, float days) { Prefs.put(c, "prints", on); Prefs.put(c, "printDays", days); }
        @JavascriptInterface public void setZones(String json) { Prefs.put(c, "zones", json); }
        @JavascriptInterface public String offers() { String v = Prefs.s(c, "offers"); return v.isEmpty() ? "[]" : v; }
        @JavascriptInterface public void setCardPrefs(float secs, float fontScale, String good, String mid, String bad) {
            Prefs.put(c, "cardSecs", secs);
            Prefs.put(c, "fontScale", fontScale);
            Prefs.put(c, "vGood", good);
            Prefs.put(c, "vMid", mid);
            Prefs.put(c, "vBad", bad);
        }
        @JavascriptInterface public void setAirport(String day, float uber, float bolt) {
            Prefs.put(c, "airDay", day);
            Prefs.put(c, "airUber", uber);
            Prefs.put(c, "airBolt", bolt);
        }
        /** Abre o ecrã de imprimir do Android com a página dada (escolher "Guardar como PDF" ou partilhar). */
        @JavascriptInterface public void printHtml(String html, String name) {
            runOnUiThread(() -> {
                WebView pw = new WebView(MainActivity.this);
                pw.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) {
                        android.print.PrintManager pm = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
                        pm.print(name, v.createPrintDocumentAdapter(name),
                                new android.print.PrintAttributes.Builder()
                                        .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build());
                        printing = null;
                    }
                });
                printing = pw;   // mantém a página viva até imprimir
                pw.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            });
        }
        /** Partilha texto (ex.: o mapa do mês em CSV) por WhatsApp, email, Drive… */
        @JavascriptInterface public void shareText(String title, String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, text);
            Intent ch = Intent.createChooser(i, title);
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ch);
        }
        @JavascriptInterface public String lastRaw() { return Prefs.s(c, "lastRaw"); }
        @JavascriptInterface public String diag() {
            String v = "?";
            try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
            String err = Prefs.s(c, "lastError");
            boolean on = readerEnabled(), run = OfferReaderService.instance != null;
            String st = !on ? "DESLIGADO (ligue em Oferta › Ligar a leitura)"
                    : run ? "LIGADO e a correr" : "LIGADO mas PARADO (desligue e volte a ligar)";
            return "TVDE Contas " + v + " · leitor " + st + "\n"
                    + appsUberBolt() + "\n"
                    + (err.isEmpty() ? "" : "ÚLTIMO ERRO: " + err + "\n") + Prefs.s(c, "diag");
        }
        @JavascriptInterface public void clearDiag() {
            Prefs.get(c).edit().remove("diag").remove("lastRaw").remove("lastError").apply();
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
