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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (!UBER.equals(pkg) && !BOLT.equals(pkg)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastScan < 350) return;   // poupa bateria: no máximo ~3 leituras por segundo
        lastScan = now;

        StringBuilder sb = new StringBuilder();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p != null && pkg.contentEquals(p)) collect(r, sb, 0);
            }
        } catch (Exception ignored) { }
        if (sb.length() == 0) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) collect(r, sb, 0);
        }
        String text = sb.toString();
        if (text.isEmpty()) return;

        String plat = UBER.equals(pkg) ? "Uber" : "Bolt";
        OfferParser.Offer o = OfferParser.parse(text, plat);
        if (o == null) return;

        // guarda o texto para afinar a leitura se algo vier errado
        Prefs.put(this, "lastRaw", plat + " · " + new java.util.Date() + "\n" + text);

        String sig = o.signature();
        long t = System.currentTimeMillis();
        if (sig.equals(lastSig) && t - lastShown < 45_000) return;  // mesma oferta, já mostrada
        lastSig = sig;
        lastShown = t;
        Prefs.put(this, "lastOffer", o.toJson(t));
        if (!Prefs.b(this, "readerCard", true)) return;
        showCard(o);
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || sb.length() > 6000) return;
        CharSequence tx = n.getText();
        CharSequence cd = n.getContentDescription();
        if (tx != null && tx.length() > 0) sb.append(tx).append(" | ");
        else if (cd != null && cd.length() > 0) sb.append(cd).append(" | ");
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
        hideCard();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
