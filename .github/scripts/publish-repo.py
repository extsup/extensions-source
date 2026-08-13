import gzip
import hashlib
import html
import json
import math
import os
import re
import subprocess
import sys
import time
from functools import cache
from pathlib import Path
from zipfile import ZipFile

from google.protobuf import json_format

import index_pb2

APPLICATION_ICON_320_REGEX = re.compile(
    r"^application-icon-320:'([^']+)'", re.MULTILINE
)
LANGUAGE_REGEX = re.compile(r"tachiyomi-([^.]+)")


@cache
def aapt() -> Path:
    *_, build_tools = (Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir()
    return build_tools / "aapt"


# Artifacts downloaded from the build jobs: one APK per extension plus the source metadata JSON
# emitted by each assembleRelease.
ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# The checked-out `repo` branch we publish into (the working directory).
REPO_DIR = Path.cwd()
REPO_APK_DIR = Path("/tmp/apk")
REPO_JAR_DIR = Path("/tmp/jar")
REPO_ICON_DIR = REPO_DIR / "icon"
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)
REPO_JAR_DIR.mkdir(parents=True, exist_ok=True)
REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

# Configuration from environment variables
REPO_BRANCH = os.environ.get("REPO_BRANCH", "ori")
REPO_NAME = os.environ.get("REPO_NAME", "extsup/extensions")
SIGNING_KEY = os.environ.get("SIGNING_KEY", "af8dce867726424977496de4c45340bcf7388c184b75a823b6d7d69bee8fafd4")
CONTACT_WEBSITE = os.environ.get("CONTACT_WEBSITE", "https://www.facebook.com/profile.php?id=61591490231900&mibextid=rS40aB7S9Ucbxw6v")

APK_BASE_URL = f"https://cdn.jsdelivr.net/gh/{REPO_NAME}@{REPO_BRANCH}/apk"
JAR_BASE_URL = f"https://raw.githubusercontent.com/{REPO_NAME}/{REPO_BRANCH}/jar"
ICON_BASE_URL = f"https://cdn.jsdelivr.net/gh/{REPO_NAME}@{REPO_BRANCH}/icon"
RELEASE_BASE_URL = f"https://github.com/{REPO_NAME}/releases/download"

# Rate limiting configuration
ASSET_LIMIT = 495  # Actual limit is 1000 but we upload 2 items per extension
RETRY_ATTEMPTS = 4
RETRY_BASE_DELAY = 60
UPLOAD_CHUNK_SIZE = 80
UPLOAD_CHUNK_INTERVAL = 30

to_delete: list[str] = json.loads(sys.argv[1])
current_sha = sys.argv[2] if len(sys.argv) > 2 else None
current_sha_short = current_sha[:7] if current_sha else "manual"

# Load release assets tracking
release_assets_path = REPO_DIR / "release-assets.json"
if release_assets_path.exists():
    with release_assets_path.open() as f:
        release_assets = json.load(f)
else:
    release_assets = {}

updated_release_assets = {
    package_name: assets
    for package_name, assets in release_assets.items()
    if not any(package_name.endswith(f".{module}") for module in to_delete)
}

# Drop stale repo assets for deleted/rebuilt modules
for module in to_delete:
    for file in REPO_APK_DIR.glob(f"tachiyomi-{module}-v*.*.*.apk"):
        print(f"removing {file.name}")
        file.unlink(missing_ok=True)
    for file in REPO_JAR_DIR.glob(f"tachiyomi-{module}-v*.*.*.jar"):
        print(f"removing {file.name}")
        file.unlink(missing_ok=True)
    for file in REPO_ICON_DIR.glob(f"eu.kanade.tachiyomi.extension.{module}.png"):
        print(f"removing {file.name}")
        file.unlink(missing_ok=True)

# Build index entries for the freshly built apks
new_extensions: list[tuple[index_pb2.Extension, Path, Path, bool]] = []
published_files: set[Path] = set()


def extract_icon_from_apk(apk: Path, package_name: str) -> Path:
    """Extract icon from APK and save to icon directory"""
    badging = subprocess.check_output(
        [aapt(), "dump", "--include-meta-data", "badging", apk]
    ).decode()
    application_icon = APPLICATION_ICON_320_REGEX.search(badging).group(1)
    
    icon_path = REPO_ICON_DIR / f"{package_name}.png"
    with (
        ZipFile(apk) as z,
        z.open(application_icon) as i,
        icon_path.open("wb") as f,
    ):
        f.write(i.read())
    
    return icon_path


