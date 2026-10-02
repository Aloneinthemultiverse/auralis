"""Auralis design worker \u2014 runs a whole block-by-block RTL design on a free Kaggle GPU.

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

JOB = {'statement': 'Design a 28 nm edge-AI SoC for real-time vision inference.\n\nWorkload: INT8 neural-network inference; CNN + lightweight Transformer layers; model size up to 20 MB; input 1920x1080 camera frames; target 30 FPS.\n\nCompute: minimum 8 TOPS INT8; INT8 MAC acceleration required; mixed operations INT8xINT8->INT32, INT16xINT8->INT32, INT8 accumulation.\n\nMemory: 8 MB on-chip SRAM; external LPDDR4 interface; DMA required; memory bandwidth >= 25 GB/s.\n\nCPU: 4-core RV64GC RISC-V, Linux-capable, L1/L2 cache hierarchy.\n\nAccelerator: programmable NPU; systolic/tensor-array architecture; dynamic tiling; weight-stationary and output-stationary modes.\n\nInterfaces: MIPI CSI-2, PCIe Gen3, USB 3.0, Ethernet, LPDDR4.\n\nPower: average < 3 W, peak < 5 W.\n\nPerformance: end-to-end latency < 33 ms/frame; >= 30 FPS sustained.\n\nReliability: ECC for memories, watchdog, error reporting, fault detection.\n\nSecurity: secure boot, hardware root of trust, memory protection, accelerator isolation.\n\nPhysical constraints: area < 35 mm^2; target clock 1 GHz; timing closure required.', 'evidence': "## CPU Subsystem Integration\n- (Predictable CNN Inference and Real-Time Scheduling on Multicore ...) performance improvements, it also introduces new challenges for real-time systems, typically resource sharing and timing unpre- dictability. 58 Chapter 3. Real Time Systems 3.6 The Memory Subsystem The challenge of resource sharing and timing unpredictability is rooted almost entirely within the memory subsystem. While the processing cores themselves can operate in parallel, they must ultimately contend for access to a common set of memory resources to fetch instructions and manipulate data. The performance and, more critically, the timing behavior of any single core are therefore no longer in \u2026\n- (Porting Linux to RISC-V: kernel configs and driver gaps) abstraction layers and platform-specific interfaces. This involves modifying existing drivers or developing new ones to accommodate RISC-V's memory model, interrupt architecture, and I/O mechanisms. The adaptation process includes handling differences in memory-mapped I/O, device tree specifications, and hardware initialization sequences compared to other architectures. RISC-V boot process and firmware integration: The Linux boot process for RISC-V systems requires specific firmware integration approaches. This includes implementing bootloaders compatible with RISC-V specifications, handling t \u2026\n- (Porting Linux to RISC-V: kernel configs and driver gaps) kernel configurations and driver gaps, hardware and software teams must collaborate closely to ensure seamless integration between the RISC-V hardware capabilities and Linux kernel requirements. RISC-V's modular instruction set architecture (ISA) presents unique co-design opportunities and challenges. The base integer instruction set (RV32I/RV64I) can be extended with optional instruction set extensions such as M (multiplication/division), A (atomic operations), F/D (floating-point), and C (compressed instructions). Kernel configurations must be tailored to match the specific RISC-V hardware i \u2026\n- (GitHub - SM0907/secure-boot-risc-v) \xa0 .gitignore .gitignore \xa0  \xa0 README.md README.md \xa0  \xa0 run.sh run.sh \xa0  \xa0 View all files Repository files navigation README More items Secure Boot Controller for a RISC-V SoC A SystemVerilog secure-boot subsystem for a small RISC-V SoC (built around the open-source PicoRV32 core). The design implements a multi-stage boot FSM that gates the CPU reset until each boot stage (ROM \u2192 Stage 1 \u2192 Stage 2) passes hash, signature, and anti-rollback version checks, and includes a SystemVerilog Assertions (SVA) environment that formally/dynamically verifies the FSM's behavior \u2014 including simulated glitch an \u2026\n## Memory Subsystem Design\n- (Predictable CNN Inference and Real-Time Scheduling on Multicore ...) performance improvements, it also introduces new challenges for real-time systems, typically resource sharing and timing unpre- dictability. 58 Chapter 3. Real Time Systems 3.6 The Memory Subsystem The challenge of resource sharing and timing unpredictability is rooted almost entirely within the memory subsystem. While the processing cores themselves can operate in parallel, they must ultimately contend for access to a common set of memory resources to fetch instructions and manipulate data. The performance and, more critically, the timing behavior of any single core are therefore no longer in \u2026\n- (Predictable CNN Inference and Real-Time Scheduling on Multicore ...) Signal Processors (DSPs) or Graphics Processing Units (GPUs). This second type of architecture is the most common in modern embedded systems. Performance between different core types are not related. While core composition is important, the memory architecture is arguably the most critical factor influencing timing predictability. Here, the primary distinction is between shared and distributed memory models: \u2022 Shared Memory Architectures use a global memory space accessible by all cores, allowing them to access the same main memory through a shared bus (Figure 3.7). This simplifies programming \u2026\n- (TRAPTI: Time-Resolved Analysis for SRAM Banking and Power Gating Optimization in Embedded Transformer Inference) providing 128 lanes with a depth of 256 8-bit elements (128\xd7256 entries). These FIFOs model continuous, pipelined data supply along the tiled inner (common) dimension during matrix multiplication. Memory subsystem. The baseline memory hierarchy in- cludes a single shared on-chip SRAM with an initial capacity of 128 MiB, a 512-bit interface with four physical ports, and an access latency of 32 ns, operating at 1 GHz frequency. The off-chip memory is modeled as a DRAM with 2 GiB capacity, two physical ports, and 80 ns access latency. TransInferSim settings. We use TransInferSim with oper- ation  \u2026\n- (BitFair: A 12-nm Bit-Serial CNN Accelerator with Learnable Early Termination and Adaptive Bit Ordering for Ultra-Low-Power XR Vision) while leakage contributes only 0.24 mW (1.8%). The PE array ac- counts for the largest share (49%), followed by the memory subsystem (43%), control logic (5%), and other peripheral circuitry (3%). The memory subsystem occupies 51.2% of the cell area (Table VI) and consumes 43% of the dynamic power, about 5.8 mW of the 13.46 mW dynamic budget, as shown in Fig- ure 8. Because dynamic power accounts for more than 98% of the total power at this corner, this share mainly reflects active read/write energy rather than standby leakage. Several archi- tectural choices keep this energy bounded. First, t \u2026\n## NPU Architecture Specification\n- (Auto-Scaling Heterogeneous Neural Processing Units for Energy and Cost-Efficient LLM Serving) developed upon different generations of Google TPUs. To the best of our knowledge, this is the first NPU cluster simulator that supports different generations of NPU chips. We will open-source it. 2 Auto-Scaling Heterogeneous Neural Processing Units for Energy and Cost-Efficient LLM Serving Table 2: Specifications of different NPU chip versions. The data for NPU-A/B/C/D are derived from TPUv4/5e/5p/6e. \u201c*\u201d means the parameter is not officially disclosed, and it is in- ferred from public data and our experiments. NPU-A NPU-B NPU-C NPU-D Release Year 2021 2023 2023 2024 Process Node 7nm 7nm* 7nm \u2026\n- (Auto-Scaling Heterogeneous Neural Processing Units for Energy and Cost-Efficient LLM Serving) abstraction, per- formance/energy trade-offs, resource allocation and scheduling mechanisms in heterogeneous NPU management. We will discuss how we overcome each of the challenges throughout \xa73. 3 Design and Implementation We design NeuScale, a fine-grained auto-scaling framework for heterogeneous NPUs to achieve the following goals: (1) Efficiency: NeuScale should map LLM requests to their best-fit NPU pods to maximize energy/cost efficiency; (2) Performance: NeuScale aims to meet SLOs of LLM requests; (3) Flexibility:When the best-fit NPUs are not available, NeuScale should automatically fai \u2026\n- (Auto-Scaling Heterogeneous Neural Processing Units for Energy and Cost-Efficient LLM Serving) SRAM Other NPU Chips Figure 1: The NPU pod architecture. We draw 2D NPU pods as examples. A pod can be 2D or 3D. ICI: Inter-Chip Intercon- nect. DCN: Data Center Network. To ease the management of heterogeneous NPUs, we first de- velop an abstraction named vPod (\u201cvirtualized\u201d NPU pod). It repre- sents a group of NPU chips interconnected with high-speed links. As different NPU versions usually share the same hardware archi- tecture, vPod abstracts core hardware parameters (see Figure 6) and provides a unified interface for different compute capabilities. To simplify vPod management and provide  \u2026\n- (NPLSD: Accelerating Line-Segment Detection on NPU Microcontrollers) a lightweight fusion stage, and a shared dense TABLE I: LINEA-N operator mapping on the STM32N6. NPU denotes the Neural-ART accelerator; CPU denotes the Cortex-M55 fallback. Block / operator Target HGNetv2-B0 backbone (Conv, BN, ReLU, Pool) NPU Encoder CNN fusion (Conv, Resize-nearest, Concat) NPU Linear/Gemm projections (q/k/v, FFN) NPU Attention Softmax CPU LayerNorm CPU GridSample (line-attention sampling) CPU TopK + Gather (query selection) CPU Decoder self-attention ([8, 1100, 1100]) cannot place head for line parameter regression. The training objective and optimization procedure are the \u2026\n## Power, Area, Timing, and Cost Constraints\n- (Scaling Inference Prefill with High-Radix Photonic Interconnects) and disaggregated inference [11] attempt to mit- igate the hardware constraints, system performance is ultimately limited by the physical characteristics of the underlying hardware interconnects [7]. 1) The Shoreline Constraint: As computational capa- bility scales with die area, I/O bandwidth is constrained to the chip\u2019s perimeter, or \u201cshoreline\u201d [7]. Large portions of this shoreline are reserved for HBM to ensure signal integrity, significantly reducing the available area for scale- up interconnect serializer/deserializer (SerDes) links [7]. This bottleneck restricts the aggregate bandwidth  \u2026\n- (Scaling Inference Prefill with High-Radix Photonic Interconnects) attempt to mitigate the hardware constraints, system performance is ultimately limited by the physical characteristics of the underlying hardware interconnects [3]. II-C1 The Shoreline Constraint As computational capability scales with die area, I/O bandwidth is constrained to the chip\u2019s perimeter, or \u201cshoreline\u201d [3]. Large portions of this shoreline are reserved for HBM to ensure signal integrity, significantly reducing the available area for scale-up interconnect serializer/deserializer (SerDes) links [3]. This bottleneck restricts the aggregate bandwidth available to support the large batch \u2026\n- (A Low-Power Hardware Architecture for Real-Time CNN Computing) to trade off for smaller area and power, and thus the area and power reductions of computational units would compensate for the overhead caused by the extra registers. Figure 12. Open in a new tab Power reductions of all convolutional layers. (a) LeNet, latency = 3.36 us. (b) LeNet, latency = 9.6 us. (c) AlexNet, latency = 573.4 us. (d) AlexNet, latency = 1597.4 us. (e) VGG16, latency = 573.4 us. (f) VGG16, latency = 1720.3 us. We provide some observations for the case of AlexNet from Figure 12c,d, as follows. The power reductions of the second and fourth layers are higher than those of the th \u2026\n- (Efficient Hardware Architectures for Accelerating Deep Neural Networks: Survey) have demonstrated TinyML\u2019s promise. Some key issues that require further research in this area include developing benchmarks, memory constraints, energy, processor capacity, cost reduction, etc. VIII. COMPARISON BETWEEN VARIOUS HARDWARE ARCHITECTURES FOR DNN ACCELERATION The performance of the various hardware accelerators for the DNN acceleration depends on the target application. However, researchers defined some standardmetrics, namely, area, power, and throughput, to measure the performance of the hardware accelerators for the development and deployment of DNNs. Here, the area is nothing b \u2026\n## Real-time Scheduling and Latency Control\n- (Predictable CNN Inference and Real-Time Scheduling on Multicore ...) latency in isolation. The literature based on these frameworks often interprets \u201creal-time\u201d as being fast enough for a specific application\u2019s functionality, but this does 20 Chapter 1. Introduction not satisfy the stringent requirements of classical real-time systems, which demand for- mal guarantees on respect of timing constraints. A significant gap exists in the analysis of ML workloads from a scheduling theory perspective, particularly when they must be co-scheduled with other critical tasks under a Real-Time Operating System (RTOS). \u2022 Unpredictability of multicore COTS hardware. To meet t \u2026\n- (Exploring the Dynamic Scheduling Space of Real-Time Generative ...) decode stage latencies are more akin to the other non-generative models. This approach balances the stringent latency demands of real-time models with the long and stage-dependent latency of LLMs. Table 4: Evaluated Scheduling Policies. Scheduler  Deadline  Dynamic HW  Heterogeneity  GenAI Aware  Selection  Aware  Aware EDF-AOT  \u2713  \u2717  \u2713  \u2717 EDF-Dyn  \u2713  \u2713  \u2713  \u2717 FCFS-AOT  \u2717  \u2717  \u2713  \u2717 FCFS-Dyn  \u2717  \u2713  \u2713  \u2717 FTF  \u2713  \u2713  \u2713  \u2713 5 Case Studies We begin by analyzing the performance of the models listed in\xa0Table\xa01. To collect performance data, we use ONNX Runtime Generative AI (OGA) for LLMs and ONNX Runtime \u2026\n- (Exploring the Dynamic Scheduling Space of Real-Time Generative ...) execution provider to run the quantized models and collect their inference latency. We use the collected latency profiles to drive\xa0Table\xa02\u2019s scenarios simulation experiments. 4.2 Runtime Simulator To evaluate the impact of scheduling on the performance of the emerging RTGen workloads, we implement a Python-level simulator highlighted in\xa0Figure\xa03 to simulate the runtime of the different scenarios defined in\xa0Table\xa02 under the five scheduling policies described in\xa0Table\xa04. The simulator inputs consist of the scenario\u2019s models, their input tensors, the real-time requirements (e.g. FPS), as well as \u2026\n- (PDF Pipelined Data-Parallel CPU/GPU Scheduling for Multi-DNN Real-Time ...) the schedulability of real-time tasks. However, other approaches, such as local deadline assignment [17], can also be potentially used in our framework and we wish to investigate it in the future. Real-time GPU scheduling has been extensively investigated in the literature. Many earlier schemes [12, 25, 38] launch one kernel at a time to guarantee bounded response time. Recent studies [37, 48] report that the concurrent execution of multiple kernels can improve throughput and GPU utilization, but at the cost of predictability. To achieve better performance isolation, techniques to split a GPU  \u2026\n## System Interfaces and Interconnect\n- (An Open-Source Platform for High-Performance Non-Coherent On-Chip Communication) and comes with communication modules such as ID width converters, a DMA engine, and on-chip memory controllers3, which are licensed separately or not available at all from commercial vendors. Cache-coherent on-chip communication protocols cur- rently in use include Intel\u2019s UltraPath Interconnect [32], AMD\u2019s scalable data fabric [33], IBM\u2019s Power9 on-chip interconnect [34], AMBA AXI Coherency Extensions (ACE) [8], AMBA5 Coherent Hub Interface (CHI) [35], and TileLink Cached (TL-C) [22]. ACE and TL-C are extensions of AXI and TL-UH, respectively. As such, our platform could be extended for coher \u2026\n- (Mixx Technologies' vision for inference at scale - Gazettabyte) compatibility and broad integration\u2014whether for scale-up (massive single clusters), scale-out (distributed), scale-in (disaggregated memory/compute), or scale-across (long-reach). Market dynamics Mixx is using the funding round to accelerate engineering-sample delivery\u2014primarily of the HBxIO chiplet\u2014for customer integration into custom packages. Company recruitment is also taking place, covering photonics, electronics, advanced packaging, and opto-mechanics. The company has over 50 staff in the US, Taiwan, and India. Asked about the recent wave of optical interconnect acquisitions \u2013 Ciena acqu \u2026\n- (PLoRA: An NDP-Enhanced Pooled-Memory System for Cost-Efficient Multi-LoRA Serving) partner ecosystem,\u201d NVIDIA Newsroom, COMPUTEX, Taipei, 2025, 18 May 2025. [Online]. Available: https://nvidianews.nvidia.com/news/nvidia-nvlink- fusion-semi-custom-ai-infrastructure-partner-ecosystem [54] NVIDIA Corporation, \u201cExtended GPU memory,\u201d CUDA C++ Programming Guide, v13.3, Sec. 4.17, 2026. [Online]. Avail- able: https://docs.nvidia.com/cuda/cuda-programming-guide/04-special- topics/extended-gpu-memory.html [55] NVIDIA Corporation, \u201cNVLink-C2C: Chip interconnect technology,\u201d NVIDIA product page, 2026, states that NVLink-C2C \u201csupports Arm\u2019s AMBA CHI (Coherent Hub Interface) or Compute E \u2026\n- (Scaling Inference Prefill with High-Radix Photonic Interconnects) generally aim to balance the ratios of compute power, memory capacity, memory bandwidth, and interconnect bandwidth to match the needs of the prefill or decode phase (or both) of inference. While compute, memory capacity, and memory bandwidth continue to scale [25], a pivotal shift in interconnect technology is required to increase bandwidth density, overcome the reach limitations of copper, and build more powerful inference systems. III-A 3D Photonic Interconnects To address these limitations, the industry is moving toward true 3D integrated photonics, exemplified by the Lightmatter Passage p \u2026\n## Verification and Reliability Framework\n- (Verification Reward Model for Reinforcement Learning in Chip Design Verification) PREPRINT, SEPTEMBER 2026 1 Verification Reward Model for Reinforcement Learning in Chip Design Verification Shashank Chaurasia Abstract\u2014We propose a Verification Reward Model (VRM) framework for training language models to create and repair chip verification artifacts. The central object is a versioned verification contract that binds requirements, permissible stim- ulus, observation boundaries, reference behavior, evaluation budgets, and acceptance criteria. Compiler, simulator, formal, mutation, coverage, and expert-review evidence are converted into auditable records. Deterministic acceptan \u2026\n- (Embedded Blockchain Infrastructure Management (eBIM): A RISC-V-Empowered Hardware--Software Co-Design Framework Towards Trustworthy Blockchain) a Trusted Execution Environment (TEE). The software side provides a cryptography accelerator, supporting opera- tions such as signature, hash, AES, SHA, and post-quantum cryptography (PQC). Above the hardware and software is the Execution & Validation layer. This layer connects trusted computing re- sources with blockchain functions. It ensures that blockchain operations can be executed, verified, and validated in a trusted way. The Blockchain layer sits above execution and valida- tion. It contains four major components: consensus such as PoS, DPoS, and PBFT; smart contracts for DApp and DeFi \u2026\n- (Verification Reward Model for Reinforcement Learning in Chip Design Verification) admitted through feature-specific qualification. This is a position paper: we specify the framework and the experiments that would test it, and we report no training, EDA, or silicon results. Index Terms\u2014Verification reward models; reinforcement learn- ing; mutation testing; EDA evidence; hardware verification; large language models I. INTRODUCTION Language models can now produce syntactically plausible SystemVerilog, assertions, and testbench components [1], [2], [3], [4]. Turning that ability into a dependable verification assistant requires a training signal that rewards what verification e \u2026\n- (Verification Reward Model for Reinforcement Learning in Chip Design Verification) September 2026. therefore not only what to reward but which quantities may be learned, which must remain deterministic, and how partial evidence should be used before a full campaign completes. This position paper proposes a Verification Reward Model (VRM) framework built on that separation. Fig. 1 summarizes the architecture. A frozen verification contract and a protected evaluator produce versioned evidence. A learned outcome model predicts expensive future evidence from the contract, the artifact, and explicitly masked partial evidence; a semantic critic identifies evidence-supported weakne \u2026\n", 'guidance': '[{"text":"Produce synthesizable SystemVerilog (IEEE 1800-2017 subset that Verilator,\\nIcarus Verilog and Yosys accept):\\n- One module per file, file named after the module (e.g. `// file: alu.sv`).\\n  Use `always_ff`/`always_comb`, explicit widths, synchronous active-low reset\\n  `rst_n` unless the requirements say otherwise; no latches, no initial blocks\\n  in RTL, no vendor primitives.\\n- Make the top module\'s ports match the specified interface exactly; state the\\n  top module name in the description.\\n- Provide at least one self-checking testbench `tb_<top>.sv` (module `tb_<top>`)\\n  that drives directed and edge-case stimulus, compares against a reference\\n  model, `$display`s `PASS <check>` / `FAIL <check>` for each check, prints\\n  `ALL TESTS PASSED` only if nothing failed, and calls `$finish`.\\n- In the description give the microarchitecture (block diagram in words,\\n  pipeline stages, FSM states, widths), how each requirement is met, and the\\n  expected PPA trade-offs.\\nValidation will lint (Verilator -Wall), simulate every tb_*.sv (Icarus) and\\nsynthesize the top (Yosys) \u2014 design so all three pass."}]', 'maxBlocks': 6, 'iterations': 4}
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
