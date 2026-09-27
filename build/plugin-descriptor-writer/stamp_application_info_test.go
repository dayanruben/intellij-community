// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package main

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

const stampApplicationInfoTestData = "testdata/stamp_application_info"

// The `idea-community` and `pycharm-core` expected files are the `idea/<prefix>ApplicationInfo.xml` entries that the
// Kotlin `platform_lib` fragments of `Idea` and `PyCharmCore` packed. The sources are copies of the module sources, and
// `build.txt` is a copy of `community/build.txt`, without a final newline.
//
// The `markers` source is synthetic. It has the markers of the language server sources, which are not in this
// repository, and the replacements that `LanguageServerProperties` states for `KotlinServer`. The expected text is the
// plain replacement of the markers.
func TestStampApplicationInfoMatchesKotlin(t *testing.T) {
	for _, tt := range []struct {
		name    string
		source  string
		options []string
		want    string
	}{
		// The markers are replaced in the text, so the comment after the build element stays.
		{"idea community", "idea-community", []string{"--product-code=IC"}, "idea-community"},
		// The copyright comment before the root element stays too.
		{"pycharm core", "pycharm-core", []string{"--product-code=PC"}, "pycharm-core"},
		// A dev distribution stamps no build date, so `majorReleaseDate` keeps its marker. The product replacements
		// come before the base replacements.
		{"product replacements", "markers", []string{
			"--product-code=ILS",
			"--replacement=BUNDLE_NAME=kotlin-server",
			"--replacement=BUNDLE_EDITION=ILSKS",
			`--replacement=BUNDLE_EAP= eap="true"`,
			"--replacement=RELEASE_DATE=",
		}, "markers"},
		{"empty overrides", "idea-community", []string{"--product-code=IC", "--eap-override=", "--version-suffix-override="}, "idea-community"},
	} {
		for _, flagfile := range []bool{false, true} {
			name := tt.name + "/direct"
			if flagfile {
				name = tt.name + "/flagfile"
			}
			t.Run(name, func(t *testing.T) {
				dir := t.TempDir()
				output := filepath.Join(dir, "out", "ApplicationInfo.xml")
				arguments := append([]string{
					"--stamp-application-info", "--out=" + output,
					"--source=" + filepath.Join(stampApplicationInfoTestData, tt.source+".xml"),
					"--build-number=" + filepath.Join(stampApplicationInfoTestData, "build.txt"),
				}, tt.options...)
				if flagfile {
					path := requestFile(t, dir, arguments...)
					write(t, path, strings.ReplaceAll(read(t, path), "\n", "\r\n")+"\r\n")
					arguments = []string{"--flagfile=" + path}
				}
				if code := run(arguments); code != 0 {
					t.Fatalf("exit %d", code)
				}
				if got, expected := read(t, output), read(t, filepath.Join(stampApplicationInfoTestData, tt.want+".expected.xml")); got != expected {
					t.Errorf("got:\n%s\nwant:\n%s", got, expected)
				}
			})
		}
	}
}

// Without the nightly flag, a build number with two dots stamps no branch name. The text path then keeps the comment.
func TestStampApplicationInfoStampsNoBranchOutsideNightly(t *testing.T) {
	dir := t.TempDir()
	build := filepath.Join(dir, "build.txt")
	output := filepath.Join(dir, "ApplicationInfo.xml")
	write(t, build, "263.1234.5")
	code := run([]string{
		"--stamp-application-info", "--out=" + output,
		"--source=" + filepath.Join(stampApplicationInfoTestData, "idea-community.xml"),
		"--build-number=" + build, "--product-code=IC", "--branch-name=feature",
	})
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	want := strings.ReplaceAll(read(t, filepath.Join(stampApplicationInfoTestData, "idea-community.expected.xml")), "263.SNAPSHOT", "263.1234.5")
	if got := read(t, output); got != want {
		t.Errorf("got:\n%s\nwant:\n%s", got, want)
	}
}

