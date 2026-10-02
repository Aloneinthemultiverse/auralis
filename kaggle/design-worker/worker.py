"""Auralis design worker — runs a whole block-by-block RTL design on a free Kaggle GPU.

Input (JOB, injected by Auralis before push): statement, evidence per part, design
guidance, max blocks, iterations. On 2x T4 it serves a large coding model with vLLM,
derives the RTL blocks, and for each block loops generate -> Verilator lint -> Icarus
simulation -> Yosys synthesis -> fix, writing /kaggle/working/design_result.json.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

JOB = __JOB__
MODELS = [os.environ.get("MODEL_ID", "Qwen/Qwen2.5-Coder-32B-Instruct-AWQ"),
          "Qwen/Qwen2.5-Coder-14B-Instruct-AWQ"]
PORT = 1235
OUT = "/kaggle/working/design_result.json"
FILE_BLOCK = re.compile(r"```[\w+-]*\s*\n\s*(?://|#|--)\s*file:\s*([\w./-]+)\s*\n(.*?)```", re.S)
MODULE = re.compile(r"\bmodule\s+([A-Za-z_][A-Za-z0-9_$]*)")


def sh(cmd, timeout=900):
    print("$", cmd, flush=True)
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)


def setup_tools():
    sh("apt-get -qq update && apt-get -qq install -y iverilog yosys verilator > /dev/null 2>&1", 1800)
    sh("pip install -q vllm", 1800)
    for t in ("iverilog -V", "yosys -V", "verilator --version"):
        print(sh(t).stdout.splitlines()[:1], flush=True)


def serve():
    for model in MODELS:
        log = open(f"/kaggle/working/vllm-{model.split('/')[-1]}.log", "w")
        p = subprocess.Popen([sys.executable, "-m", "vllm.entrypoints.openai.api_server", "--model", model,
                              "--served-model-name", "coder", "--port", str(PORT), "--dtype", "half",
                              "--tensor-parallel-size", "2", "--max-model-len", "16384",
                              "--gpu-memory-utilization", "0.92", "--quantization", "awq"],
                             stdout=log, stderr=subprocess.STDOUT)
        t0 = time.time()
        while time.time() - t0 < 1800 and p.poll() is None:
            try:
                urllib.request.urlopen(f"http://localhost:{PORT}/v1/models", timeout=5)
                print("serving", model, f"after {time.time() - t0:.0f}s", flush=True)
                return p, model
            except Exception:
                time.sleep(10)
        p.kill()
        print("could not serve", model, flush=True)
    raise SystemExit("no model could be served")


def chat(prompt, max_tokens=6000, temperature=0.2):
    body = json.dumps({"model": "coder", "messages": [
        {"role": "system", "content": "You are an expert ASIC/RTL design engineer. You write clean, synthesizable "
                                      "SystemVerilog and rigorous self-checking testbenches. You never claim a result "
                                      "you have not verified."},
        {"role": "user", "content": prompt}], "max_tokens": max_tokens, "temperature": temperature}).encode()
    req = urllib.request.Request(f"http://localhost:{PORT}/v1/chat/completions", data=body,
                                 headers={"content-type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=1800))["choices"][0]["message"]["content"]


def blocks_for(statement, evidence):
    text = chat(f"""Design statement:
{statement}

Evidence from the research notebook (by part):
{evidence[:9000]}

