#!/usr/bin/env python3
"""把 docs/demo/宣传片-12秒.html 渲染成带配乐的 MP4（1920×1080、30 fps、12 秒）。

画面是时间的纯函数：脚本逐帧调用页面里的 window.__seek(t) 并截图，配乐由页面的
window.__renderWav() 离线合成，最后用 ffmpeg 合成 MP4。只读本地文件，不联网（安装依赖除外）。

依赖（在 Mac 上装一次）：
    python3 -m pip install playwright
    python3 -m playwright install chromium
    brew install ffmpeg

用法：
    python3 docs/demo/render_promo_video.py                       # 输出到 ~/Desktop/财税AgentHarness-宣传片.mp4
    python3 docs/demo/render_promo_video.py --out /path/to/x.mp4
    python3 docs/demo/render_promo_video.py --frames-only          # 只导出 PNG 帧和 WAV，不合成

视频放在仓库外，不要提交到 Git。
"""
import argparse
import base64
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PAGE = HERE / "宣传片-12秒.html"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=Path.home() / "Desktop" / "财税AgentHarness-宣传片.mp4")
    parser.add_argument("--frames-only", action="store_true", help="只导出帧与配乐，不调用 ffmpeg")
    parser.add_argument("--keep", action="store_true", help="保留临时帧目录")
    args = parser.parse_args()

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("缺少 playwright：python3 -m pip install playwright && python3 -m playwright install chromium", file=sys.stderr)
        return 2
    if not args.frames_only and shutil.which("ffmpeg") is None:
        print("缺少 ffmpeg：brew install ffmpeg（或加 --frames-only 只导出帧）", file=sys.stderr)
        return 2

    work = Path(tempfile.mkdtemp(prefix="harness-promo-"))
    frames = work / "frames"
    frames.mkdir()
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1920, "height": 1080}, device_scale_factor=1)
        page.goto(PAGE.as_uri() + "?capture=1&t=0")
        page.wait_for_function("typeof window.__seek === 'function'")
        checks = page.evaluate("window.__checks()")
        if checks["offBeat"] or checks["misaligned"]:
            print(f"自检未通过：{checks}", file=sys.stderr)
            return 1
        duration = page.evaluate("window.__DURATION")
        fps = page.evaluate("window.__FPS")
        total = round(duration * fps)
        for i in range(total):
            page.evaluate(f"window.__seek({i / fps})")
            page.screenshot(path=str(frames / f"{i:04d}.png"))
            if i % fps == 0:
                print(f"  帧 {i}/{total}")
        wav = work / "music.wav"
        wav.write_bytes(base64.b64decode(page.evaluate("window.__renderWav()")))
        browser.close()
    print(f"已导出 {total} 帧与配乐：{work}")
    if args.frames_only:
        return 0

    args.out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error",
        "-framerate", str(fps), "-i", str(frames / "%04d.png"),
        "-i", str(wav),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16", "-preset", "slow",
        "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart",
        str(args.out),
    ], check=True)
    if not args.keep:
        shutil.rmtree(work, ignore_errors=True)
    print(f"完成：{args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
