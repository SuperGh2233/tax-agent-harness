#!/usr/bin/env python3
"""T39 fresh-checkout build: what REPRODUCE.md lists as never verified ("空缓存首次在线依赖下载、真正全新 Git
checkout"). A committed ref is cloned into a new temporary directory (only committed files; no target, .m2,
node_modules or frontend/dist), built with an empty Maven repository there (online: Maven Central and the Node
download), then checked as a user would: seed hashes, full tests, package, the UI inside the JAR, initialization
of a new database, a loopback server answering /api/health and serving the React page, and an offline rebuild
from the cache the online build filled. No model is called and nothing is written outside the temporary directory
and --out.

Usage (JDK 21 selected in the shell, network access for the first build; several hundred MB are downloaded):
    python3 scripts/fresh_checkout_check.py                 # HEAD, the mvn on PATH
    python3 scripts/fresh_checkout_check.py --ref v0.2.0 --keep
    python3 scripts/fresh_checkout_check.py --maven 3.6.3   # T41: the documented minimum, downloaded and checksummed
    python3 scripts/fresh_checkout_check.py --maven wrapper # the clone's own mvnw / mvnw.cmd (Maven 3.9.9)
Output: <out>/results.json, <out>/summary.md and one log per step.
"""
import argparse, hashlib, json, os, platform, re, shutil, subprocess, sys, tarfile, tempfile, time, urllib.request, zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from innovation_compare import free_port  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
JAR = "target/harness-0.0.1-SNAPSHOT.jar"
UI = "BOOT-INF/classes/static/react-app/index.html"


class Steps:
    def __init__(self, out):
        self.out, self.results = out, []

    def run(self, name, command, cwd, expect=None, timeout=3600):
        """One command with its log; passes on exit 0 and, if given, a regex found in the output."""
        started = time.time()
        log = self.out / f"{len(self.results) + 1:02d}-{name}.log"
        try:
            # errors="replace": Maven on Windows writes in the console code page, not UTF-8.
            done = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", timeout=timeout)
            output, exit_code = done.stdout, done.returncode
        except subprocess.TimeoutExpired as e:
            output = e.stdout.decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
            exit_code = "TIMEOUT"
        log.write_text(output, encoding="utf-8")
        found = expect is None or re.search(expect, output) is not None
        detail = f"exit {exit_code}" + ("" if found else "，输出中没有预期内容")
        return self.record(name, exit_code == 0 and found, detail, started, log, output)

    def record(self, name, ok, detail, started=None, log=None, output=""):
        self.results.append({"step": name, "passed": ok, "detail": detail,
                             "seconds": round(time.time() - started, 1) if started else None,
                             "log": log.name if log else None, "tail": output.strip().splitlines()[-3:] if output else []})
        print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}", flush=True)
        return ok, output


def surefire(source):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for report in (source / "target" / "surefire-reports").glob("TEST-*.xml"):
        head = re.search(r"<testsuite[^>]*", report.read_text(encoding="utf-8", errors="replace")[:4000])
        for key in totals:
            found = re.search(rf'\b{key}="(\d+)"', head.group(0)) if head else None
            totals[key] += int(found.group(1)) if found else 0
    return totals


def serve(steps, jar, work):
    db = work / "demo.db"
    ok, _ = steps.run("initialize-data", ["java", "-jar", str(jar), "--initialize-data", f"--database={db}"], work, timeout=300)
    if not ok: return
    port = free_port()
    started = time.time()
    log = steps.out / f"{len(steps.results) + 1:02d}-server.log"
    with log.open("w") as handle:
        process = subprocess.Popen(["java", "-jar", str(jar), f"--taxagent.database={db}", "--server.address=127.0.0.1",
                                    f"--server.port={port}"], cwd=work, stdout=handle, stderr=subprocess.STDOUT)
        try:
            health = page = None
            for _ in range(120):
                try:
                    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health", timeout=2) as r: health = json.loads(r.read())
                    break
                except Exception: time.sleep(0.5)
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/react-app/", timeout=5) as r:
                    page = (r.status, r.read().decode(errors="replace"))
            except Exception as e: page = (None, str(e))
        finally:
            process.terminate()
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: process.kill()
    steps.record("server-health", (health or {}).get("status") == "UP", f"/api/health {health}", started, log)
    steps.record("server-react-page", page[0] == 200 and 'id="root"' in page[1], f"/react-app/ HTTP {page[0]}", started, log)


