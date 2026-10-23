# ---------------------------------------------------------------------------------------------------------------------
# Packages from files (a first setup without the router's internet)
# ---------------------------------------------------------------------------------------------------------------------
# The setup program on the computer downloads the OpenWrt packages the setup needs and copies them to the router's RAM (the folder
# PISO_LOCAL_PKGS). The router does not trust them because they came from the computer: it checks them itself, the way "opkg update"
# does for a download: every package list ("Packages") against its signature with the router's own usign and keys, then every
# package file against the size and sha256 that signed list gives for it. Only then are they installed. Anything wrong: nothing
# is installed.

# local_pkg_index <Packages file>: one line per package, "<file name> <sha256> <size>" (the file name without its folder)
local_pkg_index() {
	awk '
		/^Filename:/ { f = $2; sub(".*/", "", f) }
		/^SHA256sum:/ { h = $2 }
		/^Size:/ { z = $2 }
		/^$/ { if (f != "") print f, h, z; f = ""; h = ""; z = "" }
		END { if (f != "") print f, h, z }
	' "$1"
}

# install_local_packages <folder>: the folder holds <source>/Packages + Packages.sig, the .ipk files and order.txt (one file name
# per line, dependencies first). Checks everything, then installs all of them with one opkg command.
install_local_packages() {
	_ld="$1"; _lkeys="$PISO_ROOT/etc/opkg/keys"
	step "Installing packages from the files copied to this router (it does not need the internet for this)"
	[ -f "$_ld/order.txt" ] || die "the package list order.txt is missing from $_ld: the copy from the computer is incomplete. Nothing was installed."
	[ -n "$(ls "$_lkeys" 2> /dev/null)" ] || die "this router has no package signing keys in /etc/opkg/keys, so the copied packages cannot be checked. Nothing was installed."
	_lidx="$_ld/.index.$$"; : > "$_lidx"
	for _lp in "$_ld"/*/Packages; do
		[ -f "$_lp" ] || continue
		[ -f "$_lp.sig" ] || { rm -f "$_lidx"; die "the signature of $_lp is missing. Nothing was installed."; }
		usign -V -m "$_lp" -P "$_lkeys" -x "$_lp.sig" > /dev/null 2>&1 || { rm -f "$_lidx"; die "the signature of the package list $_lp is not valid. Nothing was installed."; }
		local_pkg_index "$_lp" >> "$_lidx"
	done
	[ -s "$_lidx" ] || { rm -f "$_lidx"; if [ -s "$_ld/order.txt" ]; then die "no package list with a valid signature was copied. Nothing was installed."; fi; log "nothing to install: every package is there already"; return 0; }
	set --
	while IFS= read -r _lf; do
		[ -n "$_lf" ] || continue
		case "$_lf" in
			*/* | .* | *[!A-Za-z0-9._+-]*) rm -f "$_lidx"; die "order.txt names \"$_lf\", which is not a package file name. Nothing was installed." ;;
			*.ipk) ;;
			*) rm -f "$_lidx"; die "order.txt names \"$_lf\", which is not a package file name. Nothing was installed." ;;
		esac
		[ -f "$_ld/$_lf" ] || { rm -f "$_lidx"; die "$_lf is listed in order.txt but was not copied. Nothing was installed."; }
		_lwant=$(awk -v f="$_lf" '$1 == f { print $2, $3; exit }' "$_lidx")
		[ -n "$_lwant" ] || { rm -f "$_lidx"; die "$_lf is not in any signed package list. Nothing was installed."; }
		[ "$(sha256sum "$_ld/$_lf" | cut -d' ' -f1)" = "${_lwant%% *}" ] || { rm -f "$_lidx"; die "$_lf does not match the sha256 in the signed package list. Nothing was installed."; }
		[ "$(wc -c < "$_ld/$_lf" | tr -d ' ')" = "${_lwant##* }" ] || { rm -f "$_lidx"; die "$_lf has a different size than the signed package list says. Nothing was installed."; }
		set -- "$@" "$_ld/$_lf"
	done < "$_ld/order.txt"
	rm -f "$_lidx"
	if [ "$#" -eq 0 ]; then log "nothing to install: every package is there already"; return 0; fi
	log "checked: $# packages match the signed lists; installing"
	opkg install "$@" || die "could not install the copied packages"
}
