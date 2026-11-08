"""Build an isolated DX Manager 2.5.0 copy with only icon resources changed.

Reads the official archive, never edits an installed app, and verifies native
resources and all non-resource PE sections after updating the executable icon.
Uses Windows resource APIs in data-only mode; the executable is not launched.
"""
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
from pathlib import Path
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "build/upstream-icons/DX-Manager-win-x64-v2.5.0.zip"
UPSTREAM_SHA = "ebb91a332b603c4096b1b1fb6d179f4d45b55f5a63fc657f8b989bea8de08764"
OUTPUT = ROOT / "dist/DXManager-2.5.0-custom-icons"
BRAND = ROOT / "assets/branding/dx-manager"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pe_sections(data):
    pe = struct.unpack_from("<I", data, 0x3c)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("Not a PE file")
    count = struct.unpack_from("<H", data, pe + 6)[0]
    optional_size = struct.unpack_from("<H", data, pe + 20)[0]
    optional = pe + 24
    magic = struct.unpack_from("<H", data, optional)[0]
    directory = optional + (112 if magic == 0x20b else 96)
    cert_offset, cert_size = struct.unpack_from("<II", data, directory + 4 * 8)
    if cert_offset or cert_size:
        raise ValueError("Refusing to modify an Authenticode-signed executable")
    sections = {}
    table = optional + optional_size
    for i in range(count):
        entry = table + i * 40
        name = data[entry:entry + 8].rstrip(b"\0").decode("ascii")
        length, offset = struct.unpack_from("<II", data, entry + 16)
        sections[name] = sha(data[offset:offset + length])
    return sections


kernel = C.WinDLL("kernel32", use_last_error=True)
TypeCallback = C.WINFUNCTYPE(W.BOOL, W.HMODULE, C.c_void_p, C.c_ssize_t)
NameCallback = C.WINFUNCTYPE(W.BOOL, W.HMODULE, C.c_void_p, C.c_void_p, C.c_ssize_t)
LangCallback = C.WINFUNCTYPE(W.BOOL, W.HMODULE, C.c_void_p, C.c_void_p, W.WORD, C.c_ssize_t)


def api(name, result, *args):
    fn = getattr(kernel, name)
    fn.restype = result
    fn.argtypes = args
    return fn


load_library = api("LoadLibraryExW", W.HMODULE, W.LPCWSTR, W.HANDLE, W.DWORD)
free_library = api("FreeLibrary", W.BOOL, W.HMODULE)
enum_types = api("EnumResourceTypesW", W.BOOL, W.HMODULE, TypeCallback, C.c_ssize_t)
enum_names = api("EnumResourceNamesW", W.BOOL, W.HMODULE, C.c_void_p, NameCallback, C.c_ssize_t)
enum_langs = api("EnumResourceLanguagesW", W.BOOL, W.HMODULE, C.c_void_p, C.c_void_p, LangCallback, C.c_ssize_t)
find_resource = api("FindResourceExW", W.HANDLE, W.HMODULE, C.c_void_p, C.c_void_p, W.WORD)
resource_size = api("SizeofResource", W.DWORD, W.HMODULE, W.HANDLE)
load_resource = api("LoadResource", W.HANDLE, W.HMODULE, W.HANDLE)
lock_resource = api("LockResource", C.c_void_p, W.HANDLE)
begin_update = api("BeginUpdateResourceW", W.HANDLE, W.LPCWSTR, W.BOOL)
update_resource = api("UpdateResourceW", W.BOOL, W.HANDLE, C.c_void_p, C.c_void_p, W.WORD, C.c_void_p, W.DWORD)
end_update = api("EndUpdateResourceW", W.BOOL, W.HANDLE, W.BOOL)


def check(result):
    if not result:
        raise C.WinError(C.get_last_error())
    return result


def identifier(pointer):
    return pointer if pointer <= 0xffff else C.wstring_at(pointer)


def ref(value):
    return C.c_void_p(value) if isinstance(value, int) else C.cast(C.c_wchar_p(value), C.c_void_p)


def resources(path):
    handle = check(load_library(str(path), None, 0x02 | 0x20))
    result = {}
    errors = []

    @LangCallback
    def on_language(module, kind, name, lang, unused):
        try:
            found = check(find_resource(module, kind, name, lang))
            size = resource_size(module, found)
            loaded = check(load_resource(module, found))
            data = C.string_at(check(lock_resource(loaded)), size)
            result[(identifier(kind), identifier(name), lang)] = data
            return True
        except Exception as error:
            errors.append(error)
            return False

    @NameCallback
    def on_name(module, kind, name, unused):
        return enum_langs(module, kind, name, on_language, 0)

    @TypeCallback
    def on_type(module, kind, unused):
        return enum_names(module, kind, on_name, 0)

    try:
        ok = enum_types(handle, on_type, 0)
        if errors:
            raise errors[0]
        check(ok)
        return result
    finally:
        check(free_library(handle))