List the {JOB['maxBlocks']} most essential RTL blocks to implement and verify for this design (integrated IP
such as CPUs, PHYs and memory controllers is NOT a block to write). Return ONLY a JSON array of objects
with "name" and "spec"; each spec (max 150 words) gives the function, exact ports with widths,
parameters, and what the self-checking testbench must check.""", 3000)
    m = re.search(r"\[.*\]", text, re.S)
    return json.loads(m.group(0))[: JOB["maxBlocks"]]


def validate(files):
    rtl = {n: c for n, c in files.items() if n.endswith((".sv", ".v")) and not os.path.basename(n).startswith("tb")}
    tbs = {n: c for n, c in files.items() if n.endswith((".sv", ".v")) and os.path.basename(n).startswith("tb")}
    if not rtl:
        return "SUMMARY: no RTL files", False
    with tempfile.TemporaryDirectory() as d:
        for n, c in files.items():
            os.makedirs(os.path.dirname(os.path.join(d, n)) or d, exist_ok=True)
            open(os.path.join(d, n), "w").write(c)
        code = "\n".join(re.sub(r"//[^\n]*|/\*.*?\*/", " ", c, flags=re.S) for c in rtl.values())
        declared = MODULE.findall(code)
        top = next((m for m in reversed(declared) if len(re.findall(rf"\b{re.escape(m)}\b", code)) == 1),
                   declared[-1] if declared else "top")
        names = " ".join(rtl)
        lint = subprocess.run(f"cd {d} && verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL "
                              f"-Wno-UNUSEDPARAM --top-module {top} {names}", shell=True, capture_output=True, text=True)
        report = [f"top: {top}", "== lint ==", (lint.stdout + lint.stderr).strip() or "LINT CLEAN"]
        sim_ok = bool(tbs)
        for tb, src in tbs.items():
            tbtop = (MODULE.findall(src) or ["tb"])[0]
            r = subprocess.run(f"cd {d} && iverilog -g2012 -s {tbtop} -o sim {names} {tb} && timeout 120 vvp -n sim",
                               shell=True, capture_output=True, text=True)
            out = (r.stdout + r.stderr).strip()
            ok = r.returncode == 0 and "FAIL" not in out and ("ALL TESTS PASSED" in out or "PASS" in out)
            sim_ok = sim_ok and ok
            report += [f"== simulate {tb} ==", out[-3000:]]
        y = subprocess.run(f"cd {d} && yosys -q -p 'read_verilog -sv {names}; synth -top {top}; check; tee -o stat.txt stat' "
                           f">/dev/null 2>err.txt; cat err.txt; cat stat.txt", shell=True, capture_output=True, text=True)
        cells = re.search(r"Number of cells:\s+(\d+)", y.stdout)
        report += ["== synthesize ==", y.stdout[-3000:]]
        lint_ok = lint.returncode == 0
        synth_ok = cells is not None
        passed = lint_ok and sim_ok and synth_ok
        report.append(f"SUMMARY: lint={'clean' if lint_ok else 'ERRORS'}, simulation={'pass' if sim_ok else 'FAIL'}, "
                      f"synthesis={'ok' if synth_ok else 'FAILED'}, cells={cells.group(1) if cells else '?'}, "
                      f"OVERALL {'PASS' if passed else 'FAIL'}")
        return "\n".join(report), passed


def design_block(block, evidence, guidance):
    versions, feedback, previous = [], None, None
    for it in range(1, JOB["iterations"] + 1):
        prompt = f"""Block: {block['name']}
Spec: {block['spec']}

Overall design: {JOB['statement'][:2500]}

Relevant evidence:
{evidence[:5000]}

Design rules:
{guidance}

{('Previous version:' + chr(10) + previous[:8000]) if previous else ''}
{('Verification failures to fix:' + chr(10) + feedback[:5000]) if feedback else ''}

Write the block. Start with a short description (microarchitecture, key parameters, how the spec is met), then
EVERY file as a fenced code block whose first line is a comment naming the file, e.g. ```systemverilog
// file: mac_unit.sv ... ```. Include a self-checking testbench file named tb_<top>.sv that prints PASS/FAIL
per check, ALL TESTS PASSED only if all pass, and calls $finish. Complete files only."""
        text = chat(prompt)
        files = {m.group(1).strip(): m.group(2).strip() + "\n" for m in FILE_BLOCK.finditer(text)}
        report, passed = validate(files)
        versions.append({"iteration": it, "spec": text, "files": files, "report": report, "passed": passed})
        print(f"[{block['name']}] v{it}: {report.splitlines()[-1]}", flush=True)
        if passed:
            break
        feedback, previous = report, text
    return versions


def main():
    setup_tools()
    server, model = serve()
    result = {"model": model, "blocks": [], "error": None}
    try:
        blocks = JOB.get("blocks") or blocks_for(JOB["statement"], JOB["evidence"])
        print("blocks:", [b["name"] for b in blocks], flush=True)
        for b in blocks:
            t0 = time.time()
            try:
                versions = design_block(b, JOB["evidence"], JOB["guidance"])
                result["blocks"].append({"name": b["name"], "spec": b["spec"], "versions": versions,
                                         "seconds": round(time.time() - t0)})
            except Exception as e:  # keep going with the other blocks
                result["blocks"].append({"name": b["name"], "spec": b["spec"], "versions": [], "error": str(e)})
            json.dump(result, open(OUT, "w"))  # partial results survive a timeout
    except Exception as e:
        result["error"] = str(e)
    finally:
        json.dump(result, open(OUT, "w"))
        server.terminate()
    print("done:", [(b["name"], (b["versions"][-1]["report"].splitlines()[-1] if b["versions"] else "no version"))
                    for b in result["blocks"]], flush=True)


if __name__ == "__main__":
    main()
