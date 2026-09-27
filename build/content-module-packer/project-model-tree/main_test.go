// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package main

import (
	"bytes"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestCopiesAndCreatesAtTheDeclaredDestinations(t *testing.T) {
	dir := t.TempDir()
	source := writeSource(t, dir, "community/platform/build-scripts/intellij.platform.buildScripts.iml", "<module/>")

	target, err := materializeRows(t, dir,
		"copy\t"+source+"\tcommunity/platform/build-scripts/intellij.platform.buildScripts.iml",
		"create\t\t.ultimate.root.marker",
		"create\t\tcommunity/.community.root.marker",
	)
	if err != nil {
		t.Fatal(err)
	}

	content, err := os.ReadFile(filepath.Join(target, "community/platform/build-scripts/intellij.platform.buildScripts.iml"))
	if err != nil || string(content) != "<module/>" {
		t.Fatalf("copied content: %q, %v", content, err)
	}
	for _, marker := range []string{".ultimate.root.marker", "community/.community.root.marker"} {
		if _, err := os.Stat(filepath.Join(target, marker)); err != nil {
			t.Fatalf("marker %s: %v", marker, err)
		}
	}
}

func TestBlankLinesAreNotRows(t *testing.T) {
	dir := t.TempDir()
	target, err := materializeRows(t, dir, "", "create\t\t.ultimate.root.marker", "   ", "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(target, ".ultimate.root.marker")); err != nil {
		t.Fatal(err)
	}
}

func TestAMissingSourceFails(t *testing.T) {
	dir := t.TempDir()
	_, err := materializeRows(t, dir, "copy\t"+filepath.Join(dir, "sources/gone.iml")+"\tgone.iml")
	if !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("expected a missing file error, got %v", err)
	}
}

func TestADestinationOutsideTheTreeIsRejected(t *testing.T) {
	dir := t.TempDir()
	source := writeSource(t, dir, "build.txt", "252.1")
	for _, destination := range []string{"../escaped/build.txt", ".", "a/../.."} {
		_, err := materializeRows(t, dir, "copy\t"+source+"\t"+destination)
		if err == nil || !strings.Contains(err.Error(), "escapes") {
			t.Fatalf("%s: expected an escape error, got %v", destination, err)
		}
	}
}

func TestAMalformedRowIsRejected(t *testing.T) {
	dir := t.TempDir()
	_, err := materializeRows(t, dir, "create\t.ultimate.root.marker")
	if err == nil || !strings.Contains(err.Error(), "action<TAB>source<TAB>destination") {
		t.Fatalf("expected a malformed row error, got %v", err)
	}
}

func TestAnUnknownActionIsRejected(t *testing.T) {
	dir := t.TempDir()
	_, err := materializeRows(t, dir, "link\t\tsomewhere")
	if err == nil || !strings.Contains(err.Error(), "link") {
		t.Fatalf("expected an unknown action error, got %v", err)
	}
}

// The tree is an output of the manifest alone, so a previous run's leftovers must not survive into the next one.
func TestAStaleTreeIsReplacedRatherThanMergedInto(t *testing.T) {
	dir := t.TempDir()
	source := writeSource(t, dir, "build.txt", "252.1")
	target := filepath.Join(dir, "tree")
	if err := os.MkdirAll(filepath.Join(target, "community"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "community/stale.iml"), []byte("<module/>"), 0o644); err != nil {
		t.Fatal(err)
	}

	if _, err := materialize(writeManifest(t, dir, "copy\t"+source+"\tbuild.txt"), target); err != nil {
		t.Fatal(err)
	}

	if _, err := os.Stat(filepath.Join(target, "build.txt")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(target, "community/stale.iml")); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("the stale file survived: %v", err)
	}
}

// Many rows cross several chunks, so this exercises the workers and the order-independent result.
func TestManyRowsAreAllWritten(t *testing.T) {
	dir := t.TempDir()
	var lines []string
	for i := range 3*chunkSize + 7 {
		name := filepath.ToSlash(filepath.Join("m", string(rune('a'+i%26)), "f"+strings.Repeat("x", i%5)+"-"+strconv.Itoa(i)+".iml"))
		lines = append(lines, "copy\t"+writeSource(t, dir, name, strconv.Itoa(i))+"\t"+name)
	}
	target, err := materializeRows(t, dir, lines...)
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range lines {
		fields := strings.Split(line, "\t")
		want, _ := os.ReadFile(fields[1])
		got, err := os.ReadFile(filepath.Join(target, fields[2]))
		if err != nil || !bytes.Equal(got, want) {
			t.Fatalf("%s: %q, %v", fields[2], got, err)
		}
	}
}

func TestOptions(t *testing.T) {
	var output, errorOutput bytes.Buffer
	if code := run([]string{"--project-manifest=a"}, &output, &errorOutput); code != 2 || !strings.Contains(errorOutput.String(), "--output-dir is required") {
		t.Fatalf("exit %d, %q", code, errorOutput.String())
	}
	errorOutput.Reset()
	if code := run([]string{"--unknown=a"}, &output, &errorOutput); code != 2 || !strings.Contains(errorOutput.String(), "unknown option") {
		t.Fatalf("exit %d, %q", code, errorOutput.String())
	}
	errorOutput.Reset()
	if code := run([]string{"positional"}, &output, &errorOutput); code != 2 || !strings.Contains(errorOutput.String(), "--key=value") {
		t.Fatalf("exit %d, %q", code, errorOutput.String())
	}
}

func TestRunWritesTheSpanFile(t *testing.T) {
	dir := t.TempDir()
	manifest := writeManifest(t, dir, "create\t\t.ultimate.root.marker")
	trace := filepath.Join(dir, "spans.json")
	var output, errorOutput bytes.Buffer
	code := run([]string{"--project-manifest=" + manifest, "--output-dir=" + filepath.Join(dir, "tree"), "--trace-file=" + trace}, &output, &errorOutput)
	if code != 0 {
		t.Fatalf("exit %d, %q", code, errorOutput.String())
	}
	content, err := os.ReadFile(trace)
	if err != nil || !strings.Contains(string(content), "materialize project model tree") {
		t.Fatalf("span file: %q, %v", content, err)
	}
}

func materializeRows(t *testing.T, dir string, lines ...string) (string, error) {
	t.Helper()
	target := filepath.Join(dir, "tree")
	_, err := materialize(writeManifest(t, dir, lines...), target)
	return target, err
}

func writeManifest(t *testing.T, dir string, lines ...string) string {
	t.Helper()
	manifest := filepath.Join(dir, "model.manifest")
	if err := os.WriteFile(manifest, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return manifest
}

func writeSource(t *testing.T, dir string, relativePath string, content string) string {
	t.Helper()
	file := filepath.Join(dir, "sources", filepath.FromSlash(relativePath))
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(file, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return file
}
