package pt.tvde.contas;

import android.content.Context;
import android.content.SharedPreferences;

/** Valores partilhados entre a app (ecrã) e o leitor de ofertas. Ficam só no telemóvel. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("tvde", Context.MODE_PRIVATE);
    }

    static float f(Context c, String k, float def) {
        try { return get(c).getFloat(k, def); } catch (ClassCastException e) { return def; }
    }

    static void put(Context c, String k, float v) { get(c).edit().putFloat(k, v).apply(); }
    static void put(Context c, String k, String v) { get(c).edit().putString(k, v).apply(); }
    static void put(Context c, String k, boolean v) { get(c).edit().putBoolean(k, v).apply(); }
    static String s(Context c, String k) { return get(c).getString(k, ""); }
    static boolean b(Context c, String k, boolean def) { return get(c).getBoolean(k, def); }
}
