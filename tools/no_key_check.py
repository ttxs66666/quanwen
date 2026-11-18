"""发布前检查：APK 里不能出现任何 API 密钥。

用法：python -I tools/no_key_check.py <apk>
从 secrets.properties 读出本机所有 *_API_KEY（OpenAI、DeepSeek 测试密钥等），在 APK 的每个文件里找；另外也找任何 sk- 开头的长字符串。
找到就以非零状态退出，release.sh 会因此中止发布。
"""
import pathlib
import re
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
apk = pathlib.Path(sys.argv[1])

keys = []
secrets = root / "secrets.properties"
if secrets.exists():
    for line in secrets.read_text(encoding="utf-8").splitlines():
        name, _, value = line.partition("=")
        if name.strip().endswith("_API_KEY") and value.strip():
            keys.append(value.strip().encode())

pattern = re.compile(rb"sk-(?:proj-)?[A-Za-z0-9_\-]{20,}")
bad = []
with zipfile.ZipFile(apk) as z:
    for info in z.infolist():
        data = z.read(info)
        if any(k in data for k in keys):
            bad.append(f"{info.filename}: 含本机密钥")
        elif pattern.search(data):
            bad.append(f"{info.filename}: 含疑似密钥（sk-...）")

if bad:
    print("发现密钥，停止发布：")
    print("\n".join(bad))
    sys.exit(1)
print(f"OK：{apk.name} 里没有密钥")