for info_file in ARTIFACTS_DIR.glob("**/extsup-source-info.json"):
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)
    package_name = info["packageName"]
    
    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise FileNotFoundError(
            f"{package_name}: no release apk found under {info_file.parent}"
        )

    jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
    if jar is None:
        raise FileNotFoundError(
            f"{package_name}: no release jar found under {info_file.parent}"
        )

    # Read and calculate checksums
    apk_bytes = apk.read_bytes()
    jar_bytes = jar.read_bytes()
    
    apk_name = apk.name.replace("-release.apk", ".apk")
    repo_apk = REPO_APK_DIR / apk_name
    repo_jar = REPO_JAR_DIR / jar.name
    
    # Track assets with checksums
    assets = {
        "apk": {
            "name": apk_name,
            "sha256": hashlib.sha256(apk_bytes).hexdigest(),
        },
        "jar": {
            "name": jar.name,
            "sha256": hashlib.sha256(jar_bytes).hexdigest(),
        },
    }
    
    # Check if extension changed (new or updated)
    changed = (
        package_name not in release_assets
        or release_assets.get(package_name) != assets
    )
    
    # Write files
    published_files.update((repo_apk, repo_jar))
    updated_release_assets[package_name] = assets
    
    # Extract icon from APK
    icon_path = extract_icon_from_apk(apk, package_name)
    published_files.add(icon_path)

    new_extensions.append(
        (
            index_pb2.Extension(
                name=info["name"],
                packageName=package_name,
                resources=index_pb2.Resources(
                    apkUrl=f"{APK_BASE_URL}/{apk_name}",
                    jarUrl=f"{JAR_BASE_URL}/{jar.name}",
                    iconUrl=f"{ICON_BASE_URL}/{package_name}.png",
                ),
                extensionLib=info["extensionLib"],
                versionCode=info["versionCode"],
                versionName=info["versionName"],
                contentWarning=info["contentWarning"],
                sources=[
                    index_pb2.Source(
                        id=int(source["id"]),
                        name=source["name"],
                        language=source["lang"],
                        homeUrl=source["baseUrl"],
                        mirrorUrls=source.get("mirrorUrls", []),
                    )
                    for source in info["sources"]
                ],
            ),
            repo_apk,
            repo_jar,
            changed,
        )
    )

new_extensions.sort(key=lambda item: item[0].packageName)

# Calculate release batching
total_extensions = len(new_extensions)
release_count = math.ceil(total_extensions / ASSET_LIMIT) if total_extensions else 0
ext_per_release = math.ceil(total_extensions / release_count) if release_count else 0


def get_release_tag(batch_index: int) -> str:
    return (
        f"{current_sha_short}-{batch_index}" if release_count > 1 else current_sha_short
    )

# Load remote index for URL fallback
index_path = REPO_DIR.joinpath("index.json")
if index_path.exists():
    with index_path.open() as f:
        remote_proto = json_format.Parse(f.read(), index_pb2.Index())
else:
    remote_proto = index_pb2.Index()

remote_extensions = {
    ext.packageName: ext for ext in remote_proto.extensionList.extensions
}

# Update apkUrl/jarUrl to GitHub Releases URL
for i, (ext, apk, jar, changed) in enumerate(new_extensions):
    if changed:
        tag = get_release_tag(i // ext_per_release)
        ext.resources.apkUrl = f"{RELEASE_BASE_URL}/{tag}/{apk.name}"
        ext.resources.jarUrl = f"{RELEASE_BASE_URL}/{tag}/{jar.name}"
    else:
        old_resources = remote_extensions[ext.packageName].resources
        ext.resources.apkUrl = old_resources.apkUrl
        ext.resources.jarUrl = old_resources.jarUrl


# Merge with the already-published index
all_extensions = [
    ext
    for ext in remote_proto.extensionList.extensions
    if not any(ext.packageName.endswith(f".{module}") for module in to_delete)
]
all_extensions.extend([ext for ext, _, _, _ in new_extensions])
all_extensions.sort(key=lambda ext: ext.packageName)

# Create main index
index = index_pb2.Index(
    name="Extsup",
    badgeLabel="Extsup",
    signingKey=SIGNING_KEY,
    contact=index_pb2.Contact(
        website=CONTACT_WEBSITE
    ),
    extensionList=index_pb2.ExtensionList(extensions=all_extensions),
)

# Write index files
with REPO_DIR.joinpath("index.json").open("w", encoding="utf-8") as f:
    f.write(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        )
    )

