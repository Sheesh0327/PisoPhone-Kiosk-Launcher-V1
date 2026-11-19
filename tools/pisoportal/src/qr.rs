//! Byte-mode QR symbols, versions 1–10, with level M Reed-Solomon correction.
//! The setup wizard has an equivalent offline encoder in pisophone_setup.py.
use std::fmt::Write;

// Version-indexed (data codewords per block, correction codewords per block).
const BLOCKS: [(&[usize], usize); 10] = [
    (&[16], 10),
    (&[28], 16),
    (&[44], 26),
    (&[32, 32], 18),
    (&[43, 43], 24),
    (&[27, 27, 27, 27], 16),
    (&[31, 31, 31, 31], 18),
    (&[38, 38, 39, 39], 22),
    (&[36, 36, 36, 37, 37], 22),
    (&[43, 43, 43, 43, 44], 26),
];
const ALIGN: [&[usize]; 10] =
    [&[], &[6, 18], &[6, 22], &[6, 26], &[6, 30], &[6, 34], &[6, 22, 38], &[6, 24, 42], &[6, 26, 46], &[6, 28, 50]];

fn mul(mut a: u8, mut b: u8) -> u8 {
    let mut result = 0;
    while b != 0 {
        if b & 1 != 0 {
            result ^= a;
        }
        a = (a << 1) ^ if a & 0x80 != 0 { 0x1d } else { 0 };
        b >>= 1;
    }
    result
}

fn correction(data: &[u8], count: usize) -> Vec<u8> {
    let mut generator = vec![1];
    let mut root = 1;
    for _ in 0..count {
        generator.push(0);
        for i in (1..generator.len()).rev() {
            generator[i] ^= mul(generator[i - 1], root);
        }
        root = mul(root, 2);
    }

    let mut remainder = vec![0; count];
    for &byte in data {
        let factor = byte ^ remainder[0];
        remainder.rotate_left(1);
        remainder[count - 1] = 0;
        for (value, &coefficient) in remainder.iter_mut().zip(&generator[1..]) {
            *value ^= mul(factor, coefficient);
        }
    }
    remainder
}

fn codewords(text: &str) -> Option<(usize, Vec<u8>)> {
    let utf8_eci = usize::from(!text.is_ascii()) * 12;
    let (index, &(sizes, ecc)) = BLOCKS.iter().enumerate().find(|&(i, (sizes, _))| {
        let header = if i < 9 { 12 } else { 20 };
        text.len() <= (sizes.iter().sum::<usize>() * 8 - header - utf8_eci) / 8
    })?;
    let capacity = sizes.iter().sum::<usize>();
    let mut bits = Vec::with_capacity(capacity * 8);
    let mut append = |value: usize, count| bits.extend((0..count).rev().map(|i| (value >> i) as u8 & 1));
    if utf8_eci != 0 {
        append(0b0111, 4); // ECI assignment 26 identifies UTF-8.
        append(26, 8);
    }
    append(0b0100, 4);
    append(text.len(), if index < 9 { 8 } else { 16 });
    for byte in text.bytes() {
        append(byte.into(), 8);
    }
    // A terminator of up to four zero bits, followed by zero bits to finish the byte.
    let end = (bits.len() + 4).min(capacity * 8).div_ceil(8) * 8;
    bits.resize(end, 0);
    let mut data: Vec<u8> = bits.as_chunks::<8>().0.iter().map(|byte| byte.iter().fold(0, |acc, &bit| (acc << 1) | bit)).collect();
    data.extend([0xec, 0x11].into_iter().cycle().take(capacity - data.len()));

    let mut remaining = data.as_slice();
    let blocks: Vec<_> = sizes
        .iter()
        .map(|&size| {
            let (block, rest) = remaining.split_at(size);
            remaining = rest;
            (block, correction(block, ecc))
        })
        .collect();
    let mut stream = Vec::with_capacity(capacity + ecc * sizes.len());
    for i in 0..*sizes.last()? {
        stream.extend(blocks.iter().filter_map(|(data, _)| data.get(i).copied()));
    }
    for i in 0..ecc {
        stream.extend(blocks.iter().map(|(_, check)| check[i]));
    }
    Some((index + 1, stream))
}

