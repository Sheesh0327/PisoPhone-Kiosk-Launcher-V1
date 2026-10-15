//! Guests send a file to the owner: the guest opens /send on the portal (no payment, nothing printed), picks a file and the router hands it
//! to the print server's inbox, where the owner sees it in the admin print console. The guest never connects to the print server: this
//! program receives the bytes, checks the size and the rate, and streams them on (nothing is kept here: the router has almost no flash).
//!
//! On only when /etc/coinslot.d/send.key (the print server's /etc/webprint/send.key) exists. The print server decides what it accepts
//! (file types, its own limits); the checks here only protect this router (size, speed, how many at once, how often per device).
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Uploads running at the same time on this router.
const MAX_PARALLEL: usize = 3;
/// How long one upload may take in all (slow phones, 10 MB).
const TOTAL_TIME: Duration = Duration::from_secs(180);
/// How long the router waits for the print server to connect or to answer.
const CONNECT_TIME: Duration = Duration::from_secs(5);
const ANSWER_TIME: Duration = Duration::from_secs(20);
/// How long a quiet guest connection may stay quiet before the upload is dropped.
const IDLE_TIME: Duration = Duration::from_secs(15);

#[derive(Debug, PartialEq)]
pub enum SendError {
    /// no key file: the feature is off
    Off,
    TooBig,
    /// the file name is missing
    NoName,
    /// this device sent enough files this hour
    Rate,
    /// too many uploads right now (or this device already has one running)
    Busy,
    /// the print server did not answer
    Down,
    /// the guest's connection ended or was too slow
    Incomplete,
    /// the print server said no, with its reason (file type, inbox full...)
    Refused(u16, String),
}

impl SendError {
    pub fn code(&self) -> &'static str {
        match self {
            SendError::Off => "OFF",
            SendError::TooBig => "TOO_BIG",
            SendError::NoName => "NO_NAME",
            SendError::Rate => "RATE",
            SendError::Busy => "BUSY",
            SendError::Down => "DOWN",
            SendError::Incomplete => "INCOMPLETE",
            SendError::Refused(..) => "REFUSED",
        }
    }
}

struct State {
    /// when each device last sent a file (seconds)
    sent: HashMap<String, Vec<u64>>,
    running: HashSet<String>,
    key: Option<(Instant, String)>,
}

pub struct Send {
    host: String,
    port: u16,
    key_file: String,
    max_bytes: u64,
    per_hour: usize,
    /// the owner's switch (console, Settings): off hides the button and refuses uploads even when the key file is there
    switch: AtomicBool,
    st: Mutex<State>,
}

