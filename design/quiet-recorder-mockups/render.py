from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
HTML = ROOT / "index.html"
OUT = ROOT / "renders"
OUT.mkdir(exist_ok=True)
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=CHROME)
    page = browser.new_page(viewport={"width": 1280, "height": 1900}, device_scale_factor=2)
    page.goto(HTML.as_uri(), wait_until="load")
    page.screenshot(path=str(OUT / "quiet-recorder-comparison.png"), full_page=True)

    names = [
        "setup-light", "ready-light", "running-light",
        "setup-dark", "ready-dark", "running-dark",
    ]
    for name in names:
        element = page.locator(f"#{name}")
        element.screenshot(path=str(OUT / f"{name}.png"))
        box = element.bounding_box()
        if not box or round(box["width"]) != 372 or round(box["height"]) != 806:
            raise RuntimeError(f"unexpected render box for {name}: {box}")

    title = page.title()
    phones = page.locator(".phone").count()
    buttons = page.locator(".phone .action").count()
    print(f"title={title}")
    print(f"phones={phones} action_buttons={buttons}")
    print(f"output={OUT}")
    browser.close()
