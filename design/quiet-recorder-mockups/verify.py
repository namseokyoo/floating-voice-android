from pathlib import Path
from PIL import Image
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parent
chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=chrome)
    page = browser.new_page(viewport={"width": 1280, "height": 1900})
    page.goto((root / "index.html").as_uri())

    phone = page.locator("#setup-light")
    phone.locator(".settings-btn").click()
    assert phone.locator(".sheet").evaluate("e => e.classList.contains('open')")
    phone.locator(".back").click()
    assert not phone.locator(".sheet").evaluate("e => e.classList.contains('open')")
    phone.locator(".action").click()
    assert phone.locator(".toast").evaluate("e => e.classList.contains('show')")

    active = page.locator("#running-dark")
    active.locator(".action").click()
    assert active.locator(".modal").evaluate("e => e.classList.contains('open')")
    active.locator(".modal .secondary").click()
    assert not active.locator(".modal").evaluate("e => e.classList.contains('open')")
    browser.close()

print("INTERACTIONS PASS: settings/back, action toast, confirmation/cancel")
for file in sorted((root / "renders").glob("*.png")):
    with Image.open(file) as image:
        print(f"{file.name}: {image.width}x{image.height} {image.mode}")
