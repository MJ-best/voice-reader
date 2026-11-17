//! Minimal, zero-dependency ZIP reader/writer with full RFC 1951 Inflate & LZ77 Fixed-Huffman Deflate.
//! Preserves EPUB OCF `mimetype` as the first STORED entry.

const SIG_LOCAL: u32 = 0x0403_4b50;
const SIG_CENTRAL: u32 = 0x0201_4b50;
const SIG_EOCD: u32 = 0x0605_4b50;

pub const METHOD_STORED: u16 = 0;
pub const METHOD_DEFLATE: u16 = 8;

#[derive(Debug, Clone)]
pub struct ZipEntry {
    pub name: String,
    pub method: u16,
    pub data: Vec<u8>,
}

pub fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

fn u16_le(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes([b[off], b[off + 1]])
}

fn u32_le(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

pub fn read_zip(bytes: &[u8]) -> Result<Vec<ZipEntry>, String> {
    if bytes.len() < 22 {
        return Err("ZIP too short".into());
    }
    // Find EOCD scanning backwards up to 64KB + 22 bytes
    let min_pos = bytes.len().saturating_sub(65_557);
    let mut eocd = None;
    let mut pos = bytes.len() - 22;
    loop {
        if u32_le(bytes, pos) == SIG_EOCD {
            let comment_len = u16_le(bytes, pos + 20) as usize;
            if pos + 22 + comment_len == bytes.len() {
                eocd = Some(pos);
                break;
            }
        }
        if pos == min_pos {
            break;
        }
        pos -= 1;
    }
    let eocd = eocd.ok_or_else(|| "Missing ZIP EOCD".to_string())?;
    let total_entries = u16_le(bytes, eocd + 10) as usize;
    let cd_size = u32_le(bytes, eocd + 12) as usize;
    let cd_offset = u32_le(bytes, eocd + 16) as usize;

    if cd_offset + cd_size > bytes.len() {
        return Err("Invalid ZIP central directory bounds".into());
    }

    let mut entries = Vec::with_capacity(total_entries);
    let mut p = cd_offset;
    for _ in 0..total_entries {
        if p + 46 > bytes.len() || u32_le(bytes, p) != SIG_CENTRAL {
            return Err("Corrupt ZIP central directory entry".into());
        }
        let method = u16_le(bytes, p + 10);
        let expected_crc = u32_le(bytes, p + 16);
        let comp_size = u32_le(bytes, p + 20) as usize;
        let uncomp_size = u32_le(bytes, p + 24) as usize;
        let name_len = u16_le(bytes, p + 28) as usize;
        let extra_len = u16_le(bytes, p + 30) as usize;
        let comment_len = u16_le(bytes, p + 32) as usize;
        let local_off = u32_le(bytes, p + 42) as usize;

        let name_end = p + 46 + name_len;
        if name_end + extra_len + comment_len > bytes.len() {
            return Err("Central directory entry out of bounds".into());
        }
        let name = String::from_utf8_lossy(&bytes[p + 46..name_end]).into_owned();
        p = name_end + extra_len + comment_len;

        if local_off + 30 > bytes.len() || u32_le(bytes, local_off) != SIG_LOCAL {
            return Err(format!("Missing local header for {name}"));
        }
        let l_name_len = u16_le(bytes, local_off + 26) as usize;
        let l_extra_len = u16_le(bytes, local_off + 28) as usize;
        let data_start = local_off + 30 + l_name_len + l_extra_len;
        let data_end = data_start + comp_size;
        if data_end > bytes.len() {
            return Err(format!("Compressed data out of bounds for {name}"));
        }
        let raw = &bytes[data_start..data_end];
        let data = match method {
            METHOD_STORED => raw.to_vec(),
            METHOD_DEFLATE => inflate(raw, uncomp_size)?,
            other => return Err(format!("Unsupported ZIP compression method {other} for {name}")),
        };
        if !name.ends_with('/') && crc32(&data) != expected_crc {
            return Err(format!("CRC mismatch for {name}"));
        }
        entries.push(ZipEntry { name, method, data });
    }
    Ok(entries)
}

pub fn write_zip(entries: &[ZipEntry]) -> Vec<u8> {
    // OCF requires `mimetype` first, uncompressed (METHOD_STORED), no extra field.
    let mut ordered: Vec<&ZipEntry> = Vec::with_capacity(entries.len());
    for e in entries {
        if e.name == "mimetype" {
            ordered.push(e);
        }
    }
    for e in entries {
        if e.name != "mimetype" {
            ordered.push(e);
        }
    }

    let mut out = Vec::new();
    let mut central = Vec::new();
    let count = ordered.len() as u16;

    for e in ordered {
        let local_off = out.len() as u32;
        let name_bytes = e.name.as_bytes();
        let crc = crc32(&e.data);
        let uncomp_len = e.data.len() as u32;

        let (method, compressed) = if e.name == "mimetype" || e.data.is_empty() || should_store(&e.name) {
            (METHOD_STORED, e.data.clone())
        } else {
            let def = deflate_fixed(&e.data);
            if def.len() < e.data.len() {
                (METHOD_DEFLATE, def)
            } else {
                (METHOD_STORED, e.data.clone())
            }
        };
        let comp_len = compressed.len() as u32;

        // Local file header
        out.extend_from_slice(&SIG_LOCAL.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes()); // version needed
        out.extend_from_slice(&0x0800u16.to_le_bytes()); // UTF-8 flag
        out.extend_from_slice(&method.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // mod time
        out.extend_from_slice(&0u16.to_le_bytes()); // mod date
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&comp_len.to_le_bytes());
        out.extend_from_slice(&uncomp_len.to_le_bytes());
        out.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // extra len
        out.extend_from_slice(name_bytes);
        out.extend_from_slice(&compressed);

        // Central directory header
        central.extend_from_slice(&SIG_CENTRAL.to_le_bytes());
        central.extend_from_slice(&20u16.to_le_bytes()); // version made by
        central.extend_from_slice(&20u16.to_le_bytes()); // version needed
        central.extend_from_slice(&0x0800u16.to_le_bytes()); // UTF-8 flag
        central.extend_from_slice(&method.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&crc.to_le_bytes());
        central.extend_from_slice(&comp_len.to_le_bytes());
        central.extend_from_slice(&uncomp_len.to_le_bytes());
        central.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes()); // extra
        central.extend_from_slice(&0u16.to_le_bytes()); // comment
        central.extend_from_slice(&0u16.to_le_bytes()); // disk start
        central.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
        central.extend_from_slice(&0u32.to_le_bytes()); // external attrs
        central.extend_from_slice(&local_off.to_le_bytes());
        central.extend_from_slice(name_bytes);
    }

    let cd_offset = out.len() as u32;
    let cd_size = central.len() as u32;
    out.extend_from_slice(&central);

    // EOCD
    out.extend_from_slice(&SIG_EOCD.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&cd_size.to_le_bytes());
    out.extend_from_slice(&cd_offset.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out
}

fn should_store(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".jpg")
        || lower.ends_with(".jpeg")
        || lower.ends_with(".png")
        || lower.ends_with(".webp")
        || lower.ends_with(".gif")
        || lower.ends_with(".woff2")
        || lower.ends_with(".mp3")
        || lower.ends_with(".m4a")
}

