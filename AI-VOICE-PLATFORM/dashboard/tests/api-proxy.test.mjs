import assert from "node:assert/strict";
import test from "node:test";
import handler from "../api/[...path].js";

function responseRecorder() {
  return {
    headers: {},
    statusCode: 200,
    body: undefined,
    setHeader(name, value) {
      this.headers[name.toLowerCase()] = value;
    },
    end(body) {
      this.body = body;
    },
  };
}

test("proxies OAuth routes, forwarding session cookies and Meta redirects", async () => {
  const previousUrl = process.env.CLIENT_SERVICE_URL;
  const previousFetch = globalThis.fetch;
  let sentRequest;
  process.env.CLIENT_SERVICE_URL = "https://lead.example.test";
  globalThis.fetch = async (url, options) => {
    sentRequest = { url: String(url), options };
    return new Response(null, {
      status: 302,
      headers: { location: "https://www.facebook.com/v27.0/dialog/oauth" },
    });
  };

  try {
    const response = responseRecorder();
    await handler({
      url: "/api/oauth/meta/login?from=channels",
      method: "GET",
      headers: { cookie: "CAMPEX_ADMIN_SESSION=session-value" },
    }, response);

    assert.equal(sentRequest.url, "https://lead.example.test/oauth/meta/login?from=channels");
    assert.equal(sentRequest.options.headers.get("cookie"), "CAMPEX_ADMIN_SESSION=session-value");
    assert.equal(sentRequest.options.redirect, "manual");
    assert.equal(response.statusCode, 302);
    assert.equal(response.headers.location, "https://www.facebook.com/v27.0/dialog/oauth");
  } finally {
    globalThis.fetch = previousFetch;
    if (previousUrl === undefined) delete process.env.CLIENT_SERVICE_URL;
    else process.env.CLIENT_SERVICE_URL = previousUrl;
  }
});

test("proxies ordinary API paths unchanged and does not cache them", async () => {
  const previousUrl = process.env.CLIENT_SERVICE_URL;
  const previousFetch = globalThis.fetch;
  let sentUrl;
  process.env.CLIENT_SERVICE_URL = "https://lead.example.test";
  globalThis.fetch = async (url) => {
    sentUrl = String(url);
    return new Response('{"ok":true}', { status: 200, headers: { "content-type": "application/json" } });
  };

  try {
    const response = responseRecorder();
    await handler({ url: "/api/clients?active=true", method: "GET", headers: {} }, response);
    assert.equal(sentUrl, "https://lead.example.test/api/clients?active=true");
    assert.equal(response.statusCode, 200);
    assert.equal(response.headers["cache-control"], "private, no-store");
    assert.equal(response.body.toString(), '{"ok":true}');
  } finally {
    globalThis.fetch = previousFetch;
    if (previousUrl === undefined) delete process.env.CLIENT_SERVICE_URL;
    else process.env.CLIENT_SERVICE_URL = previousUrl;
  }
});

test("maps login requests to Spring and returns the HttpOnly session cookie to Vercel", async () => {
  const previousUrl = process.env.CLIENT_SERVICE_URL;
  const previousFetch = globalThis.fetch;
  let sentRequest;
  process.env.CLIENT_SERVICE_URL = "https://lead.example.test";
  globalThis.fetch = async (url, options) => {
    sentRequest = { url: String(url), options };
    return new Response('{"email":"admin@example.test"}', {
      status: 200,
      headers: {
        "content-type": "application/json",
        "set-cookie": "CAMPEX_ADMIN_SESSION=opaque; Path=/; HttpOnly; Secure; SameSite=Lax",
      },
    });
  };

  try {
    const response = responseRecorder();
    await handler({
      url: "/api/auth/login",
      method: "POST",
      headers: { "content-type": "application/json" },
      body: { email: "admin@example.test", password: "not-a-real-password" },
    }, response);

    assert.equal(sentRequest.url, "https://lead.example.test/auth/login");
    assert.equal(sentRequest.options.body, JSON.stringify({
      email: "admin@example.test",
      password: "not-a-real-password",
    }));
    assert.match(response.headers["set-cookie"].toString(), /HttpOnly/);
    assert.match(response.headers["set-cookie"].toString(), /Secure/);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousUrl === undefined) delete process.env.CLIENT_SERVICE_URL;
    else process.env.CLIENT_SERVICE_URL = previousUrl;
  }
});
