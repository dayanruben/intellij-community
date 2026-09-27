package main

import (
	"os"
	"path/filepath"
	"slices"
	"testing"

	"jetbrains.com/content-module-packer/internal/filemetadata"
)

// The expected values in this file come from the Kotlin code. A throwaway Java program read the same manifests with
// readDevBuildComponentManifest and called computeIdeFingerprintFromComponents, the private
// computeDevBuildLaunchMetadataHash, and orderCoreClasspathEntries.

// The neutral manifest names a supplementary character and U+FF21, whose UTF-16 order differs from the UTF-8 order.
const goldenNeutralManifest = `{"version":9,"kind":"plugins_json","platformPrefix":"idea","os":"","arch":"","additionalModules":["intellij.json","intellij.shared"],"mainClass":null,"coreClassPath":["plugins/json/lib/json.jar"],"pluginCount":1,"entries":[{"relativePath":"plugins/json/lib/json.jar","type":"component-file","hash":-5,"source":"inputs/json.jar"},{"relativePath":"plugins/json/lib/ünïcode.jar","type":"component-file","hash":1234567890123},{"relativePath":"plugins/json/lib/😀.jar","type":"component-file","hash":7},{"relativePath":"plugins/json/lib/Ａ.jar","type":"component-file","hash":8},{"relativePath":"plugins/json/bin/tool","type":"component-file","hash":9,"executable":true,"mode":488},{"relativePath":"plugins/json/bin/run","type":"component-file","hash":10,"executable":true,"mode":493},{"relativePath":"plugins/json/bin/plain","type":"component-file","hash":12,"mode":420},{"relativePath":"plugins/json/resources","type":"directory","mode":448},{"relativePath":"plugins/json/current","type":"symlink","hash":11,"symlinkTarget":"lib"}]}`

// The platform manifest repeats a path with other hashes and executable flags, so the sort needs every key.
const goldenPlatformManifest = `{"kind":"platform_core","platformPrefix":"idea","os":"mac","arch":"aarch64","additionalModules":["intellij.extra"],"mainClass":"com.intellij.idea.Main","coreClassPath":["lib/app-backend.jar","lib/util.jar","lib/a/b.jar","lib/a-b.jar","lib/platform-loader.jar","lib/product-backend.jar","lib/util-8.jar","lib/Z.jar","lib/é.jar","lib/util.jar"],"entries":[{"relativePath":"lib/app-backend.jar","type":"component-file","hash":-9223372036854775808},{"relativePath":"lib/util.jar","type":"component-file","hash":9223372036854775807},{"relativePath":"lib/util.jar","type":"component-file","hash":3},{"relativePath":"lib/util.jar","type":"component-file","hash":3,"executable":true},{"relativePath":"bin/idea.sh","type":"component-file","hash":42,"executable":true}]}`

func readGoldenManifest(test *testing.T, content string) *componentManifest {
	test.Helper()
	file := filepath.Join(test.TempDir(), "manifest.json")
	writeTestFile(test, file, content)
	manifest, err := readComponentManifest(file)
	if err != nil {
		test.Fatal(err)
	}
	return manifest
}

// writeGoldenPluginClasspath writes more than two 256 KiB blocks, so the content hash frames three of them.
func writeGoldenPluginClasspath(test *testing.T) string {
	test.Helper()
	data := make([]byte, 600001)
	for index := range data {
		data[index] = byte(index*31 + 7)
	}
	file := filepath.Join(test.TempDir(), "plugin-classpath.txt")
	if err := os.WriteFile(file, data, 0o644); err != nil {
		test.Fatal(err)
	}
	return file
}

