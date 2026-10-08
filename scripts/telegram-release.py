"""Send a published Square release to its announcement channel and forum topic."""
import json
import os
from pathlib import Path
import sys
import tempfile
from urllib import error, parse, request
import uuid

ABIS = ("arm64-v8a", "armeabi-v7a", "universal")
LABELS = {
    "arm64-v8a": "64-bit Android phones",
    "armeabi-v7a": "32-bit Android phones",
    "universal": "Universal — use this if unsure",
}


def messages(release):
    title = release.get("name") or release["tag_name"]
    text = f"Square {title}\n\n{release['html_url']}\n\n{release.get('body') or 'Release notes are available on GitHub.'}"
    # Leave room below Telegram's 4096-character limit, including emoji pairs.
    chunks = []
    while len(text.encode("utf-16-le")) // 2 > 3500:
        end = 0
        units = 0
        for char in text:
            size = len(char.encode("utf-16-le")) // 2
            if units + size > 3500:
                break
            units += size
            end += 1
        newline = text.rfind("\n", 0, end)
        if newline > end // 2:
            end = newline + 1
        chunks.append(text[:end])
        text = text[end:]
    if text:
        chunks.append(text)
    return chunks


def apks(release):
    version = release["tag_name"].removeprefix("v")
    result = []
    for abi in ABIS:
        name = f"square-{version}-{abi}.apk"
        found = [a for a in release.get("assets", []) if a["name"] == name]
        if len(found) != 1 or found[0].get("state") != "uploaded":
            raise ValueError(f"Missing uploaded APK: {name}")
        asset = found[0]
        if not 0 < asset["size"] < 50_000_000:
            raise ValueError(f"APK exceeds Telegram upload limit: {name}")
        url = parse.urlparse(asset["browser_download_url"])
        if url.scheme != "https" or url.hostname != "github.com":
            raise ValueError(f"Unexpected download source for {name}")
        result.append((asset, LABELS[abi]))
    return result


def telegram(token, method, fields, file=None):
    if file is None:
        data = json.dumps(fields).encode()
        content_type = "application/json"
    else:
        boundary = uuid.uuid4().hex
        parts = []
        for key, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="document"; filename="{file.name}"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n'.encode())
        parts.extend((file.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()))
        data = b"".join(parts)
        content_type = f"multipart/form-data; boundary={boundary}"
    req = request.Request(f"https://api.telegram.org/bot{token}/{method}", data=data,
                          headers={"Content-Type": content_type})
    try:
        with request.urlopen(req, timeout=120) as response:
            result = json.load(response)
    except error.HTTPError as exc:
        # Never print request URLs: they contain the bot credential.
        raise RuntimeError(f"Telegram {method} failed (HTTP {exc.code}). Check bot permissions and destination settings.") from None
    except error.URLError:
        raise RuntimeError(f"Telegram {method} could not connect.") from None
    if not result.get("ok"):
        raise RuntimeError(f"Telegram {method} was not accepted.")
    return result["result"]


def main():
    release = json.loads(Path(sys.argv[1]).read_text())
    if release.get("draft") or not release.get("published_at"):
        raise ValueError("Only published releases can be announced")
    assets = apks(release)
    notes = messages(release)
    channel = os.environ.get("TELEGRAM_CHANNEL", "@SquareAppOfficial")
    group = os.environ.get("TELEGRAM_GROUP", "-1003964165935")
    thread = os.environ.get("TELEGRAM_THREAD_ID", "")
    destinations = [{"chat_id": channel}, {"chat_id": group}]
    if thread:
        destinations[1]["message_thread_id"] = int(thread)
    if os.environ.get("DRY_RUN", "false").lower() == "true":
        print(json.dumps({"destinations": destinations, "notes": notes,
                          "apks": [a["name"] for a, _ in assets]}, indent=2, ensure_ascii=False))
        return
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    if not token:
        raise ValueError("Set the TELEGRAM_BOT_TOKEN repository secret first")
    # Download and validate every attachment before publishing anything.
    with tempfile.TemporaryDirectory() as tmp:
        files = []
        for asset, label in assets:
            path = Path(tmp) / asset["name"]
            with request.urlopen(asset["browser_download_url"], timeout=120) as source:
                path.write_bytes(source.read(50_000_000))
            if path.stat().st_size != asset["size"]:
                raise ValueError(f"Incomplete download: {asset['name']}")
            files.append((path, label))
        for destination in destinations:
            for text in notes:
                telegram(token, "sendMessage", {**destination, "text": text,
                         "link_preview_options": {"is_disabled": True}})
            for path, label in files:
                telegram(token, "sendDocument", {**destination, "caption": label}, file=path)
    print(f"Published {release['tag_name']} notes and {len(files)} APKs to both Telegram destinations.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, error.URLError, OSError) as exc:
        # Network exception strings can include URLs. Keep those out of logs.
        print(f"::error::{exc}" if isinstance(exc, (ValueError, RuntimeError))
              else "::error::Release download or file access failed", file=sys.stderr)
        sys.exit(1)