// An override makes `applyApplicationInfoOverrides` load the stamped text and write it again. A dev distribution states
// no override, so no Kotlin output of this path exists. The expected text is the `internal/descriptorxml` round trip,
// which drops the comments and the blank lines.
func TestStampApplicationInfoOverridesRewriteTheText(t *testing.T) {
	for _, tt := range []struct {
		name    string
		options []string
		version string
		build   string
	}{
		{"EAP", []string{"--eap-override=false"},
			`<version major="2026" minor="3" eap="false" />`,
			`<build number="IC-263.SNAPSHOT" date="__BUILD_DATE__" />`},
		{"suffix", []string{"--version-suffix-override=Preview & test"},
			`<version major="2026" minor="3" suffix="Preview &amp; test" />`,
			`<build number="IC-263.SNAPSHOT" date="__BUILD_DATE__" />`},
		{"nightly branch", []string{"--nightly", "--branch-name=feature & test"},
			`<version major="2026" minor="3" eap="true" />`,
			`<build number="IC-263.SNAPSHOT" date="__BUILD_DATE__" branchName="feature &amp; test" />`},
	} {
		t.Run(tt.name, func(t *testing.T) {
			dir := t.TempDir()
			output := filepath.Join(dir, "ApplicationInfo.xml")
			arguments := append([]string{
				"--stamp-application-info", "--out=" + output,
				"--source=" + filepath.Join(stampApplicationInfoTestData, "idea-community.xml"),
				"--build-number=" + filepath.Join(stampApplicationInfoTestData, "build.txt"),
				"--product-code=IC",
			}, tt.options...)
			if code := run(arguments); code != 0 {
				t.Fatalf("exit %d", code)
			}
			want := `<component xmlns="http://jetbrains.org/intellij/schema/application-info">
  ` + tt.version + `
  <company name="JetBrains s.r.o." url="https://www.jetbrains.com" />
  ` + tt.build + `
  <logo url="/idea_community_logo.png" />
  <icon svg="/idea-ce.svg" svg-small="/idea-ce_16.svg" />
  <icon-eap svg="/idea-ce-eap.svg" svg-small="/idea-ce-eap_16.svg" />
  <names product="IDEA" fullname="IntelliJ IDEA" script="idea" motto="The Leading IDE for Professional Development in Java and Kotlin" />
  <essential-plugin>com.intellij.idea.customization</essential-plugin>
  <essential-plugin>com.intellij.java</essential-plugin>
  <essential-plugin>com.intellij.java.ide</essential-plugin>
  <essential-plugin>com.intellij.modules.json</essential-plugin>
</component>`
			if got := read(t, output); got != want {
				t.Errorf("got:\n%s\nwant:\n%s", got, want)
			}
		})
	}
}

func TestStampApplicationInfoFindsABuildElementWithoutNamespace(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "ApplicationInfo.xml")
	output := filepath.Join(dir, "out.xml")
	write(t, source, `<component><build number="XX-__BUILD__"/></component>`)
	code := run([]string{
		"--stamp-application-info", "--out=" + output, "--source=" + source,
		"--build-number=" + filepath.Join(stampApplicationInfoTestData, "build.txt"),
		"--product-code=XX", "--nightly", "--branch-name=feature",
	})
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	if got, want := read(t, output), `<component>
  <build number="XX-263.SNAPSHOT" branchName="feature" />
</component>`; got != want {
		t.Errorf("got:\n%s\nwant:\n%s", got, want)
	}
}

