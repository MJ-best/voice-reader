/// sherpa-onnx's Supertonic `voice.bin` format:
/// int64 dims of style_ttl [n, a, b] and style_dp [n, c, d], little-endian (48 bytes),
/// followed by all voices' style_ttl f32s, then all voices' style_dp f32s.

const HEADER_BYTES: usize = 6 * 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VoiceBinHeader {
    pub count: usize,
    pub ttl: usize,
    pub dp: usize,
}

pub fn read_voice_bin_header(bytes: &[u8]) -> Result<VoiceBinHeader, String> {
    if bytes.len() < HEADER_BYTES {
        return Err("voice.bin too short".to_string());
    }
    let dims = read_dims(bytes);
    if dims[0] < 0 || dims[1] < 0 || dims[2] < 0 || dims[3] < 0 || dims[4] < 0 || dims[5] < 0 {
        return Err("negative dimensions in voice.bin".to_string());
    }
    let n = dims[0] as usize;
    if dims[3] as usize != n {
        return Err("style_ttl and style_dp disagree on the voice count".to_string());
    }
    let ttl = (dims[1] as usize)
        .checked_mul(dims[2] as usize)
        .ok_or_else(|| "ttl overflow".to_string())?;
    let dp = (dims[4] as usize)
        .checked_mul(dims[5] as usize)
        .ok_or_else(|| "dp overflow".to_string())?;
    Ok(VoiceBinHeader { count: n, ttl, dp })
}

pub fn append_voice(bin: &[u8], ttl: &[f32], dp: &[f32]) -> Result<Vec<u8>, String> {
    let h = read_voice_bin_header(bin)?;
    if ttl.len() != h.ttl || dp.len() != h.dp {
        return Err(format!(
            "style sizes {}/{} don't match this pack's {}/{}",
            ttl.len(),
            dp.len(),
            h.ttl,
            h.dp
        ));
    }
    let ttl_bytes = h.count * h.ttl * 4;
    let dp_bytes = h.count * h.dp * 4;
    if bin.len() != HEADER_BYTES + ttl_bytes + dp_bytes {
        return Err("voice.bin size doesn't match its header".to_string());
    }

    let mut dims = read_dims(bin);
    dims[0] += 1;
    dims[3] += 1;

    let mut out = Vec::with_capacity(bin.len() + (ttl.len() + dp.len()) * 4);
    for d in dims {
        out.extend_from_slice(&d.to_le_bytes());
    }
    out.extend_from_slice(&bin[HEADER_BYTES..HEADER_BYTES + ttl_bytes]);
    for &f in ttl {
        out.extend_from_slice(&f.to_le_bytes());
    }
    out.extend_from_slice(&bin[HEADER_BYTES + ttl_bytes..HEADER_BYTES + ttl_bytes + dp_bytes]);
    for &f in dp {
        out.extend_from_slice(&f.to_le_bytes());
    }
    Ok(out)
}

fn read_dims(bytes: &[u8]) -> [i64; 6] {
    let mut dims = [0i64; 6];
    for (idx, d) in dims.iter_mut().enumerate() {
        let start = idx * 8;
        let arr: [u8; 8] = bytes[start..start + 8].try_into().unwrap();
        *d = i64::from_le_bytes(arr);
    }
    dims
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_bin(ttls: &[Vec<f32>], dps: &[Vec<f32>]) -> Vec<u8> {
        let n = ttls.len() as i64;
        let mut out = Vec::new();
        for v in [n, 2, 3, n, 1, 2] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        for t in ttls {
            for &f in t {
                out.extend_from_slice(&f.to_le_bytes());
            }
        }
        for d in dps {
            for &f in d {
                out.extend_from_slice(&f.to_le_bytes());
            }
        }
        out
    }

    #[test]
    fn appends_as_next_speaker_id() {
        let before = make_bin(
            &[vec![0., 1., 2., 3., 4., 5.], vec![10., 11., 12., 13., 14., 15.]],
            &[vec![0., 1.], vec![10., 11.]],
        );
        let after = append_voice(&before, &[20., 21., 22., 23., 24., 25.], &[20., 21.]).unwrap();
        assert_eq!(read_voice_bin_header(&after).unwrap().count, 3);
        let expected = make_bin(
            &[
                vec![0., 1., 2., 3., 4., 5.],
                vec![10., 11., 12., 13., 14., 15.],
                vec![20., 21., 22., 23., 24., 25.],
            ],
            &[vec![0., 1.], vec![10., 11.], vec![20., 21.]],
        );
        assert_eq!(after, expected);
    }

    #[test]
    fn rejects_wrong_shape() {
        let before = make_bin(&[vec![0., 1., 2., 3., 4., 5.]], &[vec![0., 1.]]);
        assert!(append_voice(&before, &[1., 2., 3.], &[0., 1.]).is_err());
    }
}
