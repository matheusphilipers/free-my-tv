package io.freemytv;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Brazilian Portuguese as people say it to the TV, turned into what to look for: commands taken off ("quero assistir
 * Duna", "coloca na Globo", "abre o canal SporTV"), channel names said the way they sound ("esporte vê", "agá bê ó",
 * "esse bê tê"), letters spelled out, numbers said in words ("Duna parte dois" → "Duna parte 2", "Rocky três"), and
 * a sound key so "Bruna" and "Duna", "Xuxa" and "Chucha", "Bebê" and "Bebe" compare close.
 */
final class Speech {
    private Speech() {}

    // Said before what's wanted: "quero ver", "coloca", "abre o canal", "pesquisa por"...
    private static final String[] COMMANDS = {
        "eu quero assistir", "eu quero ver", "quero assistir", "quero ver", "queria assistir", "queria ver", "vamos assistir", "vamos ver",
        "me mostra", "me mostre", "mostra", "mostre", "mostrar", "coloca no canal", "coloca na", "coloca no", "coloca o", "coloca a", "coloca",
        "coloque", "bota na", "bota no", "bota o", "bota a", "bota", "poe na", "poe no", "poe o", "poe a", "poe", "liga na", "liga no", "liga",
        "abre o canal", "abre o", "abre a", "abre", "abrir", "abra", "toca o", "toca a", "toca", "passa o", "passa a", "passa", "procura por",
        "procurar por", "procura", "procurar", "pesquisa por", "pesquisar por", "pesquisa", "pesquisar", "busca por", "buscar por", "busca",
        "buscar", "assistir", "assiste", "ver o", "ver a", "ver", "achar", "acha", "tem o", "tem a", "tem", "onde passa o", "onde passa a",
        "onde passa", "onde assistir o", "onde assistir a", "onde assistir", "o filme", "a serie", "o seriado", "a novela", "o canal",
        "canal", "filme", "serie", "seriado", "novela", "o", "a", "na", "no"};

    // Channels and services as the microphone writes them, and what they are.
    private static final String[][] ALIASES = {
        {"esporte ve", "sportv"}, {"esporte v", "sportv"}, {"esporte tv", "sportv"}, {"sport ve", "sportv"}, {"sport tv", "sportv"},
        {"spor tv", "sportv"}, {"esportivi", "sportv"}, {"esporte ve dois", "sportv 2"}, {"esporte ve 2", "sportv 2"},
        {"esporte ve tres", "sportv 3"}, {"esporte ve 3", "sportv 3"}, {"e s p n", "espn"}, {"esse pe ene", "espn"}, {"espe ene", "espn"},
        {"aga be o", "hbo"}, {"h b o", "hbo"}, {"agabeo", "hbo"}, {"eichbiou", "hbo"}, {"esse be te", "sbt"}, {"s b t", "sbt"},
        {"te ene te", "tnt"}, {"t n t", "tnt"}, {"ge ene te", "gnt"}, {"g n t", "gnt"}, {"a e", "a&e"}, {"a mais e", "a&e"},
        {"premier", "premiere"}, {"premie", "premiere"}, {"premiere fc", "premiere"}, {"globo news", "globonews"}, {"globonius", "globonews"},
        {"band news", "bandnews"}, {"band sports", "bandsports"}, {"cnn brasil", "cnn"}, {"ce ene ene", "cnn"}, {"discoveri", "discovery"},
        {"discovery kids", "discovery kids"}, {"nat geo", "nat geo"}, {"national geographic", "nat geo"}, {"nacional geographic", "nat geo"},
        {"cartum", "cartoon"}, {"cartoon", "cartoon network"}, {"nick", "nickelodeon"}, {"glub", "gloob"}, {"glube", "gloob"},
        {"da zone", "dazn"}, {"dazone", "dazn"}, {"de a z n", "dazn"}, {"caze tv", "cazetv"}, {"caze", "cazetv"}, {"kz tv", "cazetv"},
        {"multi show", "multishow"}, {"record tv", "record"}, {"rede tv", "redetv"}, {"rede globo", "globo"}, {"tv globo", "globo"},
        {"telesine", "telecine"}, {"mega pix", "megapix"}, {"warner chanel", "warner"}, {"universal chanel", "universal"},
        {"disney mais", "disney+"}, {"disney plus", "disney+"}, {"prime video", "prime video"}, {"amazon prime", "prime video"},
        {"globo play", "globoplay"}, {"rede vida", "rede vida"}, {"cancao nova", "cancao nova"}, {"tv aparecida", "aparecida"},
        {"combat", "combate"}, {"u f c", "ufc"}, {"ufe ce", "ufc"}, {"efe um", "f1"}, {"formula um", "formula 1"}, {"f um", "f1"},
    };

    private static final String[][] LONGEST_FIRST = ALIASES.clone();
    static { java.util.Arrays.sort(LONGEST_FIRST, (a, b) -> Integer.compare(b[0].length(), a[0].length())); }

    private static final String[][] NUMBERS = {
        {"um", "1"}, {"uma", "1"}, {"dois", "2"}, {"duas", "2"}, {"tres", "3"}, {"quatro", "4"}, {"cinco", "5"}, {"seis", "6"}, {"sete", "7"},
        {"oito", "8"}, {"nove", "9"}, {"dez", "10"}, {"onze", "11"}, {"doze", "12"}, {"treze", "13"}, {"quatorze", "14"}, {"catorze", "14"},
        {"quinze", "15"}, {"dezesseis", "16"}, {"dezessete", "17"}, {"dezoito", "18"}, {"dezenove", "19"}, {"vinte", "20"},
    };
    private static final String[][] ROMAN = {{"2", "ii"}, {"3", "iii"}, {"4", "iv"}, {"5", "v"}, {"6", "vi"}, {"7", "vii"}, {"8", "viii"}};

