// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

// project-model-tree lays the declared JPS project model files out as a checkout-shaped tree, for the dev-distribution
// actions that load the project model.
//
// A Bazel action has no checkout. It names every model file it depends on in a manifest, and this tool copies them to
// the paths the model loader expects. Rows are tab-separated:
//
//	copy<TAB>path/to/the/input<TAB>relative/destination/in/the/tree
//	create<TAB><TAB>relative/destination/in/the/tree
//
// `create` rows carry the repository marker files. They are not optional: `IdeaProjectLoaderUtil` searches upwards for
// `.ultimate.root.marker`, so a tree without it is not a repository.
//
// Twin: `JpsModuleToBazelTargetsOnly` reads the same manifest format in the standalone `jps_to_bazel` project. The two
// cannot share code, so a change to the format has to be made in both.
package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"

	"jetbrains.com/content-module-packer/internal/span"
)

// chunkSize is the number of rows one worker copies before it takes the next chunk. A model is thousands of small files,
// so one file per task would spend more on scheduling than on copying.
const chunkSize = 512

type options struct {
	manifest  string
	outputDir string
	traceFile string
}

type row struct {
	action      string
	source      string
	destination string
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, output, errorOutput io.Writer) (exitCode int) {
	opts, err := parseOptions(args)
	if err != nil {
		fmt.Fprintf(errorOutput, "ERROR: %v\n", err)
		return 2
	}
	const jobName = "materialize project model tree"
	var tracer *span.Tracer
	if opts.traceFile != "" {
		tracer = span.NewTracer(jobName)
	}
	root := tracer.Start(jobName, nil)
	defer func() {
		root.End()
		if err := tracer.WriteFile(opts.traceFile); err != nil {
			fmt.Fprintf(errorOutput, "ERROR: writing the span file: %v\n", err)
			exitCode = 1
		}
	}()
	count, err := materialize(opts.manifest, opts.outputDir)
	if err != nil {
		root.Fail(err)
		fmt.Fprintf(errorOutput, "ERROR: %v\n", err)
		return 1
	}
	root.SetInt("files", int64(count))
	fmt.Fprintf(output, "Project model tree materialized into %s\n", opts.outputDir)
	return 0
}

func parseOptions(args []string) (options, error) {
	var opts options
	destinations := map[string]*string{
		"--project-manifest": &opts.manifest,
		"--output-dir":       &opts.outputDir,
		"--trace-file":       &opts.traceFile,
	}
	for _, arg := range args {
		name, value, hasValue := strings.Cut(arg, "=")
		if !strings.HasPrefix(name, "--") || !hasValue || value == "" {
			return opts, fmt.Errorf("expected an option in the '--key=value' form, but got %q", arg)
		}
		destination, known := destinations[name]
		if !known {
			return opts, fmt.Errorf("unknown option %q", name)
		}
		absolute, err := filepath.Abs(value)
		if err != nil {
			return opts, err
		}
		*destination = absolute
	}
	if opts.manifest == "" {
		return opts, errors.New("--project-manifest is required")
	}
	if opts.outputDir == "" {
		return opts, errors.New("--output-dir is required")
	}
	return opts, nil
}

// materialize rebuilds target from the manifest and returns the number of rows it wrote.
func materialize(manifest string, target string) (int, error) {
	rows, err := readRows(manifest, target)
	if err != nil {
		return 0, err
	}

	// Rebuilt from scratch every time: a file left over from a previous run is a model entry that nothing declares.
	if err := os.RemoveAll(target); err != nil {
		return 0, err
	}
	if err := os.MkdirAll(target, 0o755); err != nil {
		return 0, err
	}
	// Up front and single-threaded, so that the workers never race on the ancestors they share.
	for _, r := range rows {
		if err := os.MkdirAll(filepath.Dir(r.destination), 0o755); err != nil {
			return 0, err
		}
	}

	chunks := make(chan []row)
	var failure error
	var failureOnce sync.Once
	var workers sync.WaitGroup
	for range min(runtime.NumCPU(), (len(rows)+chunkSize-1)/chunkSize) {
		workers.Go(func() {
			for chunk := range chunks {
				for _, r := range chunk {
					if err := write(r); err != nil {
						failureOnce.Do(func() { failure = err })
					}
				}
			}
		})
	}
	for start := 0; start < len(rows); start += chunkSize {
		chunks <- rows[start:min(start+chunkSize, len(rows))]
	}
	close(chunks)
	workers.Wait()
	if failure != nil {
		return 0, failure
	}
	return len(rows), nil
}

func readRows(manifest string, target string) ([]row, error) {
	content, err := os.ReadFile(manifest)
	if err != nil {
		return nil, err
	}
	var rows []row
	for _, line := range strings.Split(string(content), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		fields := strings.SplitN(line, "\t", 3)
		if len(fields) != 3 {
			return nil, fmt.Errorf("invalid project model manifest line (expected 'action<TAB>source<TAB>destination'): %s", line)
		}
		destination := filepath.Join(target, filepath.FromSlash(fields[2]))
		relative, err := filepath.Rel(target, destination)
		if err != nil || relative == "." || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			return nil, fmt.Errorf("project model manifest destination %q escapes %s", fields[2], target)
		}
		rows = append(rows, row{action: fields[0], source: fields[1], destination: destination})
	}
	return rows, nil
}

func write(r row) error {
	switch r.action {
	case "copy":
		// A missing source fails here rather than quietly producing a thinner project model.
		return copyFile(r.source, r.destination)
	case "create":
		file, err := os.OpenFile(r.destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
		if err != nil {
			return err
		}
		return file.Close()
	default:
		return fmt.Errorf("unknown project model manifest action %q for %q", r.action, r.destination)
	}
}

// copyFile copies the content and the permission bits of source, following a link, as the sandbox stages inputs as links.
func copyFile(source string, destination string) (err error) {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	info, err := in.Stat()
	if err != nil {
		return err
	}
	out, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL, info.Mode().Perm())
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := out.Close(); err == nil {
			err = closeErr
		}
	}()
	_, err = io.Copy(out, in)
	return err
}