func TestKotlinFingerprintGolden(test *testing.T) {
	neutral := readGoldenManifest(test, goldenNeutralManifest)
	platform := readGoldenManifest(test, goldenPlatformManifest)
	pluginClasspath := writeGoldenPluginClasspath(test)
	declared := []string{"intellij.shared", "intellij.json", "intellij.extra"}
	empty := []string{}
	for _, golden := range []struct {
		name            string
		components      []*componentManifest
		pluginClasspath string
		declared        *[]string
		expected        string
	}{
		{"declared modules and plugin records", []*componentManifest{neutral, platform}, pluginClasspath, &declared, "v5:2xgglqepbv7hj"},
		{"summed modules", []*componentManifest{neutral, platform}, "", nil, "v5:3sgxji0bgzbkr"},
		{"no modules", []*componentManifest{platform}, "", &empty, "v5:1r4g1pf8wm6az"},
		{"platform first", []*componentManifest{platform, neutral}, pluginClasspath, nil, "v5:25umy5wjndqig"},
	} {
		actual, err := computeIdeFingerprintFromComponents(golden.components, golden.pluginClasspath, golden.declared)
		if err != nil || actual != golden.expected {
			test.Errorf("%s: fingerprint = %s, error = %v, expected %s", golden.name, actual, err, golden.expected)
		}
	}
}

func TestKotlinLaunchMetadataHashGolden(test *testing.T) {
	for _, golden := range []struct {
		platformPrefix, os, arch, mainClass string
		modules                             []string
		expected                            int64
	}{
		{"idea", "linux", "x64", "com.intellij.idea.Main", nil, -802626408373264443},
		{"Идея", "mac", "aarch64", "Main\U0001F600", []string{"a", "b", "ü"}, 4151155953984664682},
		{"", "", "", "", []string{""}, 2331720420689199891},
	} {
		actual := computeDevBuildLaunchMetadataHash(golden.platformPrefix, golden.os, golden.arch, golden.mainClass, golden.modules)
		if actual != golden.expected {
			test.Errorf("launch metadata hash of %+v = %d", golden, actual)
		}
	}
}

func TestKotlinCoreClasspathOrderGolden(test *testing.T) {
	platform := readGoldenManifest(test, goldenPlatformManifest)
	expected := []string{
		"lib/platform-loader.jar", "lib/util-8.jar", "lib/util.jar", "lib/product-backend.jar", "lib/Z.jar", "lib/a-b.jar",
		"lib/a/b.jar", "lib/app-backend.jar", "lib/util.jar", "lib/é.jar",
	}
	if windows {
		test.Skip("Windows sorts core classpath entries as strings")
	}
	if actual := orderCoreClasspathEntries(platform.CoreClassPath); !slices.Equal(actual, expected) {
		test.Fatalf("core classpath = %q", actual)
	}
}

// The Kotlin test "Go manifests preserve version 9 hashes and tree fingerprints" pins these content hashes. The
// composer reuses filemetadata.HashFile as computeDevBuildContentHash, and the vectors prove that the two agree.
func TestSourcedManifestHashesAndSourceIndependence(test *testing.T) {
	vectors := []struct {
		size int
		hash int64
	}{
		{0, 3244421341483603138}, {1, -2399747073602280719}, {3, -737883702129266468}, {240, 2788469911834355041},
		{241, -4155630063455057979}, {262143, 9078738661776034622}, {262144, -1692254647099917537},
		{262145, -2541306581069977202}, {524288, 3157545227256347297}, {524301, 8144707773225287728},
	}
	launch := testManifest("launch")
	for _, vector := range vectors {
		data := make([]byte, vector.size)
		for index := range data {
			data[index] = byte(index*31 + 7)
		}
		content := filepath.Join(test.TempDir(), "content.jar")
		if err := os.WriteFile(content, data, 0o644); err != nil {
			test.Fatal(err)
		}
		if hash, err := filemetadata.HashFile(content); err != nil || hash != vector.hash {
			test.Fatalf("hash for %d bytes = %d, error = %v", vector.size, hash, err)
		}
		sourced := readGoldenManifest(test, `{"kind": "files", "platformPrefix": "idea", "os": "linux", "arch": "x64",
			"additionalModules": [], "mainClass": null, "coreClassPath": [],
			"entries": [{"relativePath": "lib/content.jar", "type": "component-file", "hash": `+formatInt(vector.hash)+`, "source": "inputs/content.jar"}]}`)
		if sourced.Version != 9 || sourced.Entries[0].Executable {
			test.Fatalf("sourced manifest = %+v", sourced)
		}
		tree := *sourced
		tree.Entries = []componentEntry{{RelativePath: "lib/content.jar", Type: componentFileEntryType, Hash: pointer(vector.hash)}}
		relocated := *sourced
		relocated.Entries = []componentEntry{sourced.Entries[0]}
		relocated.Entries[0].Source = pointer("other/content.jar")
		expected := mustFingerprint(test, launch, &tree)
		if actual := mustFingerprint(test, launch, sourced); actual != expected {
			test.Fatalf("a sourced manifest fingerprints as %s, a tree manifest as %s", actual, expected)
		}
		if actual := mustFingerprint(test, launch, &relocated); actual != expected {
			test.Fatalf("the source changed the fingerprint: %s", actual)
		}
	}
}