fn bch(value: usize, poly: usize, degree: usize) -> usize {
    let mut rem = value;
    for _ in 0..degree {
        rem = (rem << 1) ^ ((rem >> (degree - 1)) * poly);
    }
    (value << degree) | rem
}

type Grid = Vec<Vec<Option<bool>>>;

fn patterns(version: usize, size: usize) -> Grid {
    let mut grid = vec![vec![None; size]; size];
    for (row, col) in [(3, 3), (3, size - 4), (size - 4, 3)] {
        for (r, line) in grid.iter_mut().enumerate().skip(row.saturating_sub(4)).take(8) {
            for (c, cell) in line.iter_mut().enumerate().skip(col.saturating_sub(4)).take(8) {
                let distance = r.abs_diff(row).max(c.abs_diff(col));
                *cell = Some(distance != 2 && distance != 4);
            }
        }
    }
    let centers = ALIGN[version - 1];
    for &r in centers {
        for &c in centers {
            if grid[r][c].is_some() {
                continue;
            }
            for (y, line) in grid.iter_mut().enumerate().skip(r - 2).take(5) {
                for (x, cell) in line.iter_mut().enumerate().skip(c - 2).take(5) {
                    *cell = Some(y.abs_diff(r).max(x.abs_diff(c)) != 1);
                }
            }
        }
    }
    for (i, cell) in grid[6].iter_mut().enumerate().take(size - 8).skip(8) {
        cell.get_or_insert(i % 2 == 0);
    }
    for (i, row) in grid.iter_mut().enumerate().take(size - 8).skip(8) {
        row[6].get_or_insert(i % 2 == 0);
    }
    grid[size - 8][8] = Some(true);
    if version >= 7 {
        let info = bch(version, 0x1f25, 12);
        for i in 0..18 {
            let bit = Some(info >> i & 1 != 0);
            grid[i / 3][size - 11 + i % 3] = bit;
            grid[size - 11 + i % 3][i / 3] = bit;
        }
    }
    grid
}

fn masked(mask: usize, r: usize, c: usize) -> bool {
    match mask {
        0 => (r + c).is_multiple_of(2),
        1 => r.is_multiple_of(2),
        2 => c.is_multiple_of(3),
        3 => (r + c).is_multiple_of(3),
        4 => (r / 2 + c / 3).is_multiple_of(2),
        5 => r * c % 2 + r * c % 3 == 0,
        6 => (r * c % 2 + r * c % 3).is_multiple_of(2),
        _ => ((r + c) % 2 + r * c % 3).is_multiple_of(2),
    }
}

fn place(mut grid: Grid, stream: &[u8], mask: usize) -> Vec<Vec<bool>> {
    let n = grid.len();
    let format = bch(mask, 0x537, 10) ^ 0x5412; // Level M has format bits 00.
    for i in 0..15 {
        let bit = Some(format >> i & 1 != 0);
        match i {
            0..=5 => grid[i][8] = bit,
            6..=7 => grid[i + 1][8] = bit,
            8 => grid[8][7] = bit,
            _ => grid[8][14 - i] = bit,
        }
        if i < 8 {
            grid[8][n - 1 - i] = bit;
        } else {
            grid[n - 15 + i][8] = bit;
        }
    }

    let mut col = n - 1;
    let mut upwards = true;
    let mut bit = 0;
    while col > 0 {
        if col == 6 {
            col -= 1;
        }
        for step in 0..n {
            let r = if upwards { n - 1 - step } else { step };
            for c in [col, col - 1] {
                if grid[r][c].is_none() {
                    let value = stream.get(bit / 8).is_some_and(|b| (b >> (7 - bit % 8)) & 1 != 0);
                    grid[r][c] = Some(value ^ masked(mask, r, c));
                    bit += 1;
                }
            }
        }
        col -= col.min(2);
        upwards = !upwards;
    }
    grid.into_iter().map(|row| row.into_iter().map(|cell| cell.unwrap_or(false)).collect()).collect()
}

