Import("env")

import json
import os
import re
import ssl
from datetime import datetime
from ftplib import FTP, FTP_TLS, error_perm
from io import BytesIO
from pathlib import Path
from urllib.parse import urlparse


PROJECT_DIR = Path(env["PROJECT_DIR"])
BUILD_STATE_PATH = PROJECT_DIR / ".pio" / "build" / "build_version_state.json"


def _stringify_macro(value):
    if hasattr(env, "StringifyMacro"):
        return env.StringifyMacro(value)
    return '\\"{}\\"'.format(value)


def _project_option(name, default=""):
    try:
        return str(env.GetProjectOption(name, default)).strip()
    except Exception:
        return str(default).strip()


def _project_option_bool(name, default=False):
    value = _project_option(name, "yes" if default else "no").lower()
    return value in ("1", "true", "yes", "on")


def _runtime_env(name, fallback=""):
    value = os.environ.get(name)
    if value is None:
        return fallback
    return value.strip()


def _parse_semver(value):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", value.strip())
    if not match:
        raise RuntimeError("custom_firmware_version deve usare il formato semver x.y.z")
    return tuple(int(group) for group in match.groups())


def _format_semver(version_tuple):
    return "{}.{}.{}".format(version_tuple[0], version_tuple[1], version_tuple[2])


def _read_protocol_version():
    header_path = PROJECT_DIR / "src" / "comm_shared.h"
    content = header_path.read_text(encoding="utf-8")
    match = re.search(r'COMM_PROTOCOL_VERSION\s*=\s*"([^"]+)"', content)
    if not match:
        return "0.0"
    return match.group(1).strip()


def _publish_settings():
    return {
        "enabled": _project_option_bool("custom_publish_enabled", True),
        "skip": _runtime_env("RIDESCOPE_SKIP_FTP_PUBLISH", "").lower() in ("1", "true", "yes", "on"),
        "url": _project_option("custom_publish_ftp_url", ""),
        "user": _runtime_env("RIDESCOPE_FTP_USER", _project_option("custom_publish_ftp_user", "anonymous")),
        "password": _runtime_env("RIDESCOPE_FTP_PASSWORD", _project_option("custom_publish_ftp_password", "")),
        "firmware_name": _project_option("custom_publish_firmware_name", "firmware.bin"),
        "manifest_name": _project_option("custom_publish_manifest_name", "manifest.json"),
        "timeout_s": int(_project_option("custom_publish_ftp_timeout_s", "15")),
        "ftps_enabled": _project_option_bool("custom_publish_ftps_enabled", False),
        "ftps_verify_cert": _project_option_bool("custom_publish_ftps_verify_cert", True),
    }


def _ftp_login(ftp_client, settings, parsed_url):
    ftp_user = parsed_url.username or settings["user"] or "anonymous"
    ftp_password = parsed_url.password or settings["password"] or ""
    if ftp_user.lower() == "anonymous" and not ftp_password:
        ftp_password = "anonymous@"
    ftp_client.login(user=ftp_user, passwd=ftp_password)


def _use_ftps(parsed_url, settings):
    return parsed_url.scheme.lower() == "ftps" or settings["ftps_enabled"]


def _open_ftp_client(settings, parsed_url):
    timeout = settings["timeout_s"]
    if _use_ftps(parsed_url, settings):
        context = ssl.create_default_context() if settings["ftps_verify_cert"] else ssl._create_unverified_context()
        ftp_client = FTP_TLS(context=context, timeout=timeout)
        ftp_client.connect(parsed_url.hostname, parsed_url.port or 21, timeout=timeout)
        ftp_client.auth()
        _ftp_login(ftp_client, settings, parsed_url)
        ftp_client.prot_p()
        return ftp_client

    ftp_client = FTP(timeout=timeout)
    ftp_client.connect(parsed_url.hostname, parsed_url.port or 21, timeout=timeout)
    _ftp_login(ftp_client, settings, parsed_url)
    return ftp_client


def _enter_remote_dir(ftp_client, remote_dir, create_missing=False):
    ftp_client.cwd("/")
    for segment in remote_dir.split("/"):
        if not segment:
            continue
        try:
            ftp_client.cwd(segment)
        except error_perm:
            if not create_missing:
                raise
            ftp_client.mkd(segment)
            ftp_client.cwd(segment)


def _parse_manifest_build(manifest_payload):
    firmware = manifest_payload.get("firmware")
    if not isinstance(firmware, dict):
        return None

    build_value = firmware.get("build")
    if not isinstance(build_value, str):
        return None

    try:
        return _parse_semver(build_value)
    except Exception:
        return None