// ============================================================================
// RFC 1951 Inflate
// ============================================================================

struct BitReader<'a> {
    data: &'a [u8],
    byte_pos: usize,
    bit_buf: u32,
    bit_cnt: u8,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self {
            data,
            byte_pos: 0,
            bit_buf: 0,
            bit_cnt: 0,
        }
    }

    fn ensure(&mut self, n: u8) -> Result<(), String> {
        while self.bit_cnt < n {
            if self.byte_pos >= self.data.len() {
                return Err("Unexpected end of deflate stream".into());
            }
            self.bit_buf |= (self.data[self.byte_pos] as u32) << self.bit_cnt;
            self.byte_pos += 1;
            self.bit_cnt += 8;
        }
        Ok(())
    }

    fn read_bits(&mut self, n: u8) -> Result<u32, String> {
        if n == 0 {
            return Ok(0);
        }
        self.ensure(n)?;
        let val = self.bit_buf & ((1u32 << n) - 1);
        self.bit_buf >>= n;
        self.bit_cnt -= n;
        Ok(val)
    }

    fn align_byte(&mut self) {
        let drop_bits = self.bit_cnt % 8;
        self.bit_buf >>= drop_bits;
        self.bit_cnt -= drop_bits;
    }

    fn read_u16_aligned(&mut self) -> Result<u16, String> {
        self.align_byte();
        // Pull remaining buffered bytes back
        let unread_bytes = (self.bit_cnt / 8) as usize;
        self.byte_pos -= unread_bytes;
        self.bit_cnt = 0;
        self.bit_buf = 0;
        if self.byte_pos + 2 > self.data.len() {
            return Err("Unexpected EOF reading stored block".into());
        }
        let v = u16::from_le_bytes([self.data[self.byte_pos], self.data[self.byte_pos + 1]]);
        self.byte_pos += 2;
        Ok(v)
    }
}