fn penalty(grid: &[Vec<bool>]) -> usize {
    const FINDER: [bool; 11] = [true, false, true, true, true, false, true, false, false, false, false];
    const REVERSE: [bool; 11] = [false, false, false, false, true, false, true, true, true, false, true];
    fn line_score(line: &[bool]) -> usize {
        let runs = line.chunk_by(|a, b| a == b).filter(|run| run.len() >= 5).map(|run| run.len() - 2).sum::<usize>();
        runs + 40 * line.windows(11).filter(|&window| window == FINDER || window == REVERSE).count()
    }

    let n = grid.len();
    let mut score = 0;
    for i in 0..n {
        score += line_score(&grid[i]);
        score += line_score(&grid.iter().map(|row| row[i]).collect::<Vec<_>>());
    }
    for rows in grid.windows(2) {
        for c in 0..n - 1 {
            let color = rows[0][c];
            if color == rows[0][c + 1] && color == rows[1][c] && color == rows[1][c + 1] {
                score += 3;
            }
        }
    }
    let dark = grid.iter().flatten().filter(|&&cell| cell).count();
    score + 10 * ((dark * 20).abs_diff(n * n * 10) / (n * n))
}

pub(crate) fn qr_svg(text: &str, module: usize, quiet: usize) -> Option<String> {
    let (version, stream) = codewords(text)?;
    let size = 17 + 4 * version;
    let base = patterns(version, size);
    let matrix = (0..8).map(|mask| place(base.clone(), &stream, mask)).min_by_key(|grid| penalty(grid))?;
    let n = size + 2 * quiet;
    let px = n * module;
    let mut svg = format!(
        r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}" width="{px}" height="{px}" shape-rendering="crispEdges"><rect width="{n}" height="{n}" fill="#fff"/><path fill="#000" d=""##
    );
    for (r, row) in matrix.iter().enumerate() {
        for (c, &dark) in row.iter().enumerate() {
            if dark {
                let _ = write!(svg, "M{} {}h1v1h-1z", c + quiet, r + quiet);
            }
        }
    }
    svg.push_str("\"/></svg>");
    Some(svg)
}

pub(crate) fn wifi_qr_text(ssid: &str, password: &str, hidden: bool) -> String {
    fn escape(value: &str) -> String {
        let mut escaped = String::new();
        for c in value.chars() {
            if matches!(c, '\\' | ';' | ',' | ':' | '"') {
                escaped.push('\\');
            }
            escaped.push(c);
        }
        escaped
    }
    let name = if !ssid.is_empty() && ssid.bytes().all(|byte| byte.is_ascii_hexdigit()) { format!("\"{ssid}\"") } else { escape(ssid) };
    let hidden_field = if hidden { "H:true;" } else { "" };
    // Scanners can interpret surrounding quotes as part of a WPA passphrase.
    format!("WIFI:T:WPA;S:{name};P:{};{hidden_field};", escape(password))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_bounds() {
        for (i, (blocks, _)) in BLOCKS.iter().enumerate() {
            let max = blocks.iter().sum::<usize>() - if i < 9 { 2 } else { 3 };
            assert_eq!(codewords(&"a".repeat(max)).unwrap().0, i + 1);
            assert_eq!(codewords(&"a".repeat(max + 1)).map(|(version, _)| version), (i < 9).then_some(i + 2));
        }
    }

    #[test]
    fn wifi_escaping() {
        assert_eq!(wifi_qr_text("a;b", "c,d:e\\f\"g", true), "WIFI:T:WPA;S:a\\;b;P:c\\,d\\:e\\\\f\\\"g;H:true;;");
        assert_eq!(wifi_qr_text("Cafe", "12345678", false), "WIFI:T:WPA;S:\"Cafe\";P:12345678;;");
        assert_eq!(
            wifi_qr_text("PisoAdmin-a1b2", "AB0123456789CD0123456789", true),
            "WIFI:T:WPA;S:PisoAdmin-a1b2;P:AB0123456789CD0123456789;H:true;;"
        );
    }

    #[test]
    fn svg_structure() {
        let svg = qr_svg("<hello>", 4, 4).unwrap();
        assert!(svg.contains("viewBox=\"0 0 29 29\" width=\"116\""));
        assert!(svg.contains("M4 4h1v1h-1z"));
        assert!(!svg.contains("<hello>"));
        assert!(svg.ends_with("/></svg>"));
    }
}
