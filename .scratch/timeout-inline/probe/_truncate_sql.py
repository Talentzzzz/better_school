import io, sys

p = r"C:\Users\a1368\Desktop\better_school\sql.md"
with io.open(p, encoding="utf-8") as f:
    text = f.read()

marker = "\n---\n\n## 3. 超时中心"
idx = text.find(marker)
if idx < 0:
    print("marker not found")
    sys.exit(1)
new = text[:idx] + "\n"
with io.open(p, "w", encoding="utf-8", newline="\n") as f:
    f.write(new)
print("truncated at", idx, "new length", len(new))
