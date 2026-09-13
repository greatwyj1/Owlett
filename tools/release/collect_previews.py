#!/usr/bin/env python3
"""Collect the latest real-component screenshot per case into a local comparison gallery."""
import argparse
import configparser
import html
import json
import pathlib
import re
import shutil


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    version = configparser.ConfigParser()
    version.read_string("[version]\n" + (pathlib.Path(__file__).resolve().parents[2] / "version.properties").read_text())
    version_name = html.escape(version["version"]["versionName"])
    samples = {}
    for run in sorted((args.report / "runs").glob("*.js")):
        payload = run.read_text().partition(" = ")[2].strip().removesuffix(";")
        for entry in json.loads(payload):
            name = entry["testName"].split("#")[-1]
            if name not in samples or entry["timestamp"] > samples[name]["timestamp"]:
                samples[name] = entry
    args.output.mkdir(parents=True, exist_ok=False)
    entries = []
    for name, entry in sorted(samples.items()):
        source = (args.report / entry["file"]).resolve()
        if not source.is_relative_to(args.report.resolve()) or source.suffix != ".png":
            raise SystemExit("Unexpected screenshot path")
        filename = re.sub(r"[^a-zA-Z0-9._-]", "-", name).rstrip("-") + ".png"
        shutil.copy2(source, args.output / filename)
        entries.append(dict(case=name, image=filename))
    (args.output / "screenshots.json").write_text(json.dumps(entries, indent=2) + "\n")
    groups = []
    for color, label in [("feather", "羽色·灰褐"), ("gold", "羽色·暖金"), ("green", "清爽绿")]:
        for mode, mode_label in [("light", "浅色"), ("dark", "深色")]:
            group = [entry for entry in entries if f"[{color}-{mode}-" in entry["case"]]
            figures = "".join(f'<figure><figcaption>{html.escape(e["case"])}</figcaption><a href="{e["image"]}"><img loading="lazy" src="{e["image"]}" alt="{html.escape(e["case"])}"></a></figure>' for e in group)
            groups.append(f"<section><h2>{label} · {mode_label}</h2><div class=grid>{figures}</div></section>")
    page = '''<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Owlett 1.0.1 界面对照</title><style>
body{margin:0;background:#f5f6f6;color:#252b29;font:14px/1.6 system-ui,sans-serif;letter-spacing:0}main{padding:24px;max-width:1500px;margin:auto}
h1{font-size:24px}h2{font-size:18px;border-bottom:1px solid #ccd5d0;padding-bottom:8px}section{margin-top:32px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:20px}
figure{margin:0;min-width:0}figcaption{font-size:12px;overflow-wrap:anywhere;margin-bottom:6px}img{display:block;width:100%;height:auto;border:1px solid #cbd3cf;border-radius:8px;box-sizing:border-box}
</style><main><h1>Owlett 1.0.1 · 实际组件预览</h1><p>三套主题，360dp / 411dp，以及360dp下1.5倍字体。录音、计划详情、聊天和外观设置均使用应用组件；数据为合成示例，图片为本地图鉴。不是手机运行截图，不代表网络、键盘或业务流程已完成真机验收。</p>'''
    (args.output / "index.html").write_text(page.replace("1.0.1", version_name) + "".join(groups) + "</main></html>")
    print(json.dumps(dict(screenshots=len(entries), gallery=str(args.output / "index.html"))))


if __name__ == "__main__":
    main()
