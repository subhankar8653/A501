// Cloudflare Worker = your app's public address (https://<name>.<you>.workers.dev),
// free, no domain needed. It forwards everything to your VPS and hides the VPS.
// Worker Settings -> Variables and Secrets:
//   ORIGIN      e.g.  http://203-0-113-5.sslip.io      (your VPS IP with dashes + .sslip.io)
//   ORIGIN_KEY  same text as ORIGIN_KEY in backend/config.env
// Moving to a new VPS = just change ORIGIN here. Apps never notice.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const target = new URL(url.pathname + url.search, env.ORIGIN);
    const headers = new Headers(request.headers);
    headers.set("X-Origin-Key", env.ORIGIN_KEY);
    headers.delete("cf-connecting-ip");
    const hasBody = !["GET", "HEAD"].includes(request.method);
    try {
      const res = await fetch(target, {
        method: request.method,
        headers,
        body: hasBody ? request.body : undefined,
        redirect: "manual",
      });
      const out = new Headers(res.headers);
      out.delete("server");
      return new Response(res.body, { status: res.status, statusText: res.statusText, headers: out });
    } catch (e) {
      // 530 makes the app try its backup address (if you set one).
      return new Response("", { status: 530 });
    }
  },
};
