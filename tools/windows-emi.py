"""Read Windows Energy Meter Interface counters via the installed driver."""

import ctypes as ct
from ctypes import wintypes as wt
import json
import re
import struct
import subprocess
import time


EMI_CLASS = "{45BD8344-7ED6-49CF-A440-C276C933B053}"
kernel = ct.WinDLL("kernel32", use_last_error=True)
kernel.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ct.c_void_p, wt.DWORD, wt.DWORD, wt.HANDLE]
kernel.CreateFileW.restype = wt.HANDLE
kernel.DeviceIoControl.argtypes = [
    wt.HANDLE, wt.DWORD, ct.c_void_p, wt.DWORD, ct.c_void_p, wt.DWORD, ct.POINTER(wt.DWORD), ct.c_void_p
]
kernel.DeviceIoControl.restype = wt.BOOL
kernel.CloseHandle.argtypes = [wt.HANDLE]


def interface_paths():
    data = subprocess.check_output(["pnputil", "/enum-interfaces", "/enabled", "/class", EMI_CLASS])
    paths = re.findall(rb"\\\\\?\\[^\r\n]+", data)
    return [raw.decode("ascii").strip() for raw in paths]


class Meter:
    def __init__(self, interface):
        self.handle = kernel.CreateFileW(interface, 0x80000000, 3, None, 3, 0, None)
        if self.handle == ct.c_void_p(-1).value:
            raise ct.WinError(ct.get_last_error())
        try:
            self.version, = struct.unpack("<H", self.read(0, 2))
            size, = struct.unpack("<I", self.read(1, 4))
            if not 1 <= size <= 65536:
                raise ValueError("Invalid EMI metadata size")
            metadata = self.read(2, size)
            self.channels = self.channel_names(metadata)
        except Exception:
            self.close()
            raise

    def close(self):
        kernel.CloseHandle(self.handle)

    def read(self, function, size):
        # FILE_DEVICE_UNKNOWN, METHOD_BUFFERED, FILE_READ_ACCESS from emi.h.
        code = (0x22 << 16) | (1 << 14) | (function << 2)
        buffer = ct.create_string_buffer(size)
        returned = wt.DWORD()
        if not kernel.DeviceIoControl(self.handle, code, None, 0, buffer, size, ct.byref(returned), None):
            raise ct.WinError(ct.get_last_error())
        if returned.value != size:
            raise ValueError(f"Expected {size} bytes from EMI, got {returned.value}")
        return buffer.raw

    def channel_names(self, metadata):
        if self.version == 1:
            unit, = struct.unpack_from("<I", metadata)
            length, = struct.unpack_from("<H", metadata, 70)
            fields = [(unit, metadata[72:72 + length])]
        elif self.version == 2:
            count, = struct.unpack_from("<H", metadata, 66)
            if not 1 <= count <= 128:
                raise ValueError("Unexpected EMI channel count")
            offset = 68
            fields = []
            for _ in range(count):
                unit, length = struct.unpack_from("<IH", metadata, offset)
                first = offset + 6
                offset = first + length
                fields.append((unit, metadata[first:offset]))
        else:
            raise ValueError(f"Unsupported EMI version {self.version}")
        names = []
        for unit, raw in fields:
            if unit != 0 or not raw.endswith(b"\0\0"):
                raise ValueError("EMI channel has an unsupported unit or invalid name")
            names.append(raw.decode("utf-16-le").rstrip("\0"))
        if len(set(names)) != len(names):
            raise ValueError("Duplicate EMI channel names")
        return names

    def snapshot(self):
        data = self.read(3, 16 * len(self.channels))
        return {
            name: {"energyPWh": energy, "driverTime100ns": stamp}
            for name, (energy, stamp) in zip(self.channels, struct.iter_unpack("<QQ", data), strict=True)
        }


def main():
    paths = interface_paths()
    if len(paths) != 1:
        raise SystemExit(f"Expected one enabled EMI interface. Found {len(paths)}.")
    meter = Meter(paths[0])
    try:
        before = meter.snapshot()
        time.sleep(2)
        after = meter.snapshot()
        delta = {name: after[name]["energyPWh"] - before[name]["energyPWh"] for name in meter.channels}
        if "RAPL_Package0_PKG" not in delta or not all(value >= 0 for value in delta.values()) or \
                delta["RAPL_Package0_PKG"] <= 0:
            raise ValueError("No positive, monotonic package measurement")
        print(json.dumps({"version": meter.version, "before": before, "after": after, "deltaPWh": delta}, indent=2))
    finally:
        meter.close()


if __name__ == "__main__":
    main()
