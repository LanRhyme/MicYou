#!/usr/bin/env python3
import argparse
import struct
import sys
import time


MAGIC = 0x4D595643  # "MYVC"


def read_exact(stream, size):
    data = b""
    while len(data) < size:
        chunk = stream.read(size - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--fps", type=int, required=True)
    parser.add_argument("--backend", type=str, default="")
    args = parser.parse_args()

    try:
        import numpy as np
        import pyvirtualcam
    except Exception as e:
        print(f"ERROR: missing dependency: {e}", flush=True)
        print("Install with: pip install pyvirtualcam numpy", flush=True)
        return 2

    width = max(1, args.width)
    height = max(1, args.height)
    fps = max(1, args.fps)
    backend = args.backend.strip() or None

    print(f"Starting pyvirtualcam {width}x{height}@{fps}", flush=True)
    with pyvirtualcam.Camera(
        width=width,
        height=height,
        fps=fps,
        fmt=pyvirtualcam.PixelFormat.BGR,
        backend=backend
    ) as cam:
        print(f"VCAM_BACKEND: {backend or 'default'}", flush=True)
        print(f"VCAM_DEVICE: {cam.device}", flush=True)
        while True:
            header = read_exact(sys.stdin.buffer, 16)
            if header is None:
                break
            magic, fw, fh, size = struct.unpack(">IIII", header)
            if magic != MAGIC:
                print("WARN: invalid frame header magic", flush=True)
                return 3
            payload = read_exact(sys.stdin.buffer, size)
            if payload is None:
                break
            if fw <= 0 or fh <= 0:
                continue
            expected = fw * fh * 4
            if len(payload) != expected:
                continue
            frame = np.frombuffer(payload, dtype=np.uint8).reshape((fh, fw, 4))
            bgr = frame[:, :, :3]
            cam.send(bgr)
            cam.sleep_until_next_frame()
    print("Virtual camera helper stopped", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        time.sleep(0.1)
        sys.exit(0)
