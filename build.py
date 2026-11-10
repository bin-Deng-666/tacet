#!/usr/bin/env python3
"""Build Tacet with an installed Android SDK and JDK.

No Gradle, no network access, no third-party Python packages.
"""
import argparse
import hashlib
import secrets
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PACKAGE_PATH = ROOT / "src" / "io" / "github" / "bindeng666" / "tacet"
PURE_MODULES = ["DomainPolicy.java", "DnsMessage.java", "IpPacket.java"]
KEY_ALIAS = "tacet"
APK_NAME = "tacet.apk"


def run(args):
    subprocess.run([str(x) for x in args], check=True)


def locate(sdk):
    """Return (build-tools, android.jar) for either SDK-manager or unzipped layout."""
    tools = sdk / "build-tools" / "35.0.0"
    if not tools.exists():
        tools = sdk / "android-15"
    platform = sdk / "platforms" / "android-35" / "android.jar"
    if not platform.exists():
        platform = sdk / "android-35" / "android.jar"
    return tools, platform


def keystore_alias(java, keystore, password):
    """Return the first key alias in an existing PKCS12 keystore."""
    result = subprocess.run([str(java / "bin" / "keytool"), "-list", "-keystore", str(keystore),
                             "-storepass:file", str(password)], capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"Cannot read keystore {keystore}: {result.stderr.strip()}")
    for line in result.stdout.splitlines():
        if "PrivateKeyEntry" in line:
            return line.split(",")[0].strip()
    raise SystemExit(f"Keystore {keystore} contains no private key entry")


def build(sdk, java, work, output, tests=False):
    work.mkdir(parents=True, exist_ok=True)
    tools, platform = locate(sdk)
    javac, jvm, keytool = java / "bin" / "javac", java / "bin" / "java", java / "bin" / "keytool"
    for path in (tools / "aapt2", tools / "zipalign", tools / "lib" / "d8.jar", platform, javac):
        if not path.exists():
            raise SystemExit(f"Missing build tool: {path}")

    if tests:
        testclasses = work / "testclasses"
        testclasses.mkdir(exist_ok=True)
        pure = [PACKAGE_PATH / name for name in PURE_MODULES]
        run([javac, "-d", testclasses, *pure, ROOT / "test" / "CoreTests.java"])
        run([jvm, "-cp", testclasses, "io.github.bindeng666.tacet.test.CoreTests"])

    res, unsigned, aligned = work / "res.zip", work / "unsigned.apk", work / "aligned.apk"
    for path in (res, unsigned, aligned):
        path.unlink(missing_ok=True)

    run([tools / "aapt2", "compile", "--dir", ROOT / "res", "-o", res])
    generated = work / "generated"
    generated.mkdir(exist_ok=True)
    run([tools / "aapt2", "link", "-o", unsigned, "-I", platform,
         "--manifest", ROOT / "AndroidManifest.xml", "--java", generated,
         "-A", ROOT / "assets", "--min-sdk-version", "30", "--target-sdk-version", "35", res])

    classes = work / "classes"
    classes.mkdir(exist_ok=True)
    sources = sorted((ROOT / "src").rglob("*.java")) + sorted(generated.rglob("*.java"))
    run([javac, "-encoding", "UTF-8", "--release", "8", "-classpath", platform, "-d", classes, *sources])

    jar = work / "classes.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes))
    dex = work / "dex"
    dex.mkdir(exist_ok=True)
    run([jvm, "-cp", tools / "lib" / "d8.jar", "com.android.tools.r8.D8",
         "--release", "--min-api", "30", "--lib", platform, "--output", dex, jar])
    with zipfile.ZipFile(unsigned, "a") as archive:
        for path in sorted(dex.glob("*.dex")):
            archive.write(path, path.name, compress_type=zipfile.ZIP_STORED)
    run([tools / "zipalign", "-f", "-P", "16", "4", unsigned, aligned])

    sign = ROOT / ".signing"
    sign.mkdir(mode=0o700, exist_ok=True)
    password, keystore = sign / "password", sign / "release.p12"
    if not password.exists():
        password.write_text(secrets.token_hex(24))
        password.chmod(0o600)
    alias = KEY_ALIAS
    if not keystore.exists():
        run([keytool, "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
             "-storepass:file", password, "-alias", alias, "-keyalg", "RSA",
             "-keysize", "3072", "-validity", "10000", "-dname", "CN=Tacet Android App", "-noprompt"])
        keystore.chmod(0o600)
    else:
        alias = keystore_alias(java, keystore, password)

    output.parent.mkdir(parents=True, exist_ok=True)
    signer = [jvm, "-jar", tools / "lib" / "apksigner.jar"]
    run([*signer, "sign", "--ks", keystore, "--ks-pass", f"file:{password}",
         "--ks-key-alias", alias, "--out", output, aligned])
    run([*signer, "verify", "--verbose", output])
    print(f"APK: {output}\nBytes: {output.stat().st_size}\nSHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build Tacet.")
    parser.add_argument("--sdk", type=Path, required=True, help="Android SDK or unzipped build-tools/platform directory")
    parser.add_argument("--java", type=Path, required=True, help="JDK home containing bin/javac")
    parser.add_argument("--work", type=Path, help="Keep intermediate files in this directory")
    parser.add_argument("--output", type=Path, default=ROOT / APK_NAME, help="APK output path")
    parser.add_argument("--test", action="store_true", help="Run core protocol and domain tests first")
    options = parser.parse_args()
    if options.work:
        build(options.sdk, options.java, options.work, options.output, options.test)
    else:
        with tempfile.TemporaryDirectory(prefix="tacet-build-") as directory:
            build(options.sdk, options.java, Path(directory), options.output, options.test)
