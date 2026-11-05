package io.freemytv;

import android.content.Context;

/** The tab pill's tabs: one list for the home screen and for Assistir, so the two never differ. */
final class NavTabs {
    static final int MAX = 8;

    private NavTabs() {}

    /** Tab ids in order, Home first. Light mode has no TMDB-only tabs (Para você, Descobrir). */
    static int[] ids(Context c) {
        boolean lists = Iptv.get(c).connected(), full = !Lite.on();
        int[] ids = new int[MAX];
        int n = 0;
        ids[n++] = MoviesActivity.T_HOME;
        if (full) ids[n++] = MoviesActivity.T_EXPLORE;
        if (full) ids[n++] = lists ? MoviesActivity.T_DISCOVER : MoviesActivity.T_RANKINGS;
        ids[n++] = MoviesActivity.T_IPTV_MOVIES;
        ids[n++] = MoviesActivity.T_IPTV_SHOWS;
        ids[n++] = MoviesActivity.T_LIVE;
        ids[n++] = MoviesActivity.T_SEARCH;
        ids[n++] = MoviesActivity.T_PREFS;
        return java.util.Arrays.copyOf(ids, n);
    }

    static String label(int id) {
        switch (id) {
            case MoviesActivity.T_HOME: return Ui.tr("tab|Home");
            case MoviesActivity.T_SEARCH: return Ui.tr("Search");
            case MoviesActivity.T_EXPLORE: return "Para você";
            case MoviesActivity.T_LIVE: return Ui.tr("Live");
            case MoviesActivity.T_IPTV_MOVIES: return Ui.tr("Movies");
            case MoviesActivity.T_IPTV_SHOWS: return Ui.tr("Shows");
            case MoviesActivity.T_DISCOVER: return "Descobrir";
            case MoviesActivity.T_RANKINGS: return "Rankings";
            default: return Ui.tr("Settings");
        }
    }
}
