"""Call Auralis knowledge_search (Laya-rated retrieval loop) over MCP without a client timeout.
usage: python scripts/ksearch.py "query" [topK] [rounds]"""
import json, sys, urllib.request
U = "http://localhost:8080/mcp"
H = {"content-type": "application/json", "accept": "application/json,text/event-stream"}
def call(p, s=None):
    h = dict(H)
    if s: h["mcp-session-id"] = s
    r = urllib.request.urlopen(urllib.request.Request(U, json.dumps(p).encode(), h, method="POST"), timeout=900)
    return r.headers.get("mcp-session-id"), r.read().decode()
s, _ = call({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ksearch", "version": "1"}}})
call({"jsonrpc": "2.0", "method": "notifications/initialized"}, s)
args = {"query": sys.argv[1], "topK": int(sys.argv[2]) if len(sys.argv) > 2 else 6}
if len(sys.argv) > 3: args["rounds"] = int(sys.argv[3])
_, raw = call({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "knowledge_search", "arguments": args}}, s)
sys.stdout.reconfigure(encoding="utf-8")
print(json.loads(raw[raw.index("{"):])["result"]["content"][0]["text"])
