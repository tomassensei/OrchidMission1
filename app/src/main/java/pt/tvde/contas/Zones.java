package pt.tvde.contas;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zonas do motorista (cap. 11 do manual): desenhadas no mapa ou por regra
 * (código postal, intervalo de códigos postais ou parte da morada).
 * Níveis: "p" perigosa, "a" atenção, "b" boa.
 */
final class Zones {
    private Zones() {}

    static final class Hit {
        final String name, level;
        Hit(String n, String l) { name = n; level = l; }
    }

    private static final Pattern CP = Pattern.compile("(\\d{4})-(\\d{3})");
    private static final Map<String, double[]> geoCache = new HashMap<>();

    static JSONArray load(Context c) {
        try { String s = Prefs.s(c, "zones"); return new JSONArray(s.isEmpty() ? "[]" : s); }
        catch (Exception e) { return new JSONArray(); }
    }

    static boolean hasPolygons(JSONArray zs) {
        for (int i = 0; i < zs.length(); i++) {
            JSONArray p = zs.optJSONObject(i) != null ? zs.optJSONObject(i).optJSONArray("poly") : null;
            if (p != null && p.length() >= 3) return true;
        }
        return false;
    }

    private static int rank(String l) { return "p".equals(l) ? 3 : "a".equals(l) ? 2 : "b".equals(l) ? 1 : 0; }

    /** Só regras de texto/código postal (sem internet). */
    static Hit byRules(JSONArray zs, String addr) {
        if (addr == null || addr.isEmpty()) return null;
        String a = addr.toLowerCase(Locale.ROOT);
        Matcher m = CP.matcher(addr);
        int cp = m.find() ? Integer.parseInt(m.group(1)) * 1000 + Integer.parseInt(m.group(2)) : -1;
        int cp4 = cp >= 0 ? cp / 1000 : -1;
        Hit best = null;
        for (int i = 0; i < zs.length(); i++) {
            JSONObject z = zs.optJSONObject(i);
            if (z == null) continue;
            JSONArray rules = z.optJSONArray("rules");
            if (rules == null) continue;
            for (int r = 0; r < rules.length(); r++) {
                JSONObject ru = rules.optJSONObject(r);
                if (ru == null) continue;
                String t = ru.optString("t"), v = ru.optString("v").trim();
                boolean ok = false;
                if ("text".equals(t)) ok = !v.isEmpty() && a.contains(v.toLowerCase(Locale.ROOT));
                else if ("cp".equals(t) && cp >= 0) {
                    Matcher mv = CP.matcher(v);
                    if (mv.find()) ok = cp == Integer.parseInt(mv.group(1)) * 1000 + Integer.parseInt(mv.group(2));
                    else if (v.matches("\\d{4}")) ok = cp4 == Integer.parseInt(v);
                } else if ("range".equals(t) && cp >= 0) {
                    Matcher mv = CP.matcher(v);
                    if (mv.find()) {
                        int lo = Integer.parseInt(mv.group(1)) * 1000 + Integer.parseInt(mv.group(2));
                        if (mv.find()) {
                            int hi = Integer.parseInt(mv.group(1)) * 1000 + Integer.parseInt(mv.group(2));
                            ok = cp >= Math.min(lo, hi) && cp <= Math.max(lo, hi);
                        }
                    }
                }
                if (ok && (best == null || rank(z.optString("level")) > rank(best.level)))
                    best = new Hit(z.optString("name"), z.optString("level"));
            }
        }
        return best;
    }

    /** Morada → coordenadas (OpenStreetMap). Chamar fora do fio principal. */
    static double[] geocode(String addr) {
        if (addr == null || addr.length() < 5) return null;
        synchronized (geoCache) { if (geoCache.containsKey(addr)) return geoCache.get(addr); }
        double[] res = null;
        try {
            String q = addr.toLowerCase(Locale.ROOT).contains("portugal") ? addr : addr + ", Portugal";
            URL u = new URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=pt&q="
                    + URLEncoder.encode(q, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestProperty("User-Agent", "TVDEContas/2.1 (app pessoal de motorista)");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray a = new JSONArray(sb.toString());
            if (a.length() > 0) res = new double[]{a.getJSONObject(0).getDouble("lat"), a.getJSONObject(0).getDouble("lon")};
        } catch (Exception ignored) { }
        synchronized (geoCache) { geoCache.put(addr, res); }
        return res;
    }

    /** Ponto dentro de uma zona desenhada (ou a menos de "margin" metros dela). */
    static Hit byPolygons(JSONArray zs, double[] p) {
        if (p == null) return null;
        Hit best = null;
        for (int i = 0; i < zs.length(); i++) {
            JSONObject z = zs.optJSONObject(i);
            JSONArray poly = z != null ? z.optJSONArray("poly") : null;
            if (poly == null || poly.length() < 3) continue;
            double margin = z.optDouble("margin", 100);
            if (inside(poly, p) || distanceM(poly, p) <= margin) {
                if (best == null || rank(z.optString("level")) > rank(best.level))
                    best = new Hit(z.optString("name"), z.optString("level"));
            }
        }
        return best;
    }

    private static boolean inside(JSONArray poly, double[] p) {
        boolean in = false;
        int n = poly.length();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = poly.optJSONArray(i).optDouble(0), xi = poly.optJSONArray(i).optDouble(1);
            double yj = poly.optJSONArray(j).optDouble(0), xj = poly.optJSONArray(j).optDouble(1);
            if (((yi > p[0]) != (yj > p[0])) && (p[1] < (xj - xi) * (p[0] - yi) / (yj - yi) + xi)) in = !in;
        }
        return in;
    }

    /** Distância aproximada (metros) do ponto ao contorno da zona. */
    private static double distanceM(JSONArray poly, double[] p) {
        double kx = 111320 * Math.cos(Math.toRadians(p[0])), ky = 110540, best = Double.MAX_VALUE;
        int n = poly.length();
        for (int i = 0; i < n; i++) {
            JSONArray a = poly.optJSONArray(i), b = poly.optJSONArray((i + 1) % n);
            double ax = (a.optDouble(1) - p[1]) * kx, ay = (a.optDouble(0) - p[0]) * ky;
            double bx = (b.optDouble(1) - p[1]) * kx, by = (b.optDouble(0) - p[0]) * ky;
            double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
            double t = len > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len)) : 0;
            double cx = ax + t * dx, cy = ay + t * dy;
            best = Math.min(best, Math.sqrt(cx * cx + cy * cy));
        }
        return best;
    }
}