struct HuffTable {
    counts: [u16; 16],
    symbols: Vec<u16>,
}

impl HuffTable {
    fn from_lengths(lengths: &[u8]) -> Result<Self, String> {
        let mut counts = [0u16; 16];
        for &len in lengths {
            if len as usize >= 16 {
                return Err("Huffman length > 15".into());
            }
            if len > 0 {
                counts[len as usize] += 1;
            }
        }
        let mut offsets = [0u16; 16];
        let mut total = 0u16;
        for bits in 1..=15 {
            offsets[bits] = total;
            total += counts[bits];
        }
        let mut symbols = vec![0u16; total as usize];
        for (sym, &len) in lengths.iter().enumerate() {
            if len > 0 {
                let idx = offsets[len as usize] as usize;
                offsets[len as usize] += 1;
                symbols[idx] = sym as u16;
            }
        }
        Ok(Self { counts, symbols })
    }

    fn decode(&self, br: &mut BitReader<'_>) -> Result<u16, String> {
        let mut code: i32 = 0;
        let mut first: i32 = 0;
        let mut index: i32 = 0;
        for len in 1..=15 {
            code |= br.read_bits(1)? as i32;
            let count = self.counts[len] as i32;
            if code - count < first {
                return Ok(self.symbols[(index + (code - first)) as usize]);
            }
            index += count;
            first = (first + count) << 1;
            code <<= 1;
        }
        Err("Invalid Huffman code".into())
    }
}

pub fn inflate(src: &[u8], expected_size: usize) -> Result<Vec<u8>, String> {
    const LEN_BASE: [u16; 29] = [
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115,
        131, 163, 195, 227, 258,
    ];
    const LEN_EXTRA: [u8; 29] = [
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    ];
    const DIST_BASE: [u16; 30] = [
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537,
        2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    ];
    const DIST_EXTRA: [u8; 30] = [
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12,
        13, 13,
    ];
    const CL_ORDER: [usize; 19] = [
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
    ];

    let mut br = BitReader::new(src);
    let mut out = Vec::with_capacity(expected_size);

    loop {
        let bfinal = br.read_bits(1)?;
        let btype = br.read_bits(2)?;

        match btype {
            0 => {
                let len = br.read_u16_aligned()? as usize;
                let nlen = br.read_u16_aligned()?;
                if (len as u16) != !nlen {
                    return Err("Corrupt stored block length".into());
                }
                if br.byte_pos + len > br.data.len() {
                    return Err("Stored block EOF".into());
                }
                out.extend_from_slice(&br.data[br.byte_pos..br.byte_pos + len]);
                br.byte_pos += len;
            }
            1 | 2 => {
                let (lit_table, dist_table) = if btype == 1 {
                    let mut ll = [0u8; 288];
                    ll[0..=143].fill(8);
                    ll[144..=255].fill(9);
                    ll[256..=279].fill(7);
                    ll[280..=287].fill(8);
                    let dl = [5u8; 32];
                    (HuffTable::from_lengths(&ll)?, HuffTable::from_lengths(&dl)?)
                } else {
                    let hlit = br.read_bits(5)? as usize + 257;
                    let hdist = br.read_bits(5)? as usize + 1;
                    let hclen = br.read_bits(4)? as usize + 4;
                    let mut cl_lens = [0u8; 19];
                    for i in 0..hclen {
                        cl_lens[CL_ORDER[i]] = br.read_bits(3)? as u8;
                    }
                    let cl_table = HuffTable::from_lengths(&cl_lens)?;
                    let total = hlit + hdist;
                    let mut lengths = Vec::with_capacity(total);
                    while lengths.len() < total {
                        let sym = cl_table.decode(&mut br)? as usize;
                        match sym {
                            0..=15 => lengths.push(sym as u8),
                            16 => {
                                let prev = *lengths.last().ok_or("Invalid repeat code 16")?;
                                let rep = br.read_bits(2)? as usize + 3;
                                lengths.resize(lengths.len() + rep, prev);
                            }
                            17 => {
                                let rep = br.read_bits(3)? as usize + 3;
                                lengths.resize(lengths.len() + rep, 0);
                            }
                            18 => {
                                let rep = br.read_bits(7)? as usize + 11;
                                lengths.resize(lengths.len() + rep, 0);
                            }
                            _ => return Err("Invalid code length symbol".into()),
                        }
                    }
                    if lengths.len() != total {
                        return Err("Code length overflow".into());
                    }
                    (
                        HuffTable::from_lengths(&lengths[..hlit])?,
                        HuffTable::from_lengths(&lengths[hlit..])?,
                    )
                };

                loop {
                    let sym = lit_table.decode(&mut br)?;
                    if sym < 256 {
                        out.push(sym as u8);
                    } else if sym == 256 {
                        break;
                    } else {
                        let idx = (sym - 257) as usize;
                        if idx >= LEN_BASE.len() {
                            return Err("Invalid length symbol".into());
                        }
                        let len = LEN_BASE[idx] as usize + br.read_bits(LEN_EXTRA[idx])? as usize;
                        let d_sym = dist_table.decode(&mut br)? as usize;
                        if d_sym >= DIST_BASE.len() {
                            return Err("Invalid distance symbol".into());
                        }
                        let dist =
                            DIST_BASE[d_sym] as usize + br.read_bits(DIST_EXTRA[d_sym])? as usize;
                        if dist > out.len() {
                            return Err("Invalid back-reference distance".into());
                        }
                        for _ in 0..len {
                            let b = out[out.len() - dist];
                            out.push(b);
                        }
                    }
                }
            }
            _ => return Err("Reserved deflate block type".into()),
        }

        if bfinal == 1 {
            break;
        }
    }
    Ok(out)
}