def replace_icon(executable, ico):
    before_sections = pe_sections(executable.read_bytes())
    before = resources(executable)
    groups = [key for key in before if key[0] == 14]
    if not groups:
        raise ValueError("Executable has no icon group")
    raw = ico.read_bytes()
    reserved, kind, count = struct.unpack_from("<HHH", raw)
    if reserved or kind != 1 or count != 10:
        raise ValueError("Expected the validated ten-size ICO")
    base_id = max((key[1] for key in before if key[0] == 3 and isinstance(key[1], int)), default=0) + 1
    if base_id + count >= 65536:
        raise ValueError("No free native icon resource IDs")
    frames = []
    group = bytearray(struct.pack("<HHH", 0, 1, count))
    for i in range(count):
        width, height, colors, reserved, planes, depth, size, offset = struct.unpack_from("<BBBBHHII", raw, 6 + 16 * i)
        image = raw[offset:offset + size]
        if len(image) != size:
            raise ValueError("Truncated ICO frame")
        frames.append((base_id + i, image))
        group.extend(struct.pack("<BBBBHHIH", width, height, colors, reserved, planes, depth, size, base_id + i))
    handle = check(begin_update(str(executable), False))
    committed = False
    try:
        for language in {key[2] for key in groups}:
            for resource_id, image in frames:
                buffer = C.create_string_buffer(image)
                check(update_resource(handle, ref(3), ref(resource_id), language, buffer, len(image)))
        for _, name, language in groups:
            buffer = C.create_string_buffer(bytes(group))
            check(update_resource(handle, ref(14), ref(name), language, buffer, len(group)))
        check(end_update(handle, False))
        committed = True
    finally:
        if not committed:
            end_update(handle, True)
    after = resources(executable)
    for key, data in before.items():
        if key[0] not in (3, 14) and after.get(key) != data:
            raise ValueError(f"Non-icon resource changed: {key}")
    after_sections = pe_sections(executable.read_bytes())
    if {k: v for k, v in before_sections.items() if k != ".rsrc"} != {k: v for k, v in after_sections.items() if k != ".rsrc"}:
        raise ValueError("Non-resource PE section changed")
    for key in groups:
        if after[key] != bytes(group):
            raise ValueError("Icon group did not persist")
        for resource_id, image in frames:
            if after[(3, resource_id, key[2])] != image:
                raise ValueError("Icon frame did not persist")
    return {"icon_groups": len(groups), "icon_sizes": count,
            "non_resource_sections_unchanged": True, "non_icon_resources_unchanged": True}


def main():
    if sha(ARCHIVE.read_bytes()) != UPSTREAM_SHA:
        raise ValueError("Official archive hash mismatch")
    if OUTPUT.exists():
        raise FileExistsError(f"Refusing to overwrite existing output: {OUTPUT}")
    OUTPUT.mkdir(parents=True)
    originals = {}
    with zipfile.ZipFile(ARCHIVE) as archive:
        for entry in archive.infolist():
            path = (OUTPUT / entry.filename).resolve()
            if not path.is_relative_to(OUTPUT.resolve()) or ":" in entry.filename:
                raise ValueError(f"Unsafe archive path: {entry.filename}")
            if entry.is_dir():
                path.mkdir(parents=True, exist_ok=True)
                continue
            data = archive.read(entry)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            originals[path] = sha(data)
    executables = list(OUTPUT.rglob("DXManager.exe"))
    if len(executables) != 1:
        raise ValueError("Expected exactly one DXManager.exe")
    executable = executables[0]
    evidence = replace_icon(executable, BRAND / "DXManager.ico")
    video_icons = list(executable.parent.glob("tools/scrcpy/scrcpy.png"))
    if len(video_icons) != 1:
        raise ValueError("Expected the portable scrcpy window icon")
    shutil.copyfile(BRAND / "DXManager-256.png", video_icons[0])
    changed = {path for path, digest in originals.items() if sha(path.read_bytes()) != digest}
    if changed != {executable, video_icons[0]}:
        raise ValueError("Unexpected changed files in portable copy")
    evidence.update({"upstream_version": "2.5.0", "upstream_sha256": UPSTREAM_SHA,
                     "changed_files": [str(p.relative_to(OUTPUT)).replace("\\", "/") for p in sorted(changed)],
                     "files_preserved": len(originals) - len(changed),
                     "custom_exe_sha256": sha(executable.read_bytes()),
                     "gui_and_device_tested": False})
    (OUTPUT / "ICON-VALIDATION.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "CUSTOM-ICONS.txt").write_text(
        "DX Manager 2.5.0 - personal custom-icon copy (not an official upstream release).\n"
        "Only DXManager.exe native icon resources and tools/scrcpy/scrcpy.png were replaced.\n"
        "Application code, non-icon resources and all other bundled files are unchanged.\n"
        "Existing installations and settings were not modified. GUI/device behavior was not tested.\n"
        "Upstream: https://github.com/maze-mei/DX-Manager/releases/tag/v2.5.0\n"
        "Keep the original license and third-party notices.\n", encoding="utf-8")
    output_zip = shutil.make_archive(str(OUTPUT), "zip", OUTPUT)
    (ROOT / "dist" / (Path(output_zip).name + ".sha256")).write_text(sha(Path(output_zip).read_bytes()) + "  " + Path(output_zip).name + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    print(f"Executable: {executable}")
    print(f"Portable ZIP: {output_zip}")


if __name__ == "__main__":
    main()
