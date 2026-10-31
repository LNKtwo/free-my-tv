(function () {
  if (window.__tvNav) return;
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[tabindex]:not([tabindex="-1"]),' +
    'ha-switch,ha-control-switch,ha-entity-toggle,[role=button],[role=link],[role=checkbox],[role=switch],[role=tab],[role=menuitem],[role=option],[role=slider],[role=combobox],[role=radio]';
  var cur = null;
  var ring = document.createElement('div');
  ring.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:4px solid #18bcf2;border-radius:12px;box-shadow:0 0 18px #18bcf2;display:none;box-sizing:border-box';
  document.documentElement.appendChild(ring);

  function walk(root, out) {
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.matches(SEL) && !el.disabled) out.push(el);
      if (el.shadowRoot) walk(el.shadowRoot, out);
    }
    return out;
  }
  function deepPoint(x, y) {
    var el = document.elementFromPoint(x, y);
    while (el && el.shadowRoot) {
      var inner = el.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === el) break;
      el = inner;
    }
    return el;
  }
  function parent(n) { return n.assignedSlot || n.parentNode || n.host || null; }
  function holds(a, b) { for (var n = b; n; n = parent(n)) if (n === a) return true; return false; }
  function usable(el) {
    if (!el.isConnected) return false;
    if (el.tagName === 'HUI-TILE-CARD') return true;
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return false;
    var s = getComputedStyle(el);
    if (s.visibility === 'hidden' || s.pointerEvents === 'none' || (+s.opacity === 0 && el.tagName !== 'INPUT')) return false;
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return true;   // off screen: scrolled to when picked
    var hit = deepPoint(x, y);
    return !!hit && (holds(el, hit) || holds(hit, el));
  }
  // The controls themselves, not what holds them: a row with a switch has the switch to land on.
  function list() {
    var all = walk(document, []).filter(usable), holders = new Set();
    all.forEach(function (el) { for (var n = parent(el); n; n = parent(n)) holders.add(n); });
    var leaves = all.filter(function (el) { return !holders.has(el); });
    if (!tvPage() || dialogOpen()) return leaves;
    var cards = [], seen = new Set();
    walkTiles(document, cards);
    return cards.filter(function (c) {
      if (seen.has(c)) return false;
      seen.add(c);
      var r = c.getBoundingClientRect(), action = c._config && c._config.tap_action && c._config.tap_action.action;
      return r.width >= 8 && r.height >= 8 && action !== 'none';   // a tile that only shows a value isn't a stop
    });
  }
  function tvPage() { return location.pathname.indexOf('/tv-dashboard') === 0; }
  function dialogOpen() {
    var found = false;
    (function look(root) {
      var all = root.querySelectorAll('ha-more-info-dialog,ha-dialog,dialog[open]');
      for (var i = 0; i < all.length; i++) {
        var r = all[i].getBoundingClientRect();
        if (r.width > 0 && r.height > 0) found = true;
      }
      var every = root.querySelectorAll('*');
      for (var j = 0; j < every.length && !found; j++) if (every[j].shadowRoot) look(every[j].shadowRoot);
    })(document);
    return found;
  }
  function walkTiles(root, out) {
    var every = root.querySelectorAll('*');
    for (var i = 0; i < every.length; i++) {
      var el = every[i];
      if (el.tagName === 'HUI-TILE-CARD') out.push(el);
      else if (el.shadowRoot) walkTiles(el.shadowRoot, out);
    }
  }
  function center(el) { var r = el.getBoundingClientRect(); return {x: r.left + r.width / 2, y: r.top + r.height / 2, r: r}; }
  function show() {
    if (!cur || !cur.isConnected) { ring.style.display = 'none'; return; }
    var r = cur.getBoundingClientRect();
    ring.style.display = 'block';
    ring.style.left = (r.left - 4) + 'px'; ring.style.top = (r.top - 4) + 'px';
    ring.style.width = (r.width + 8) + 'px'; ring.style.height = (r.height + 8) + 'px';
  }
  function pick(el) {
    cur = el;
    try { el.scrollIntoView({block: 'nearest', inline: 'nearest'}); } catch (e) {}
    try { el.focus({preventScroll: true}); } catch (e) {}
    show();
  }
  function start() {
    var all = list(), active = document.activeElement;
    while (active && active.shadowRoot && active.shadowRoot.activeElement) active = active.shadowRoot.activeElement;
    if (active && all.indexOf(active) >= 0) return pick(active);
    all = all.filter(function (e) { var c = center(e); return c.y >= 0 && c.y < innerHeight && c.x >= 0 && c.x < innerWidth; });
    all.sort(function (a, b) { var p = center(a), q = center(b); return (p.y - q.y) || (p.x - q.x); });
    if (all[0]) pick(all[0]);
  }
  function move(key) {
    if (!cur || !usable(cur)) return start();
    var from = center(cur), best = null, bestScore = Infinity;
    list().forEach(function (el) {
      if (el === cur || holds(cur, el) || holds(el, cur)) return;
      var c = center(el), a = c.x - from.x, b = c.y - from.y, main, side;
      if (key === 'left') { main = -a; side = Math.abs(b); }
      else if (key === 'right') { main = a; side = Math.abs(b); }
      else if (key === 'up') { main = -b; side = Math.abs(a); }
      else { main = b; side = Math.abs(a); }
      if (main <= 8) return;   // a few pixels off isn't another place
      // Mostly in that direction first; anything further to the side only when nothing else is there.
      var score = main + 2.5 * side + (side > main * 1.5 ? 100000 : 0);
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (best) pick(best);
  }
  window.__tvNav = function (key) {
    if (key === 'ok') {
      if (!cur || !usable(cur)) start();
      if (!cur) return null;
      var c = center(cur);
      if (cur.tagName === 'INPUT' && /^(checkbox|radio|button|submit)$/.test(cur.type) === false) cur.focus();
      return {x: c.x / innerWidth, y: c.y / innerHeight};
    }
    move(key);
    return null;
  };
  if (tvPage()) {
    var vp = document.querySelector('meta[name=viewport]');
    if (vp) vp.setAttribute('content', 'width=1440');
  }
  setInterval(show, 250);
  setTimeout(function () { if (!cur) start(); }, 1200);
})();