// ============================================================================
// RFC 1951 Deflate (LZ77 + Fixed Huffman)
// ============================================================================

struct BitWriter {
    out: Vec<u8>,
    bit_buf: u32,
    bit_cnt: u8,
}

impl BitWriter {
    fn new() -> Self {
        Self {
            out: Vec::new(),
            bit_buf: 0,
            bit_cnt: 0,
        }
    }

    fn write_bits(&mut self, val: u32, n: u8) {
        self.bit_buf |= val << self.bit_cnt;
        self.bit_cnt += n;
        while self.bit_cnt >= 8 {
            self.out.push((self.bit_buf & 0xFF) as u8);
            self.bit_buf >>= 8;
            self.bit_cnt -= 8;
        }
    }

    /// Huffman codes are MSB-first in the stream bit order.
    fn write_huff(&mut self, code: u16, bits: u8) {
        let mut rev = 0u32;
        for i in 0..bits {
            if (code >> i) & 1 != 0 {
                rev |= 1 << (bits - 1 - i);
            }
        }
        self.write_bits(rev, bits);
    }

    fn finish(mut self) -> Vec<u8> {
        if self.bit_cnt > 0 {
            self.out.push((self.bit_buf & 0xFF) as u8);
        }
        self.out
    }
}

fn write_fixed_lit(bw: &mut BitWriter, sym: u16) {
    match sym {
        0..=143 => bw.write_huff(0x30 + sym, 8),
        144..=255 => bw.write_huff(0x190 + (sym - 144), 9),
        256..=279 => bw.write_huff(sym - 256, 7),
        280..=287 => bw.write_huff(0xC0 + (sym - 280), 8),
        _ => unreachable!(),
    }
}

