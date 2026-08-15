import concurrent.futures
import json
import sys
import time
import urllib.request
import uuid
from pathlib import Path

from playwright.sync_api import sync_playwright


API_BASE = "http://127.0.0.1:8080"
OVERLAY_URL = "http://127.0.0.1:5173/overlay"
OUTPUT_DIR = Path(__file__).resolve().parent / "overlay-live-update-results"


def get_json(path):
    with urllib.request.urlopen(f"{API_BASE}{path}", timeout=8) as response:
        return json.loads(response.read().decode("utf-8"))


def publish(sender_id, sender_name, message_text):
    body = json.dumps({
        "roomId": "overlay-live-update-test",
        "senderId": sender_id,
        "senderName": sender_name,
        "messageText": message_text,
    }, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{API_BASE}/api/mock/messages",
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=70) as response:
        return json.loads(response.read().decode("utf-8"))


def snapshot(page):
    return {
        "rootClass": page.locator(".paper-room-overlay").get_attribute("class"),
        "heading": page.locator(".letter-heading strong").all_text_contents(),
        "source": page.locator(".letter-source").all_text_contents(),
        "message": page.locator(".letter-message").all_text_contents(),
        "status": page.locator(".host-status p").all_text_contents(),
    }


def main():
    silent_sse = "--silent-sse" in sys.argv
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex[:8]
    sender_id = f"live-update-{token}"
    sender_name = f"实时刷新测试{token}"
    message_text = f"请回复验证码{token}，用来确认页面会自动更新。"
    report = {
        "token": token,
        "runtimeBefore": get_json("/api/runtime"),
        "responses": [],
        "console": [],
        "pageErrors": [],
        "snapshots": [],
    }
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1920, "height": 1080})
        if silent_sse:
            page.add_init_script("""
                class SilentEventSource extends EventTarget {
                    static CONNECTING = 0;
                    static OPEN = 1;
                    static CLOSED = 2;
                    constructor(url) {
                        super();
                        this.url = String(url);
                        this.readyState = SilentEventSource.CONNECTING;
                        setTimeout(() => {
                            this.readyState = SilentEventSource.OPEN;
                            this.dispatchEvent(new Event('open'));
                        }, 20);
                    }
                    close() { this.readyState = SilentEventSource.CLOSED; }
                }
                window.EventSource = SilentEventSource;
            """)
        page.route("https://fonts.googleapis.com/**", lambda route: route.abort())
        page.route("https://fonts.gstatic.com/**", lambda route: route.abort())
        page.on("response", lambda response: report["responses"].append({
            "url": response.url,
            "status": response.status,
        }) if "/api/overlay/" in response.url else None)
        page.on("console", lambda message: report["console"].append({"type": message.type, "text": message.text}))
        page.on("pageerror", lambda error: report["pageErrors"].append(str(error)))
        page.goto(OVERLAY_URL, wait_until="domcontentloaded", timeout=20000)
        page.wait_for_selector(".paper-room-overlay", timeout=15000)
        page.wait_for_timeout(1500)
        report["snapshots"].append({"phase": "before", **snapshot(page)})
        report["runtimeAfterSubscribe"] = get_json("/api/runtime")

        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(publish, sender_id, sender_name, message_text)
            deadline = time.time() + 65
            last_signature = None
            while time.time() < deadline and not future.done():
                current = snapshot(page)
                signature = json.dumps(current, ensure_ascii=False, sort_keys=True)
                if signature != last_signature:
                    report["snapshots"].append({"phase": "during", **current})
                    last_signature = signature
                page.wait_for_timeout(200)
            report["publishResult"] = future.result()

        if silent_sse:
            page.wait_for_function(
                "expected => document.querySelector('.letter-heading strong')?.textContent?.includes(expected)",
                arg=token,
                timeout=7000,
            )
        else:
            page.wait_for_timeout(1200)
        report["snapshots"].append({"phase": "after", **snapshot(page)})
        report["runtimeAfter"] = get_json("/api/runtime")
        page.screenshot(path=str(OUTPUT_DIR / "after.png"), full_page=True)
        browser.close()

    report_path = OUTPUT_DIR / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
