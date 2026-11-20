/*! Campex tracking SDK v1 — https://campex.app
 * <script src="https://<campex-api-host>/campex.js" data-client-id="CLIENT_ID" data-pixel-id="PIXEL_ID" async></script>
 *
 * Privacy: with data-consent-mode="required" (default) nothing is stored or sent until Campex.consent(...) grants it.
 * No internal keys are used; the endpoint only accepts traffic from domains configured on the pixel.
 */
(function (window, document) {
  "use strict";
  if (window.Campex && window.Campex.__loaded) return;

  var script = document.currentScript || (function () {
    var s = document.querySelectorAll("script[data-client-id][data-pixel-id]");
    return s[s.length - 1];
  })();
  if (!script) return;

  var cfg = {
    clientId: script.getAttribute("data-client-id"),
    pixelId: script.getAttribute("data-pixel-id"),
    endpoint: (script.getAttribute("data-endpoint") || new URL(script.src).origin).replace(/\/+$/, ""),
    consentMode: script.getAttribute("data-consent-mode") === "not_required" ? "not_required" : "required",
    autoPageView: script.getAttribute("data-auto-pageview") !== "false",
    source: script.getAttribute("data-source") === "landing_page" ? "landing_page" : "website",
    policyVersion: script.getAttribute("data-policy-version") || null
  };
  var BROWSER_EVENTS = ["PageView", "ViewContent", "Lead", "Contact", "CompleteRegistration", "Schedule"];
  var CONSENT_KEY = "_cx_consent", VID = "_cx_vid", SID = "_cx_sid", ATTR = "_cx_attr";
  var queue = [];
  var consent = readConsent();

  function readConsent() {
    if (cfg.consentMode === "not_required") {
      return { analytics_storage: "granted", ad_storage: "granted", ad_user_data: "granted" };
    }
    try { return JSON.parse(window.localStorage.getItem(CONSENT_KEY)) || null; } catch (e) { return null; }
  }
  function granted(key) { return !!consent && consent[key] === "granted"; }

  function uuid() {
    if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID();
    var b = new Uint8Array(16); window.crypto.getRandomValues(b);
    b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
    var h = Array.prototype.map.call(b, function (x) { return ("0" + x.toString(16)).slice(-2); }).join("");
    return h.slice(0, 8) + "-" + h.slice(8, 12) + "-" + h.slice(12, 16) + "-" + h.slice(16, 20) + "-" + h.slice(20);
  }
  function getCookie(name) {
    var m = document.cookie.match(new RegExp("(?:^|; )" + name.replace(/[.$?*|{}()[\]\\/+^]/g, "\\$&") + "=([^;]*)"));
    return m ? decodeURIComponent(m[1]) : null;
  }
  function setCookie(name, value, days) {
    var secure = location.protocol === "https:" ? "; Secure" : "";
    document.cookie = name + "=" + encodeURIComponent(value) + "; Max-Age=" + days * 86400 + "; Path=/; SameSite=Lax" + secure;
  }

  function visitorId() {
    if (!granted("analytics_storage")) return null;
    var v = getCookie(VID);
    if (!v) { v = uuid(); setCookie(VID, v, 390); }
    return v;
  }
  function sessionId() {
    if (!granted("analytics_storage")) return null;
    try {
      var now = Date.now(), raw = JSON.parse(window.sessionStorage.getItem(SID) || "null");
      if (!raw || now - raw.t > 30 * 60 * 1000) raw = { id: uuid(), t: now };
      raw.t = now; window.sessionStorage.setItem(SID, JSON.stringify(raw));
      return raw.id;
    } catch (e) { return null; }
  }

  // Attribution from the landing URL. Kept in memory until analytics consent, then in sessionStorage.
  var params = new URLSearchParams(location.search);
  var landing = {
    fbclid: params.get("fbclid"),
    utm_source: params.get("utm_source"), utm_medium: params.get("utm_medium"),
    utm_campaign: params.get("utm_campaign"), utm_content: params.get("utm_content"), utm_term: params.get("utm_term"),
    campaign_id: numeric(params.get("campaign_id")), adset_id: numeric(params.get("adset_id")), ad_id: numeric(params.get("ad_id")),
    landing_page: location.origin + location.pathname, referrer_url: document.referrer || null
  };
  function numeric(v) { return v && /^[0-9]{1,128}$/.test(v) ? v : null; }
  function attribution() {
    var stored = null;
    try { stored = JSON.parse(window.sessionStorage.getItem(ATTR) || "null"); } catch (e) { stored = null; }
    var hasNew = landing.fbclid || landing.utm_source || landing.campaign_id || landing.ad_id;
    var attr = hasNew || !stored ? landing : stored;
    if (granted("analytics_storage")) {
      try { window.sessionStorage.setItem(ATTR, JSON.stringify(attr)); } catch (e) { /* storage blocked */ }
    }
    return attr;
  }
  function clickIds(attr) {
    if (!granted("ad_storage")) return { fbc: null, fbp: null };
    var fbc = getCookie("_fbc"), fbp = getCookie("_fbp");
    if (!fbc && attr.fbclid) { fbc = "fb.1." + Date.now() + "." + attr.fbclid; setCookie("_fbc", fbc, 90); }
    if (!fbp) { fbp = "fb.1." + Date.now() + "." + Math.floor(Math.random() * 1e10); setCookie("_fbp", fbp, 90); }
    return { fbc: fbc, fbp: fbp };
  }

  function normalize(key, value) {
    if (value == null) return null;
    var v = String(value).trim().toLowerCase();
    if (!v) return null;
    if (/^[a-f0-9]{64}$/.test(v)) return v;
    if (key === "em") return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v) ? v : null;
    if (key === "ph") { v = v.replace(/\D/g, "").replace(/^0+/, ""); if (v.length === 10) v = "91" + v; return v.length >= 11 && v.length <= 15 ? v : null; }
    if (key === "fn" || key === "ln" || key === "ct") return v.replace(/[\s\p{P}]/gu, "") || null;
    if (key === "zp") return v.replace(/\s/g, "") || null;
    if (key === "st" || key === "country") return v.replace(/[^a-z]/g, "") || null;
    if (key === "external_id") return v;
    return null;
  }
  function sha256(text) {
    if (/^[a-f0-9]{64}$/.test(text)) return Promise.resolve(text);
    return window.crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)).then(function (buf) {
      return Array.prototype.map.call(new Uint8Array(buf), function (x) { return ("0" + x.toString(16)).slice(-2); }).join("");
    });
  }
  // Raw identifiers never leave the browser: they are normalized and SHA-256 hashed here first.
  function hashUserData(userData) {
    if (!userData || !granted("ad_user_data")) return Promise.resolve(null);
    var keys = Object.keys(userData), out = {};
    return Promise.all(keys.map(function (k) {
      var n = normalize(k, userData[k]);
      return n ? sha256(n).then(function (h) { out[k] = h; }) : null;
    })).then(function () { return Object.keys(out).length ? out : null; });
  }

  function post(path, body) {
    return window.fetch(cfg.endpoint + path, {
      method: "POST", mode: "cors", credentials: "omit", keepalive: true,
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body)
    }).catch(function () { return null; });
  }

  function send(item) {
    var attr = attribution(), ids = clickIds(attr);
    return hashUserData(item.userData).then(function (hashed) {
      var body = {
        client_id: cfg.clientId, pixel_id: cfg.pixelId, event_name: item.name, event_id: item.eventId,
        event_time: item.time, event_source: cfg.source, visitor_id: visitorId(), session_id: sessionId(),
        event_source_url: location.href.split("#")[0], referrer_url: attr.referrer_url, landing_page: attr.landing_page,
        fbclid: granted("ad_storage") ? attr.fbclid : null, fbc: ids.fbc, fbp: ids.fbp,
        utm_source: attr.utm_source, utm_medium: attr.utm_medium, utm_campaign: attr.utm_campaign,
        utm_content: attr.utm_content, utm_term: attr.utm_term,
        campaign_id: attr.campaign_id, adset_id: attr.adset_id, ad_id: attr.ad_id,
        consent: consent, user_data: hashed, custom_data: item.customData || null
      };
      return post("/api/tracking/events", body);
    });
  }

  function track(name, customData, options) {
    if (BROWSER_EVENTS.indexOf(name) < 0) { if (window.console) console.warn("Campex: unsupported event " + name); return null; }
    var item = {
      name: name, customData: customData || null, userData: options && options.userData,
      eventId: (options && options.eventId) || uuid(), time: Math.floor(Date.now() / 1000)
    };
    // Same event_id on the Meta Pixel lets Meta deduplicate browser and server copies.
    if (typeof window.fbq === "function" && granted("ad_storage")) {
      try { window.fbq("track", name, customData || {}, { eventID: item.eventId }); } catch (e) { /* ignore */ }
    }
    if (cfg.consentMode === "required" && !consent) {
      if (queue.length < 20) queue.push(item);
      return item.eventId;
    }
    if (!granted("analytics_storage")) return null;
    send(item);
    return item.eventId;
  }

  function setConsent(decision) {
    consent = {
      analytics_storage: decision.analytics_storage === "granted" ? "granted" : "denied",
      ad_storage: decision.ad_storage === "granted" ? "granted" : "denied",
      ad_user_data: decision.ad_user_data === "granted" ? "granted" : "denied"
    };
    try { window.localStorage.setItem(CONSENT_KEY, JSON.stringify(consent)); } catch (e) { /* storage blocked */ }
    var vid = granted("analytics_storage") ? visitorId() : null;
    if (vid) {
      post("/api/tracking/consent", {
        client_id: cfg.clientId, pixel_id: cfg.pixelId, visitor_id: vid, policy_version: cfg.policyVersion,
        analytics_storage: consent.analytics_storage, ad_storage: consent.ad_storage, ad_user_data: consent.ad_user_data
      });
    }
    var pending = queue.splice(0, queue.length);
    if (granted("analytics_storage")) pending.forEach(send);
  }

  window.Campex = {
    __loaded: true,
    track: track,
    consent: setConsent,
    grantConsent: function () { setConsent({ analytics_storage: "granted", ad_storage: "granted", ad_user_data: "granted" }); },
    denyConsent: function () { setConsent({ analytics_storage: "denied", ad_storage: "denied", ad_user_data: "denied" }); },
    hasConsent: function () { return granted("analytics_storage"); },
    /** Attach to lead forms (e.g. POST /api/ad-leads) so the CRM lead keeps its campaign attribution. */
    getAttribution: function () {
      if (!granted("analytics_storage")) return {};
      var attr = attribution(), ids = clickIds(attr);
      return {
        visitor_id: visitorId(), session_id: sessionId(), fbclid: granted("ad_storage") ? attr.fbclid : null,
        fbc: ids.fbc, fbp: ids.fbp, utm_source: attr.utm_source, utm_medium: attr.utm_medium,
        utm_campaign: attr.utm_campaign, utm_content: attr.utm_content, utm_term: attr.utm_term,
        campaign_id: attr.campaign_id, adset_id: attr.adset_id, ad_id: attr.ad_id,
        landing_page: attr.landing_page, referrer: attr.referrer_url
      };
    }
  };

  if (cfg.autoPageView) track("PageView");
})(window, document);
