// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Transparent gzip with decompression-bomb limits, and byte caps for any input.
//!
//! Guides arrive as `.xml` or `.xml.gz` (sometimes gzip without the suffix): [open_maybe_gzip] sniffs the magic
//! bytes. The decompressed stream is capped in total size and, once past a grace size, in expansion ratio, so a
//! 10 KB file that inflates to terabytes fails fast instead of filling memory or disk.

use crate::error::Error;
use flate2::bufread::MultiGzDecoder;
use std::io::{self, BufRead, BufReader, Read};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Clone, Copy, Debug)]
pub struct Limits {
    /// Maximum bytes after decompression (and for plain input).
    pub max_bytes: u64,
    /// Maximum decompressed / compressed ratio, checked after `ratio_grace` output bytes.
    pub max_ratio: u64,
    pub ratio_grace: u64,
}

impl Default for Limits {
    /// 2 GiB of XML (a week of a very large guide is ~1 GB), ratio 200 (XMLTV compresses ~10-25x).
    fn default() -> Self {
        Limits { max_bytes: 2 << 30, max_ratio: 200, ratio_grace: 16 << 20 }
    }
}

/// Counts bytes read from the inner reader.
#[derive(Debug)]
struct Counting<R> {
    inner: R,
    n: Arc<AtomicU64>,
}

impl<R: Read> Read for Counting<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let k = self.inner.read(buf)?;
        self.n.fetch_add(k as u64, Ordering::Relaxed);
        Ok(k)
    }
}

/// A reader that fails (with [Error::TooLarge] / [Error::CompressionRatio] inside the io::Error) past its limits.
#[derive(Debug)]
pub struct Capped<R> {
    inner: R,
    out: u64,
    limits: Limits,
    compressed: Option<Arc<AtomicU64>>,
}

pub(crate) fn limit_error(e: Error) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, e)
}

/// Turns an io::Error made by [Capped] back into the core error it carries.
pub fn unwrap_io(e: io::Error) -> Error {
    if e.get_ref().is_some_and(|i| i.is::<Error>()) {
        match e.into_inner().map(|b| b.downcast::<Error>()) {
            Some(Ok(b)) => *b,
            _ => Error::Invalid("i/o".into()),
        }
    } else {
        Error::Io(e)
    }
}

impl<R: Read> Read for Capped<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let k = self.inner.read(buf)?;
        self.out += k as u64;
        if self.out > self.limits.max_bytes {
            return Err(limit_error(Error::TooLarge { limit: self.limits.max_bytes }));
        }
        if let Some(c) = &self.compressed
            && self.out > self.limits.ratio_grace
        {
            let inp = c.load(Ordering::Relaxed).max(1);
            if self.out / inp > self.limits.max_ratio {
                return Err(limit_error(Error::CompressionRatio { ratio: self.limits.max_ratio }));
            }
        }
        Ok(k)
    }
}

/// Plain or gzip input as one buffered, capped stream.
pub fn open_maybe_gzip<'a, R: BufRead + 'a>(mut r: R, limits: Limits) -> io::Result<Box<dyn BufRead + 'a>> {
    let gz = {
        let head = r.fill_buf()?;
        head.len() >= 2 && head[0] == 0x1f && head[1] == 0x8b
    };
    if gz {
        let n = Arc::new(AtomicU64::new(0));
        let counted = BufReader::with_capacity(32 * 1024, Counting { inner: r, n: n.clone() });
        let dec = MultiGzDecoder::new(counted);
        Ok(Box::new(BufReader::with_capacity(64 * 1024, Capped { inner: dec, out: 0, limits, compressed: Some(n) })))
    } else {
        Ok(Box::new(BufReader::with_capacity(64 * 1024, Capped { inner: r, out: 0, limits, compressed: None })))
    }
}

/// Reads a whole (possibly gzip) input into memory within `limits` (small JSON / playlists).
pub fn read_all<R: BufRead>(r: R, limits: Limits) -> crate::Result<Vec<u8>> {
    let mut out = Vec::new();
    open_maybe_gzip(r, limits).map_err(Error::Io)?.read_to_end(&mut out).map_err(unwrap_io)?;
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::Compression;
    use flate2::write::GzEncoder;
    use std::io::Write;

    fn gz(data: &[u8]) -> Vec<u8> {
        let mut e = GzEncoder::new(Vec::new(), Compression::best());
        e.write_all(data).unwrap();
        e.finish().unwrap()
    }

    #[test]
    fn plain_and_gzip() {
        assert_eq!(read_all(&b"hello"[..], Limits::default()).unwrap(), b"hello");
        assert_eq!(read_all(&gz(b"hello")[..], Limits::default()).unwrap(), b"hello");
        // two gzip members back to back (some providers concatenate)
        let mut two = gz(b"ab");
        two.extend(gz(b"cd"));
        assert_eq!(read_all(&two[..], Limits::default()).unwrap(), b"abcd");
    }

    #[test]
    fn size_cap() {
        let l = Limits { max_bytes: 10, ..Default::default() };
        assert!(matches!(read_all(&b"0123456789AB"[..], l), Err(Error::TooLarge { .. })));
        assert!(matches!(read_all(&gz(&[b'x'; 100])[..], l), Err(Error::TooLarge { .. })));
    }

    #[test]
    fn bomb_ratio() {
        // 64 MB of zeros compresses to ~64 KB: ratio ~1000
        let zeros = vec![0u8; 64 << 20];
        let bomb = gz(&zeros);
        let l = Limits { max_bytes: 1 << 40, max_ratio: 100, ratio_grace: 1 << 20 };
        assert!(matches!(read_all(&bomb[..], l), Err(Error::CompressionRatio { .. })));
        // streaming consumer sees the error too, long before the end
        let mut r = open_maybe_gzip(&bomb[..], l).unwrap();
        let mut buf = vec![0u8; 1 << 16];
        let mut total = 0u64;
        let err = loop {
            match r.read(&mut buf) {
                Ok(0) => break None,
                Ok(k) => total += k as u64,
                Err(e) => break Some(e),
            }
        };
        assert!(err.is_some());
        assert!(total < 8 << 20);
    }
}
