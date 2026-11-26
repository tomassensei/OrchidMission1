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
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        h = new Handler(Looper.getMainLooper());
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
    private com.google.mlkit.vision.text.TextRecognizer ocr;
    private final java.util.ArrayDeque<String> offerDiag = new java.util.ArrayDeque<>();
    private final Runnable scan = this::scanNow;
    private int evUber, evBolt;
    private long lastEventAt, connectedAt;
    private final java.util.ArrayDeque<String> diag = new java.util.ArrayDeque<>();
    private String lastDiagText = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (UBER.equals(pkg)) evUber++;
        else if (BOLT.equals(pkg)) evBolt++;
        else return;
        lastEventAt = System.currentTimeMillis();
        pendingPkg = pkg;
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
        String pkg = pendingPkg;
        if (pkg == null) return;
        lastScan = SystemClock.uptimeMillis();

        StringBuilder sb = new StringBuilder();
        int nWin = 0;
        java.util.List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
        String rootInfo = "";
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p != null && pkg.contentEquals(p)) {
                    collect(r, sb, 0); nWin++; roots.add(r);
                    rootInfo = r.getClassName() + "/" + r.getChildCount() + " filhos";
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
        if (Prefs.b(this, "readerCard", true)) showCard(o);
        return o;
    }

    /** Tira uma imagem do ecrã (só na memória, nunca é guardada) e lê o texto dela no próprio telemóvel. */
    private void ocrScreen(String plat) {
        if (android.os.Build.VERSION.SDK_INT < 30 || shotBusy || card != null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastShotAt < 1500) return;
        lastShotAt = now;
        shotBusy = true;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    android.graphics.Bitmap bmp = null;
                    try {
                        android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                        android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) { bmp = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false); hw.recycle(); }
                        hb.close();
                    } catch (Exception ex) { bmp = null; }
                    if (bmp == null) { shotBusy = false; return; }
                    if (ocr == null) ocr = com.google.mlkit.vision.text.TextRecognition.getClient(
                            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
                    final android.graphics.Bitmap img = bmp;
                    ocr.process(com.google.mlkit.vision.common.InputImage.fromBitmap(img, 0))
                            .addOnSuccessListener(res -> {
                                StringBuilder sb = new StringBuilder();
                                for (com.google.mlkit.vision.text.Text.TextBlock b : res.getTextBlocks())
                                    for (com.google.mlkit.vision.text.Text.Line l : b.getLines())
                                        sb.append(l.getText()).append(" | ");
                                img.recycle();
                                shotBusy = false;
                                handleText(plat, "leitura da imagem", sb.toString());
                            })
                            .addOnFailureListener(ex -> { img.recycle(); shotBusy = false; });
                }
                @Override public void onFailure(int code) {
                    shotBusy = false;
                    recordDiag(plat, "imagem do ecrã recusada (código " + code + ")", "", null);
                }
            });
        } catch (Exception ex) { shotBusy = false; }
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
        if (o != null) showCard(o);
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

    private void showCard(OfferParser.Offer o) {
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

            if (o.airport) box.addView(banner("✈ AEROPORTO — veja quantas já fez hoje na app", "#F59E0B", Color.BLACK));
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
            h.postDelayed(hide, secs * 1000L);

            if (hasMin) {
                if (!(gk && gh)) vibrate();
                if (Prefs.b(this, "voice", true) && ttsReady) {
                    String phrase = gk && gh ? "Viagem boa" : (gk || gh ? "Analisar" : "Recusar");
                    if (o.stops > 0) phrase += ". Atenção, tem paragem";
                    tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "offer");
                }
            }
        });
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
        hideCard();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
