import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

// Feature 056, group 0 (d): what does the app's NanoHTTPD 2.3.1 do with a request body of a voice clip's size?
public class NanoProbe extends NanoHTTPD {
    static final int PORT = 18099;
    static final StringBuilder LOG = new StringBuilder();

    NanoProbe() { super(PORT); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    @Override
    public Response serve(IHTTPSession s) {
        String uri = s.getUri();
        String cl = s.getHeaders().get("content-length");
        String te = s.getHeaders().get("transfer-encoding");
        String note;
        try {
            if (uri.equals("/raw")) {
                // Read the body straight from the session's stream, the way a voice route would, counting as it goes.
                long want = cl == null ? -1 : Long.parseLong(cl);
                InputStream in = s.getInputStream();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[1024];
                long got = 0;
                long cap = 70_000;
                while (want < 0 || got < want) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, want < 0 ? buf.length : want - got));
                    if (n < 0) break;
                    md.update(buf, 0, n);
                    got += n;
                    if (got > cap) break;
                }
                note = "content-length=" + cl + " transfer-encoding=" + te + " read=" + got + " sha256=" + hex(md.digest()).substring(0, 16);
            } else if (uri.equals("/parsebody")) {
                Map<String, String> files = new HashMap<>();
                s.parseBody(files);
                StringBuilder sb = new StringBuilder("keys=" + files.keySet());
                for (Map.Entry<String, String> e : files.entrySet()) {
                    File f = new File(e.getValue());
                    sb.append(" ").append(e.getKey()).append("=").append(f.exists() ? "file(" + f.length() + " bytes)" : "text(" + e.getValue().length() + " chars)");
                }
                note = sb.toString();
            } else {
                note = "params=" + s.getParameters().keySet() + " payloadLen=" + (s.getParameters().containsKey("payload") ? s.getParameters().get("payload").get(0).length() : -1);
            }
        } catch (Throwable t) {
            note = "EXCEPTION " + t;
        }
        synchronized (LOG) { LOG.append(uri).append(": ").append(note).append("\n"); }
        System.out.println("SERVER SAW " + uri + ": " + note + " at t=" + (System.currentTimeMillis() % 100000));
        return newFixedLengthResponse(Response.Status.OK, "text/plain", note);
    }

    static byte[] clip(int n) {
        byte[] b = new byte[n];
        new Random(56).nextBytes(b);
        return b;
    }

    static String request(String head, byte[] body, int sendOnly, boolean chunked, boolean abort) throws Exception {
        try (Socket sock = new Socket("127.0.0.1", PORT)) {
            sock.setSoTimeout(8000);
            OutputStream out = sock.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (chunked) {
                int off = 0;
                while (off < body.length) {
                    int n = Math.min(1500, body.length - off);
                    out.write((Integer.toHexString(n) + "\r\n").getBytes());
                    out.write(body, off, n);
                    out.write("\r\n".getBytes());
                    off += n;
                }
                out.write("0\r\n\r\n".getBytes());
            } else {
                // Sent in 1436-byte pieces with a small pause, like a box relaying a browser's upload.
                int limit = sendOnly < 0 ? body.length : sendOnly;
                for (int off = 0; off < limit; off += 1436) {
                    out.write(body, off, Math.min(1436, limit - off));
                    out.flush();
                    Thread.sleep(2);
                }
            }
            out.flush();
            if (abort) { sock.close(); return "(client closed the socket after " + sendOnly + " bytes)"; }
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line, last = "";
            StringBuilder sb = new StringBuilder();
            while ((line = r.readLine()) != null) { sb.append(line).append(" | "); last = line; }
            return last;
        } catch (SocketTimeoutException e) {
            return "(client timed out waiting for an answer)";
        }
    }

    public static void main(String[] a) throws Exception {
        NanoProbe srv = new NanoProbe();
        srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        if (a.length > 0 && a[0].equals("server")) { Thread.sleep(80_000); System.exit(0); }
        byte[] clip = clip(60_000);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        System.out.println("sent sha256=" + hex(md.digest(clip)).substring(0, 16) + " (60000 bytes)");

        String q = "?payload=abc&hmac=def";
        String post = "POST /raw" + q + " HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\nContent-Length: 60000\r\nConnection: close\r\n\r\n";

        System.out.println("1 known length, 60000 bytes, in 1436-byte pieces:  " + request(post, clip, -1, false, false));
        System.out.println("2 chunked transfer-encoding, same bytes:            " + request(
            "POST /raw" + q + " HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", clip, -1, true, false));
        System.out.println("3 client stops after 30000 of 60000 and closes:     " + request(post, clip, 30_000, false, true));
        System.out.println("4 parseBody() on a raw octet-stream body:           " + request(
            "POST /parsebody" + q + " HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\nContent-Length: 60000\r\nConnection: close\r\n\r\n", clip, -1, false, false));
        // A signature in the URL is how the box sends today (a GET with payload=...&hmac=...). How long can it be?
        for (int len : new int[]{1000, 4000, 7000, 8000, 9000}) {
            String payload = "a".repeat(len);
            String h = "GET /params?payload=" + payload + "&hmac=def HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n";
            System.out.println("5 query string with payload of " + len + " chars:        " + request(h, new byte[0], -1, false, false));
        }
        Thread.sleep(500);
        System.out.println("--- what the server saw ---");
        System.out.print(LOG);
        srv.stop();
    }
}
