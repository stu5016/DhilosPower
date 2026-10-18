"""DhilosPower BLE 讀取測試（只送讀取類指令，不會改設定）。

需求: pip install bleak
用法: python ble_test.py            # 掃描名稱含 Dhilos 的裝置並讀取狀態
      python ble_test.py AA:BB:..   # 指定 MAC
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

SERVICE = "00001523-1212-efde-1523-785feabcd123"
NOTIFY = "00001524-1212-efde-1523-785feabcd123"
WRITE = "00001525-1212-efde-1523-785feabcd123"


def packet(*payload: int) -> bytes:
    data = bytearray(16)
    data[: len(payload)] = bytes(payload)
    data[15] = sum(data[:15]) & 0xFF
    return bytes(data)


def read_cmd(sub: int, arg: int = 0) -> bytes:
    return packet(0xF6, 0x12, 0x34, sub, arg)


def sbs(reg: int) -> bytes:
    return packet(0xC5, 0x12, 0x34, reg)


def mac(hi: int, lo: int) -> bytes:
    return packet(0xC4, 0x12, 0x34, hi, lo)


def u16(d: bytes, i: int) -> int:
    return (d[i] << 8) | d[i + 1]


def s16(d: bytes, i: int) -> int:
    v = u16(d, i)
    return v - 0x10000 if v & 0x8000 else v


def describe(cmd: bytes, d: bytes) -> str:
    t = d[0]
    if t == 0x01:
        return f"FW={u16(d, 5)} model={d[9]}"
    if t == 0x0F:
        return f"port flags b4={d[4]:08b} b5={d[5]:08b}"
    if t == 0x0A:
        return f"USB-A {u16(d, 3) / 1000:.2f}V {u16(d, 5) / 1000:.2f}A  PCB {u16(d, 9) / 1000:.1f}°C"
    if t == 0x0B:
        return (f"C1 {s16(d, 3) / 1000:.2f}V {abs(s16(d, 5)) / 1000:.2f}A  "
                f"C2 {s16(d, 7) / 1000:.2f}V {abs(s16(d, 9)) / 1000:.2f}A")
    if t == 0x0C:
        return (f"0C b3-6: {s16(d, 3) / 1000:.2f}V {abs(s16(d, 5)) / 1000:.2f}A  "
                f"b7-10: {s16(d, 7) / 1000:.2f}V {abs(s16(d, 9)) / 1000:.2f}A")
    if t == 0x0D:
        return f"DC-In {s16(d, 3) / 1000:.2f}V {abs(s16(d, 5)) / 1000:.2f}A"
    if t == 0x04:
        return f"PCB max {d[3]}°C min {d[4]}°C"
    if t == 0x25:
        val = d[3] | (d[4] << 8)
        names = {0x0D: "SOC %", 0x12: "TimeToEmpty min", 0x13: "TimeToFull min",
                 0x17: "CycleCount", 0x18: "DesignCapacity mAh", 0x4F: "SOH %",
                 0x09: "Voltage mV", 0x0A: "Current mA"}
        return f"SBS 0x{cmd[3]:02X} ({names.get(cmd[3], '?')}) = {val}"
    if t == 0x21:
        seq = u16(d, 11)
        extra = ""
        if cmd[3:5] == b"\x00\x72" and seq == 0:
            extra = f"  battery temp {u16(d, 5) * 0.1 - 273.15:.1f}°C"
        return f"MAC block seq={seq} data={d[3:11].hex()}{extra}"
    return ""


async def main() -> None:
    address = sys.argv[1] if len(sys.argv) > 1 else None
    if address is None:
        print("掃描中…")
        dev = await BleakScanner.find_device_by_filter(
            lambda d, ad: (d.name or "").find("Dhilos") >= 0
            or SERVICE in [u.lower() for u in ad.service_uuids],
            timeout=10,
        )
        if dev is None:
            print("找不到裝置")
            return
        address = dev.address
        print(f"找到 {dev.name} {address}")

    queue: asyncio.Queue[bytes] = asyncio.Queue()

    def on_notify(_, data: bytearray) -> None:
        queue.put_nowait(bytes(data))

    async with BleakClient(address) as client:
        await client.start_notify(NOTIFY, on_notify)
        commands = [
            read_cmd(0x0F), read_cmd(0x01), read_cmd(0x0A), read_cmd(0x0B),
            read_cmd(0x0C), read_cmd(0x0D), read_cmd(0x04),
            sbs(0x0D), sbs(0x12), sbs(0x13), sbs(0x17), sbs(0x18), sbs(0x4F),
            sbs(0x09), sbs(0x0A), mac(0x00, 0x72),
        ]
        for cmd in commands:
            while not queue.empty():
                queue.get_nowait()
            await client.write_gatt_char(WRITE, cmd, response=True)
            print(f">> {cmd.hex().upper()}")
            try:
                while True:
                    d = await asyncio.wait_for(queue.get(), timeout=1.0)
                    ok = (sum(d[:15]) & 0xFF) == d[15]
                    print(f"<< {d.hex().upper()} {'' if ok else '[SUM錯誤] '}{describe(cmd, d)}")
            except asyncio.TimeoutError:
                pass


if __name__ == "__main__":
    asyncio.run(main())
