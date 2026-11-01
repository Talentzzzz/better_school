set -e
BASE=http://localhost:8080
K='api:/orders/create'
hdr() { printf '\n=== %s ===\n' "$1"; }
post() { curl -s -H 'Content-Type: application/json' -d @"$1" "$2"; }

hdr "① 首租 A：实测需求 400 次/5s = 80/s（规则上界 100/s）"
cat > a1.json <<EOF
{"instance_id":"pod-A","generation":"genA","rule_version":"v1","idempotency_key":"a1",
 "keys":[{"key":"$K","usage":{"time_elapsed_ms":5000,"allowed":400,"denied":0}}]}
EOF
post a1.json $BASE/v1/lease; echo

hdr "② 首租 B：同 key 第二个实例 ⇒ 只拿未用量 20（Σ = 100 = R）"
cat > b1.json <<EOF
{"instance_id":"pod-B","generation":"genB","rule_version":"v1","idempotency_key":"b1",
 "keys":[{"key":"$K","usage":{"time_elapsed_ms":5000,"allowed":400,"denied":0}}]}
EOF
post b1.json $BASE/v1/lease; echo

hdr "③ A 立刻续租（过频窗内）⇒ 不重算份额、回剩余 ttl"
cat > a2.json <<EOF
{"instance_id":"pod-A","generation":"genA","rule_version":"v1","idempotency_key":"a2",
 "keys":[{"key":"$K","usage":{"time_elapsed_ms":1000,"allowed":90,"denied":0}}]}
EOF
post a2.json $BASE/v1/lease; echo

hdr "④ B 归还 ⇒ 200"
cat > b-ret.json <<EOF
{"instance_id":"pod-B","generation":"genB","keys":["$K"]}
EOF
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -H 'Content-Type: application/json' -d @b-ret.json $BASE/v1/lease/return

hdr "⑤ B 归还后立刻用同一三元组再租 ⇒ 首租命中过频窗 ⇒ throttled"
post b1.json $BASE/v1/lease; echo

hdr "⑥ 换世代（真实重启语义）⇒ 条目确实已删，按冷 key 重新发放"
cat > b3.json <<EOF
{"instance_id":"pod-B","generation":"genB2","rule_version":"v1","idempotency_key":"b3",
 "keys":[{"key":"$K","usage":{"time_elapsed_ms":5000,"allowed":400,"denied":0}}]}
EOF
post b3.json $BASE/v1/lease; echo

hdr "⑦ 配置基线：零到达窗口 + wants=5 ⇒ 保住 5；不带 wants ⇒ 0"
cat > d1.json <<'EOF'
{"instance_id":"pod-D","generation":"genD","rule_version":"v1","idempotency_key":"d1",
 "keys":[{"key":"api:/orders/baseline","usage":{"time_elapsed_ms":5000,"allowed":0,"denied":0},"wants":5.0},
         {"key":"api:/orders/no-baseline","usage":{"time_elapsed_ms":5000,"allowed":0,"denied":0}}]}
EOF
post d1.json $BASE/v1/lease; echo

hdr "⑧ 未匹配规则的 key ⇒ unmatched（无 share 字段）"
cat > e1.json <<'EOF'
{"instance_id":"pod-E","generation":"genE","rule_version":"v1","idempotency_key":"e1",
 "keys":[{"key":"web:/home","usage":{"time_elapsed_ms":5000,"allowed":9,"denied":0}}]}
EOF
post e1.json $BASE/v1/lease; echo

hdr "⑨ /metrics 里本票新增与相关的计数"
curl -s $BASE/metrics | grep -E '^gatekeeper_' | sort
