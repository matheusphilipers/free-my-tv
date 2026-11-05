/*
 * Sports for web-app TVs, like the Android app's (Sports.java): the games of today in the main Brazilian and European
 * competitions, the next games of the football club picked on the TV, and the list of Série A and B clubs to pick from.
 * All from ESPN, no key. It only reads while the Sports sheet is open (and keeps the last answer, so it opens with games);
 * ESPN counts its days in US Eastern time, so it asks for the Eastern day(s) the TV's day touches and cuts the TV's day
 * out of them. Written for the Chromium of older TVs (webOS 3, Tizen 3): ES5.
 */
var Sports = (function () {
  'use strict';

  var API = 'https://site.api.espn.com/apis/site/v2/sports/soccer/';
  var TABLE = 'https://site.api.espn.com/apis/v2/sports/soccer/';
  var PRE = 0, LIVE = 1, FINAL = 2;
  var KEY = 'fmtv.sports';
  var HOUR = 3600000;

  /** The competitions of today's list, in the order they show: ESPN's code and the name. */
  var TODAY = [
    ['bra.1', 'Brasileirão Série A'], ['bra.2', 'Brasileirão Série B'], ['bra.copa_do_brazil', 'Copa do Brasil'],
    ['conmebol.libertadores', 'Libertadores'], ['conmebol.sudamericana', 'Sudamericana'],
    ['bra.camp.paulista', 'Paulistão'], ['bra.camp.carioca', 'Carioca'], ['bra.camp.mineiro', 'Mineiro'],
    ['bra.camp.gaucho', 'Gaúcho'], ['bra.copa_do_nordeste', 'Copa do Nordeste'],
    ['uefa.champions', 'Champions League'], ['uefa.europa', 'Europa League'], ['eng.1', 'Premier League'],
    ['esp.1', 'La Liga'], ['ita.1', 'Serie A (ITA)'], ['ger.1', 'Bundesliga'], ['fra.1', 'Ligue 1'],
    ['por.1', 'Liga Portugal'], ['fifa.world', 'World Cup'], ['fifa.worldq.conmebol', 'World Cup qualifying']
  ];

  /** ESPN's English names for the competitions, as the Android app shows them. */
  var LEAGUES = {'Brazilian Serie A': 'Brasileirão Série A', 'Brazilian Serie B': 'Brasileirão Série B',
    'CONMEBOL Libertadores': 'Libertadores', 'CONMEBOL Sudamericana': 'Sudamericana', 'UEFA Champions League': 'Champions League',
    'UEFA Europa League': 'Europa League', 'English Premier League': 'Premier League', 'Spanish LaLiga': 'La Liga',
    'Italian Serie A': 'Serie A (ITA)', 'German Bundesliga': 'Bundesliga', 'French Ligue 1': 'Ligue 1'};

  var store = load();
  var busyToday = false, busyTeam = false, failedAt = 0;

  function load() {
    try {
      var s = JSON.parse(localStorage.getItem(KEY));
      if (s && s.games) return s;
    } catch (e) {}
    return {games: [], at: 0, fullAt: 0, leagues: [], next: [], nextFor: '', nextAt: 0};
  }

  function save() { try { localStorage.setItem(KEY, JSON.stringify(store)); } catch (e) {} }

  /** GET a JSON document; done(error, json). */
  function get(url, done) {
    var x = new XMLHttpRequest(), over = false;
    x.open('GET', url, true);
    x.timeout = 12000;
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      if (x.status !== 200) { done(new Error('HTTP ' + x.status)); return; }
      var json;
      try { json = JSON.parse(x.responseText); } catch (e) { done(e); return; }
      done(null, json);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; done(new Error('no connection')); } };
    x.send();
  }

  /** "2026-11-02T13:00Z" → epoch ms (Date.parse is unsure about this form on old TVs). */
  function iso(s) {
    var m = /^(\d+)-(\d+)-(\d+)T(\d+):(\d+)(?::(\d+))?/.exec(s || '');
    return m ? Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)) : 0;
  }

  function score(t) {
    var s = t.score;
    if (s && typeof s === 'object') return s.displayValue === undefined ? '0' : String(s.displayValue);
    return s === undefined || s === null ? '' : String(s);
  }

  /** One ESPN event as a game; with a team id, only that club's games (null for the others). */
  function parse(ev, teamId) {
    var comp = ev.competitions && ev.competitions[0];
    if (!comp || !comp.competitors) return null;
    var m = {home: {id: '', name: ''}, away: {id: '', name: ''}, homeScore: '', awayScore: '', clock: '', half: false,
      state: PRE, at: iso(ev.date || comp.date), comp: '', weHome: false}, found = false;
    for (var i = 0; i < comp.competitors.length; i++) {
      var t = comp.competitors[i], team = t.team || {};
      var x = {id: String(t.id || team.id || ''), name: team.shortDisplayName || team.displayName || ''};
      if (t.homeAway === 'home') { m.home = x; m.homeScore = score(t); } else { m.away = x; m.awayScore = score(t); }
      if (teamId && x.id === teamId) { found = true; m.weHome = t.homeAway === 'home'; }
    }
    if (teamId && !found) return null;
    var status = comp.status || ev.status, type = status && status.type;
    var state = type ? type.state : 'pre';
    m.state = state === 'in' ? LIVE : state === 'post' ? FINAL : PRE;
    if (m.state === LIVE) {
      m.half = /^(HT|Halftime)$/i.test(type.shortDetail || '');
      m.clock = status.displayClock || '';
    }
    if (ev.league && ev.league.name) m.comp = LEAGUES[ev.league.name] || ev.league.name;
    return m;
  }

  // MARK: - Today's games

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function stamp(t) { var d = new Date(t); return d.getUTCFullYear() + pad(d.getUTCMonth() + 1) + pad(d.getUTCDate()); }

  /** The TV's day: midnight to midnight, local time. */
  function dayWindow() {
    var d = new Date();
    d.setHours(0, 0, 0, 0);
    var from = d.getTime();
    d.setDate(d.getDate() + 1);
    return {from: from, to: d.getTime()};
  }

  function anyLive() {
    for (var i = 0; i < store.games.length; i++) if (store.games[i].state === LIVE) return true;
    return false;
  }

  /** Every 10 min; every minute while a game is on. */
  function every() { return anyLive() ? 60000 : 10 * 60000; }

  /** Reads the competitions (all of them every 10 min; in between only those that had games); done(changed). */
  function refreshToday(done) {
    var now = Date.now();
    if (busyToday || (now - store.at < every() && now >= store.at) || (failedAt && now - failedAt < 2 * 60000 && now >= failedAt)) { done(false); return; }
    busyToday = true;
    var w = dayWindow(), dates = [stamp(w.from - 6 * HOUR)], last = stamp(w.to - 1 - 4 * HOUR);
    if (last !== dates[0]) dates.push(last);
    var full = now - store.fullAt > 10 * 60000 || now < store.fullAt || !store.leagues.length;
    var jobs = [];
    for (var i = 0; i < TODAY.length; i++) {
      if (!full && store.leagues.indexOf(i) < 0) continue;
      for (var d = 0; d < dates.length; d++) jobs.push([i, dates[d]]);
    }
    var left = jobs.length, failed = 0, found = [];
    function one(job) {
      get(API + TODAY[job[0]][0] + '/scoreboard?limit=100&dates=' + job[1], function (err, body) {
        if (err) failed++;
        else {
          for (var e = 0; body.events && e < body.events.length; e++) {
            var m = null;
            try { m = parse(body.events[e], null); } catch (x) {}
            if (!m || m.at < w.from || m.at >= w.to) continue;
            m.comp = TODAY[job[0]][1];
            m.order = job[0];
            found.push(m);
          }
        }
        if (--left > 0) return;
        busyToday = false;
        if (failed) { failedAt = Date.now(); done(false); return; }   // keeps what it had; tries again in 2 min
        failedAt = 0;
        var seen = {}, games = [], leagues = [];
        for (var g = 0; g < found.length; g++) {
          var key = found[g].order + '|' + found[g].home.id + '|' + found[g].away.id + '|' + found[g].at;
          if (seen[key]) continue;
          seen[key] = true;
          games.push(found[g]);
          if (leagues.indexOf(found[g].order) < 0) leagues.push(found[g].order);
        }
        games.sort(function (a, b) { return a.order !== b.order ? a.order - b.order : a.at - b.at; });
        store.games = games;
        store.at = Date.now();
        if (full) { store.fullAt = store.at; store.leagues = leagues; }
        save();
        done(true);
      });
    }
    if (!left) { busyToday = false; done(false); return; }
    for (var j = 0; j < jobs.length; j++) one(jobs[j]);
  }

  // MARK: - The club's next games

  function refreshTeam(done) {
    var team = Prefs.soccer(), now = Date.now();
    if (!team || !team.id) { done(false); return; }
    if (busyTeam || (store.nextFor === team.id && now - store.nextAt < every() && now >= store.nextAt)) { done(false); return; }
    busyTeam = true;
    get(API + 'all/teams/' + team.id + '/schedule?fixture=true', function (err, body) {
      busyTeam = false;
      if (err) { done(false); return; }
      var next = [];
      for (var i = 0; body.events && i < body.events.length; i++) {
        var m = null;
        try { m = parse(body.events[i], team.id); } catch (x) {}
        if (m && m.state !== FINAL && m.at > Date.now() - 3 * HOUR) next.push(m);
      }
      next.sort(function (a, b) { return a.at - b.at; });
      store.next = next.slice(0, 3);
      store.nextFor = team.id;
      store.nextAt = Date.now();
      save();
      done(true);
    });
  }

  // MARK: - The clubs to pick from

  /** Série A and B, as {label, id, league, name}; done(error, list). */
  function clubs(done) {
    var leagues = [['bra.1', 'Série A'], ['bra.2', 'Série B']], out = [], left = leagues.length, failed = null;
    function part(i) {
      // The table lists the league's clubs (ESPN's "teams" answers without CORS headers, and a web app needs them).
      get(TABLE + leagues[i][0] + '/standings', function (err, body) {
        if (err) failed = err;
        else {
          var list = [];
          try {
            var holder = body.children && body.children.length ? body.children[0] : body, entries = holder.standings.entries;
            for (var t = 0; t < entries.length; t++) {
              var name = entries[t].team.displayName;
              list.push({label: name + ' · ' + leagues[i][1], id: String(entries[t].team.id), league: leagues[i][0], name: name});
            }
          } catch (e) { failed = e; }
          list.sort(function (a, b) { return a.name.toLowerCase() < b.name.toLowerCase() ? -1 : 1; });
          out[i] = list;
        }
        if (--left > 0) return;
        if (failed || !out[0] || !out[1]) { done(failed || new Error('no clubs')); return; }
        done(null, out[0].concat(out[1]));
      });
    }
    for (var i = 0; i < leagues.length; i++) part(i);
  }

  // MARK: - What the sheet shows

  function rank(m) { return m.state === LIVE ? 0 : m.state === PRE ? 1 : 2; }

  /** Today's games for the list: on now first, then the next ones by time, then the finished ones (the last first). */
  function games() {
    var w = dayWindow(), out = [];
    for (var i = 0; i < store.games.length; i++) if (store.games[i].at >= w.from && store.games[i].at < w.to) out.push(store.games[i]);
    out.sort(function (a, b) {
      if (rank(a) !== rank(b)) return rank(a) - rank(b);
      if (a.at !== b.at) return rank(a) === 2 ? b.at - a.at : a.at - b.at;
      return a.order - b.order;
    });
    return out;
  }

  function next() {
    var team = Prefs.soccer();
    return team && store.nextFor === team.id ? store.next : [];
  }

  return {
    PRE: PRE, LIVE: LIVE, FINAL: FINAL,
    /** The Sports shortcut shows when today's games are on or a club is picked. */
    on: function () { return Prefs.today() || !!(Prefs.soccer() && Prefs.soccer().id); },
    games: games,
    next: next,
    clubs: clubs,
    loading: function () { return busyToday && !store.games.length; },
    /** Reads what is stale (today's games when they're on, the club's next games); done() when something changed. */
    refresh: function (done) {
      if (Prefs.today()) refreshToday(function (c) { if (c) done(); });
      refreshTeam(function (c) { if (c) done(); });
    },
    /** The club or the games switch changed: read again at the next refresh. */
    changed: function () { store.at = 0; store.nextAt = 0; store.fullAt = 0; failedAt = 0; }
  };
})();
