#!/usr/bin/env python3
"""Build the device test APK, run it on a connected device, then uninstall it.

The test APK is signed with the same local key as the app so it can drive the
real VpnService. It is removed afterwards in all cases.
"""
import argparse
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_PACKAGE = "io.github.bindeng666.tacet.tests"
TEST_RUNNER = "io.github.bindeng666.tacet.test.DeviceTests"
MANIFEST = f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{TEST_PACKAGE}" android:versionCode="1" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35"/>
  <application android:label="Tacet 测试"/>
  <instrumentation android:name="{TEST_RUNNER}"
      android:targetPackage="io.github.bindeng666.tacet" android:functionalTest="true"/>
</manifest>"""


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def locate(sdk):
    tools = sdk / "build-tools" / "35.0.0"
    if not tools.exists():
        tools = sdk / "android-15"
    platform = sdk / "platforms" / "android-35" / "android.jar"
    if not platform.exists():
        platform = sdk / "android-35" / "android.jar"
    return tools, platform, sdk / "platform-tools" / "adb"


def main():
    parser = argparse.ArgumentParser(description="Run Tacet device tests.")
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True, help="Scratch directory for the test APK")
    parser.add_argument("--app-build", type=Path, required=True, help="build.py --work directory of the app")
    options = parser.parse_args()

    tools, platform, adb = locate(options.sdk)
    java = options.java / "bin"
    work = options.work
    work.mkdir(parents=True, exist_ok=True)

    manifest = work / "AndroidManifest.xml"
    manifest.write_text(MANIFEST, encoding="utf-8")
    run([tools / "aapt2", "link", "-I", platform, "--manifest", manifest, "-o", work / "unsigned.apk"])

    classes = work / "classes"
    classes.mkdir(exist_ok=True)
    run([java / "javac", "--release", "8", "-encoding", "UTF-8",
         "-cp", f"{platform}:{options.app_build / 'classes'}", "-d", classes,
         ROOT / "test" / "DeviceTests.java"])

    classes_jar = work / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w") as archive:
        for path in classes.rglob("*.class"):
            archive.write(path, path.relative_to(classes))

    dex = work / "dex"
    dex.mkdir(exist_ok=True)
    run([java / "java", "-cp", tools / "lib" / "d8.jar", "com.android.tools.r8.D8",
         "--min-api", "30", "--lib", platform,
         "--classpath", options.app_build / "classes.jar", "--output", dex, classes_jar])

    with zipfile.ZipFile(work / "unsigned.apk", "a") as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    run([tools / "zipalign", "-f", "4", work / "unsigned.apk", work / "aligned.apk"])

    signing = ROOT / ".signing"
    if not (signing / "release.p12").exists():
        sys.exit("Missing .signing/release.p12 — run build.py once to create the local signing key.")
    run([java / "java", "-jar", tools / "lib" / "apksigner.jar", "sign",
         "--ks", signing / "release.p12", "--ks-pass", f"file:{signing / 'password'}",
         "--out", work / "test.apk", work / "aligned.apk"])

    run([adb, "install", "-r", "-t", work / "test.apk"])
    try:
        result = run([adb, "shell", "am", "instrument", "-w", f"{TEST_PACKAGE}/{TEST_RUNNER}"],
                     capture_output=True, text=True, check=False)
        print(result.stdout, flush=True)
        if "PASS:" not in result.stdout or "FAILED" in result.stdout:
            sys.exit("Device verification failed")
    finally:
        run([adb, "uninstall", TEST_PACKAGE], check=False)


if __name__ == "__main__":
    main()
