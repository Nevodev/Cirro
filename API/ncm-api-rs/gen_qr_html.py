import re

svg = open("login_qr.svg", encoding="utf-8").read()
svg = re.sub(r"<\?xml[^>]*\?>", "", svg).strip()

html = (
    "<!doctype html><html><head><meta charset='utf-8'>"
    "<title>网易云扫码登录</title><style>"
    "body{font-family:system-ui,-apple-system,sans-serif;background:#fff;color:#111;"
    "display:flex;flex-direction:column;align-items:center;justify-content:center;"
    "min-height:100vh;margin:0;padding:24px;text-align:center}"
    "h2{font-size:17px;font-weight:500;margin:0 0 4px}"
    "p{font-size:13px;color:#666;margin:12px 0 0}"
    ".box{background:#fff;padding:16px;border:1px solid #e5e5e5;border-radius:12px}"
    "</style></head><body>"
    "<h2>用「网易云音乐 App」扫码登录</h2>"
    "<div class='box'>" + svg + "</div>"
    "<p>二维码约 3 分钟后过期 · 扫码后需在手机上点确认</p>"
    "</body></html>"
)

open("qr.html", "w", encoding="utf-8").write(html)
print("written", len(html))
