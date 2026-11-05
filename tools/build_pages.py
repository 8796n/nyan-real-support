#!/usr/bin/env python3
"""src/pages/ の本文と共通の枠（src/layout.html）から公開HTMLを生成する。

    python tools/build_pages.py          # 生成する
    python tools/build_pages.py --check  # 生成物と、公開するリモコン入口が現行版だけかを確認する

src/pages/<出力パス> が <出力パス> になる。ページの先頭に `キー: 値` を並べ、`---` の行の
後ろへ <main> 要素を書く。head・ナビ・言語切替・フッター・CSSのキャッシュ版数は生成器が作る。
本文中の <!-- product-cards --> はトップの製品カードに置き換わる。

製品を足すときは src/products.json へ1件足し、src/pages/<key>/index.html と
src/pages/<key>/en/index.html を作る。ナビ・トップの製品カード・フッターは全ページへ反映される。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import sys
from html import escape
from pathlib import Path
from string import Template

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
SITE_URL = "https://support.nyanreal.jp/"
SUPPORT_REPO = "https://github.com/8796n/nyan-real-support"
REQUIRED = {"title", "description", "alt"}
OPTIONAL = {"og_title", "og_description", "og_image", "body_class"}

LABELS = {
	"ja": {
		"home": "トップ", "nav": "メインナビゲーション", "brand": "nyan Real サポートトップ",
		"footer": "フッターナビゲーション", "support_top": "サポートトップ",
	},
	"en": {
		"home": "Home", "nav": "Main navigation", "brand": "nyan Real support home",
		"footer": "Footer navigation", "support_top": "Support home",
	},
}
HOME = {"ja": "", "en": "en/"}


def read_page(path: Path) -> tuple[dict[str, str], str]:
	text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
	header, sep, content = text.partition("\n---\n")
	if not sep:
		raise SystemExit(f"`---` の区切りがありません: {path.relative_to(ROOT)}")
	meta = {}
	for line in header.splitlines():
		key, _, value = line.partition(":")
		meta[key.strip()] = value.strip()
	unknown = meta.keys() - REQUIRED - OPTIONAL
	missing = REQUIRED - meta.keys()
	if unknown or missing:
		raise SystemExit(f"{path.relative_to(ROOT)}: 不明なキー {sorted(unknown)} / 不足 {sorted(missing)}")
	return meta, content.strip("\n")


def render(source: Path, layout: Template, products: list[dict], css_version: str) -> str:
	out = source.relative_to(SRC / "pages")
	parts = out.parts
	root = "../" * (len(parts) - 1)
	page_dir = "".join(part + "/" for part in parts[:-1])
	lang = "en" if "en" in parts[:-1] else "ja"
	label = LABELS[lang]
	meta, content = read_page(source)

	def href(path: str) -> str:
		if path.startswith(("http://", "https://", "#")):
			return path
		rel = posixpath.relpath("/" + path, "/" + page_dir)
		return "./" if rel == "." else rel + "/" if path.endswith("/") or not path else rel

	def product_home(product: dict) -> str:
		return product["key"] + ("/en/" if lang == "en" else "/")

	product = next((p for p in products if p["key"] == parts[0]), None)

	nav = [(label["home"], HOME[lang], "page" if page_dir == HOME[lang] else None)]
	for p in products:
		current = None
		if p is product:
			current = "page" if page_dir == product_home(p) else "location"
		nav.append((p["nav"], product_home(p), current))
	nav_html = "\n".join(
		f'        <a href="{href(path)}"' + (f' aria-current="{cur}"' if cur else "") + f">{escape(text)}</a>"
		for text, path, cur in nav
	)

	footer = [(label["support_top"], HOME[lang])]
	if product:
		footer += product.get("footer", {}).get(lang, [])
	footer.append(("GitHub", (product or {}).get("repo", SUPPORT_REPO)))
	footer_html = "\n".join(f'        <a href="{escape(href(path))}">{escape(text)}</a>' for text, path in footer)

	og = [
		("property", "og:title", meta.get("og_title", meta["title"])),
		("property", "og:description", meta.get("og_description", meta["description"])),
		("property", "og:type", "website"),
		("property", "og:url", SITE_URL + page_dir),
	]
	if "og_image" in meta:
		og += [("property", "og:image", meta["og_image"]), ("name", "twitter:card", "summary_large_image")]
	meta_html = "\n".join(f'  <meta {attr}="{name}" content="{escape(value)}">' for attr, name, value in og)

	cards = "\n".join(
		f'          <a class="card" href="{href(product_home(p))}">'
		+ (f'<img class="product-card-image" src="{escape(href(p["image"]))}" width="1200" height="630" loading="lazy" alt="">' if p.get("image") else "")
		+ f'<span class="tag">{escape(p["tag"][lang])}</span>'
		f'<h3>{escape(p["title"])}</h3><p>{escape(p["summary"][lang])}</p></a>'
		for p in products
	)
	content = content.replace("          <!-- product-cards -->", cards)

	other = "en" if lang == "ja" else "ja"
	return layout.substitute(
		lang=lang, source=out.as_posix(), root=root, css_version=css_version,
		title=escape(meta["title"], quote=False), description=escape(meta["description"]), meta=meta_html,
		body_attr=f' class="{escape(meta["body_class"])}"' if "body_class" in meta else "",
		nav_label=label["nav"], brand_label=label["brand"], home=href(HOME[lang]), nav=nav_html,
		alt_href=escape(href(meta["alt"])), alt_lang=other, alt_label="English" if other == "en" else "日本語",
		content=content, footer_label=label["footer"], footer=footer_html,
	)


def main() -> int:
	sys.stdout.reconfigure(encoding="utf-8", errors="replace")
	parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	parser.add_argument("--check", action="store_true", help="差分を報告するだけで書き込まない")
	args = parser.parse_args()

	layout = Template((SRC / "layout.html").read_text(encoding="utf-8").replace("\r\n", "\n"))
	products = json.loads((SRC / "products.json").read_text(encoding="utf-8"))
	css = (ROOT / "assets/site.css").read_bytes().replace(b"\r\n", b"\n")
	css_version = hashlib.sha256(css).hexdigest()[:10]

	stale = []
	for source in sorted((SRC / "pages").rglob("*.html")):
		target = ROOT / source.relative_to(SRC / "pages")
		html = render(source, layout, products, css_version)
		current = target.read_text(encoding="utf-8").replace("\r\n", "\n") if target.is_file() else None
		if current == html:
			continue
		stale.append(target)
		if not args.check:
			target.parent.mkdir(parents=True, exist_ok=True)
			target.write_bytes(html.encode("utf-8"))
			print(f"生成: {target.relative_to(ROOT).as_posix()}")

	if args.check:
		for target in stale:
			print(f"NG: {target.relative_to(ROOT).as_posix()} が src/ と一致しません。python tools/build_pages.py を実行してください。")
		if stale:
			return 1
		remote = ROOT / "spatial-wall/remote"
		release = json.loads((remote / "loader-manifest.json").read_text(encoding="utf-8"))["release"]
		assets = list((remote / "assets").iterdir())
		if len(assets) != 1 or assets[0].name != release or not assets[0].is_dir():
			print(f"NG: spatial-wall/remote/assets/ には現行版 {release} のディレクトリだけを配置してください。")
			return 1
		print("OK: 生成物は最新です。")
	elif not stale:
		print("最新: 生成物に変更はありません。")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
