from pathlib import Path
from shutil import copy2, rmtree

import markdown


ROOT = Path(__file__).resolve().parent.parent
WEBSITE = ROOT / "website"
OUTPUT = ROOT / "_site"

PRIVACY_POLICY = ROOT / "PRIVACY_POLICY.md"
APP_ICON = ROOT / "graphics" / "icon.webp"


def require_file(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"Missing required file: {path}")


def build_privacy_page(markdown_source: str) -> str:
    privacy_body = markdown.markdown(
        markdown_source,
        extensions=["extra", "sane_lists"],
        output_format="html5",
    )

    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Privacy Policy for SMSecure">
  <meta name="theme-color" content="#07161d">
  <meta property="og:title" content="SMSecure Privacy Policy">
  <meta property="og:type" content="website">
  <meta property="og:image" content="https://jimvixx.github.io/ShortMessageSecure/icon.webp">
  <title>SMSecure Privacy Policy</title>
  <link rel="icon" type="image/webp" href="icon.webp">
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <a class="skip-link" href="#privacy-content">Skip to content</a>
  <header class="policy-header">
    <div class="container policy-nav">
      <a class="brand" href="./">
        <img src="icon.webp" alt="" width="36" height="36">
        <span>SMSecure</span>
      </a>
      <a class="text-link" href="./">Back to home</a>
    </div>
  </header>

  <main id="privacy-content" class="policy-page">
    {privacy_body}
  </main>

  <footer class="footer container">
    <span>SMSecure</span>
    <div>
      <a href="./">Home</a>
      <a href="https://github.com/jimvixx/ShortMessageSecure">Source code</a>
    </div>
  </footer>
</body>
</html>
"""


def build() -> None:
    index_path = WEBSITE / "index.html"
    styles_path = WEBSITE / "styles.css"

    for required in (index_path, styles_path, PRIVACY_POLICY, APP_ICON):
        require_file(required)

    if OUTPUT.exists():
        rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True)

    index_html = index_path.read_text(encoding="utf-8")
    styles_css = styles_path.read_text(encoding="utf-8")
    privacy_markdown = PRIVACY_POLICY.read_text(encoding="utf-8")

    (OUTPUT / "index.html").write_text(index_html, encoding="utf-8")
    (OUTPUT / "styles.css").write_text(styles_css, encoding="utf-8")
    (OUTPUT / "privacy-policy.html").write_text(
        build_privacy_page(privacy_markdown),
        encoding="utf-8",
    )
    copy2(APP_ICON, OUTPUT / "icon.webp")

    # Prevent optional Jekyll processing if the artifact is ever served directly.
    (OUTPUT / ".nojekyll").touch()

    print(f"Website built successfully: {OUTPUT}")


if __name__ == "__main__":
    build()