def _read_remote_build_version():
    settings = _publish_settings()
    if settings["skip"] or not settings["enabled"]:
        return None

    parsed_url = urlparse(settings["url"])
    if parsed_url.scheme.lower() not in ("ftp", "ftps") or not parsed_url.hostname:
        return None

    ftp_client = None
    try:
        ftp_client = _open_ftp_client(settings, parsed_url)
        _enter_remote_dir(ftp_client, parsed_url.path or "/", create_missing=False)
        buffer = BytesIO()
        ftp_client.retrbinary("RETR {}".format(settings["manifest_name"]), buffer.write)
        remote_manifest = json.loads(buffer.getvalue().decode("utf-8"))
        return _parse_manifest_build(remote_manifest)
    except Exception:
        return None
    finally:
        try:
            ftp_client.quit()
        except Exception:
            ftp_client.close()


def _read_local_build_version():
    if not BUILD_STATE_PATH.exists():
        return None

    try:
        return _parse_manifest_build(json.loads(BUILD_STATE_PATH.read_text(encoding="utf-8")))
    except Exception:
        return None


def _next_build_version():
    base_version = _parse_semver(_project_option("custom_firmware_version", "0.0.0"))
    known_versions = [version for version in (_read_remote_build_version(), _read_local_build_version()) if version is not None]
    if not known_versions:
        return _format_semver(base_version)

    last_known_version = max(known_versions)

    if last_known_version[:2] == base_version[:2] and last_known_version[2] >= base_version[2]:
        return _format_semver((base_version[0], base_version[1], last_known_version[2] + 1))

    if last_known_version > base_version:
        return _format_semver((last_known_version[0], last_known_version[1], last_known_version[2] + 1))

    return _format_semver(base_version)


def _build_metadata():
    build_time = datetime.now().astimezone()
    build_stamp = build_time.strftime("%Y%m%d.%H%M%S")

    return {
        "firmware": {
            "build": _next_build_version(),
            "timestamp": build_stamp,
            "protocol": _read_protocol_version(),
        },
    }


def _publish_file(ftp_client, local_path, remote_name):
    with local_path.open("rb") as handle:
        ftp_client.storbinary("STOR {}".format(remote_name), handle)
    print("Published {} -> {}".format(local_path.name, remote_name))


def _publish_release_artifacts(firmware_path, manifest_path):
    settings = _publish_settings()
    if settings["skip"]:
        print("Skipped remote publish because RIDESCOPE_SKIP_FTP_PUBLISH is set")
        return

    if not settings["enabled"]:
        print("Skipped remote publish because custom_publish_enabled is disabled")
        return

    parsed_url = urlparse(settings["url"])
    if parsed_url.scheme.lower() not in ("ftp", "ftps") or not parsed_url.hostname:
        raise RuntimeError("custom_publish_ftp_url non valido: {}".format(settings["url"]))

    transport_name = "FTPS" if _use_ftps(parsed_url, settings) else "FTP"
    print("Publishing build artifacts to {} via {}".format(settings["url"], transport_name))

    ftp_client = None
    try:
        ftp_client = _open_ftp_client(settings, parsed_url)
        _enter_remote_dir(ftp_client, parsed_url.path or "/", create_missing=True)
        _publish_file(ftp_client, firmware_path, settings["firmware_name"])
        _publish_file(ftp_client, manifest_path, settings["manifest_name"])
    finally:
        try:
            ftp_client.quit()
        except Exception:
            ftp_client.close()


BUILD_METADATA = _build_metadata()
print("Resolved firmware build {}".format(BUILD_METADATA["firmware"]["build"]))

env.Append(
    CPPDEFINES=[
        ("AUTO_FIRMWARE_BUILD", _stringify_macro(BUILD_METADATA["firmware"]["build"])),
        ("AUTO_FIRMWARE_TIMESTAMP", _stringify_macro(BUILD_METADATA["firmware"]["timestamp"])),
        ("AUTO_FIRMWARE_PROTOCOL", _stringify_macro(BUILD_METADATA["firmware"]["protocol"])),
    ]
)


def _write_manifest(source, target, env, **kwargs):
    firmware_path = Path(str(target[0]))
    manifest_path = Path(env.subst("$BUILD_DIR")) / "manifest.json"
    manifest_payload = json.dumps(BUILD_METADATA, indent=2) + "\n"
    manifest_path.write_text(manifest_payload, encoding="utf-8")
    BUILD_STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    BUILD_STATE_PATH.write_text(manifest_payload, encoding="utf-8")
    print("Generated {}".format(manifest_path))
    _publish_release_artifacts(firmware_path, manifest_path)


env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", _write_manifest)
