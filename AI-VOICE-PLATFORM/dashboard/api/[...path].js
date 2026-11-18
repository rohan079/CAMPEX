const PATHS_TO_STRIP_API = ["/api/auth", "/api/oauth"];

function backendPath(pathname) {
  if (pathname === "/api/health") return "/health";
  for (const prefix of PATHS_TO_STRIP_API) {
    if (pathname === prefix || pathname.startsWith(`${prefix}/`)) return pathname.slice(4);
  }
  return pathname;
}

/**
 * Vercel invokes /api/[...path] with either the original pathname or a
 * `...path` query (e.g. oauth/meta/status). Nested /api/* routes 404 without
 * reconstructing the path from that query.
 */
function resolveIncoming(req) {
  const url = new URL(req.url || "/", "https://vercel.invalid");
  const catchAll = url.searchParams.get("...path");
  if (catchAll) {
    url.searchParams.delete("...path");
    const search = url.searchParams.toString();
    return {
      pathname: `/api/${catchAll}`,
      search: search ? `?${search}` : "",
    };
  }
  return { pathname: url.pathname, search: url.search };
}

function requestBody(req) {
  if (req.method === "GET" || req.method === "HEAD") return undefined;
  if (req.body === undefined || req.body === null) return undefined;
  if (typeof req.body === "string" || Buffer.isBuffer(req.body)) return req.body;
  return JSON.stringify(req.body);
}

function headerValue(req, name) {
  const value = req.headers[name];
  if (typeof value === "string") return value;
  if (Array.isArray(value) && typeof value[0] === "string") return value[0];
  return undefined;
}

// Campaign AI / Meta publish can wait on upstream model calls.
export const config = {
  maxDuration: 60,
};

export default async function handler(req, res) {
  let backend;
  try {
    backend = new URL(process.env.CLIENT_SERVICE_URL || process.env.LEAD_SERVICE_URL);
  } catch {
    res.statusCode = 503;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ detail: "CLIENT_SERVICE_URL is not configured as an absolute URL" }));
    return;
  }
  const localHttp = process.env.NODE_ENV !== "production"
    && ["localhost", "127.0.0.1"].includes(backend.hostname);
  if ((backend.protocol !== "https:" && !(backend.protocol === "http:" && localHttp))
      || backend.pathname !== "/" || backend.search || backend.hash) {
    res.statusCode = 503;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ detail: "CLIENT_SERVICE_URL must be an HTTPS origin without a path" }));
    return;
  }

  const incoming = resolveIncoming(req);
  const upstream = new URL(backendPath(incoming.pathname) + incoming.search, backend.origin);
  const headers = new Headers();
  for (const name of ["accept", "authorization", "content-type", "cookie", "x-ad-popup-token"]) {
    const value = headerValue(req, name);
    if (value) headers.set(name, value);
  }

  try {
    const response = await fetch(upstream, {
      method: req.method,
      headers,
      body: requestBody(req),
      redirect: "manual",
    });
    res.statusCode = response.status;
    res.setHeader("Cache-Control", "private, no-store");
    for (const name of ["content-type", "cache-control", "location", "retry-after", "etag"]) {
      const value = response.headers.get(name);
      if (value) res.setHeader(name, value);
    }
    const setCookies = response.headers.getSetCookie?.() || [];
    if (!setCookies.length) {
      const setCookie = response.headers.get("set-cookie");
      if (setCookie) setCookies.push(setCookie);
    }
    if (setCookies.length) res.setHeader("Set-Cookie", setCookies);
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    console.error("Client-service proxy failed", error instanceof Error ? error.name : "unknown error");
    res.statusCode = 502;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ detail: "The client service could not be reached" }));
  }
}
