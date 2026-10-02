# Shrinking the repository history (optional, run by the owner)

Every APK that was ever committed to `website/update/` is still stored in git history (the repository is about
64 MB because of it). Once APKs no longer go into git (`docs/RELEASES.md`, `KEEP_PAGES_APK=false`), you can remove
the old ones. **This rewrites history: every branch and clone changes, open pull requests must be recreated, and
the old history cannot be recovered from the repository.** Do it once, when nothing else is in flight.

1. Make sure a stable (`main`) build with `url` in `app.json` has been published and `KEEP_PAGES_APK` is `false`.
2. Make a safety copy: `git clone --mirror <repo-url> pisophone-backup.git`.
3. In a fresh clone: `pip install git-filter-repo`, then
   `git filter-repo --invert-paths --path-glob '*.apk'`
   This removes every APK ever committed. Old firmware images (`website/update/firmware-*.bin`) stay in history; add
   `--path-glob 'website/update/*.bin'` as well only if you also want those gone (the current ones are re-added by your next firmware release).
4. Add the remote again and force-push every branch: `git remote add origin <repo-url>`,
   `git push --force --all origin`, `git push --force --tags origin`.
5. Everyone re-clones. Cloudflare Pages redeploys from the new history. Branch protection on `main` may need to be
   switched off for the force-push and back on afterwards.
6. Check: `git count-objects -vH` should now report well under 10 MB.
