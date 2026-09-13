#!/usr/bin/env python3
"""Local macOS release signing. Never stores a password in the project."""
import argparse
import os
import pathlib
import secrets
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
PRIVATE = pathlib.Path.home() / ".owlett-private" / "signing"
STORE = PRIVATE / "owlett-release.p12"
SERVICE = "io.github.greatwyj1.owlett.release-signing"
JAVA = pathlib.Path(os.getenv("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def password():
    return subprocess.check_output(["security", "find-generic-password", "-a", "greatwyj1", "-s", SERVICE, "-w"], stderr=subprocess.DEVNULL).decode().rstrip("\n")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["create", "certificate", "build", "migration"])
    parser.add_argument("--resources", type=pathlib.Path, help="Verified local model/field-guide assets")
    args = parser.parse_args()
    if args.action == "create":
        PRIVATE.mkdir(parents=True, exist_ok=True, mode=0o700)
        PRIVATE.chmod(0o700)
        if STORE.exists():
            password()  # Do not silently replace a lost key or orphaned credential.
            print("Existing signing key retained.")
            return
        secret = secrets.token_urlsafe(40)
        # Store credentials first so a failed key generation can be recovered.
        run(["security", "add-generic-password", "-U", "-a", "greatwyj1", "-s", SERVICE, "-w", secret],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        env = dict(os.environ, OWLETT_SIGNING_PASSWORD=secret)
        run([JAVA / "bin/keytool", "-genkeypair", "-keystore", STORE, "-storetype", "PKCS12",
             "-storepass:env", "OWLETT_SIGNING_PASSWORD", "-keypass:env", "OWLETT_SIGNING_PASSWORD",
             "-alias", "owlett", "-keyalg", "RSA", "-keysize", "3072", "-validity", "36500",
             "-dname", "CN=Owlett, OU=Release, O=greatwyj1, C=CN"], env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        STORE.chmod(0o600)
        print("Created release key:", STORE)
    elif args.action == "certificate":
        run([JAVA / "bin/keytool", "-list", "-v", "-keystore", STORE,
             "-storepass:env", "OWLETT_SIGNING_PASSWORD", "-alias", "owlett"],
            env=dict(os.environ, OWLETT_SIGNING_PASSWORD=password()))
    elif args.action == "build":
        resource_args = ["-PbundledResourcesDir=" + str(args.resources.resolve())] if args.resources else []
        run([ROOT / "gradlew", "--no-daemon", *resource_args, ":app:assembleRelease"], cwd=ROOT,
            env=dict(os.environ, JAVA_HOME=str(JAVA), OWLETT_SIGNING_STORE=str(STORE),
                     OWLETT_SIGNING_PASSWORD=password()))
    else:
        if not (pathlib.Path.home() / ".android/debug.keystore").is_file():
            raise SystemExit("Existing debug key missing. Do not generate a replacement or uninstall the old app.")
        run([ROOT / "gradlew", "--no-daemon", "-PlegacyMigration=true", ":app:assembleDebug"],
            cwd=ROOT, env=dict(os.environ, JAVA_HOME=str(JAVA)))

if __name__ == "__main__":
    main()
