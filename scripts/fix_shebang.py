"""
Copies cgi/verify.py into the Apache cgi-bin directory, rewriting its
shebang line to point at the python.exe that is actually installed.

Why this needs doing at all
---------------------------
Apache on Windows works out how to run a CGI script by reading its
shebang line. The default CPython installation path is

    C:\\Program Files\\Python313\\python.exe

and that space in "Program Files" breaks interpreter resolution. Every
Windows path has an equivalent 8.3 short form without spaces, so this
script substitutes that instead:

    C:/PROGRA~1/Python313/python.exe

Usage:  python scripts/fix_shebang.py <source.py> <destination-dir>
"""

import ctypes
import os
import sys


def short_path(path):
    """The 8.3 short form of a Windows path, or the path unchanged."""
    if os.name != "nt":
        return path
    buf = ctypes.create_unicode_buffer(512)
    length = ctypes.windll.kernel32.GetShortPathNameW(path, buf, 512)
    return buf.value if length else path


def main():
    if len(sys.argv) != 3:
        print(__doc__.strip())
        return 2

    source, dest_dir = sys.argv[1], sys.argv[2]
    dest = os.path.join(dest_dir, os.path.basename(source))

    interpreter = short_path(sys.executable).replace("\\", "/")

    with open(source, "r", encoding="utf-8") as fh:
        lines = fh.readlines()

    if lines and lines[0].startswith("#!"):
        lines[0] = "#!" + interpreter + "\n"
    else:
        lines.insert(0, "#!" + interpreter + "\n")

    os.makedirs(dest_dir, exist_ok=True)
    # Newlines written verbatim: Apache is happy with either line ending,
    # and rewriting them would only risk corrupting the file.
    with open(dest, "w", encoding="utf-8", newline="") as fh:
        fh.writelines(lines)

    print("[shebang] %s -> %s" % (dest, interpreter))
    return 0


if __name__ == "__main__":
    sys.exit(main())
