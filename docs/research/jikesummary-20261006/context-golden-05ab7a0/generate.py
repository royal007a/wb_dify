#!/usr/bin/env python3
"""Compile old serialization entrypoints and emit synthetic, immutable fixture bytes.

No Maven, Spring application, database, HTTP or model. Supply an already built
dependency tree and its Surefire XML only to obtain the Java classpath. No other
JVM properties or application output from that XML are copied to evidence.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import xml.etree.ElementTree as ET

BASELINE = "05ab7a06f485a5d800009dedbbe566508bd9e775"
EXPECTED_FIXTURES = {
    "history-messages.json", "checkpoint-messages.json", "checkpoint-before-model.json",
    "committed-model-response.json", *(f"detail-message-{i}.json" for i in range(7)),
}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--repo", type=pathlib.Path, required=True)
    p.add_argument("--dependency-tree", type=pathlib.Path, required=True)
    p.add_argument("--classpath-report", type=pathlib.Path, required=True)
    p.add_argument("--jdk", type=pathlib.Path, required=True)
    p.add_argument("--output", type=pathlib.Path, required=True)
    a = p.parse_args()
    if a.output.exists():
        raise SystemExit("Output directory must not already exist")
    commit = subprocess.check_output(["git", "rev-parse", BASELINE + "^{commit}"], cwd=a.repo, text=True).strip()
    paths = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", commit, "backend"],
                                    cwd=a.repo, text=True).splitlines()
    production = [s for s in paths if "/src/main/" in s]
    # Dependencies may have been built later, but their source inputs must still match the baseline.
    for name in production:
        original = subprocess.check_output(["git", "show", commit + ":" + name], cwd=a.repo)
        if (a.dependency_tree / name).read_bytes() != original:
            raise SystemExit("Dependency source differs from baseline: " + name)
    props = ET.parse(a.classpath_report).getroot().find("properties")
    classpath = next(x.attrib["value"] for x in props if x.attrib.get("name") == "java.class.path")
    selected = [
        "backend/hify-common/src/main/java/com/hify/common/JacksonConfig.java",
        "backend/hify-provider/src/main/java/com/hify/runtime/RuntimeMessage.java",
        "backend/hify-knowledge/src/main/java/com/hify/knowledge/api/KnowledgeCitation.java",
        "backend/hify-chat/src/main/java/com/hify/application/CommittedHistoryWriter.java",
        "backend/hify-chat/src/main/java/com/hify/application/RunApplicationService.java",
        "backend/hify-chat/src/main/java/com/hify/memory/CanonicalDetailReader.java",
    ]
    probe = pathlib.Path(__file__).with_name("CanonicalGoldenProbe.java")
    manifest = {"sourceCommit": commit, "productionInputsCompared": len(production),
                "dependencySourceInputsMatch": True, "productionSources": {},
                "probeSha256": sha(probe.read_bytes()), "artifacts": {},
                "limitations": ["Synthetic fixtures, not a production database capture",
                                "Only serialization methods invoked; no replay or admission test",
                                "Classpath dependencies reused; source comparison is not binary build attestation",
                                "Spring mapper builder plus baseline customizer; no Spring Boot application started"]}
    # Bound only these child JVMs; do not inherit unrelated process-wide JVM injections.
    child_env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        child_env.pop(key, None)
    with tempfile.TemporaryDirectory(prefix="hify-canonical-golden-") as temp:
        tmp = pathlib.Path(temp)
        files = []
        for name in selected:
            raw = subprocess.check_output(["git", "show", commit + ":" + name], cwd=a.repo)
            source = tmp / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(raw)
            files.append(str(source))
            manifest["productionSources"][name] = sha(raw)
        classes = tmp / "classes"
        classes.mkdir()
        commands = [
            [str(a.jdk / "bin/javac"), "-J-Xmx192m", "--release", "17", "-encoding", "UTF-8",
             "-implicit:none", "-cp", classpath, "-d", str(classes), *files, str(probe)],
            [str(a.jdk / "bin/java"), "-Xmx192m", "-cp", str(classes) + ":" + classpath,
             "com.hify.common.CanonicalGoldenProbe", str(a.output)],
        ]
        for stage, command in zip(["compile", "generate"], commands):
            result = subprocess.run(command, capture_output=True, text=True, env=child_env,
                                    timeout=90 if stage == "compile" else 30)
            print(stage + " exit=" + str(result.returncode))
            if result.returncode:
                print(result.stderr)
                raise SystemExit(result.returncode)
            manifest[stage + "ExitCode"] = result.returncode
        for artifact in sorted(a.output.glob("*.json")):
            manifest["artifacts"][artifact.name] = sha(artifact.read_bytes())
        if set(manifest["artifacts"]) != EXPECTED_FIXTURES:
            raise SystemExit("Unexpected fixture file set")
        (a.output / "provenance.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Generated 11 fixtures and provenance; no services started.")


if __name__ == "__main__":
    main()
