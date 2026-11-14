"""GitHub-only packaging checks. Never imports or executes classes from the jars."""
import io
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[2]
    versions = re.findall(r'^version = "([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)"$',
                          (root / "build.gradle.kts").read_text(encoding="utf-8"), re.M)
    if len(versions) != 1:
        raise ValueError("Expected one validated root version")
    version = versions[0]
    server = root / f"build/libs/NetSqueeze-{version}-all.jar"
    client = root / f"client/build/libs/NetSqueeze-Client-{version}.jar"
    with zipfile.ZipFile(server) as jar:
        names = set(jar.namelist())
        assert "me/albert/netsqueeze/NetSqueeze.class" in names
        assert "me/albert/netsqueeze/protocol/TunnelProtocol.class" in names
        assert "me/albert/netsqueeze/protocol/SnapshotCodec.class" in names
        assert "me/albert/netsqueeze/protocol/ZstdDictionary.class" in names
        assert "me/albert/netsqueeze/protocol/DictionaryProtocol.class" in names
        assert "me/albert/netsqueeze/tunnel/TunnelCompressor.class" in names
        assert "com/github/luben/zstd/Zstd.class" in names
        assert any(n.endswith((".so", ".dll", ".dylib")) for n in names), "Missing ZSTD native libraries"
        assert "me/albert/netsqueeze/libs/kotlin/jvm/internal/Intrinsics.class" in names
        assert not any(name.startswith("kotlin/") for name in names), "Kotlin was not relocated"
        descriptor = jar.read("plugin.yml").decode("utf-8")
        assert re.search(r"version:\s*['\"]?" + re.escape(version) + r"['\"]?\s*$", descriptor, re.M)
        assert "${version}" not in descriptor
        config = jar.read("config.yml").decode("utf-8")
        assert "compression-level: 6" in config
        assert "chunk-cache-enabled: false" in config
        assert "zstd-dictionary-enabled: true" in config
    with zipfile.ZipFile(client) as jar:
        assert "me/albert/netsqueeze/protocol/TunnelProtocol.class" in jar.namelist()
        assert "me/albert/netsqueeze/client/TunnelClientHandler.class" in jar.namelist()
        assert "me/albert/netsqueeze/client/ClientChunkCache.class" in jar.namelist()
        assert "me/albert/netsqueeze/client/cache/SnapshotDiskStore.class" in jar.namelist()
        assert "me/albert/netsqueeze/protocol/SnapshotCodec.class" in jar.namelist()
        assert "me/albert/netsqueeze/client/ZstdFrameDecoder.class" in jar.namelist()
        assert "me/albert/netsqueeze/client/ZstdDictionarySupport.class" in jar.namelist()
        assert "me/albert/netsqueeze/protocol/ZstdDictionary.class" in jar.namelist()
        assert "me/albert/netsqueeze/protocol/DictionaryProtocol.class" in jar.namelist()
        assert "me/albert/netsqueeze/protocol/ZstdFrameHeader.class" in jar.namelist()
        meta = json.loads(jar.read("fabric.mod.json"))
        assert meta["version"] == version
        assert meta["environment"] == "client"
        nested_paths = [entry["file"] for entry in meta.get("jars", [])]
        zstd_jars = [name for name in nested_paths if "zstd" in name.lower()]
        assert len(zstd_jars) == 1, "Client must package exactly one ZSTD nested jar"
        with zipfile.ZipFile(io.BytesIO(jar.read(zstd_jars[0]))) as nested:
            assert "com/github/luben/zstd/Zstd.class" in nested.namelist()
            assert any(n.endswith((".so", ".dll", ".dylib")) for n in nested.namelist()), "Missing client ZSTD natives"
        mixins = json.loads(jar.read("netsqueeze.mixins.json"))
        for name in mixins["client"]:
            assert mixins["package"].replace(".", "/") + "/" + name + ".class" in jar.namelist()
    dist = root / "dist"
    dist.mkdir(exist_ok=True)
    checksums = []
    for source in (server, client):
        shutil.copy2(source, dist / source.name)
        checksums.append(f"{hashlib.sha256(source.read_bytes()).hexdigest()}  {source.name}\n")
    (dist / "SHA256SUMS.txt").write_text("".join(checksums), encoding="utf-8")
    print(f"Validated plugin and client {version}; staged exactly two jars and checksums")


if __name__ == "__main__":
    main()