func TestFingerprintCoversPackagedBytesAndTheExecutableBit(test *testing.T) {
	base := testManifest("plugins_air")
	base.Entries = []componentEntry{{RelativePath: "plugins/air/lib/air.jar", Type: componentFileEntryType, Hash: pointer(int64(1))}}
	changedBytes := *base
	changedBytes.Entries = []componentEntry{{RelativePath: "plugins/air/lib/air.jar", Type: componentFileEntryType, Hash: pointer(int64(2))}}
	executable := *base
	executable.Entries = []componentEntry{{RelativePath: "plugins/air/lib/air.jar", Type: componentFileEntryType, Hash: pointer(int64(1)), Executable: true}}
	fingerprint := mustFingerprint(test, base)
	for _, changed := range []*componentManifest{&changedBytes, &executable} {
		if mustFingerprint(test, changed) == fingerprint {
			test.Fatalf("the fingerprint ignores %+v", changed.Entries)
		}
	}
}

func TestComponentFingerprintCoversGeneratedLaunchAndClasspathData(test *testing.T) {
	base := testManifest("platform_core")
	base.CoreClassPath = []string{"lib/platform.jar"}
	changedCoreClasspath := *base
	changedCoreClasspath.CoreClassPath = []string{"lib/renamed-platform.jar"}
	changedMainClass := *base
	changedMainClass.MainClass = pointer("com.intellij.idea.OtherMain")
	pluginClasspath := filepath.Join(test.TempDir(), "plugin-classpath.txt")
	writeTestBytes(test, pluginClasspath, []byte{1, 2, 3})
	fingerprint := func(manifest *componentManifest) string {
		result, err := computeIdeFingerprintFromComponents([]*componentManifest{manifest}, pluginClasspath, nil)
		if err != nil {
			test.Fatal(err)
		}
		return result
	}
	expected := fingerprint(base)
	if fingerprint(&changedCoreClasspath) == expected || fingerprint(&changedMainClass) == expected {
		test.Fatal("the fingerprint ignores the core classpath or the main class")
	}
	writeTestBytes(test, pluginClasspath, []byte{1, 2, 4})
	if fingerprint(base) == expected {
		test.Fatal("the fingerprint ignores the plugin classpath")
	}
}

func TestFingerprintOfExactModes(test *testing.T) {
	file := componentEntry{RelativePath: "plugins/demo/bin/tool", Type: componentFileEntryType, Hash: pointer(int64(1)), Executable: true,
		Mode: pointer(int64(0o750))}
	withMode := func(mode *int64) *componentManifest {
		manifest := testManifest("plugin")
		entry := file
		entry.Mode = mode
		manifest.Entries = []componentEntry{entry}
		return manifest
	}
	exact := mustFingerprint(test, withMode(pointer(int64(0o750))))
	conventional := mustFingerprint(test, withMode(pointer(int64(0o755))))
	if exact == conventional {
		test.Fatal("the fingerprint ignores an exact mode")
	}
	if implicit := mustFingerprint(test, withMode(nil)); implicit != conventional {
		test.Fatalf("a conventional mode changed the fingerprint: %s and %s", implicit, conventional)
	}
}

func TestFingerprintRequiresAMainClass(test *testing.T) {
	jars := testManifest("platform_jars")
	jars.MainClass = nil
	if _, err := computeIdeFingerprintFromComponents([]*componentManifest{jars}, "", nil); err == nil {
		test.Fatal("accepted components without a main class")
	}
	if _, err := computeIdeFingerprintFromComponents(nil, "", nil); err == nil {
		test.Fatal("accepted no components")
	}
}

func mustFingerprint(test *testing.T, components ...*componentManifest) string {
	test.Helper()
	result, err := computeIdeFingerprintFromComponents(components, "", nil)
	if err != nil {
		test.Fatal(err)
	}
	return result
}