def download_maven(version, work):
    """T41: an exact Maven release from the Apache archive, checked against its published SHA-512."""
    base = f"https://archive.apache.org/dist/maven/maven-3/{version}/binaries/apache-maven-{version}-bin.tar.gz"
    archive = work / f"apache-maven-{version}-bin.tar.gz"
    with urllib.request.urlopen(base, timeout=120) as r: archive.write_bytes(r.read())
    with urllib.request.urlopen(base + ".sha512", timeout=60) as r: expected = r.read().decode().split()[0].lower()
    actual = hashlib.sha512(archive.read_bytes()).hexdigest()
    if actual != expected: raise RuntimeError(f"SHA-512 mismatch for Maven {version}")
    with tarfile.open(archive) as t:
        try: t.extractall(work / "maven-dist", filter="data")
        except TypeError: t.extractall(work / "maven-dist")  # Python without extraction filters (e.g. macOS 3.9.6)
    home = work / "maven-dist" / f"apache-maven-{version}"
    return str(home / "bin" / ("mvn.cmd" if os.name == "nt" else "mvn")), expected


def summary(meta, results):
    lines = ["# T39 全新检出与空缓存构建检查（自动生成，未经人工修改）", "",
             f"- 生成时间：{meta['generatedAt']}；提交：`{meta['commit']}`（{meta['ref']}）",
             f"- 环境：{meta['java']}；{meta['maven']}（{meta['mavenSource']}）；{meta['os']}",
             "- 做法：从本仓库克隆该提交到新临时目录（只含已提交文件），Maven 仓库为该目录下的空目录，首次在线构建后再离线复跑。", "",
             "| 步骤 | 结果 | 明细 | 耗时（秒） |", "|---|---|---|---|"]
    lines += [f"| {r['step']} | {'通过' if r['passed'] else '未通过'} | {r['detail']} | {r['seconds'] if r['seconds'] is not None else '—'} |"
              for r in results]
    lines += ["", "每步完整输出见同目录的编号日志。"]
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ref", default="HEAD")
    p.add_argument("--mvn", default="mvn", help="Maven executable on PATH (ignored with --maven)")
    p.add_argument("--maven", help="an exact version to download (e.g. 3.6.3), or 'wrapper' for the clone's mvnw")
    p.add_argument("--out", default=str(ROOT / "target" / "t39-fresh-checkout"))
    p.add_argument("--keep", action="store_true", help="keep the temporary clone and Maven cache")
    a = p.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="t39-fresh-"))
    source, cache = work / "source", work / "m2"
    steps = Steps(out)
    def version(cmd, marker):
        """The line naming the version: JDK 24+ prints native-access warnings first when Maven loads jansi."""
        output = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace").stdout
        lines = re.sub(r"\x1b\[[0-9;]*m", "", output).strip().splitlines()  # Maven 3.6 colours -v even when piped
        return next((line for line in lines if re.search(marker, line)), lines[0] if lines else "?")
    for stale in out.glob("[0-9][0-9]-*.log"): stale.unlink()  # step numbers shift between options; keep only this run
    java = version(["java", "-version"], r'version "')
    commit = subprocess.run(["git", "-C", str(ROOT), "rev-parse", a.ref], stdout=subprocess.PIPE, text=True, check=True).stdout.strip()
    meta = {"generatedAt": time.strftime("%Y-%m-%d %H:%M:%S %z"), "ref": a.ref, "commit": commit, "java": java, "maven": "?",
            "mavenSource": "PATH", "os": f"{sys.platform} {platform.machine()}", "workDir": str(work)}
    try:
        steps.record("java-21", '"21' in java, java + ("" if '"21' in java else "；请先切换到 JDK 21（macOS：export JAVA_HOME=$(/usr/libexec/java_home -v 21)）"))
        ok, _ = steps.run("clone", ["git", "clone", "--quiet", "--no-local", str(ROOT), str(source)], work, timeout=600)
        if ok: ok, _ = steps.run("checkout", ["git", "-C", str(source), "checkout", "--quiet", "--detach", commit], work)
        if not ok: return
        # Windows resolves mvn.cmd only through which(); a downloaded or wrapper Maven is used by absolute path.
        mvn = shutil.which(a.mvn) or a.mvn
        if a.maven == "wrapper":
            mvn, meta["mavenSource"] = str(source / ("mvnw.cmd" if os.name == "nt" else "mvnw")), "仓库 Maven Wrapper"
            os.environ["MAVEN_USER_HOME"] = str(work / "wrapper-home")  # the wrapper's Maven download stays in the temp dir
            if os.name != "nt" and not steps.record("mvnw-executable", os.access(mvn, os.X_OK),
                                                    "克隆出的 mvnw 可执行" if os.access(mvn, os.X_OK) else "克隆出的 mvnw 没有执行权限（Git 文件模式应为 100755）")[0]:
                return
        elif a.maven:
            started = time.time()
            try:
                mvn, digest = download_maven(a.maven, work)
                steps.record("maven-download", True, f"Apache archive，SHA-512 {digest[:16]}… 一致", started)
                meta["mavenSource"] = f"下载 {a.maven}"
            except Exception as e:
                steps.record("maven-download", False, f"{type(e).__name__}: {e}", started)
                return
        meta["maven"] = version([mvn, "-v"], r"^Apache Maven ")
        steps.record("maven-version", a.maven in (None, "wrapper") or a.maven in meta["maven"], meta["maven"])
        leftovers = [n for n in ("target", ".m2", "frontend/node_modules", "frontend/dist", "frontend/node", "data", ".env") if (source / n).exists()]
        steps.record("clean-tree", not leftovers, f"不应存在的目录 {leftovers or '—'}")
        steps.run("official-seed", [sys.executable, "-B", "scripts/official_seed.py", "--check"], source,
                  expect=r"PASS: original hashes and all seed fields verified", timeout=300)
        maven_args = [mvn, "-B", "-ntp", f"-Dmaven.repo.local={cache}"]
        ok, _ = steps.run("mvn-test-online", maven_args + ["test"], source, expect=r"BUILD SUCCESS")
        totals = surefire(source)
        steps.record("test-counts", ok and totals["tests"] > 0 and totals["failures"] == totals["errors"] == 0, json.dumps(totals))
        ok, _ = steps.run("mvn-package-online", maven_args + ["-DskipTests", "package"], source, expect=r"BUILD SUCCESS")
        jar = source / JAR
        if ok and jar.exists():
            with zipfile.ZipFile(jar) as z: names = set(z.namelist())
            steps.record("jar-contains-ui", UI in names, f"{UI} {'存在' if UI in names else '缺失'}；JAR {jar.stat().st_size:,} 字节")
            serve(steps, jar, work)
        steps.run("mvn-package-offline", [mvn, "-o", "-B", "-ntp", f"-Dmaven.repo.local={cache}", "-DskipTests", "package"], source,
                  expect=r"BUILD SUCCESS")
    finally:
        results = steps.results
        (out / "results.json").write_text(json.dumps({"meta": meta, "steps": results}, ensure_ascii=False, indent=2), encoding="utf-8")
        (out / "summary.md").write_text(summary(meta, results), encoding="utf-8")
        if not a.keep: shutil.rmtree(work, ignore_errors=True)
        print(f"written: {out / 'summary.md'}" + ("" if a.keep else f"（临时目录已删除：{work}）"))
        if not all(r["passed"] for r in results): sys.exit(1)


if __name__ == "__main__":
    main()