// Kotlin's `Map.plus` keeps the position of a product key that the base map also states, and takes the base value.
func TestApplicationInfoReplacementsFollowMapPlus(t *testing.T) {
	got := applicationInfoReplacements([]replacement{{"NAME", "n"}, {"BUILD", "product"}, {"EAP", "e"}}, "XX", "263.1")
	want := []replacement{
		{"NAME", "n"}, {"BUILD", "263.1"}, {"EAP", "e"}, {"BUILD_NUMBER", "XX-263.1"}, {"BUILTIN_PLUGINS_URL", ""},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestStampApplicationInfoRequest(t *testing.T) {
	parsed, err := parseStampApplicationInfoRequest([]string{
		"--out=out.xml", "--stamp-application-info", "", "--source=a file=1.xml", "--build-number=build.txt",
		"--product-code=IU", "--replacement=B=x = y", "--replacement=A=", "--eap-override=true", "--nightly",
		"--branch-name=feature",
	})
	if err != nil {
		t.Fatal(err)
	}
	want := stampApplicationInfoRequest{
		output:       "out.xml",
		source:       "a file=1.xml",
		buildNumber:  "build.txt",
		productCode:  "IU",
		replacements: []replacement{{"B", "x = y"}, {"A", ""}},
		overrides:    applicationInfoOverrides{eap: "true", nightly: true, branchName: "feature"},
	}
	if !reflect.DeepEqual(parsed, want) {
		t.Errorf("got %#v, want %#v", parsed, want)
	}
}

func TestStampApplicationInfoRejectsInvalidRequests(t *testing.T) {
	valid := []string{"--stamp-application-info", "--out=o", "--source=s", "--build-number=b", "--product-code=IU"}
	for _, tt := range []struct {
		name      string
		arguments []string
		want      string
	}{
		{"no mode", valid[1:], "--stamp-application-info is required"},
		{"no output", withoutOption(valid, "--out"), "--out is required"},
		{"no source", withoutOption(valid, "--source"), "--source is required"},
		{"no build number", withoutOption(valid, "--build-number"), "--build-number is required"},
		{"no product code", withoutOption(valid, "--product-code"), "--product-code is required"},
		{"unknown option", append(valid, "--unknown=1"), "unknown application info stamp option"},
		{"frontend option", append(valid, "--client-application-info=c.xml"), "unknown application info stamp option"},
		{"replacement pair", append(valid, "--replacement=missing-separator"), "a replacement is"},
		{"replacement key", append(valid, "--replacement==value"), "a replacement is"},
		{"duplicate replacement", append(valid, "--replacement=A=1", "--replacement=A=2"), "stated more than once"},
		{"nightly value", append(valid, "--nightly=true"), "--nightly is a flag"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			if _, err := parseStampApplicationInfoRequest(tt.arguments); err == nil || !strings.Contains(err.Error(), tt.want) {
				t.Fatalf("got %v, want %q", err, tt.want)
			}
			if code := run(tt.arguments); code != 2 {
				t.Errorf("exit %d, want 2", code)
			}
		})
	}
}

func withoutOption(arguments []string, option string) []string {
	var result []string
	for _, argument := range arguments {
		if !strings.HasPrefix(argument, option+"=") {
			result = append(result, argument)
		}
	}
	return result
}

func TestStampApplicationInfoFailuresWriteNoOutput(t *testing.T) {
	for _, tt := range []struct {
		name    string
		source  string
		build   string
		options []string
		want    string
	}{
		{"missing source", "", "263.1", nil, "ApplicationInfo.xml"},
		{"missing build number", "<component/>", "", nil, "build.txt"},
		{"blank build number", "<component/>", " \n", nil, "the product build number is empty"},
		{"malformed text with an override", "<component>", "263.1", []string{"--eap-override=true"}, `the element "component" does not end`},
		{"no version element", "<component/>", "263.1", []string{"--eap-override=true"}, "no unique version element"},
		{"two build elements", "<component><build/><build/></component>", "263.1",
			[]string{"--nightly", "--branch-name=b"}, "no unique build element"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			dir := t.TempDir()
			source := filepath.Join(dir, "ApplicationInfo.xml")
			build := filepath.Join(dir, "build.txt")
			output := filepath.Join(dir, "out", "ApplicationInfo.xml")
			if tt.source != "" {
				write(t, source, tt.source)
			}
			if tt.build != "" {
				write(t, build, tt.build)
			}
			arguments := append([]string{
				"--stamp-application-info", "--out=" + output, "--source=" + source, "--build-number=" + build, "--product-code=XX",
			}, tt.options...)
			parsed, err := parseStampApplicationInfoRequest(arguments)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := stampApplicationInfo(parsed); err == nil || !strings.Contains(err.Error(), tt.want) {
				t.Errorf("got %v, want %q", err, tt.want)
			}
			if code := run(arguments); code != 1 {
				t.Errorf("exit %d, want 1", code)
			}
			if _, err := os.Stat(output); !os.IsNotExist(err) {
				t.Errorf("the failure wrote %s", output)
			}
		})
	}
}
