"""发布前检查：APK 里不能出现 OpenAI 密钥。

用法：python -I tools/no_key_check.py <apk>
从 secrets.properties 读出本机的密钥，在 APK 的每个文件里找它；另外也找任何 sk-proj- 开头的字符串。
找到就以非零状态退出，release.sh 会因此中止发布。
"""
import pathlib
import re
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
apk = pathlib.Path(sys.argv[1])

key = ""
secrets = root / "secrets.properties"
if secrets.exists():
    for line in secrets.read_text(encoding="utf-8").splitlines():
        if line.startswith("OPENAI_API_KEY="):
            key = line.split("=", 1)[1].strip()

pattern = re.compile(rb"sk-(?:proj-)?[A-Za-z0-9_\-]{20,}")
bad = []
with zipfile.ZipFile(apk) as z:
    for info in z.infolist():
        data = z.read(info)
        if key and key.encode() in data:
            bad.append(f"{info.filename}: 含本机密钥")
        elif pattern.search(data):
            bad.append(f"{info.filename}: 含疑似密钥（sk-...）")

if bad:
    print("发现密钥，停止发布：")
    print("\n".join(bad))
    sys.exit(1)
print(f"OK：{apk.name} 里没有密钥")
