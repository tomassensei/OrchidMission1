package pt.tvde.contas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tira de um texto do ecrã da Uber Driver ou da Bolt Driver o valor da oferta,
 * os minutos e os km até ao cliente e da viagem.
 *
 * Os formatos das apps mudam com as versões. Se uma oferta não for lida,
 * o texto bruto fica guardado na app ("Última leitura") para ajustar estes padrões.
 */
public final class OfferParser {

    public static final class Offer {
        public String platform;
        public double value;
        public double pickupMin, pickupKm, tripMin, tripKm;
        public int stops;
        public boolean airport;

        public double totalKm() { return pickupKm + tripKm; }
        public double totalMin() { return pickupMin + tripMin; }
        public double perKm() { return totalKm() > 0 ? value / totalKm() : 0; }
        public double perHour() { return totalMin() > 0 ? value / (totalMin() / 60.0) : 0; }

        public String signature() {
            return String.format(Locale.ROOT, "%s|%.2f|%.1f|%.1f|%.0f|%.0f",
                    platform, value, pickupKm, tripKm, pickupMin, tripMin);
        }

        public String toJson(long at) {
            return String.format(Locale.ROOT,
                    "{\"at\":%d,\"plat\":\"%s\",\"v\":%.2f,\"pm\":%.0f,\"pk\":%.1f,\"tm\":%.0f,\"tk\":%.1f,\"stops\":%d,\"air\":%b}",
                    at, platform, value, pickupMin, pickupKm, tripMin, tripKm, stops, airport);
        }
    }

    // 9,80 €  |  €9.80  |  € 9,80  |  EUR 9,80
    private static final Pattern MONEY = Pattern.compile(
            "(?:€|EUR)\\s?(\\d{1,3}(?:[.,]\\d{1,2})?)|(\\d{1,3}(?:[.,]\\d{1,2})?)\\s?(?:€|EUR)");
    // +2,50 € (bónus/dinâmica) — não é o valor principal
    private static final Pattern PLUS_MONEY = Pattern.compile("\\+\\s?(?:€\\s?)?\\d{1,3}(?:[.,]\\d{1,2})?\\s?€?");
    private static final Pattern MIN = Pattern.compile("(\\d{1,3})\\s?(?:min|mins|minutos)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KM = Pattern.compile("(\\d{1,3}(?:[.,]\\d{1,2})?)\\s?km\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern METERS = Pattern.compile("(\\d{2,3})\\s?m\\b");
    private static final Pattern STOPS = Pattern.compile(
            "(\\d+)\\s*(?:paragens|paragem|stops|stop)|(?:paragens|stops)\\s*[:·]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AIRPORT = Pattern.compile(
            "aeroporto|airport|humberto delgado|terminal 1|\\bLIS\\b", Pattern.CASE_INSENSITIVE);

    private OfferParser() {}

    static double num(String s) {
        try { return Double.parseDouble(s.replace(',', '.')); } catch (Exception e) { return 0; }
    }

    /** Devolve null se o texto não parecer uma oferta. */
    public static Offer parse(String raw, String platform) {
        if (raw == null) return null;
        String t = raw.replace(' ', ' ').replace(' ', ' ');
        String noBonus = PLUS_MONEY.matcher(t).replaceAll(" ");
        // "0,59 €/km est." e "18 €/h" não são o valor da oferta
        noBonus = noBonus.replaceAll("\\d{1,3}(?:[.,]\\d{1,2})?\\s?€\\s?/\\s?(?:km|h)", " ");

        Matcher m = MONEY.matcher(noBonus);
        double value = 0;
        while (m.find()) {
            double v = num(m.group(1) != null ? m.group(1) : m.group(2));
            if (v >= 2 && v <= 500) { value = v; break; }
        }
        if (value == 0) return null;

        List<Double> mins = new ArrayList<>();
        Matcher mm = MIN.matcher(t);
        while (mm.find()) mins.add(num(mm.group(1)));

        List<Double> kms = new ArrayList<>();
        Matcher mk = KM.matcher(t);
        while (mk.find()) kms.add(num(mk.group(1)));
        // recolha muito perto: "350 m"
        if (kms.size() == 1) {
            Matcher me = METERS.matcher(t);
            if (me.find() && me.start() < t.indexOf("km")) kms.add(0, num(me.group(1)) / 1000.0);
        }
        if (kms.isEmpty() || mins.isEmpty()) return null;

        Offer o = new Offer();
        o.platform = platform;
        o.value = value;
        if (kms.size() >= 2) { o.pickupKm = kms.get(0); o.tripKm = kms.get(1); }
        else o.tripKm = kms.get(0);
        if (mins.size() >= 2) { o.pickupMin = mins.get(0); o.tripMin = mins.get(1); }
        else o.tripMin = mins.get(0);
        if (o.totalKm() <= 0 || o.totalKm() > 400) return null;

        // a leitura da imagem às vezes lê "1 paragem" como "l paragem" ou "I paragem"
        String ts = t.replaceAll("(^|[|\\s])[lI|]\\s+(paragem|stop)\\b", "$11 $2");
        Matcher ms = STOPS.matcher(ts);
        if (ms.find()) {
            String g = ms.group(1) != null ? ms.group(1) : ms.group(2);
            o.stops = (int) num(g);
        }
        o.airport = AIRPORT.matcher(t).find();
        return o;
    }
}
