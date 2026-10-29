package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Your teams, for the sports rows and the Sports screen: a football club (ESPN: every competition it plays, and its
 * league table), an NHL team (the NHL's own API: schedule, division, live clock) and Formula 1 (Jolpica: the
 * calendar with every session, drivers' and constructors' standings). Where each game is on TV in your country comes
 * from Sofascore. None needs a key. The teams come from config.properties. Each source is fetched on its own: one
 * failing keeps the others.
 */
final class Sports {
    private Sports() {}

    static final int PRE = 0, LIVE = 1, FINAL = 2;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36";

    static boolean on() {
        return Config.SHOW_SPORTS;
    }

    static final class Team {
        String id = "", abbrev = "", name = "", logo = "";
    }

    static final class Match {
        Team home = new Team(), away = new Team();
        boolean weHome;
        long at;
        int state = PRE;
        String homeScore = "", awayScore = "", clock = "", comp = "", venue = "";
        final List<String> tv = new ArrayList<>();
        Team us() { return weHome ? home : away; }
        Team them() { return weHome ? away : home; }
    }

    static final class Row {
        int pos, played, pts, diff;
        String name = "", abbrev = "", logo = "", extra = "";
        boolean mine;
    }

    static final class Club {
        Team me = new Team();
        String label = "", league = "", record = "";
        int pos, pts;
        Match live, recent;
        final List<Match> upcoming = new ArrayList<>();
        final List<Row> table = new ArrayList<>();
        /** The cup the club is still in (round), e.g. "Copa do Brasil · Semifinal". */
        String cup = "";
    }

    static final class Session {
        String name;
        long at;
        Session(String name, long at) { this.name = name; this.at = at; }
    }

    static final class Race {
        int round;
        String name = "", circuit = "", place = "";
        long at;
        final List<Session> sessions = new ArrayList<>();
    }

    static final class Standing {
        int pos, pts;
        String name = "", team = "", teamId = "";
    }

    static final class F1 {
        int rounds;
        Race current, next;
        final List<Race> later = new ArrayList<>();
        final List<Standing> drivers = new ArrayList<>(), teams = new ArrayList<>();
        final List<String> tv = new ArrayList<>();
        /** The session on now (a practice, qualifying, the race), or null. */
        Session live;
        /** The whole season, so the weekend moves on by itself even when a fetch fails. */
        final List<Race> season = new ArrayList<>();

        /**
         * Picks the current weekend (it stays current until 3 h after the race starts), the next ones and the session
         * on now. Cheap: called after each fetch and before drawing.
         */
        synchronized void roll(long now) {
            current = next = null;
            later.clear();
            live = null;
            for (Race r : season) {
                if (r.at + 3 * 60 * 60_000L < now) continue;
                if (current == null) current = r;
                else if (next == null) next = r;
                else later.add(r);
            }
            if (current == null) return;
            for (Session s : current.sessions) {
                long length = s.name.equals(Ui.tr("Race")) ? 2 * 60 * 60_000L : 60 * 60_000L;
                if (now >= s.at && now < s.at + length) live = s;
            }
        }
    }

    // The calendar barely changes and the standings only after a session: no need to ask every minute
    // (jolpica.ca limits requests, and an error left the finished race on screen).
    private static String seasonJson, driversJson, teamsJson;
    private static long seasonAt, standingsAt;

    static final class All {
        Club soccer, hockey;
        F1 f1;
        boolean anyLive() {
            return soccer != null && soccer.live != null || hockey != null && hockey.live != null || f1 != null && f1.live != null;
        }
        /** Something starts within 15 minutes: refresh often. */
        boolean startsSoon(long now) {
            for (Club c : new Club[] {soccer, hockey}) {
                if (c != null && !c.upcoming.isEmpty() && c.upcoming.get(0).at - now < 15 * 60_000L) return true;
            }
            if (f1 != null && f1.current != null) {
                for (Session s : f1.current.sessions) if (s.at - now < 15 * 60_000L && now - s.at < 3 * 60 * 60_000L) return true;
            }
            return false;
        }
    }

