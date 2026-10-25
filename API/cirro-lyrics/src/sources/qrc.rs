//! QQ's QRC transport uses a modified DES, followed by zlib.
//! Protocol and S-boxes adapted from Lyricify-Lyrics-Helper (Apache-2.0).
//! Copyright 2023 XY Wang, WXRIW. See THIRD_PARTY_NOTICES.md.

use flate2::read::ZlibDecoder;
use std::io::Read;

const S: [[u8; 64]; 8] = [
    [
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12,
        11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9,
        1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
    ],
    [
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1,
        10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15,
        4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
    ],
    [
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5,
        14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6,
        9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
    ],
    [
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2,
        12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10,
        10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
    ],
    [
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15,
        10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14,
        2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
    ],
    [
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13,
        14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5,
        15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
    ],
    [
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5,
        12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4,
        10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
    ],
    [
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6,
        11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10,
        8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    ],
];
const IP: [u8; 64] = [
    58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    64, 56, 48, 40, 32, 24, 16, 8, 57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61,
    53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
];
const FP: [u8; 64] = [
    40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
    37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
    34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25,
];
const E: [u8; 48] = [
    32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11, 12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18,
    19, 20, 21, 20, 21, 22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1,
];
const P: [u8; 32] = [
    16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10, 2, 8, 24, 14, 32, 27, 3, 9, 19,
    13, 30, 6, 22, 11, 4, 25,
];

fn permute(value: u64, width: u8, table: &[u8]) -> u64 {
    table
        .iter()
        .fold(0, |out, &bit| (out << 1) | ((value >> (width - bit)) & 1))
}

fn word_order(mut bytes: [u8; 8]) -> [u8; 8] {
    bytes[..4].reverse();
    bytes[4..].reverse();
    bytes
}

fn schedule(key: [u8; 8], decrypt: bool) -> [u64; 16] {
    const C: [u8; 28] = [
        57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 27, 19, 11, 3,
        60, 52, 44, 36,
    ];
    const D: [u8; 28] = [
        63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13,
        5, 28, 20, 12, 4,
    ];
    const COMP: [u8; 48] = [
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40,
        51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    ];
    let key = u64::from_be_bytes(word_order(key));
    let mut c = (permute(key, 64, &C) as u32) << 4;
    let mut d = (permute(key, 64, &D) as u32) << 4;
    let mut keys = [0; 16];
    for (i, shift) in [1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1]
        .into_iter()
        .enumerate()
    {
        c = ((c << shift) | (c >> (28 - shift))) & 0xfffffff0;
        d = ((d << shift) | (d >> (28 - shift))) & 0xfffffff0;
        // QQ's second half uses bit - 27, unlike standard DES's bit - 28.
        keys[if decrypt { 15 - i } else { i }] =
            COMP.iter().enumerate().fold(0, |out, (j, &bit)| {
                (out << 1)
                    | u64::from(if j < 24 {
                        (c >> (31 - bit)) & 1
                    } else {
                        (d >> (58 - bit)) & 1
                    })
            });
    }
    keys
}

fn crypt(block: [u8; 8], keys: &[u64; 16]) -> [u8; 8] {
    let input = permute(u64::from_be_bytes(word_order(block)), 64, &IP);
    let (mut l, mut r) = ((input >> 32) as u32, input as u32);
    for key in keys {
        let expanded = permute(u64::from(r), 32, &E) ^ key;
        let mut s = 0u32;
        for (i, table) in S.iter().enumerate() {
            let six = ((expanded >> (42 - i * 6)) & 63) as usize;
            let index = (six & 32) | ((six & 31) >> 1) | ((six & 1) << 4);
            s = (s << 4) | u32::from(table[index]);
        }
        (l, r) = (r, l ^ permute(u64::from(s), 32, &P) as u32);
    }
    word_order(permute((u64::from(r) << 32) | u64::from(l), 64, &FP).to_be_bytes())
}

pub fn decrypt(hex: &str) -> Result<String, String> {
    let hex = hex.trim();
    if hex.is_empty() {
        return Ok(String::new());
    }
    if hex.len() % 16 != 0 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("invalid QRC hex blocks".into());
    }
    const KEY: &[u8; 24] = b"!@#)(*$%123ZXC!@!@#)(NHL";
    let keys = [
        schedule(KEY[16..24].try_into().unwrap(), true),
        schedule(KEY[8..16].try_into().unwrap(), false),
        schedule(KEY[..8].try_into().unwrap(), true),
    ];
    let mut data = Vec::with_capacity(hex.len() / 2);
    for chunk in hex.as_bytes().chunks_exact(16) {
        let mut block = [0; 8];
        for (out, pair) in block.iter_mut().zip(chunk.chunks_exact(2)) {
            *out = (pair[0] as char).to_digit(16).unwrap() as u8 * 16
                + (pair[1] as char).to_digit(16).unwrap() as u8;
        }
        for key in &keys {
            block = crypt(block, key);
        }
        data.extend_from_slice(&block);
    }
    let mut text = String::new();
    ZlibDecoder::new(data.as_slice())
        .take(4 * 1024 * 1024)
        .read_to_string(&mut text)
        .map_err(|e| format!("QRC zlib: {e}"))?;
    Ok(text.trim_start_matches('\u{feff}').to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_malformed_blocks() {
        for value in ["xyz", "1234", "0000000000000000", "éééééééé"] {
            assert!(decrypt(value).is_err());
        }
    }

    #[test]
    fn decrypts_vector_generated_by_lyricify() {
        // Synthetic text encrypted with upstream DESHelper and .NET ZLibStream.
        assert_eq!(decrypt("D6A2BE95D6447372C4CA7B8E40D7D64D8BC465AD495E05AAD5D9488AA15A9C5FF8ABBE4377FA8361D4A4DD6A2D4F28B9").unwrap(), "[1000,500]测(1000,200)试(1250,250)\n");
    }
}
