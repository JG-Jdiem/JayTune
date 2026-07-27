import yt_dlp
import json
import urllib.request
import urllib.error
import ssl

def download(video_id):
    print(f"[Download] Starting extraction for video ID: {video_id}")

    # --- ATTEMPT 1: yt-dlp ---
    print("[Download] Trying yt-dlp...")
    try:
        opts = {'format': 'bestaudio'}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(video_id, download=False)
            if info and info.get('url'):
                print(f"[Download] ✓ yt-dlp SUCCESS - URL: {info.get('url')[:50]}...")
                return json.dumps({
                    "id": info.get('id'),
                    "url": info.get('url'),
                    "format_id": info.get('format_id'),
                    "filesize": info.get('filesize') or info.get('filesize_approx'),
                    "source": "yt-dlp"
                })
    except Exception as e:
        print(f"[Download] ✗ yt-dlp FAILED: {e}")

    # --- ATTEMPT 2: Piped API ---
    print("[Download] Trying Piped API...")
    piped_instances = [
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.privacydev.net",
        "https://pipedapi.tokhmi.xyz"
    ]

    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    for instance in piped_instances:
        try:
            print(f"[Download] Trying Piped instance: {instance}")
            api_url = f"{instance}/streams/{video_id}"
            req = urllib.request.Request(api_url)
            req.add_header('User-Agent', 'Mozilla/5.0')

            with urllib.request.urlopen(req, timeout=10, context=ctx) as response:
                data = json.loads(response.read().decode())

                audio_streams = data.get('audioStreams', [])
                if audio_streams:
                    best_audio = audio_streams[0]
                    print(f"[Download] ✓ Piped SUCCESS ({instance}) - Format: {best_audio.get('quality', 'unknown')}")
                    return json.dumps({
                        "id": video_id,
                        "url": best_audio.get('url'),
                        "format_id": best_audio.get('itag', '140'),
                        "filesize": best_audio.get('contentLength'),
                        "source": "piped"
                    })
        except Exception as err:
            print(f"[Download] ✗ Piped instance {instance} FAILED: {err}")
            continue

    print("[Download] All Piped instances failed")

    # --- ATTEMPT 3: Invidious (fallback) ---
    print("[Download] Trying Invidious API...")
    invidious_instances = [
        "https://invidious.lunar.icu",
        "https://inv.tux.pizza",
        "https://invidious.projectsegfau.lt"
    ]

    for instance in invidious_instances:
        try:
            print(f"[Download] Trying Invidious instance: {instance}")
            api_url = f"{instance}/api/v1/videos/{video_id}"
            req = urllib.request.Request(api_url)
            req.add_header('User-Agent', 'Mozilla/5.0')

            with urllib.request.urlopen(req, timeout=10, context=ctx) as response:
                data = json.loads(response.read().decode())
                formats = data.get('adaptiveFormats', [])
                audio = [f for f in formats if 'audio' in f.get('type', '').lower()]

                if audio:
                    audio_url = audio[0].get('url')
                    if audio_url and audio_url.startswith('/'):
                        audio_url = f"{instance}{audio_url}"

                    print(f"[Download] ✓ Invidious SUCCESS ({instance})")
                    return json.dumps({
                        "id": video_id,
                        "url": audio_url,
                        "format_id": "140",
                        "filesize": audio[0].get('contentLength'),
                        "source": "invidious"
                    })
        except Exception as err:
            print(f"[Download] ✗ Invidious instance {instance} FAILED: {err}")
            continue

    # Final fallback
    print(f"[Download] ✗✗✗ ALL METHODS FAILED for video ID: {video_id}")
    return json.dumps({
        "id": video_id,
        "url": None,
        "error": "All extraction methods failed",
        "filesize": None
    })


def upgrade(package_name):
    try:
        import ensurepip
        ensurepip.bootstrap()
    except Exception as e:
        print(f"Error running ensurepip: ${e}")

    try:
        import pip
        from pip._internal import main as pip_main

        pip_main(['install', '--upgrade', package_name])
        print(f"Successfully upgraded {package_name}")
    except Exception as e:
        print(f"Error upgrading package {package_name}: {e}")