    /** Fetches everything; a source that fails keeps what it had before (`last`). */
    static All fetch(Context c, All last) {
        All a = new All();
        String soccer = Config.SPORTS_SOCCER.trim(), nhl = Config.SPORTS_NHL.trim();
        if (!soccer.isEmpty()) {
            try {
                a.soccer = soccer(c, soccer);
            } catch (Exception e) {
                AppLog.w("sports soccer: " + e.getMessage());
                a.soccer = last != null ? last.soccer : null;
            }
        }
        if (!nhl.isEmpty()) {
            try {
                a.hockey = hockey(c, nhl);
            } catch (Exception e) {
                AppLog.w("sports nhl: " + e.getMessage());
                a.hockey = last != null ? last.hockey : null;
            }
        }
        if (Config.SPORTS_F1) {
            try {
                a.f1 = f1();
            } catch (Exception e) {
                AppLog.w("sports f1: " + e.getMessage());
                a.f1 = last != null ? last.f1 : null;
                if (a.f1 != null) a.f1.roll(System.currentTimeMillis());
            }
        }
        return a;
    }

    // MARK: - Football (ESPN)

    private static final long RESULT_FOR = 12 * 60 * 60_000L;

    private static Club soccer(Context ctx, String teamId) throws Exception {
        Club club = new Club();
        String base = "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/" + teamId + "/schedule";
        long now = System.currentTimeMillis();
        try {
            JSONArray past = new JSONObject(get(base)).optJSONArray("events");
            for (int i = 0; past != null && i < past.length(); i++) {
                Match m = espnMatch(past.getJSONObject(i), teamId);
                if (m == null || m.state == PRE) continue;
                if (m.state == LIVE) club.live = m;
                else if (now - m.at < RESULT_FOR && (club.recent == null || m.at > club.recent.at)) club.recent = m;
            }
        } catch (Exception e) {
            AppLog.w("sports soccer results: " + e.getMessage());
        }
        JSONArray fixtures = new JSONObject(get(base + "?fixture=true")).optJSONArray("events");
        for (int i = 0; fixtures != null && i < fixtures.length(); i++) {
            JSONObject ev = fixtures.getJSONObject(i);
            Match m = espnMatch(ev, teamId);
            if (m == null) continue;
            if (m.state == LIVE) { club.live = m; continue; }
            if (m.state == PRE && m.at > now - 3 * 60 * 60_000L) club.upcoming.add(m);
            if (club.cup.isEmpty()) {
                String league = ev.optJSONObject("league") != null ? ev.getJSONObject("league").optString("name") : "";
                JSONObject type = ev.optJSONObject("seasonType");
                if (!league.contains("Serie A") && type != null) club.cup = leagueName(league) + " · " + roundName(type.optString("name"));
            }
        }
        java.util.Collections.sort(club.upcoming, (x, y) -> Long.compare(x.at, y.at));
        Match any = club.live != null ? club.live : !club.upcoming.isEmpty() ? club.upcoming.get(0) : club.recent;
        if (any != null) club.me = any.us();
        club.label = Config.SPORTS_SOCCER_NAME.isEmpty() ? club.me.name : Config.SPORTS_SOCCER_NAME;
        try {
            soccerTable(club, teamId);
        } catch (Exception e) {
            AppLog.w("sports soccer table: " + e.getMessage());
        }
        tv(ctx, Config.SPORTS_SOCCER_SOFA, "football", club);
        return club;
    }

