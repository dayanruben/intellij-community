package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestQuoteEscapesLikeKotlinx(t *testing.T) {
	cases := map[string]string{
		`plain $APP_PACKAGE/x`: `"plain $APP_PACKAGE/x"`,
		`a"b`:                  `"a\"b"`,
		`%IDE_HOME%\lib`:       `"%IDE_HOME%\\lib"`,
		"tab\tnew\nline":       `"tab\tnew\nline"`,
		"\x01":                 `"\u0001"`,
		"ünïcode":              `"ünïcode"`,
	}
	for input, expected := range cases {
		if actual := quote(input); actual != expected {
			t.Errorf("quote(%q) = %s, want %s", input, actual, expected)
		}
	}
}

func TestJavaLinesSplitsLikeFilesLines(t *testing.T) {
	cases := map[string][]string{
		"a\nb\n":   {"a", "b"},
		"a\r\nb":   {"a", "b"},
		"a\rb\r\n": {"a", "b"},
		"a\n\nb":   {"a", "", "b"},
		"":         nil,
	}
	for input, expected := range cases {
		if actual := javaLines(input); !reflect.DeepEqual(actual, expected) {
			t.Errorf("javaLines(%q) = %q, want %q", input, actual, expected)
		}
	}
}

func TestOpenedPackagesDropsThePackagesOfOtherSystems(t *testing.T) {
	text := strings.Join([]string{
		"--add-opens=java.base/java.lang=ALL-UNNAMED",
		"--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
		"--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
		"--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED",
		"--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
		"--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED",
	}, "\n") + "\n"
	expected := map[string][]string{
		osMac:     {"--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED", "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED"},
		osLinux:   {"--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED", "--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED"},
		osWindows: {"--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED"},
	}
	for os, lines := range expected {
		if actual := openedPackages(text, os); !reflect.DeepEqual(actual, lines) {
			t.Errorf("openedPackages(%s) = %q, want %q", os, actual, lines)
		}
	}
}

func TestJvmArgumentsFollowTheSystemConventions(t *testing.T) {
	loader := "com.intellij.util.lang.PathClassLoader"
	jvm := jvmArguments{
		MultiRoutingFileSystem: true,
		ClassLoader:            &loader,
		VendorName:             "JetBrains",
		PathsSelector:          "IntelliJIdea2026.3",
		Jna:                    true,
		NativeAccess:           true,
	}
	windows := additionalJvmArguments(jvm, platform{os: osWindows, arch: "amd64"}, []string{"--add-opens=x"}, false)
	expected := []string{
		`-Xbootclasspath/a:%IDE_HOME%\lib\nio-fs.jar`,
		"-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader",
		"-Didea.vendor.name=JetBrains",
		"-Didea.paths.selector=IntelliJIdea2026.3",
		"-Djna.boot.library.path=%IDE_HOME%/lib/jna/amd64",
		"-Djna.nosys=true",
		"-Djna.noclasspath=true",
		"-Dio.netty.allocator.type=pooled",
		"-Daether.connector.resumeDownloads=false",
		"-Dcompose.swing.render.on.graphics=true",
		"--enable-native-access=ALL-UNNAMED",
		"--add-opens=x",
	}
	if !reflect.DeepEqual(windows, expected) {
		t.Errorf("Windows arguments = %q, want %q", windows, expected)
	}
	qodana := additionalJvmArguments(jvm, platform{os: osMac, arch: "aarch64"}, nil, true)
	if strings.HasPrefix(qodana[0], "-Xbootclasspath") {
		t.Errorf("a Qodana launch must not load the multi-routing file system, got %q", qodana[0])
	}
}

func TestRenderedFilesOfOneModel(t *testing.T) {
	model := testModel(t)
	files, err := renderLaunchFiles(model, platform{os: osLinux, arch: "aarch64"}, "--add-opens=java.base/java.lang=ALL-UNNAMED\n", "a=@@settings_dir@@\n")
	if err != nil {
		t.Fatal(err)
	}
	if files.buildTxt != "IU-263.SNAPSHOT" {
		t.Errorf("build.txt = %q", files.buildTxt)
	}
	if files.ideaProperties != "a=IntelliJIdea\n\nb=1#end\n" {
		t.Errorf("idea.properties = %q", files.ideaProperties)
	}
	if files.vmOptions != "-Xmx2048m\n-Dx=linux\n" {
		t.Errorf("vmoptions = %q", files.vmOptions)
	}
	if strings.HasSuffix(files.productInfo, "\n") {
		t.Error("product-info.json must not end with a newline, as kotlinx writes it")
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(files.productInfo), &parsed); err != nil {
		t.Fatalf("product-info.json is not JSON: %v\n%s", err, files.productInfo)
	}
	if !strings.HasPrefix(files.productInfo, "{\n  \"name\": \"IntelliJ IDEA\",\n  \"version\": \"2026.3\",\n  \"versionSuffix\": \"EAP\",\n") {
		t.Errorf("product-info.json does not start with the fields in declaration order:\n%s", files.productInfo)
	}
	for _, fragment := range []string{
		`"startupWmClass": "jetbrains-idea"`,
		`"launcherPath": "bin/idea"`,
		`"vmOptionsFilePath": "bin/idea64.vmoptions"`,
		`"commands": [` + "\n" + `            "stdioMcpServer"`,
	} {
		if !strings.Contains(files.productInfo, fragment) {
			t.Errorf("product-info.json lacks %s:\n%s", fragment, files.productInfo)
		}
	}
	if strings.Contains(files.productInfo, "customProperties") || strings.Contains(files.productInfo, "flavors") {
		t.Errorf("product-info.json states an empty default list:\n%s", files.productInfo)
	}
}

func TestTheToolWritesTheFourFiles(t *testing.T) {
	dir := t.TempDir()
	write := func(name, content string) string {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
		return path
	}
	modelText, err := os.ReadFile(filepath.Join("testdata", "model.json"))
	if err != nil {
		t.Fatal(err)
	}
	args := []string{
		"--model=" + write("model.json", string(modelText)),
		"--platform=darwin_aarch64",
		"--opened-packages=" + write("opened.txt", "--add-opens=java.base/java.lang=ALL-UNNAMED\n"),
		"--idea-properties=" + write("idea.properties", "a=@@settings_dir@@\n"),
		"--build-txt-out=" + filepath.Join(dir, "build.txt"),
		"--idea-properties-out=" + filepath.Join(dir, "out.properties"),
		"--vmoptions-out=" + filepath.Join(dir, "out.vmoptions"),
		"--product-info-out=" + filepath.Join(dir, "product-info.json"),
	}
	var output, errors strings.Builder
	if code := run(args, &output, &errors); code != 0 {
		t.Fatalf("exit code %d: %s", code, errors.String())
	}
	vmOptions, err := os.ReadFile(filepath.Join(dir, "out.vmoptions"))
	if err != nil {
		t.Fatal(err)
	}
	if string(vmOptions) != "-Xmx2048m\n-Dx=mac\n" {
		t.Errorf("vmoptions = %q", vmOptions)
	}
	if code := run(args[:1], &output, &errors); code != 2 {
		t.Errorf("a missing option must fail with exit code 2, got %d", code)
	}
}

func testModel(t *testing.T) launchModel {
	t.Helper()
	text, err := os.ReadFile(filepath.Join("testdata", "model.json"))
	if err != nil {
		t.Fatal(err)
	}
	model, err := parseLaunchModel(text)
	if err != nil {
		t.Fatal(err)
	}
	return model
}
