#!/usr/bin/env python3
"""Minimal zipalign: rewrites an APK so every STORED entry's data starts on a
4-byte boundary (16 KiB for .so files), as required for resources.arsc on
Android 11+. Usage: zipalign.py in.apk out.apk"""
import sys
import zipfile

def main(src, dst):
    with zipfile.ZipFile(src) as zin, open(dst, "wb") as raw:
        with zipfile.ZipFile(raw, "w") as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                out.compress_type = info.compress_type
                out.external_attr = info.external_attr
                out.create_system = info.create_system
                if info.compress_type == zipfile.ZIP_STORED:
                    align = 16384 if info.filename.endswith(".so") else 4
                    name_len = len(info.filename.encode("utf-8"))
                    data_start = raw.tell() + 30 + name_len
                    pad = (-data_start) % align
                    if pad and pad < 4:
                        pad += align  # extra field header needs 4 bytes
                    if pad:
                        out.extra = b"\xd9\xd9" + (pad - 4).to_bytes(2, "little") + b"\x00" * (pad - 4)
                zout.writestr(out, data)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
