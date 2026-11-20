import { execFileSync } from "node:child_process";
import { appendFile, copyFile, cp, lstat, mkdir, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

function httpsOrigin(name) {
    const value = process.env[name];
    if (!value || !/^https:\/\/[a-z0-9.-]+\/?$/i.test(value)) {
        throw new Error(`${name} must be an HTTPS origin without a path.`);
    }
    return new URL(value).origin;
}

const supabaseUrl = httpsOrigin("SUPABASE_URL");
const workerUrl = httpsOrigin("ACCOUNT_API_URL");
const publishableKey = process.env.SUPABASE_PUBLISHABLE_KEY;
if (!/^sb_publishable_[A-Za-z0-9_-]+$/.test(publishableKey ?? "")) {
    throw new Error("Set SUPABASE_PUBLISHABLE_KEY to the project's sb_publishable_ key.");
}

const repo = fileURLToPath(new URL("../", import.meta.url));
const output = fileURLToPath(new URL("./dist", import.meta.url));
const previous = await lstat(output).catch((error) => {
    if (error.code !== "ENOENT") throw error;
});
if (previous?.isSymbolicLink()) throw new Error("dist must be a local directory.");
await rm(output, { recursive: true, force: true });

// Copy only tracked site files, so a local configuration file cannot enter the upload.
const tracked = execFileSync("git", ["ls-files", "-z", "--", "website"], { cwd: repo, encoding: "utf8" });
for (const file of tracked.split("\0").filter(Boolean)) {
    const source = join(repo, file);
    if (!(await lstat(source)).isFile()) throw new Error(`Expected a regular site file: ${file}`);
    const target = join(output, file.slice("website/".length));
    await mkdir(dirname(target), { recursive: true });
    await copyFile(source, target);
}
const account = join(output, "account");
await cp(new URL("./pages/", import.meta.url), account, { recursive: true });
await cp(new URL("./browser/", import.meta.url), account, { recursive: true });
await copyFile(new URL("./node_modules/@supabase/supabase-js/dist/umd/supabase.js", import.meta.url),
    join(account, "supabase.js"));
await writeFile(join(account, "config.js"),
    `export default ${JSON.stringify({ supabaseUrl, publishableKey, workerUrl })};\n`);

await appendFile(join(output, "_headers"), `
/account/*
  ! Content-Security-Policy
  Content-Security-Policy: default-src 'self'; connect-src 'self' ${supabaseUrl} ${workerUrl}; img-src 'self' ${supabaseUrl}; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
  ! Referrer-Policy
  Referrer-Policy: no-referrer
  X-Robots-Tag: noindex
  Cache-Control: no-store
`);