/// A file name for the X-Filename header: letters, digits and . - _ stay, everything else is %-escaped, at most 120 bytes of name.
pub fn encode_name(raw: &str) -> String {
    let name = crate::util::url_decode(raw);
    let name = name.rsplit(['/', '\\']).next().unwrap_or("").trim();
    let mut out = String::new();
    for b in name.bytes().take(120) {
        if b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_' {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

impl Send {
    pub fn new(host: &str, port: u16, data_dir: &str, max_mb: u64, per_hour: usize, on: bool) -> Send {
        Send {
            host: host.to_string(),
            port,
            key_file: format!("{}/send.key", data_dir),
            max_bytes: max_mb.clamp(1, 50) * 1024 * 1024,
            per_hour,
            switch: AtomicBool::new(on),
            st: Mutex::new(State { sent: HashMap::new(), running: HashSet::new(), key: None }),
        }
    }

    /// The shared key, read again at most every 10 s (the owner can add or remove the file without a restart).
    fn key(&self) -> Option<String> {
        let mut g = self.st.lock().unwrap();
        if let Some((at, k)) = &g.key {
            if at.elapsed() < Duration::from_secs(10) {
                return if k.is_empty() { None } else { Some(k.clone()) };
            }
        }
        let k = fs::read_to_string(&self.key_file).map(|t| t.trim().to_string()).unwrap_or_default();
        let k = if k.len() >= 16 && k.bytes().all(|b| b.is_ascii_graphic()) { k } else { String::new() };
        g.key = Some((Instant::now(), k.clone()));
        if k.is_empty() {
            None
        } else {
            Some(k)
        }
    }

    /// Offered to guests: the owner's switch is on and the print server's key is installed.
    pub fn enabled(&self) -> bool {
        self.switch_on() && self.key().is_some()
    }

    pub fn switch_on(&self) -> bool {
        self.switch.load(Ordering::Relaxed)
    }

    pub fn set_switch(&self, on: bool) {
        self.switch.store(on, Ordering::Relaxed);
    }

    /// Is the print server's key installed (whatever the switch says)? The console shows it next to the switch.
    pub fn has_key(&self) -> bool {
        self.key().is_some()
    }

    pub fn max_bytes(&self) -> u64 {
        self.max_bytes
    }

    pub fn max_mb(&self) -> u64 {
        self.max_bytes / 1024 / 1024
    }

    /// Send `len` bytes read from `body` to the print server's inbox as `name` (already encoded by `encode_name`) for `device`.
    pub fn upload(&self, device: &str, name: &str, len: u64, body: &mut dyn Read, now: u64) -> Result<(), SendError> {
        if !self.switch_on() {
            return Err(SendError::Off);
        }
        let key = self.key().ok_or(SendError::Off)?;
        if len == 0 || len > self.max_bytes {
            return Err(SendError::TooBig);
        }
        if name.is_empty() {
            return Err(SendError::NoName);
        }
        {
            let mut g = self.st.lock().unwrap();
            let recent = g.sent.entry(device.to_string()).or_default();
            recent.retain(|t| now.saturating_sub(*t) < 3600);
            if recent.len() >= self.per_hour {
                return Err(SendError::Rate);
            }
            if g.running.len() >= MAX_PARALLEL || !g.running.insert(device.to_string()) {
                return Err(SendError::Busy);
            }
        }
        let result = self.relay(&key, device, name, len, body);
        let mut g = self.st.lock().unwrap();
        g.running.remove(device);
        if result.is_ok() {
            g.sent.entry(device.to_string()).or_default().push(now);
        }
        result
    }

    fn relay(&self, key: &str, device: &str, name: &str, len: u64, body: &mut dyn Read) -> Result<(), SendError> {
        let addr = (self.host.as_str(), self.port).to_socket_addrs().ok().and_then(|mut a| a.next()).ok_or(SendError::Down)?;
        let mut up = TcpStream::connect_timeout(&addr, CONNECT_TIME).map_err(|_| SendError::Down)?;
        let _ = up.set_nodelay(true);
        let _ = up.set_write_timeout(Some(ANSWER_TIME));
        let safe_device: String =
            device.chars().filter(|c| c.is_ascii_alphanumeric() || *c == ':' || *c == '.' || *c == '-').take(40).collect();
        let head = format!(
            "POST /api/inbox/upload HTTP/1.1\r\nHost: {}\r\nX-Router-Key: {}\r\nX-Filename: {}\r\nX-Device: {}\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            self.host, key, name, safe_device, len
        );
        up.write_all(head.as_bytes()).map_err(|_| SendError::Down)?;
        let started = Instant::now();
        let mut left = len;
        let mut cut = false;
        let mut buf = [0u8; 8192];
        while left > 0 {
            if started.elapsed() > TOTAL_TIME {
                return Err(SendError::Incomplete);
            }
            let want = buf.len().min(left as usize);
            match body.read(&mut buf[..want]) {
                Ok(0) => return Err(SendError::Incomplete),
                Ok(n) => {
                    // (the print server may refuse early, for a file type say, and close: its answer is read below)
                    if up.write_all(&buf[..n]).is_err() {
                        cut = true;
                        break;
                    }
                    left -= n as u64;
                }
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => return Err(SendError::Incomplete),
            }
        }
        let _ = up.set_read_timeout(Some(ANSWER_TIME));
        let mut answer = Vec::new();
        let _ = up.take(4096).read_to_end(&mut answer);
        let text = String::from_utf8_lossy(&answer);
        let status: u16 = text.split_whitespace().nth(1).and_then(|s| s.parse().ok()).ok_or(SendError::Down)?;
        if status == 200 && !cut {
            return Ok(());
        }
        let msg = crate::util::jget(text.split("\r\n\r\n").nth(1).unwrap_or(""), "error");
        let msg: String = msg.chars().filter(|c| !c.is_control()).take(100).collect();
        Err(SendError::Refused(status, msg))
    }
}

/// The idle time a guest's connection may stay quiet (the caller sets it on the socket).
pub fn idle_time() -> Duration {
    IDLE_TIME
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::net::TcpListener;
    use std::thread;

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-send-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    /// A fake print server: reads one request, answers with `status` and `json`, returns what it got.
    fn fake_server(status: &'static str, json: &'static str) -> (u16, thread::JoinHandle<(String, Vec<u8>)>) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let h = thread::spawn(move || {
            let (mut s, _) = l.accept().unwrap();
            let mut got = Vec::new();
            let mut chunk = [0u8; 4096];
            let (mut head_end, mut want) = (None, 0usize);
            loop {
                let n = s.read(&mut chunk).unwrap();
                if n == 0 {
                    break;
                }
                got.extend_from_slice(&chunk[..n]);
                if head_end.is_none() {
                    if let Some(i) = got.windows(4).position(|w| w == b"\r\n\r\n") {
                        head_end = Some(i + 4);
                        let head = String::from_utf8_lossy(&got[..i]).to_lowercase();
                        want = head.split("content-length:").nth(1).unwrap().split("\r\n").next().unwrap().trim().parse().unwrap();
                    }
                }
                if let Some(h) = head_end {
                    if got.len() >= h + want {
                        break;
                    }
                }
            }
            let h = head_end.unwrap_or(got.len());
            let _ = write!(
                s,
                "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                status,
                json.len(),
                json
            );
            (String::from_utf8_lossy(&got[..h]).into_owned(), got[h..].to_vec())
        });
        (port, h)
    }

    fn make(port: u16, dir: &str) -> Send {
        fs::write(format!("{}/send.key", dir), "k".repeat(40) + "\n").unwrap();
        Send::new("127.0.0.1", port, dir, 1, 2, true)
    }

    #[test]
    fn names_are_made_safe_for_a_header() {
        assert_eq!(encode_name("My%20Resume.pdf"), "My%20Resume.pdf");
        assert_eq!(encode_name("..%2F..%2Fetc%2Fpasswd.pdf"), "passwd.pdf");
        assert_eq!(encode_name("a b\r\nX-Evil: 1.pdf"), "a%20b%0D%0AX-Evil%3A%201.pdf");
        assert_eq!(encode_name("C:\\Users\\me\\doc.docx"), "doc.docx");
        assert_eq!(encode_name("%E2%82%B1.pdf"), "%E2%82%B1.pdf");
        assert_eq!(encode_name(&"a".repeat(500)).len(), 120);
        assert_eq!(encode_name(""), "");
    }

    #[test]
    fn a_file_is_streamed_to_the_print_server_with_the_key() {
        let dir = tmp("ok");
        let (port, srv) = fake_server("200 OK", "{\"success\":true}");
        let s = make(port, &dir);
        let data: Vec<u8> = (0..30_000u32).map(|i| (i % 251) as u8).collect();
        let mut body = Cursor::new(data.clone());
        assert_eq!(s.upload("aa:bb:cc:00:00:01", "Essay.pdf", data.len() as u64, &mut body, 1000), Ok(()));
        let (head, got) = srv.join().unwrap();
        assert!(head.starts_with("POST /api/inbox/upload HTTP/1.1"), "{}", head);
        assert!(head.contains(&format!("X-Router-Key: {}", "k".repeat(40))), "{}", head);
        assert!(head.contains("X-Filename: Essay.pdf") && head.contains("X-Device: aa:bb:cc:00:00:01"), "{}", head);
        assert_eq!(got, data, "every byte arrives, in order");
    }

    #[test]
    fn it_is_off_without_a_key_and_checks_sizes_before_connecting() {
        let dir = tmp("off");
        let s = Send::new("127.0.0.1", 9, &dir, 1, 2, true);
        assert!(!s.enabled());
        assert_eq!(s.upload("d", "a.pdf", 10, &mut Cursor::new(vec![0; 10]), 1), Err(SendError::Off));
        fs::write(format!("{}/send.key", dir), "short").unwrap();
        let s = Send::new("127.0.0.1", 9, &dir, 1, 2, true);
        assert!(!s.enabled(), "a key that is too short is no key");
        let s = make(9, &dir);
        assert_eq!(s.upload("d", "a.pdf", 0, &mut Cursor::new(vec![]), 1), Err(SendError::TooBig));
        assert_eq!(s.upload("d", "a.pdf", 2 * 1024 * 1024, &mut Cursor::new(vec![0; 10]), 1), Err(SendError::TooBig));
        assert_eq!(s.upload("d", "", 10, &mut Cursor::new(vec![0; 10]), 1), Err(SendError::NoName));
        assert_eq!(s.upload("d", "a.pdf", 10, &mut Cursor::new(vec![0; 10]), 1), Err(SendError::Down), "nothing listens on port 9");
    }

    #[test]
    fn a_device_may_send_a_few_files_an_hour() {
        let dir = tmp("rate");
        let s = make(1, &dir);
        for t in [100u64, 200] {
            s.st.lock().unwrap().sent.entry("dev".into()).or_default().push(t);
        }
        assert_eq!(s.upload("dev", "a.pdf", 3, &mut Cursor::new(vec![1, 2, 3]), 300), Err(SendError::Rate), "the third in an hour");
        assert_ne!(
            s.upload("dev2", "a.pdf", 3, &mut Cursor::new(vec![1, 2, 3]), 300),
            Err(SendError::Rate),
            "another device is not affected"
        );
        assert_ne!(
            s.upload("dev", "a.pdf", 3, &mut Cursor::new(vec![1, 2, 3]), 100 + 3601),
            Err(SendError::Rate),
            "an hour later it may again"
        );
    }

    #[test]
    fn the_print_servers_no_is_passed_on_and_a_cut_connection_is_reported() {
        let dir = tmp("no");
        let (port, srv) =
            fake_server("415 UNSUPPORTED MEDIA TYPE", "{\"success\":false,\"error\":\"File extension '.exe' is not supported\"}");
        let s = make(port, &dir);
        let r = s.upload("d", "run.exe", 4, &mut Cursor::new(vec![1, 2, 3, 4]), 1);
        srv.join().unwrap();
        assert_eq!(r, Err(SendError::Refused(415, "File extension '.exe' is not supported".into())));

        let (port, srv) = fake_server("200 OK", "{}");
        let s = make(port, &dir);
        // the guest promised 10 bytes and sent 4: the upload is reported as incomplete and the server never sees a full body
        let r = s.upload("d2", "a.pdf", 10, &mut Cursor::new(vec![1, 2, 3, 4]), 1);
        assert_eq!(r, Err(SendError::Incomplete));
        drop(srv);
    }

    #[test]
    fn the_owner_can_switch_it_off_even_with_the_key() {
        let dir = tmp("switch");
        let s = make(9, &dir);
        assert!(s.enabled() && s.has_key());
        s.set_switch(false);
        assert!(!s.enabled() && s.has_key() && !s.switch_on());
        assert_eq!(
            s.upload("d", "a.pdf", 3, &mut Cursor::new(vec![1, 2, 3]), 1),
            Err(SendError::Off),
            "switched off: refused before anything is read"
        );
        s.set_switch(true);
        assert!(s.enabled());
    }

    #[test]
    fn one_upload_at_a_time_per_device() {
        let dir = tmp("busy");
        let s = make(1, &dir);
        s.st.lock().unwrap().running.insert("dev".into());
        assert_eq!(s.upload("dev", "a.pdf", 3, &mut Cursor::new(vec![1, 2, 3]), 1), Err(SendError::Busy));
        for i in 0..MAX_PARALLEL {
            s.st.lock().unwrap().running.insert(format!("other{}", i));
        }
        assert_eq!(s.upload("new", "a.pdf", 3, &mut Cursor::new(vec![1, 2, 3]), 1), Err(SendError::Busy), "the router is full");
    }
}