pub fn deflate_fixed(src: &[u8]) -> Vec<u8> {
    const LEN_CODES: [(u16, u16, u8); 29] = [
        (3, 257, 0),
        (4, 258, 0),
        (5, 259, 0),
        (6, 260, 0),
        (7, 261, 0),
        (8, 262, 0),
        (9, 263, 0),
        (10, 264, 0),
        (11, 265, 1),
        (13, 266, 1),
        (15, 267, 1),
        (17, 268, 1),
        (19, 269, 2),
        (23, 270, 2),
        (27, 271, 2),
        (31, 272, 2),
        (35, 273, 3),
        (43, 274, 3),
        (51, 275, 3),
        (59, 276, 3),
        (67, 277, 4),
        (83, 278, 4),
        (99, 279, 4),
        (115, 280, 4),
        (131, 281, 5),
        (163, 282, 5),
        (195, 283, 5),
        (227, 284, 5),
        (258, 285, 0),
    ];
    const DIST_CODES: [(u16, u16, u8); 30] = [
        (1, 0, 0),
        (2, 1, 0),
        (3, 2, 0),
        (4, 3, 0),
        (5, 4, 1),
        (7, 5, 1),
        (9, 6, 2),
        (13, 7, 2),
        (17, 8, 3),
        (25, 9, 3),
        (33, 10, 4),
        (49, 11, 4),
        (65, 12, 5),
        (97, 13, 5),
        (129, 14, 6),
        (193, 15, 6),
        (257, 16, 7),
        (385, 17, 7),
        (513, 18, 8),
        (769, 19, 8),
        (1025, 20, 9),
        (1537, 21, 9),
        (2049, 22, 10),
        (3073, 23, 10),
        (4097, 24, 11),
        (6145, 25, 11),
        (8193, 26, 12),
        (12289, 27, 12),
        (16385, 28, 13),
        (24577, 29, 13),
    ];

    let mut bw = BitWriter::new();
    bw.write_bits(1, 1); // BFINAL = 1
    bw.write_bits(1, 2); // BTYPE = 01 (Fixed Huffman)

    let n = src.len();
    let mut head = vec![u32::MAX; 32768];
    let mut prev = vec![u32::MAX; n];
    let mut i = 0usize;

    let hash3 = |p: usize| -> usize {
        (((src[p] as usize) << 10) ^ ((src[p + 1] as usize) << 5) ^ (src[p + 2] as usize)) & 32767
    };

    while i < n {
        let mut best_len = 0usize;
        let mut best_dist = 0usize;
        if i + 3 <= n {
            let h = hash3(i);
            let mut cand = head[h];
            prev[i] = cand;
            head[h] = i as u32;
            let mut chain = 0usize;
            while cand != u32::MAX && chain < 32 {
                let c = cand as usize;
                let dist = i - c;
                if dist > 32768 {
                    break;
                }
                if src[c] == src[i] && src[c + 1] == src[i + 1] && src[c + 2] == src[i + 2] {
                    let max_l = (n - i).min(258);
                    let mut l = 3;
                    while l < max_l && src[c + l] == src[i + l] {
                        l += 1;
                    }
                    if l > best_len {
                        best_len = l;
                        best_dist = dist;
                        if l == 258 {
                            break;
                        }
                    }
                }
                cand = prev[c];
                chain += 1;
            }
        }
        // Fix prev linking properly:
        if best_len >= 3 {
            let mut idx = 0;
            for (k, &(base, _, _)) in LEN_CODES.iter().enumerate() {
                if base as usize <= best_len {
                    idx = k;
                } else {
                    break;
                }
            }
            let (l_base, l_sym, l_ext) = LEN_CODES[idx];
            write_fixed_lit(&mut bw, l_sym);
            if l_ext > 0 {
                bw.write_bits((best_len - l_base as usize) as u32, l_ext);
            }

            let mut d_idx = 0;
            for (k, &(base, _, _)) in DIST_CODES.iter().enumerate() {
                if base as usize <= best_dist {
                    d_idx = k;
                } else {
                    break;
                }
            }
            let (d_base, d_sym, d_ext) = DIST_CODES[d_idx];
            bw.write_huff(d_sym, 5);
            if d_ext > 0 {
                bw.write_bits((best_dist - d_base as usize) as u32, d_ext);
            }

            // Insert intermediate positions into hash table
            let end = (i + best_len).min(n);
            let mut k = i + 1;
            while k < end && k + 3 <= n {
                let h = hash3(k);
                prev[k] = head[h];
                head[h] = k as u32;
                k += 1;
            }
            i += best_len;
        } else {
            write_fixed_lit(&mut bw, src[i] as u16);
            i += 1;
        }
    }

    write_fixed_lit(&mut bw, 256); // End of block
    bw.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_zip_and_epub_mimetype() {
        let entries = vec![
            ZipEntry {
                name: "OEBPS/ch1.xhtml".into(),
                method: METHOD_DEFLATE,
                data: b"<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Hello ubiquitous world! Hello ubiquitous world!</p></body></html>".to_vec(),
            },
            ZipEntry {
                name: "mimetype".into(),
                method: METHOD_STORED,
                data: b"application/epub+zip".to_vec(),
            },
        ];
        let zipped = write_zip(&entries);
        let parsed = read_zip(&zipped).unwrap();
        assert_eq!(parsed[0].name, "mimetype");
        assert_eq!(parsed[0].method, METHOD_STORED);
        assert_eq!(parsed[0].data, b"application/epub+zip");
        assert_eq!(parsed[1].name, "OEBPS/ch1.xhtml");
        assert_eq!(parsed[1].data, entries[0].data);
    }
}
