"""Reproduce the curated JAR from an official 1.7.9 release JAR."""
from pathlib import Path
import hashlib
import sys
import zipfile

source = Path(sys.argv[1])
expected = "7d0987f3b4ed239a9f08d5699890b946b1a633c33fb499c9deee622943d5a934"
if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
    raise SystemExit("Source JAR checksum does not match BRouter 1.7.9")
prefixes = tuple("btools/" + name + "/" for name in ("router", "mapaccess", "codec", "expressions", "util"))
target = Path(__file__).parent / "brouter-engine-1.7.9.jar"
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as result:
    for name in sorted(original.namelist()):
        if name.endswith(".class") and name.startswith(prefixes):
            entry = zipfile.ZipInfo(name, date_time=(2026, 4, 20, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(entry, original.read(name))
