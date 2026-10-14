#ifndef ORIGIN_CHECK_H
#define ORIGIN_CHECK_H

// Which requests to the box's admin pages come from another website's page (host_tests/origin_check_test.cpp).
//
// A browser that has the admin password cached sends it with every request to the box, including one that a page on some other
// site makes behind the owner's back ("cross-site request forgery": a hidden form that posts to the box's address). Browsers
// say where such a request came from in the Origin header (always on a POST) or the Referer header. The box refuses a
// state-changing request whose Origin/Referer names a different site than the one the request is addressed to. A request
// with neither header (a script, curl, the router) is not a browser page and is judged by its credentials alone.

#include <cctype>
#include <string>

namespace origincheck {

// "http://192.168.1.50/path?x" -> "192.168.1.50"; "http://[fe80::1]:81/" -> "[fe80::1]:81"; empty when it is not a URL.
inline std::string hostOf(const std::string& url) {
    size_t scheme = url.find("://");
    if (scheme == std::string::npos) return "";
    size_t start = scheme + 3;
    size_t end = url.find_first_of("/?#", start);
    std::string host = url.substr(start, end == std::string::npos ? std::string::npos : end - start);
    size_t at = host.rfind('@');
    if (at != std::string::npos) host = host.substr(at + 1); // user-info is never part of the host
    return host;
}

inline std::string lower(std::string s) {
    for (char& c : s)
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

// `source` is the Origin header, or the Referer when there is no Origin (empty if neither was sent); `hostHeader` is the Host header.
inline bool isCrossSite(const std::string& source, const std::string& hostHeader) {
    if (source.empty()) return false;
    if (source == "null") return true; // a sandboxed or redirected page: its origin is hidden on purpose
    std::string from = hostOf(source);
    if (from.empty() || hostHeader.empty()) return true;
    return lower(from) != lower(hostHeader);
}

} // namespace origincheck

#endif // ORIGIN_CHECK_H