    // Letters said one by one ("esse", "bê", "tê"), joined when two or more come in a row.
    private static final String[][] LETTERS = {
        {"be", "b"}, {"ce", "c"}, {"efe", "f"}, {"ge", "g"}, {"aga", "h"}, {"jota", "j"}, {"ka", "k"}, {"ele", "l"},
        {"eme", "m"}, {"ene", "n"}, {"pe", "p"}, {"erre", "r"}, {"esse", "s"}, {"te", "t"}, {"ve", "v"}, {"dablio", "w"},
        {"xis", "x"}, {"ipsilon", "y"}, {"ze", "z"},
    };

    /** What to look for in what was said: the command words off, channel names as they're written. */
    static String clean(String said) {
        String n = " " + Lineup.norm(said) + " ";
        if (n.trim().isEmpty()) return "";
        // The command at the start, a few times ("quero ver o canal" → "").
        for (int round = 0; round < 3; round++) {
            boolean cut = false;
            for (String c : COMMANDS) {
                if (n.startsWith(" " + c + " ") && n.length() > c.length() + 3) { n = n.substring(c.length() + 1); cut = true; break; }
            }
            if (!cut) break;
        }
        boolean named = false;
        for (String[] a : LONGEST_FIRST) {
            String w = " " + a[0] + " ";
            if (n.contains(w)) { n = n.replace(w, " " + a[1] + " "); named = true; break; }
        }
        if (!named) n = letters(n);
        return n.trim();
    }

    /** "esse be te" → "sbt": two or more spelled letters in a row become one word. */
    private static String letters(String n) {
        String[] w = n.trim().split(" +");
        StringBuilder out = new StringBuilder(), run = new StringBuilder();
        int count = 0;
        for (int i = 0; i <= w.length; i++) {
            String l = i < w.length ? letter(w[i]) : null;
            if (l != null) { run.append(l); count++; continue; }
            if (count >= 2) out.append(' ').append(run);
            else if (count == 1) out.append(' ').append(w[i - 1]);
            run.setLength(0);
            count = 0;
            if (i < w.length) out.append(' ').append(w[i]);
        }
        return " " + out.toString().trim() + " ";
    }

    private static String letter(String w) {
        for (String[] l : LETTERS) if (l[0].equals(w)) return l[1];
        return null;
    }

    /** The ways a title may be written: as said, with numbers in digits ("parte 2"), and in Roman ("Rocky II"). */
    static List<String> variants(String text) {
        Set<String> out = new LinkedHashSet<>();
        String n = Lineup.norm(text);
        out.add(n);
        String digits = " " + n + " ";
        for (String[] d : NUMBERS) digits = digits.replace(" " + d[0] + " ", " " + d[1] + " ");
        // "um"/"uma" at the start are articles ("Uma Noite..."): only numbers after the first word count.
        if (n.startsWith("um ") || n.startsWith("uma ")) digits = " " + n.substring(0, n.indexOf(' ')) + digits.substring(digits.indexOf(' ', 1));
        out.add(digits.trim());
        String roman = " " + digits.trim() + " ";
        for (String[] r : ROMAN) roman = roman.replace(" " + r[0] + " ", " " + r[1] + " ");
        out.add(roman.trim());
        return new ArrayList<>(out);
    }

    /**
     * A sound key for Brazilian Portuguese: letters that sound alike written the same ("ch", "x" and "sh" → x; "ç",
     * "ss", "c" before e/i → s; "qu", "k", "c" → k; "lh" → li; "nh" → ni; "ph" → f; "w" → v; "y" → i; a silent first
     * "h"; "l" closing a syllable → u; final "o" → u and final "e" → i; doubled letters once).
     */
    static String sound(String text) {
        String s = Lineup.norm(text);
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            String k = w;
            if (k.startsWith("h")) k = k.substring(1);
            k = k.replace("sch", "x").replace("ch", "x").replace("sh", "x").replace("lh", "li").replace("nh", "ni").replace("ph", "f")
                    .replace("th", "t").replace("qu", "k").replace("gu", "g").replace("ck", "k").replace("w", "v").replace("y", "i")
                    .replace("sc", "s").replace("xc", "s");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < k.length(); i++) {
                char c = k.charAt(i), next = i + 1 < k.length() ? k.charAt(i + 1) : ' ';
                char m = c;
                if (c == 'c') m = next == 'e' || next == 'i' ? 's' : 'k';
                else if (c == 'g' && (next == 'e' || next == 'i')) m = 'j';
                else if (c == 'z' && i == k.length() - 1) m = 's';
                else if (c == 'l' && (next == ' ' || "aeiou".indexOf(next) < 0)) m = 'u';
                else if (c == 'h') continue;
                else if (c == 'o' && i == k.length() - 1) m = 'u';
                else if (c == 'e' && i == k.length() - 1 && k.length() > 2) m = 'i';
                if (b.length() > 0 && b.charAt(b.length() - 1) == m) continue;   // doubled
                b.append(m);
            }
            out.append(out.length() == 0 ? "" : " ").append(b);
        }
        return out.toString();
    }
}
