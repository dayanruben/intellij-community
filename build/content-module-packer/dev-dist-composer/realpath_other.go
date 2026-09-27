// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

//go:build !windows

package main

import "path/filepath"

// evalSymlinks returns the path with every symbolic link resolved.
func evalSymlinks(path string) (string, error) {
	return filepath.EvalSymlinks(path)
}
