#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 KWM 测试夹具（供 JVM 单测使用）。

思路：先做出**真实格式的音频**（Ogg Vorbis —— 酷我下发的绝大多数就是这个），
再用 KWM 的容器格式把它包起来。这样单测里解密出来的东西有明确的正确答案可比：
文件名对得上、字节逐一对得上，而不是「看起来像个 ogg」。

加密就是解密本身（异或自反），所以这里的 encrypt() 与
Kwm解密说明.md 里第 4 步的循环完全一致。

用法：
    python make_kwm_fixture.py <输出目录>
"""

import os
import struct
import sys

import av
import numpy as np

MAGIC = b"yeelion-kuwo-tme"
HEADER_SIZE = 0x400
KEY_OFFSET = 0x18
FORMAT_OFFSET = 0x2C
MASK_SIZE = 32
PREDEFINED_KEY = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk"


def build_mask(file_key: int) -> bytes:
    """与文档第 3 步完全一致：十进制字符串循环填充到 32 位，再与固定串逐字符异或。"""
    raw = str(file_key)
    if len(raw) >= MASK_SIZE:
        key32 = raw[:MASK_SIZE]
    else:
        key32 = (raw * (MASK_SIZE // len(raw) + 1))[:MASK_SIZE]
    return bytes((ord(PREDEFINED_KEY[i]) ^ ord(key32[i])) & 0xFF for i in range(MASK_SIZE))


def encrypt(plain: bytes, file_key: int) -> bytes:
    """与解密同一个操作。掩码下标跨块连续是这里唯一的坑，所以整段一次算完。"""
    mask = build_mask(file_key)
    out = bytearray(len(plain))
    for i, b in enumerate(plain):
        out[i] = b ^ mask[i % MASK_SIZE]
    return bytes(out)


def make_header(file_key: int, plain_head: bytes) -> bytes:
    header = bytearray(HEADER_SIZE)
    header[0:16] = MAGIC
    header[0x10:0x14] = struct.pack("<I", 1)          # 版本号
    struct.pack_into("<Q", header, KEY_OFFSET, file_key)
    header[FORMAT_OFFSET:FORMAT_OFFSET + 3] = plain_head[:3]
    return bytes(header)


def encode_audio(seconds: float, rate: int, codec: str, filename: str,
                 container_format: str, bit_rate: int) -> bytes:
    """现场合成一段双声道音频并编码；左 440Hz、右 880Hz，便于分辨声道。"""
    n = int(rate * seconds)
    t = np.arange(n) / rate
    left = (0.6 * np.sin(2 * np.pi * 440.0 * t) * 32767).astype(np.int16)
    right = (0.4 * np.sin(2 * np.pi * 880.0 * t) * 32767).astype(np.int16)
    interleaved = np.stack([left, right], axis=1).reshape(-1)

    out = av.open(filename, mode="w", format=container_format)
    stream = out.add_stream(codec, rate=rate)
    stream.layout = "stereo"
    stream.bit_rate = bit_rate

    frame = av.AudioFrame.from_ndarray(
        interleaved.reshape(1, -1), format="s16", layout="stereo"
    )
    frame.sample_rate = rate
    frame.pts = 0
    for packet in stream.encode(frame):
        out.mux(packet)
    for packet in stream.encode(None):
        out.mux(packet)
    out.close()

    with open(filename, "rb") as f:
        return f.read()


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out_dir, exist_ok=True)

    cases = [
        # (名称, 说明, 真实扩展名, 编码器, 容器格式, 采样率, 码率, fileKey)
        #
        # 酷我实际下发的大多是 Ogg Vorbis，但这个 PyAV 构建里没有 vorbis 编码器，
        # 所以用 **Opus-in-Ogg** 代替：容器头同样是 "OggS"，对「解密逐字节比对」
        # 与「嗅探出 ogg」这两件事完全等价。
        # 注意 libopus 只接受 48000 Hz，给别的采样率会在 open 时报 Invalid argument。
        ("sample_ogg", "Ogg 容器（Opus 编码）", "ogg", "libopus", "ogg", 48000, 64000, 95769),
        ("sample_mp3", "MPEG 音频", "mp3", "libmp3lame", "mp3", 44100, 96000, 1234567),
    ]

    for name, label, ext, codec, container, rate, bit_rate, file_key in cases:
        scratch = os.path.join(out_dir, f".scratch.{container}")
        plain = encode_audio(0.9, rate, codec, scratch, container, bit_rate)
        os.remove(scratch)

        # 整个明文文件都是被加密的音频体 —— 这一点可以拿文档里的真实数字核对：
        # 输入 12,720,513 字节、输出 12,719,489 字节，差值正好是 1024 的头部。
        # 所以能容纳的明文长度 = kwm 长度 - 1024。
        masked = encrypt(plain, file_key)
        kwm = make_header(file_key, plain) + masked

        kwm_path = os.path.join(out_dir, f"{name}.kwm")
        expect_path = os.path.join(out_dir, f"{name}_expected.{ext}")
        with open(kwm_path, "wb") as f:
            f.write(kwm)
        with open(expect_path, "wb") as f:
            f.write(plain)

        mask = build_mask(file_key)
        print(
            f"{name}: {label} plain={len(plain)} kwm={len(kwm)} "
            f"fileKey={file_key} mask={mask[:4].hex()}"
        )
        print(f"   -> {kwm_path}")
        print(f"   -> {expect_path}")

    # 顺带把文档里的实测掩码也打印出来，用于给单测写死期望值
    print("mask(95769) =", build_mask(95769).hex())
    print("mask(0)     =", build_mask(0).hex())
    print("mask(7)     =", build_mask(7).hex())


if __name__ == "__main__":
    main()
