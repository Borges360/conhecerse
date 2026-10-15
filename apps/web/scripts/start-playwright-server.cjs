const fs = require("node:fs");
const path = require("node:path");

const webDir = path.resolve(__dirname, "..");
const standaloneDir = path.join(webDir, ".next", "standalone", "apps", "web");

for (const assetDir of ["static", "public"]) {
  const source = path.join(webDir, assetDir === "static" ? ".next" : "", assetDir);
  const destination = path.join(
    standaloneDir,
    assetDir === "static" ? ".next" : "",
    assetDir,
  );
  if (fs.existsSync(source)) {
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.cpSync(source, destination, { recursive: true, force: true });
  }
}

process.env.HOSTNAME = "127.0.0.1";
process.env.PORT = "3017";
require(path.join(standaloneDir, "server.js"));
