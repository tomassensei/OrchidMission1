package pt.tvde.contas;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Lê as ofertas da Uber Driver e da Bolt Driver e mostra um cartão por cima
 * com quanto a viagem paga por km e por hora. Só lê: não carrega em nada.
 */
public class OfferReaderService extends AccessibilityService {

    static final String UBER = "com.ubercab.driver";
    static final String BOLT = "ee.mtakso.driver";

    private WindowManager wm;
    private Handler h;
    private TextToSpeech tts;
    private boolean ttsReady;
    private View card;
    private String lastSig = "";
    private long lastShown;
    private long lastScan;
    private final Runnable hide = this::hideCard;
    private final NumberFormat nf = NumberFormat.getNumberInstance(new Locale("pt", "PT"));

    @Override
    protected void onServiceConnected() {
        instance = this;
        connectedAt = System.currentTimeMillis();
        Prefs.put(this, "diag", "Ligado desde " + new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new java.util.Date(connectedAt))
                + " · à espera de sinais da Uber/Bolt\n");
        h = new Handler(Looper.getMainLooper());
        cleanPrints();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("pt", "PT"));
                ttsReady = true;
            }
        });
    }

    static OfferReaderService instance;
    private String pendingPkg;
    private AccessibilityNodeInfo lastSource;
    private String eventText = "";
    private long eventTextAt;
    private int retries;
    private long lastStateAt, lastShotAt;
    private int sameCount;
    private String prevText = "";
    private boolean shotBusy;
    private android.graphics.Rect offerBounds;   // janela da oferta flutuante (imagem), se houver
    private long hiddenAt;                         // última vez que a Uber mostrou um ecrã sem texto
    private int ocrMisses;
    private String ocrPlat = "Uber";
    private final Runnable reOcr = () -> { if (SystemClock.uptimeMillis() - hiddenAt < 30_000 && ocrMisses < 3) ocrScreen(ocrPlat); };
    private com.google.mlkit.vision.text.TextRecognizer ocr;
    private final java.util.ArrayDeque<String> offerDiag = new java.util.ArrayDeque<>();
    private final Runnable scan = this::scanNow;
    private int evUber, evBolt;
    private long lastEventAt, connectedAt;
    private final java.util.ArrayDeque<String> diag = new java.util.ArrayDeque<>();
    private String lastDiagText = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try { onEvent(e); } catch (Throwable t) { logError("evento", t); }
    }

    private void onEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (UBER.equals(pkg)) evUber++;
        else if (BOLT.equals(pkg)) evBolt++;
        else return;
        lastEventAt = System.currentTimeMillis();
        pendingPkg = pkg;
        bubbleTick();
        try { AccessibilityNodeInfo src = e.getSource(); if (src != null) lastSource = src; } catch (Exception ignored) { }
        // o próprio sinal traz texto quando abre uma janela nova (ex.: a oferta flutuante)
        StringBuilder et = new StringBuilder();
        for (CharSequence c : e.getText()) if (c != null && c.length() > 0) et.append(c).append(" | ");
        if (e.getContentDescription() != null) et.append(e.getContentDescription()).append(" | ");
        if (et.length() > 0) { eventText = et.toString(); eventTextAt = SystemClock.uptimeMillis(); }
        retries = 0;
        long now = SystemClock.uptimeMillis();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { lastStateAt = now; sameCount = 0; }
        h.removeCallbacks(scan);
        // lê 250 ms depois do último movimento no ecrã; se o ecrã não parar de mexer, lê no máximo 1 vez por segundo.
        // Se o texto não muda (ex.: só o mapa a mexer), passa a ler de 3 em 3 segundos para poupar bateria.
        long gap = sameCount > 3 ? 3000 : 1000;
        if (now - lastScan > gap) scanNow();
        else h.postDelayed(scan, Math.max(250, gap - (now - lastScan)));
    }

    private void scanNow() {
        try { scanNowInner(); } catch (Throwable t) { logError("leitura", t); }
    }

    private void scanNowInner() {
        String pkg = pendingPkg;
        if (pkg == null) return;
        lastScan = SystemClock.uptimeMillis();

        StringBuilder sb = new StringBuilder();
        int nWin = 0;
        java.util.List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
        String rootInfo = "";
        android.graphics.Rect imgBounds = null;
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p != null && pkg.contentEquals(p)) {
                    collect(r, sb, 0); nWin++; roots.add(r);
                    rootInfo = r.getClassName() + "/" + r.getChildCount() + " filhos";
                    if (r.getChildCount() == 0) {          // a oferta da Uber desenhada como imagem
                        android.graphics.Rect b = new android.graphics.Rect();
                        w.getBoundsInScreen(b);
                        if (!b.isEmpty()) imgBounds = b;
                    }
                }
            }
        } catch (Exception ignored) { }
        // A oferta flutuante (Uber em segundo plano) pode não vir na lista de janelas:
        // então sobe a partir do elemento que mandou o sinal até ao topo e lê esse cartão.
        // Só aceita elementos da Uber/Bolt — nunca lê outras apps.
        String how = "janelas " + nWin;
        if (lastSource != null) {
            try {
                AccessibilityNodeInfo top = lastSource;
                for (int i = 0; i < 60; i++) {
                    AccessibilityNodeInfo up = top.getParent();
                    if (up == null) break;
                    top = up;
                }
                // lê este cartão se ainda não foi lido com as janelas (ex.: oferta flutuante por cima de outra app)
                if (top.getPackageName() != null && pkg.contentEquals(top.getPackageName()) && !roots.contains(top)) {
                    StringBuilder card = new StringBuilder();
                    collect(top, card, 0);
                    if (card.length() > 0) {
                        sb.insert(0, card);
                        how = nWin > 0 ? how + " + cartão flutuante" : "cartão flutuante";
                    }
                }
            } catch (Exception ignored) { }
        }
        if (sb.length() == 0 && !eventText.isEmpty() && SystemClock.uptimeMillis() - eventTextAt < 3000) {
            sb.append(eventText);
            how = "texto do sinal";
        }
        // ainda vazio: a janela pode estar a abrir — tenta outra vez daqui a pouco (até 4 vezes)
        if (sb.length() == 0 && retries < 4) {
            retries++;
            h.postDelayed(scan, 300L * retries);
            return;
        }
        String text = sb.toString();
        String plat = UBER.equals(pkg) ? "Uber" : "Bolt";
        if (text.equals(prevText)) sameCount++; else { sameCount = 0; prevText = text; }
        if (text.isEmpty() && !rootInfo.isEmpty()) how += " (" + rootInfo + ")";
        OfferParser.Offer o = handleText(plat, how, text);
        // A Uber esconde o texto do cartão da oferta: lê a imagem do ecrã quando
        // a leitura vem vazia, ou quando uma janela acabou de abrir e não se reconheceu oferta.
        boolean justOpened = SystemClock.uptimeMillis() - lastStateAt < 2500;
        if (text.isEmpty()) { hiddenAt = SystemClock.uptimeMillis(); ocrMisses = 0; offerBounds = imgBounds; }
        if (o == null && (text.isEmpty() || justOpened)) ocrScreen(plat);
    }

    /** Reconhece a oferta num texto (lido do ecrã ou da imagem), regista no diagnóstico e mostra o cartão. */
    private OfferParser.Offer handleText(String plat, String how, String text) {
        OfferParser.Offer o = text.isEmpty() ? null : OfferParser.parse(text, plat);
        recordDiag(plat, how, text, o);
        if (o == null) return null;

        Prefs.put(this, "lastRaw", plat + " · " + new java.util.Date() + "\n" + redact(text));

        String sig = o.signature();
        long t = System.currentTimeMillis();
        if (sig.equals(lastSig) && t - lastShown < 45_000) return o;  // mesma oferta, já mostrada
        lastSig = sig;
        lastShown = t;
        Prefs.put(this, "lastOffer", o.toJson(t));
        // histórico para o ecrã Viagens (últimas 300)
        try {
            org.json.JSONArray arr = new org.json.JSONArray(Prefs.s(this, "offers").isEmpty() ? "[]" : Prefs.s(this, "offers"));
            arr.put(new org.json.JSONObject(o.toJson(t)));
            while (arr.length() > 300) arr.remove(0);
            Prefs.put(this, "offers", arr.toString());
        } catch (Exception ex) { logError("histórico", ex); }
        if (Prefs.b(this, "readerCard", true)) {
            final String mySig = sig;
            org.json.JSONArray zs = Zones.load(this);
            Zones.Hit zr = Zones.byRules(zs, o.pickupAddr), zd = Zones.byRules(zs, o.destAddr);
            showCard(o, zr, zd, true);
            // zonas desenhadas: procura a morada no mapa (internet) e atualiza o cartão
            if (Zones.hasPolygons(zs) && (!o.pickupAddr.isEmpty() || !o.destAddr.isEmpty())) {
                bg.execute(() -> {
                    Zones.Hit pr = Zones.byPolygons(zs, Zones.geocode(o.pickupAddr));
                    Zones.Hit pd = Zones.byPolygons(zs, Zones.geocode(o.destAddr));
                    Zones.Hit fr = worse(zr, pr), fd = worse(zd, pd);
                    if (fr != zr || fd != zd) h.post(() -> { if (mySig.equals(lastSig) && card != null) showCard(o, fr, fd, false); });
                });
            }
        }
        if (Prefs.b(this, "prints", false)) h.postDelayed(this::savePrint, 700);
        return o;
    }

    private final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static int rank(Zones.Hit z) { return z == null ? 0 : "p".equals(z.level) ? 3 : "a".equals(z.level) ? 2 : "b".equals(z.level) ? 1 : 0; }
    private static Zones.Hit worse(Zones.Hit a, Zones.Hit b) { return rank(b) > rank(a) ? b : a; }

    /** Guarda um print da oferta em Fotos › TVDEContas (opção da Config). */
    private void savePrint() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                        android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        android.graphics.Bitmap bmp = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                        hw.recycle(); hb.close();
                        bg.execute(() -> {
                            try {
                                android.content.ContentValues cv = new android.content.ContentValues();
                                cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "oferta_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()) + ".jpg");
                                cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                                cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVDEContas");
                                android.net.Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                                if (u != null) try (java.io.OutputStream os = getContentResolver().openOutputStream(u)) {
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, os);
                                }
                            } catch (Throwable t) { logError("guardar print", t); }
                            bmp.recycle();
                        });
                    } catch (Throwable t) { logError("print", t); }
                }
                @Override public void onFailure(int code) { }
            });
        } catch (Throwable t) { logError("print", t); }
    }

    /** Apaga os prints mais antigos do que os dias escolhidos (0 = nunca). */
    private void cleanPrints() {
        int days = (int) Prefs.f(this, "printDays", 15f);
        if (days <= 0 || android.os.Build.VERSION.SDK_INT < 30) return;
        bg.execute(() -> {
            try {
                long lim = System.currentTimeMillis() / 1000 - days * 86400L;
                getContentResolver().delete(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND " + android.provider.MediaStore.Images.Media.DATE_ADDED + " < ?",
                        new String[]{"Pictures/TVDEContas%", String.valueOf(lim)});
            } catch (Throwable t) { logError("apagar prints", t); }
        });
    }

    /** Tira uma imagem do ecrã (só na memória, nunca é guardada) e lê o texto dela no próprio telemóvel. */
    private void ocrScreen(String plat) {
        if (android.os.Build.VERSION.SDK_INT < 30 || shotBusy) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastShotAt < 1500) return;
        lastShotAt = now;
        shotBusy = true;
        ocrPlat = plat;
        h.removeCallbacks(reOcr);
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    android.graphics.Bitmap bmp = null;
                    try {
                        android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                        android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) { bmp = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false); hw.recycle(); }
                        hb.close();
                    } catch (Throwable ex) { bmp = null; logError("imagem do ecrã", ex); }
                    if (bmp == null) { shotBusy = false; return; }
                    // lê só a zona da oferta e nunca o nosso próprio cartão
                    try {
                        android.graphics.Rect c = new android.graphics.Rect(0, 0, bmp.getWidth(), bmp.getHeight());
                        if (offerBounds != null && !c.intersect(offerBounds)) c.set(0, 0, bmp.getWidth(), bmp.getHeight());
                        if (card != null) {
                            int[] loc = new int[2];
                            card.getLocationOnScreen(loc);
                            int cardBottom = loc[1] + card.getHeight();
                            if (cardBottom > c.top && cardBottom < c.bottom - 150) c.top = cardBottom;
                        }
                        if (c.width() > 50 && c.height() > 50 && (c.width() != bmp.getWidth() || c.height() != bmp.getHeight())) {
                            android.graphics.Bitmap cut = android.graphics.Bitmap.createBitmap(bmp, c.left, c.top, c.width(), c.height());
                            bmp.recycle();
                            bmp = cut;
                        }
                    } catch (Throwable t) { logError("cortar imagem", t); }
                    if (ocr == null) ocr = com.google.mlkit.vision.text.TextRecognition.getClient(
                            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
                    final android.graphics.Bitmap img = bmp;
                    try {
                    ocr.process(com.google.mlkit.vision.common.InputImage.fromBitmap(img, 0))
                            .addOnSuccessListener(res -> {
                                StringBuilder sb = new StringBuilder();
                                for (com.google.mlkit.vision.text.Text.TextBlock b : res.getTextBlocks())
                                    for (com.google.mlkit.vision.text.Text.Line l : b.getLines())
                                        sb.append(l.getText()).append(" | ");
                                img.recycle();
                                shotBusy = false;
                                OfferParser.Offer got = null;
                                try { got = handleText(plat, "leitura da imagem", sb.toString()); }
                                catch (Throwable t) { logError("imagem", t); }
                                // a Uber passa à oferta seguinte sem avisar: enquanto houver oferta
                                // em imagem no ecrã, volta a ler de 2,5 em 2,5 s (pára após 3 leituras sem oferta ou 30 s)
                                if (got == null) ocrMisses++; else ocrMisses = 0;
                                h.removeCallbacks(reOcr);
                                h.postDelayed(reOcr, 2500);
                            })
                            .addOnFailureListener(ex -> { img.recycle(); shotBusy = false; logError("reconhecer texto", ex); });
                    } catch (Throwable t) { shotBusy = false; logError("reconhecer texto", t); }
                }
                @Override public void onFailure(int code) {
                    shotBusy = false;
                    recordDiag(plat, "imagem do ecrã recusada (código " + code + ")", "", null);
                }
            });
        } catch (Throwable ex) { shotBusy = false; logError("imagem do ecrã", ex); }
    }

    /** Regista o último erro para aparecer no diagnóstico, em vez de o leitor parar. */
    void logError(String where, Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String st = sw.toString();
            if (st.length() > 1200) st = st.substring(0, 1200);
            String hhmm = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new java.util.Date());
            Prefs.put(this, "lastError", hhmm + " · " + where + "\n" + st);
        } catch (Throwable ignored) { }
    }

    /** Tira do texto o nome de passageiro que a Bolt mostra a seguir à percentagem (ex.: "84% | Ivone |"). */
    static String redact(String s) {
        return s.replaceAll("(\\d{1,3}\\s?% \\| )\\p{Lu}[\\p{L}'’.-]{1,24}(?: \\p{Lu}[\\p{L}'’.-]{0,24})?( \\| )", "$1[nome]$2");
    }

    /** Apaga o diagnóstico (botão na app). */
    void clearDiag() {
        diag.clear();
        offerDiag.clear();
        lastDiagText = "";
    }

    /** Guarda os últimos ecrãs lidos (só os que têm números) para perceber porque uma oferta não foi lida. */
    private void recordDiag(String plat, String how, String text, OfferParser.Offer o) {
        String hhmm = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date());
        String head = "Ligado desde " + new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new java.util.Date(connectedAt))
                + " · eventos Uber " + evUber + " · Bolt " + evBolt
                + " · Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        String clean = redact(text);
        String shortTxt = clean.length() > 3000 ? clean.substring(0, 3000) + "…" : clean;
        boolean hasDigit = shortTxt.matches("(?s).*\\d.*");
        if ((hasDigit || text.isEmpty()) && !shortTxt.equals(lastDiagText)) {
            lastDiagText = shortTxt;
            diag.addFirst("[" + hhmm + "] " + plat + " · " + how + " · "
                    + (text.isEmpty() ? "SEM TEXTO (a app não deixa ler este ecrã?)"
                       : o != null ? "OFERTA LIDA " + nf.format(o.value) + " €" : "não parece oferta")
                    + "\n" + shortTxt);
            while (diag.size() > 8) diag.removeLast();
        }
        boolean looksOffer = text.contains("€") && text.toLowerCase(Locale.ROOT).contains("km");
        if (looksOffer && (offerDiag.isEmpty() || !offerDiag.peekFirst().endsWith(shortTxt))) {
            offerDiag.addFirst("[" + hhmm + "] " + plat + " · " + how + " · "
                    + (o != null ? "LIDA " + nf.format(o.value) + " € · " + nf.format(o.perKm()) + " €/km · " + nf.format(o.perHour()) + " €/h" : "NÃO RECONHECIDA")
                    + "\n" + shortTxt);
            while (offerDiag.size() > 6) offerDiag.removeLast();
        }
        StringBuilder all = new StringBuilder(head).append("\n\n");
        if (!offerDiag.isEmpty()) {
            all.append("=== OFERTAS (últimas ").append(offerDiag.size()).append(") ===\n\n");
            for (String d : offerDiag) all.append(d).append("\n\n");
            all.append("=== OUTROS ECRÃS ===\n\n");
        }
        for (String d : diag) all.append(d).append("\n\n");
        Prefs.put(this, "diag", all.toString());
    }

    /** Mostra um cartão de exemplo, para confirmar que o cartão consegue aparecer por cima. */
    void showTest() {
        OfferParser.Offer o = OfferParser.parse("Teste | 9,80 € | 6 min (2,5 km) | 18 min (7,2 km)", "Teste");
        if (o != null) showCard(o, null, null, true);
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 90 || sb.length() > 9000) return;
        CharSequence tx = n.getText();
        CharSequence cd = n.getContentDescription();
        String t = tx != null ? tx.toString().trim() : "";
        String d = cd != null ? cd.toString().trim() : "";
        if (!t.isEmpty()) sb.append(t).append(" | ");
        if (!d.isEmpty() && !d.equals(t)) sb.append(d).append(" | ");
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            CharSequence st = n.getStateDescription();
            if (st != null && st.length() > 0) sb.append(st).append(" | ");
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) collect(c, sb, depth + 1);
        }
    }

    private String eu(double v) { return nf.format(v) + " €"; }

    private void showCard(OfferParser.Offer o, Zones.Hit zr, Zones.Hit zd, boolean speak) {
        h.post(() -> {
            hideCard();
            float mk = Prefs.f(this, "minKm", 0f);
            float mh = Prefs.f(this, "minH", 0f);
            float ck = Prefs.f(this, "costKm", mk);
            boolean hasMin = mk > 0 && mh > 0;
            double ek = o.perKm(), eh = o.perHour(), lucro = o.value - o.totalKm() * ck;
            boolean gk = ek >= mk, gh = eh >= mh;

            int green = Color.parseColor("#4ADE80"), red = Color.parseColor("#FF7A7A"), white = Color.WHITE;

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(12);
            box.setPadding(p, dp(10), p, dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#F00B1F15"));
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(2), hasMin ? (gk && gh ? green : (gk || gh ? Color.parseColor("#FACC15") : red)) : Color.GRAY);
            box.setBackground(bg);

            if (o.airport) {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
                int n = today.equals(Prefs.s(this, "airDay")) ? (int) Prefs.f(this, "air" + o.platform, 0f) : 0;
                box.addView(banner(n >= 2 ? "✈ " + (n + 1) + ".ª VIAGEM DO AEROPORTO HOJE · TEM DE PAGAR O ACESSO"
                        : "✈ Aeroporto · já fez " + n + " hoje na " + o.platform + " (2 grátis)", "#F59E0B", Color.BLACK));
            }
            box.addView(line(o.platform.toUpperCase() + " · " + eu(o.value), 13, Color.parseColor("#B7D7C3"), false));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(cell(nf.format(ek) + " €/km", hasMin ? (gk ? green : red) : white), weight());
            row.addView(cell(nf.format(eh) + " €/h", hasMin ? (gh ? green : red) : white), weight());
            box.addView(row);

            String det = (o.pickupKm > 0 || o.pickupMin > 0
                    ? "Recolha " + fmt0(o.pickupMin) + " min · " + fmt1(o.pickupKm) + " km   " : "")
                    + "Viagem " + fmt0(o.tripMin) + " min · " + fmt1(o.tripKm) + " km";
            box.addView(line(det, 12, Color.parseColor("#B7D7C3"), false));
            if (ck > 0) box.addView(line("Lucro " + (lucro >= 0 ? "+" : "−") + eu(Math.abs(lucro)), 16, lucro >= 0 ? green : red, true));
            if (!hasMin) box.addView(line("Abra a TVDE Contas uma vez para carregar os seus mínimos.", 12, white, false));
            if (o.stops > 0) box.addView(banner("⚠ Atenção: viagem com " + o.stops + (o.stops == 1 ? " paragem" : " paragens"), "#F05252", Color.WHITE));
            if (!o.pickupAddr.isEmpty()) box.addView(zoneLine("RECOLHA", o.pickupAddr, zr));
            if (!o.destAddr.isEmpty()) box.addView(zoneLine("DESTINO", o.destAddr, zd));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.y = dp(28);
            lp.x = 0;
            LinearLayout wrap = new LinearLayout(this);
            wrap.setPadding(dp(10), 0, dp(10), 0);
            wrap.addView(box, new LinearLayout.LayoutParams(-1, -2));
            wrap.setOnClickListener(v -> hideCard());   // um toque fecha
            try {
                wm.addView(wrap, lp);
                card = wrap;
            } catch (Exception ignored) { return; }

            int secs = (int) Prefs.f(this, "cardSecs", 15f);
            if (secs > 0) h.postDelayed(hide, secs * 1000L);   // 0 = até eu fechar

            boolean danger = (zr != null && "p".equals(zr.level)) || (zd != null && "p".equals(zd.level));
            if (danger) vibrate();
            if (!speak) {
                if (danger && Prefs.b(this, "voice", true) && ttsReady) tts.speak("Atenção, zona perigosa", TextToSpeech.QUEUE_FLUSH, null, "zone");
                return;
            }
            if (hasMin) {
                if (!(gk && gh)) vibrate();
                if (Prefs.b(this, "voice", true) && ttsReady) {
                    String phrase = gk && gh ? Prefs.s(this, "vGood") : (gk || gh ? Prefs.s(this, "vMid") : Prefs.s(this, "vBad"));
                    if (phrase.isEmpty()) phrase = gk && gh ? "Viagem boa" : (gk || gh ? "Analisar" : "Recusar");
                    if (o.stops > 0) phrase += ". Atenção, tem paragem";
                    if (danger) phrase += ". Atenção, zona perigosa";
                    tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "offer");
                }
            }
        });
    }

    private TextView zoneLine(String what, String addr, Zones.Hit z) {
        String lvl = z == null ? "" : "p".equals(z.level) ? "  🔴 " : "a".equals(z.level) ? "  🟠 " : "  🟢 ";
        TextView t = line(what + " · " + addr + (z == null ? "" : lvl + z.name), 12,
                z == null ? Color.parseColor("#B7D7C3") : "p".equals(z.level) ? Color.parseColor("#FF7A7A")
                        : "a".equals(z.level) ? Color.parseColor("#FBBF24") : Color.parseColor("#4ADE80"), z != null);
        t.setMaxLines(2);
        return t;
    }

    /* ---------- botão flutuante "TVDE" (aparece com a Uber/Bolt abertas, durante o dia de trabalho) ---------- */
    private View bubble;
    private final Runnable hideBubble = this::removeBubble;

    void bubbleTick() {
        if (!Prefs.b(this, "bubble", false) || !Prefs.b(this, "working", false)) { removeBubble(); return; }
        if (bubble == null) addBubble();
        h.removeCallbacks(hideBubble);
        h.postDelayed(hideBubble, 20_000);   // some se a Uber/Bolt deixarem de dar sinal
    }

    private void addBubble() {
        try {
            int size = dp((int) Prefs.f(this, "bubbleSize", 56f));
            TextView b = new TextView(this);
            b.setText("TVDE");
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.24f);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.parseColor("#10B981"));
            g.setStroke(dp(3), Color.parseColor("#0B1F15"));
            b.setBackground(g);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = (int) Prefs.f(this, "bubbleX", 0f);
            lp.y = (int) Prefs.f(this, "bubbleY", dp(300));
            final float[] st = new float[4];
            final boolean[] moved = {false};
            b.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        st[0] = ev.getRawX(); st[1] = ev.getRawY(); st[2] = lp.x; st[3] = lp.y; moved[0] = false; return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = ev.getRawX() - st[0], dy = ev.getRawY() - st[1];
                        if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved[0] = true;
                        lp.x = (int) (st[2] + dx); lp.y = (int) (st[3] + dy);
                        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (!moved[0]) {
                            android.content.Intent i = new android.content.Intent(this, MainActivity.class);
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(i);
                        } else { Prefs.put(this, "bubbleX", (float) lp.x); Prefs.put(this, "bubbleY", (float) lp.y); }
                        return true;
                }
                return false;
            });
            wm.addView(b, lp);
            bubble = b;
        } catch (Throwable t) { logError("botão flutuante", t); }
    }

    private void removeBubble() {
        h.removeCallbacks(hideBubble);
        if (bubble != null) { try { wm.removeView(bubble); } catch (Exception ignored) { } bubble = null; }
    }

    private void hideCard() {
        h.removeCallbacks(hide);
        if (card != null) {
            try { wm.removeView(card); } catch (Exception ignored) { }
            card = null;
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    private String fmt0(double v) { return String.valueOf(Math.round(v)); }
    private String fmt1(double v) { return String.format(new Locale("pt", "PT"), "%.1f", v); }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private TextView line(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Prefs.f(this, "fontScale", 1f));
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private TextView cell(String s, int color) {
        TextView t = line(s, 26, color, true);
        t.setGravity(Gravity.START);
        return t;
    }

    private TextView banner(String s, String bgHex, int fg) {
        TextView t = line(s, 13, fg, true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(bgHex));
        g.setCornerRadius(dp(8));
        t.setBackground(g);
        t.setPadding(dp(8), dp(5), dp(8), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        t.setLayoutParams(lp);
        return t;
    }

    @Override
    public void onInterrupt() { hideCard(); }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacks(scan);
        removeBubble();
        hideCard();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