    private static Match espnMatch(JSONObject ev, String teamId) throws Exception {
        JSONObject comp = ev.getJSONArray("competitions").getJSONObject(0);
        JSONArray teams = comp.getJSONArray("competitors");
        Match m = new Match();
        boolean found = false;
        for (int j = 0; j < teams.length(); j++) {
            JSONObject t = teams.getJSONObject(j);
            JSONObject team = t.optJSONObject("team");
            Team x = new Team();
            x.id = t.optString("id", team != null ? team.optString("id") : "");
            if (team != null) {
                x.abbrev = team.optString("abbreviation");
                x.name = team.optString("shortDisplayName", team.optString("displayName"));
                JSONArray logos = team.optJSONArray("logos");
                x.logo = logos != null && logos.length() > 0 ? logos.getJSONObject(0).optString("href") : team.optString("logo");
            }
            boolean home = "home".equals(t.optString("homeAway"));
            if (home) { m.home = x; m.homeScore = espnScore(t); } else { m.away = x; m.awayScore = espnScore(t); }
            if (teamId.equals(x.id)) { found = true; m.weHome = home; }
        }
        if (!found) return null;
        JSONObject league = ev.optJSONObject("league");
        m.comp = league != null ? leagueName(league.optString("name")) : "";
        JSONObject type = ev.optJSONObject("seasonType");
        if (type != null && !m.comp.equals("Brasileirão") && !type.optString("name").contains("Serie")) {
            String round = roundName(type.optString("name"));
            if (!round.isEmpty() && !round.equals(m.comp)) m.comp += " · " + round;
        }
        JSONObject venue = comp.optJSONObject("venue");
        if (venue != null) m.venue = venue.optString("fullName");
        m.at = iso(ev.optString("date", comp.optString("date")));
        JSONObject status = comp.optJSONObject("status");
        if (status == null) status = ev.optJSONObject("status");
        String state = status == null ? "pre" : status.getJSONObject("type").optString("state", "pre");
        m.state = state.equals("in") ? LIVE : state.equals("post") ? FINAL : PRE;
        if (m.state == LIVE && status != null) {
            String detail = status.getJSONObject("type").optString("shortDetail", "");
            m.clock = detail.equalsIgnoreCase("HT") || detail.equalsIgnoreCase("Halftime") ? Ui.tr("Half-time") : status.optString("displayClock", "");
        }
        return m;
    }

    private static String espnScore(JSONObject competitor) {
        Object s = competitor.opt("score");
        if (s instanceof JSONObject) return ((JSONObject) s).optString("displayValue", "0");
        return s == null ? "" : String.valueOf(s);
    }