with REPO_DIR.joinpath("index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True)))

# Save release assets tracking
with release_assets_path.open("w", encoding="utf-8") as f:
    json.dump(updated_release_assets, f, indent=2, sort_keys=True)
    f.write("\n")


def get_legacy_lang(ext) -> str:
    apk_filename = ext.resources.apkUrl.split("/")[-1]
    lang = LANGUAGE_REGEX.search(apk_filename).group(1)
    if len(ext.sources) == 1:
        source_language = ext.sources[0].language
        if (
            source_language != lang
            and source_language not in {"all", "other"}
            and lang not in {"all", "other"}
        ):
            lang = source_language
    return lang


# Generate legacy index
legacy_json_index = [
    {
        "name": f"Tachiyomi: {ext.name}",
        "pkg": ext.packageName,
        "apk": ext.resources.apkUrl.split("/")[-1],
        "lang": get_legacy_lang(ext),
        "code": ext.versionCode,
        "version": ext.versionName,
        "nsfw": 1 if ext.contentWarning > 2 else 0,
        "sources": [
            {
                "name": source.name,
                "lang": source.language,
                "id": str(source.id),
                "baseUrl": source.homeUrl,
            }
            for source in ext.sources
        ],
    }
    for ext in all_extensions
]

with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as f:
    json.dump(legacy_json_index, f, ensure_ascii=False, separators=(",", ":"))

# Generate repo.json
repo_json = {
    "meta": {
        "name": "Extsup",
        "shortName": "Extsup",
        "website": CONTACT_WEBSITE,
        "signingKeyFingerprint": SIGNING_KEY
    }
}
with REPO_DIR.joinpath("repo.json").open("w", encoding="utf-8") as f:
    json.dump(repo_json, f, indent=2)

# Generate HTML index
with REPO_DIR.joinpath("index.html").open("w", encoding="utf-8") as f:
    f.write(
        '<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n'
    )
    for ext in all_extensions:
        apk_escaped = html.escape(ext.resources.apkUrl)
        name_escaped = html.escape(f"Tachiyomi: {ext.name}")
        f.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    f.write("</pre>\n</body>\n</html>\n")


# --- Upload to GitHub Releases (only if enabled) ---
if not new_extensions:
    sys.exit(0)

if os.environ.get("ENABLE_RELEASES", "true").lower() == "false":
    print("GitHub Releases upload disabled")
    sys.exit(0)


def run_gh(*args: str, success_codes: tuple[int, ...] = ()) -> str:
    """Run GitHub CLI with retry logic"""
    delay = RETRY_BASE_DELAY
    for attempt in range(1, RETRY_ATTEMPTS + 1):
        result = subprocess.run(
            ["gh", *args],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode == 0 or result.returncode in success_codes:
            return result.stdout.strip()

        if attempt < RETRY_ATTEMPTS and "secondary rate limit" in result.stderr.lower():
            print(
                f"secondary rate limit hit, retrying in {delay}s "
                f"(attempt {attempt}/{RETRY_ATTEMPTS})",
                file=sys.stderr,
            )
            time.sleep(delay)
            delay *= 2
            continue

        print(f"gh {' '.join(args)} failed: {result.stderr}", file=sys.stderr)
        sys.exit(result.returncode)


def create_release(tag: str):
    """Create a draft GitHub release"""
    if run_gh(
        "release",
        "view",
        tag,
        "--repo",
        REPO_NAME,
        "--json",
        "tagName",
        success_codes=(1,),
    ):
        print(f"Release {tag} already exists")
        return

    print(f"Creating release {tag}")
    run_gh(
        "release",
        "create",
        tag,
        "--repo",
        REPO_NAME,
        "--draft",
        "--title",
        f"Repository Update {tag}",
        "--notes",
        f"Automated update from extsup/extensions-source@{current_sha if current_sha else 'manual'}",
    )


def publish_release(tag: str):
    """Publish a draft release"""
    print(f"Publishing release {tag}")
    run_gh("release", "edit", tag, "--repo", REPO_NAME, "--draft=false")


def upload_assets(tag: str, files: list[Path]):
    """Upload assets to release with chunking"""
    if not files:
        return
    print(f"Uploading {len(files)} assets to {tag}")
    for i in range(0, len(files), UPLOAD_CHUNK_SIZE):
        chunk = files[i : i + UPLOAD_CHUNK_SIZE]
        if i:
            time.sleep(UPLOAD_CHUNK_INTERVAL)
        print(f"  assets {i + 1}-{i + len(chunk)} of {len(files)}")
        run_gh(
            "release",
            "upload",
            tag,
            *[str(f) for f in chunk],
            "--repo",
            REPO_NAME,
            "--clobber",
        )
    publish_release(tag)


# Upload changed extensions to releases
for i in range(0, total_extensions, ext_per_release):
    batch = new_extensions[i : i + ext_per_release]
    tag = get_release_tag(i // ext_per_release)
    files_to_upload = []
    for ext, apk, jar, changed in batch:
        if changed:
            files_to_upload.extend([apk, jar])

    if not files_to_upload:
        print(f"Nothing changed for {tag}, skipping release")
        continue

    create_release(tag)
    upload_assets(tag, files_to_upload)