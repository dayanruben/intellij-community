// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

//go:build windows

package main

import (
	"os"
	"strings"
	"syscall"
	"unsafe"
)

var procGetFinalPathNameByHandleW = syscall.NewLazyDLL("kernel32.dll").NewProc("GetFinalPathNameByHandleW")

// evalSymlinks returns the final path of an existing file or directory, as Java's `toRealPath` does on Windows.
//
// It does not use `filepath.EvalSymlinks`. That function normalizes each name with `FindFirstFile` on the plain path,
// which fails for a path longer than 260 characters. `os.Open` adds the long-path prefix, and the handle gives the
// final path.
func evalSymlinks(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	buffer := make([]uint16, 512)
	for {
		length, _, callErr := procGetFinalPathNameByHandleW.Call(file.Fd(), uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer)), 0)
		if length == 0 {
			return "", &os.PathError{Op: "GetFinalPathNameByHandle", Path: path, Err: callErr}
		}
		if int(length) < len(buffer) {
			return stripExtendedPathPrefix(syscall.UTF16ToString(buffer[:length])), nil
		}
		// the buffer is too small, and length is the size it needs, including the terminating zero
		buffer = make([]uint16, length)
	}
}

// stripExtendedPathPrefix removes the `\\?\` prefix that GetFinalPathNameByHandle adds, so that the path compares equal
// to the paths the rest of the composer builds.
func stripExtendedPathPrefix(path string) string {
	if rest, found := strings.CutPrefix(path, `\\?\UNC\`); found {
		return `\\` + rest
	}
	if rest, found := strings.CutPrefix(path, `\\?\`); found {
		return rest
	}
	return path
}
