import io, re, sys

p = r"C:\Users\a1368\Desktop\better_school\backend\better-school\bs-app\target\surefire-reports\TEST-com.betterschool.integration.timeout.TimeoutTaskEndToEndIntegrationTest.xml"
text = io.open(p, encoding="utf-8", errors="replace").read()
m = re.search(r"<system-out><!\[CDATA\[(.*?)\]\]></system-out>", text, re.S)
if not m:
    print("no system-out")
    sys.exit(0)
lines = m.group(1).splitlines()
for line in lines:
    if any(k in line for k in ("超时", "Timeout", "timeout", "扫描", "scан")):
        print(line)