    private static void soccerTable(Club club, String teamId) throws Exception {
        String league = Config.SPORTS_SOCCER_LEAGUE.isEmpty() ? "bra.1" : Config.SPORTS_SOCCER_LEAGUE;
        JSONObject o = new JSONObject(get("https://site.api.espn.com/apis/v2/sports/soccer/" + league + "/standings"));
        club.league = leagueName(o.optString("name"));
        JSONArray children = o.optJSONArray("children");
        JSONObject holder = children != null && children.length() > 0 ? children.getJSONObject(0) : o;
        JSONArray entries = holder.getJSONObject("standings").getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            JSONObject team = e.getJSONObject("team");
            Map<String, Double> stats = new HashMap<>();
            JSONArray s = e.getJSONArray("stats");
            for (int j = 0; j < s.length(); j++) stats.put(s.getJSONObject(j).optString("name"), s.getJSONObject(j).optDouble("value", 0));
            Row r = new Row();
            r.pos = (int) Math.round(stats.getOrDefault("rank", (double) (i + 1)));
            r.name = team.optString("shortDisplayName", team.optString("displayName"));
            r.abbrev = team.optString("abbreviation");
            JSONArray logos = team.optJSONArray("logos");
            r.logo = logos != null && logos.length() > 0 ? logos.getJSONObject(0).optString("href") : "";
            r.played = (int) Math.round(stats.getOrDefault("gamesPlayed", 0.0));
            r.pts = (int) Math.round(stats.getOrDefault("points", 0.0));
            r.diff = (int) Math.round(stats.getOrDefault("pointDifferential", 0.0));
            r.mine = teamId.equals(team.optString("id"));
            if (r.mine) {
                club.pos = r.pos;
                club.pts = r.pts;
                club.record = Ui.trf("{w}W {d}D {l}L", "w", Math.round(stats.getOrDefault("wins", 0.0)), "d",
                        Math.round(stats.getOrDefault("ties", 0.0)), "l", Math.round(stats.getOrDefault("losses", 0.0)));
                if (club.me.logo.isEmpty()) club.me.logo = r.logo;
            }
            club.table.add(r);
        }
        java.util.Collections.sort(club.table, (x, y) -> Integer.compare(x.pos, y.pos));
    }

    private static String leagueName(String name) {
        switch (name) {
            case "Brazilian Serie A": return "Brasileirão";
            case "Copa do Brasil": return "Copa do Brasil";
            case "CONMEBOL Libertadores": return "Libertadores";
            case "CONMEBOL Sudamericana": return "Sul-Americana";
            default: return name;
        }
    }

    private static String roundName(String name) {
        String n = name.trim();
        if (n.contains("Semifinal")) return Ui.tr("Semifinal");
        if (n.contains("Quarterfinal")) return Ui.tr("Quarterfinal");
        if (n.equals("Final") || n.endsWith(" Final")) return Ui.tr("Final");
        if (n.contains("Round of 16")) return Ui.tr("Round of 16");
        return n.matches(".*\\d{4}.*") ? "" : n;
    }

    // MARK: - NHL

    private static Club hockey(Context ctx, String abbrev) throws Exception {
        String me = abbrev.toUpperCase(Locale.ROOT);
        Club club = new Club();
        JSONArray games = new JSONObject(get("https://api-web.nhle.com/v1/club-schedule-season/" + me + "/now")).getJSONArray("games");
        long now = System.currentTimeMillis();
        String liveId = null;
        for (int i = 0; i < games.length(); i++) {
            JSONObject o = games.getJSONObject(i);
            Match m = new Match();
            m.home = nhlTeam(o.getJSONObject("homeTeam"));
            m.away = nhlTeam(o.getJSONObject("awayTeam"));
            m.weHome = me.equals(m.home.abbrev);
            int type = o.optInt("gameType", 2);
            m.comp = type == 1 ? Ui.tr("Preseason") : type == 3 ? Ui.tr("Playoffs") : "NHL";
            JSONObject venue = o.optJSONObject("venue");
            if (venue != null) m.venue = venue.optString("default");
            m.at = iso(o.optString("startTimeUTC"));
            String st = o.optString("gameState");
            m.state = st.equals("LIVE") || st.equals("CRIT") ? LIVE : st.equals("FINAL") || st.equals("OFF") ? FINAL : PRE;
            m.homeScore = o.getJSONObject("homeTeam").has("score") ? String.valueOf(o.getJSONObject("homeTeam").optInt("score")) : "";
            m.awayScore = o.getJSONObject("awayTeam").has("score") ? String.valueOf(o.getJSONObject("awayTeam").optInt("score")) : "";
            if (m.state == LIVE) { club.live = m; liveId = o.optString("id"); }
            else if (m.state == FINAL && now - m.at < RESULT_FOR + 3 * 60 * 60_000L && (club.recent == null || m.at > club.recent.at)) club.recent = m;
            else if (m.state == PRE && m.at > now - 4 * 60 * 60_000L) club.upcoming.add(m);
        }
        java.util.Collections.sort(club.upcoming, (x, y) -> Long.compare(x.at, y.at));
        if (club.live != null && liveId != null) {
            try {
                nhlClock(club.live, liveId);
            } catch (Exception e) {
                AppLog.w("sports nhl clock: " + e.getMessage());
            }
        }
        Match any = club.live != null ? club.live : !club.upcoming.isEmpty() ? club.upcoming.get(0) : club.recent;
        if (any != null) club.me = any.us();
        club.label = club.me.name;
        try {
            nhlTable(club, me);
        } catch (Exception e) {
            AppLog.w("sports nhl table: " + e.getMessage());
        }
        tv(ctx, Config.SPORTS_NHL_SOFA, "ice-hockey", club);
        return club;
    }

    private static Team nhlTeam(JSONObject o) {
        Team t = new Team();
        t.abbrev = o.optString("abbrev");
        t.id = t.abbrev;
        JSONObject common = o.optJSONObject("commonName");
        t.name = common != null ? common.optString("default", t.abbrev) : t.abbrev;
        t.logo = nhlLogo(t.abbrev);
        return t;
    }

    /** ESPN's PNG logos (the NHL's own are SVG, which Android can't draw from a URL). */
    static String nhlLogo(String abbrev) {
        String a = abbrev.toLowerCase(Locale.ROOT);
        switch (a) {
            case "sjs": a = "sj"; break;
            case "lak": a = "la"; break;
            case "njd": a = "nj"; break;
            case "tbl": a = "tb"; break;
            case "uta": a = "utah"; break;
            default: break;
        }
        return "https://a.espncdn.com/i/teamlogos/nhl/500/" + a + ".png";
    }

    private static void nhlClock(Match m, String id) throws Exception {
        JSONObject o = new JSONObject(get("https://api-web.nhle.com/v1/gamecenter/" + id + "/landing"));
        JSONObject clock = o.optJSONObject("clock");
        JSONObject period = o.optJSONObject("periodDescriptor");
        int p = period != null ? period.optInt("number") : 0;
        String type = period != null ? period.optString("periodType") : "";
        String when = type.equals("OT") ? Ui.tr("OT") : type.equals("SO") ? Ui.tr("Shootout") : Ui.trf("P{n}", "n", p);
        if (clock != null && clock.optBoolean("inIntermission")) m.clock = Ui.tr("Intermission");
        else m.clock = when + (clock != null ? " " + clock.optString("timeRemaining") : "");
        JSONObject home = o.optJSONObject("homeTeam"), away = o.optJSONObject("awayTeam");
        if (home != null && home.has("score")) m.homeScore = String.valueOf(home.optInt("score"));
        if (away != null && away.has("score")) m.awayScore = String.valueOf(away.optInt("score"));
    }

    private static void nhlTable(Club club, String me) throws Exception {
        JSONArray all = new JSONObject(get("https://api-web.nhle.com/v1/standings/now")).getJSONArray("standings");
        String division = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            if (me.equals(o.getJSONObject("teamAbbrev").optString("default"))) division = o.optString("divisionName");
        }
        if (division == null) return;
        club.league = Ui.trf("{division} Division", "division", division);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            if (!division.equals(o.optString("divisionName"))) continue;
            Row r = new Row();
            r.abbrev = o.getJSONObject("teamAbbrev").optString("default");
            JSONObject common = o.optJSONObject("teamCommonName");
            r.name = common != null ? common.optString("default", r.abbrev) : r.abbrev;
            r.pos = o.optInt("divisionSequence");
            r.played = o.optInt("gamesPlayed");
            r.pts = o.optInt("points");
            r.diff = o.optInt("goalDifferential", o.optInt("goalFor") - o.optInt("goalAgainst"));
            r.logo = nhlLogo(r.abbrev);
            r.mine = me.equals(r.abbrev);
            if (r.mine) {
                club.pos = r.pos;
                club.pts = r.pts;
                club.record = Ui.trf("{w}W {l}L {o}OT", "w", o.optInt("wins"), "l", o.optInt("losses"), "o", o.optInt("otLosses"));
                int streak = o.optInt("streakCount");
                String code = o.optString("streakCode");
                if (streak >= 3 && code.equals("L")) club.cup = Ui.trf("{n} losses in a row", "n", streak);
                else if (streak >= 3 && code.equals("W")) club.cup = Ui.trf("{n} wins in a row", "n", streak);
            }
            club.table.add(r);
        }
        java.util.Collections.sort(club.table, (x, y) -> Integer.compare(x.pos, y.pos));
    }

    // MARK: - Formula 1 (Jolpica)

    private static F1 f1() throws Exception {
        F1 f = new F1();
        long now = System.currentTimeMillis();
        if (seasonJson == null || now - seasonAt > 6 * 60 * 60_000L || now < seasonAt) {
            seasonJson = get("https://api.jolpi.ca/ergast/f1/current.json");
            seasonAt = now;
        }
        JSONArray races = new JSONObject(seasonJson).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        f.rounds = races.length();
        for (int i = 0; i < races.length(); i++) f.season.add(race(races.getJSONObject(i)));
        f.roll(now);
        // Standings: every 30 min, and again soon after each session ends.
        boolean afterSession = false;
        for (Race r : f.season) for (Session s : r.sessions) if (s.at > standingsAt && s.at + 2 * 60 * 60_000L < now) afterSession = true;
        if (driversJson == null || teamsJson == null || now - standingsAt > 30 * 60_000L || afterSession || now < standingsAt) {
            try {
                driversJson = get("https://api.jolpi.ca/ergast/f1/current/driverStandings.json");
                teamsJson = get("https://api.jolpi.ca/ergast/f1/current/constructorStandings.json");
                standingsAt = now;
            } catch (Exception e) {
                AppLog.w("sports f1 standings: " + e.getMessage());
            }
        }
        try {
            JSONArray d = new JSONObject(driversJson).getJSONObject("MRData")
                    .getJSONObject("StandingsTable").getJSONArray("StandingsLists").getJSONObject(0).getJSONArray("DriverStandings");
            for (int i = 0; i < d.length() && i < 10; i++) {
                JSONObject o = d.getJSONObject(i);
                Standing s = new Standing();
                s.pos = o.optInt("position", i + 1);
                s.pts = (int) Math.round(o.optDouble("points", 0));
                JSONObject driver = o.getJSONObject("Driver");
                s.name = driver.optString("givenName").replace("Andrea Kimi", "Kimi") + " " + driver.optString("familyName");
                JSONArray cons = o.optJSONArray("Constructors");
                if (cons != null && cons.length() > 0) {
                    JSONObject c = cons.getJSONObject(cons.length() - 1);
                    s.team = teamName(c.optString("name"));
                    s.teamId = c.optString("constructorId");
                }
                f.drivers.add(s);
            }
            JSONArray c = new JSONObject(teamsJson).getJSONObject("MRData")
                    .getJSONObject("StandingsTable").getJSONArray("StandingsLists").getJSONObject(0).getJSONArray("ConstructorStandings");
            for (int i = 0; i < c.length(); i++) {
                JSONObject o = c.getJSONObject(i);
                Standing s = new Standing();
                s.pos = o.optInt("position", i + 1);
                s.pts = (int) Math.round(o.optDouble("points", 0));
                s.name = teamName(o.getJSONObject("Constructor").optString("name"));
                s.teamId = o.getJSONObject("Constructor").optString("constructorId");
                f.teams.add(s);
            }
        } catch (Exception e) {
            AppLog.w("sports f1 standings: " + e.getMessage());
        }
        for (String t : Config.SPORTS_F1_TV.split(",")) if (!t.trim().isEmpty()) f.tv.add(t.trim());
        return f;
    }

    private static Race race(JSONObject o) throws Exception {
        Race r = new Race();
        r.round = o.optInt("round");
        r.name = raceName(o.optString("raceName"));
        JSONObject circuit = o.optJSONObject("Circuit");
        if (circuit != null) {
            r.circuit = circuit.optString("circuitName");
            JSONObject loc = circuit.optJSONObject("Location");
            if (loc != null) r.place = loc.optString("locality");
        }
        r.at = sessionTime(o);
        String[][] names = {{"FirstPractice", Ui.tr("Practice 1")}, {"SecondPractice", Ui.tr("Practice 2")}, {"ThirdPractice", Ui.tr("Practice 3")},
                {"SprintQualifying", Ui.tr("Sprint qualifying")}, {"Sprint", Ui.tr("Sprint")}, {"Qualifying", Ui.tr("Qualifying")}};
        for (String[] n : names) {
            JSONObject s = o.optJSONObject(n[0]);
            if (s != null && s.has("date")) r.sessions.add(new Session(n[1], sessionTime(s)));
        }
        r.sessions.add(new Session(Ui.tr("Race"), r.at));
        java.util.Collections.sort(r.sessions, (x, y) -> Long.compare(x.at, y.at));
        return r;
    }

    private static long sessionTime(JSONObject o) throws Exception {
        String time = o.optString("time", "12:00:00Z").replace("Z", "");
        if (time.length() > 5) time = time.substring(0, 5);
        return iso(o.getString("date") + "T" + time + "Z");
    }

    private static String raceName(String name) {
        String n = name.replace(" Grand Prix", "");
        switch (n) {
            case "United States": return "GP dos Estados Unidos";
            case "Mexico City": return "GP da Cidade do México";
            case "Brazilian": case "São Paulo": return "GP de São Paulo";
            case "Las Vegas": return "GP de Las Vegas";
            case "Qatar": return "GP do Catar";
            case "Abu Dhabi": return "GP de Abu Dhabi";
            case "Australian": return "GP da Austrália";
            case "Chinese": return "GP da China";
            case "Japanese": return "GP do Japão";
            case "Bahrain": return "GP do Bahrein";
            case "Saudi Arabian": return "GP da Arábia Saudita";
            case "Miami": return "GP de Miami";
            case "Canadian": return "GP do Canadá";
            case "Monaco": return "GP de Mônaco";
            case "Spanish": case "Barcelona-Catalunya": return "GP da Espanha";
            case "Austrian": return "GP da Áustria";
            case "British": return "GP da Inglaterra";
            case "Belgian": return "GP da Bélgica";
            case "Hungarian": return "GP da Hungria";
            case "Dutch": return "GP da Holanda";
            case "Italian": return "GP da Itália";
            case "Madrid": return "GP de Madri";
            case "Azerbaijan": return "GP do Azerbaijão";
            case "Singapore": return "GP de Singapura";
            default: return "GP " + n;
        }
    }

    private static String teamName(String n) {
        return n.replace(" F1 Team", "").replace("RB", "Racing Bulls").replace("Racing Bulls Racing Bulls", "Racing Bulls");
    }

    /** Team colors (the 2026 cars), by Jolpica's constructorId. */
    static int teamColor(String id) {
        switch (id) {
            case "mercedes": return 0xFF27F4D2;
            case "ferrari": return 0xFFE8002D;
            case "red_bull": return 0xFF3671C6;
            case "mclaren": return 0xFFFF8000;
            case "rb": return 0xFF6692FF;
            case "alpine": return 0xFFFF87BC;
            case "haas": return 0xFFB6BABD;
            case "audi": case "sauber": return 0xFFBB0A30;
            case "williams": return 0xFF64C4FF;
            case "aston_martin": return 0xFF229971;
            case "cadillac": return 0xFF1E1E1E;
            default: return 0xFF8E8E93;
        }
    }

    // MARK: - Where it's on TV (Sofascore)

    private static final Map<String, List<String>> TV_CACHE = new HashMap<>();
    private static final Map<String, Long> TV_AT = new HashMap<>();

    /** Fills each game's TV channels in your country (tmdb_region), from Sofascore. Cached 3 h per game. */
    private static void tv(Context ctx, String sofaTeam, String sport, Club club) {
        if (sofaTeam.isEmpty()) return;
        List<Match> games = new ArrayList<>();
        if (club.live != null) games.add(club.live);
        for (int i = 0; i < club.upcoming.size() && i < 4; i++) games.add(club.upcoming.get(i));
        if (games.isEmpty()) return;
        try {
            List<long[]> events = new ArrayList<>();   // {sofaId, start}
            String body = sofa(ctx, "/team/" + sofaTeam + "/events/next/0");
            JSONArray next = new JSONObject(body).optJSONArray("events");
            if (next == null || next.length() == 0) AppLog.w("sports tv " + sport + ": Sofascore answered " + body.substring(0, Math.min(200, body.length())).replace('\n', ' '));
            for (int i = 0; next != null && i < next.length(); i++) {
                JSONObject e = next.getJSONObject(i);
                events.add(new long[] {e.optLong("id"), e.optLong("startTimestamp") * 1000});
            }
            if (club.live != null) {
                JSONArray last = new JSONObject(sofa(ctx, "/team/" + sofaTeam + "/events/last/0")).optJSONArray("events");
                for (int i = 0; last != null && i < last.length(); i++) {
                    JSONObject e = last.getJSONObject(i);
                    events.add(new long[] {e.optLong("id"), e.optLong("startTimestamp") * 1000});
                }
            }
            AppLog.w("sports tv " + sport + ": " + events.size() + " Sofascore games, " + games.size() + " to match");
            SharedPreferences names = ctx.getSharedPreferences("tv-channels", Context.MODE_PRIVATE);
            String country = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
            long now = System.currentTimeMillis();
            for (Match m : games) {
                long id = 0;
                for (long[] e : events) if (Math.abs(e[1] - m.at) < 20 * 60_000L) id = e[0];
                if (id == 0) continue;
                String key = String.valueOf(id);
                Long at = TV_AT.get(key);
                if (at != null && now - at < 3 * 60 * 60_000L && TV_CACHE.containsKey(key)) {
                    m.tv.addAll(TV_CACHE.get(key));
                    continue;
                }
                List<String> list = new ArrayList<>();
                try {
                    JSONObject cc = new JSONObject(sofa(ctx, "/tv/event/" + id + "/country-channels")).optJSONObject("countryChannels");
                    JSONArray ids = cc != null ? cc.optJSONArray(country) : null;
                    for (int i = 0; ids != null && i < ids.length() && list.size() < 3; i++) {
                        String cid = String.valueOf(ids.optLong(i));
                        String name = names.getString(cid, null);
                        if (name == null) {
                            JSONObject v = new JSONObject(sofa(ctx, "/tv/channel/" + cid + "/event/" + id + "/votes"));
                            JSONObject ch = v.getJSONObject("tvChannelVotes").getJSONObject("tvChannel");
                            name = ch.optString("name");
                            names.edit().putString(cid, name).apply();
                        }
                        if (!name.isEmpty()) list.add(name.replace(" Brazil", "").replace(" Brasil", "").replace(" (BR)", "").trim());
                    }
                } catch (IOException e) {
                    AppLog.w("sports tv " + id + ": " + e.getMessage());   // 404: no channels yet for that game
                }
                AppLog.w("sports tv " + sport + " " + id + " (" + m.them().name + "): " + list + (sofaWeb ? " via web" : ""));
                TV_CACHE.put(key, list);
                TV_AT.put(key, now);
                m.tv.addAll(list);
            }
        } catch (Exception e) {
            AppLog.w("sports tv (" + sport + "): " + e.getMessage());
        }
        fromLists(ctx, games, club);
    }

    /**
     * Today's games the channel isn't known for: your IPTV lists' game-of-the-day entries name it
     * ("CAM x CRU - 19h30 - (Premiere)").
     */
    private static void fromLists(Context ctx, List<Match> games, Club club) {
        try {
            if (!Iptv.get(ctx).connected()) return;
            Lineup lineup = Lineup.get(ctx);
            for (Match m : games) {
                if (!m.tv.isEmpty() || !day(m.at).equals(Ui.tr("Today"))) continue;
                List<String> found = lineup.broadcastersFor(java.util.Arrays.asList(m.home.name, m.away.name, m.home.abbrev, m.away.abbrev, club.label), m.at);
                if (!found.isEmpty()) {
                    AppLog.w("sports tv " + m.them().name + ": " + found + " from the IPTV lists");
                    m.tv.addAll(found);
                }
            }
        } catch (Exception e) {
            AppLog.w("sports tv lists: " + e.getMessage());
        }
    }

    private static boolean sofaWeb;

    /**
     * Sofascore: straight first (its site's address, then the API's); once it refuses, from inside its site in an
     * off-screen WebView (like a browser on sofascore.com), which it answers.
     */
    private static String sofa(Context ctx, String path) throws IOException {
        if (!sofaWeb) {
            for (String host : new String[] {"https://www.sofascore.com/api/v1", "https://api.sofascore.com/api/v1"}) {
                try {
                    String body = get(host + path);
                    if (!refused(body)) return body;
                } catch (IOException e) {
                    if ("HTTP 404".equals(e.getMessage())) throw e;
                }
            }
            sofaWeb = true;
            AppLog.w("sofascore: refused straight, asking from its site");
        }
        String body = SofaWeb.fromSite(ctx, path);
        if (body.contains("\"code\": 404") || body.contains("\"code\":404")) throw new IOException("HTTP 404");
        if (refused(body)) AppLog.w("sofascore site: " + body.substring(0, Math.min(160, body.length())));
        return body;
    }

    private static boolean refused(String body) {
        return body.contains("\"error\"") && (body.contains("403") || body.contains("Forbidden"));
    }

    // MARK: - Network

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        if (url.contains("sofascore.com")) {
            c.setRequestProperty("Referer", "https://www.sofascore.com/");
            c.setRequestProperty("Origin", "https://www.sofascore.com");
        }
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Time

    /** "2026-10-26T23:00Z", "2026-10-24T17:00:00Z" → epoch ms. */
    private static long iso(String s) throws Exception {
        String t = s.replace("Z", "");
        if (t.length() > 16) t = t.substring(0, 16);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(t).getTime();
    }

    static String time(long at) {
        return new SimpleDateFormat("HH:mm", Ui.locale()).format(at);
    }

    /** "Hoje", "Amanhã", "Qua 29/10". */
    static String day(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(at);
        int days = (then.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 366 + then.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR);
        if (days == 0) return Ui.tr("Today");
        if (days == 1) return Ui.tr("Tomorrow");
        String d = new SimpleDateFormat("EEE dd/MM", Ui.locale()).format(at).replace(".", "");
        return d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
    }

    /** "Hoje 20:00", "Amanhã 19:30", "Qua 29/10 19:30". */
    static String when(long at) {
        return day(at) + " · " + time(at);
    }

    /** "em 3 dias", "em 5 h", "em 20 min". */
    static String until(long at) {
        long d = at - System.currentTimeMillis();
        if (d <= 0) return "";
        if (d < 60 * 60_000L) return Ui.trf("in {n} min", "n", d / 60_000 + 1);
        if (d < 24 * 60 * 60_000L) return Ui.trf("in {n} h", "n", d / (60 * 60_000L));
        return Ui.plural("plural|one:in {n} day|other:in {n} days", (int) (d / (24 * 60 * 60_000L)));
    }
}
