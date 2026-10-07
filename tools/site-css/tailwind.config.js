/** The website's stylesheet is built once, here, instead of loading Tailwind's Play CDN script on the provisioning page
 * (third-party code on a page that hands the box secret to phones). Rebuild after changing classes: npm ci && npm run build */
module.exports = {
  content: ["../../website/index.html"],
  theme: { extend: {} },
  plugins: [],
};
