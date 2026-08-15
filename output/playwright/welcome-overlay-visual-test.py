import json
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright


OUTPUT_DIR = Path(__file__).resolve().parent / "welcome-overlay-results"


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    report = {"consoleErrors": [], "pageErrors": []}
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1920, "height": 1080})
        page.route("https://fonts.googleapis.com/**", lambda route: route.abort())
        page.route("https://fonts.gstatic.com/**", lambda route: route.abort())
        page.add_init_script("""
            class ControlledEventSource extends EventTarget {
                static CONNECTING = 0;
                static OPEN = 1;
                static CLOSED = 2;
                static instances = [];
                constructor(url) {
                    super();
                    this.url = String(url);
                    this.readyState = ControlledEventSource.CONNECTING;
                    ControlledEventSource.instances.push(this);
                    setTimeout(() => {
                        this.readyState = ControlledEventSource.OPEN;
                        this.dispatchEvent(new Event('open'));
                    }, 20);
                }
                close() { this.readyState = ControlledEventSource.CLOSED; }
            }
            window.EventSource = ControlledEventSource;
            window.dispatchOverlayTestEvent = (name, payload) => {
                for (const source of ControlledEventSource.instances) {
                    source.dispatchEvent(new MessageEvent(name, { data: JSON.stringify(payload) }));
                }
            };
        """)
        page.on("console", lambda message: report["consoleErrors"].append(message.text) if message.type == "error" else None)
        page.on("pageerror", lambda error: report["pageErrors"].append(str(error)))
        page.goto("http://127.0.0.1:5173/overlay", wait_until="domcontentloaded", timeout=20000)
        page.wait_for_selector(".paper-room-overlay", timeout=15000)
        page.wait_for_timeout(1200)

        welcome = {
            "id": "welcome-visual-test",
            "roomId": "1000",
            "viewerNames": ["小纸船", "晨风", "薄荷"],
            "totalViewers": 5,
            "text": "@小纸船、@晨风、@薄荷，还有刚进来的 2 位朋友，欢迎来到直播间～",
            "displayDurationMs": 8000,
            "occurredAt": "2026-08-12T00:00:00Z",
        }
        page.evaluate("payload => window.dispatchOverlayTestEvent('overlay-welcome', payload)", welcome)
        page.wait_for_selector(".welcome-ribbon", timeout=3000)
        page.wait_for_timeout(600)
        report["welcome"] = {
            "mode": page.locator(".paper-room-overlay").get_attribute("data-host-state"),
            "text": page.locator(".welcome-ribbon p").inner_text(),
            "status": page.locator(".host-status p").inner_text(),
            "live2dOpacity": page.locator(".live2d-host-canvas").evaluate("node => getComputedStyle(node).opacity"),
        }
        page.screenshot(path=str(OUTPUT_DIR / "welcome.png"), full_page=True)

        received = {
            "messageId": "reply-priority-test",
            "senderName": "正在提问的观众",
            "sourceText": "欢迎会不会抢走我的回复？",
        }
        page.evaluate("payload => window.dispatchOverlayTestEvent('overlay-reply-received', payload)", received)
        page.wait_for_timeout(250)
        report["replyPriority"] = {
            "mode": page.locator(".paper-room-overlay").get_attribute("data-host-state"),
            "welcomeVisible": page.locator(".welcome-ribbon").count(),
            "heading": page.locator(".letter-heading strong").inner_text(),
            "message": page.locator(".letter-message").inner_text(),
        }
        page.screenshot(path=str(OUTPUT_DIR / "reply-priority.png"), full_page=True)
        browser.close()

    report_path = OUTPUT_DIR / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
