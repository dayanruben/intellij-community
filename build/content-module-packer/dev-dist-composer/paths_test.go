package main

import (
	"slices"
	"testing"
)

// The expected identities come from Java: `Normalizer.normalize(value, NFC)`, then `lowercase(Locale.ROOT)`,
// `uppercase(Locale.ROOT)`, `lowercase(Locale.ROOT)`, and NFC again.
func TestDevBuildPathIdentityMatchesJava(test *testing.T) {
	for _, sample := range []struct{ value, identity string }{
		{"ı", "i"}, {"i", "i"}, {"ς", "σ"}, {"σ", "σ"}, {"a.Σ", "a.ς"}, {"ß", "ss"},
		{"İ", "i̇"}, {"ﬀ", "ff"}, {"Ꭰ", "ꭰ"}, {"ꭰ", "ꭰ"}, {"ǅ", "ǆ"},
		{"straẞe", "strasse"}, {"STRASSE", "strasse"}, {"café", "café"}, {"Plugins/Lib", "plugins/lib"},
	} {
		if actual := devBuildPathIdentity(sample.value); actual != sample.identity {
			test.Errorf("identity of %q = %q, expected %q", sample.value, actual, sample.identity)
		}
	}
}

func TestJavaPath(test *testing.T) {
	if windows {
		test.Skip("Windows paths use backslashes")
	}
	for _, sample := range []struct{ value, path string }{
		{"lib/a//b.jar", "lib/a/b.jar"}, {"lib/", "lib"}, {"/", "/"}, {"//a//", "/a"}, {"", ""}, {"./a/../b", "./a/../b"},
	} {
		if actual, err := javaPath(sample.value); err != nil || actual != sample.path {
			test.Errorf("Path.of(%q) = %q, error = %v", sample.value, actual, err)
		}
	}
	if _, err := javaPath("a\x00b"); err == nil {
		test.Error("accepted a NUL character")
	}
	for _, value := range []string{"a/b", "./a/../b", ""} {
		if spelling, _ := hasJavaPathSpelling(value); !spelling {
			test.Errorf("%q changes its spelling", value)
		}
	}
	for _, value := range []string{"a//b", "a/"} {
		if spelling, _ := hasJavaPathSpelling(value); spelling {
			test.Errorf("%q keeps its spelling", value)
		}
	}
}

func TestCompareUTF16(test *testing.T) {
	values := []string{"Ａ", "\U0001F600", "b", "a", "ab", "", "é"}
	slices.SortFunc(values, compareUTF16)
	if !slices.Equal(values, []string{"", "a", "ab", "b", "é", "\U0001F600", "Ａ"}) {
		test.Fatalf("sorted = %q", values)
	}
}

func TestPathStartsWith(test *testing.T) {
	if windows {
		test.Skip("Windows paths use backslashes")
	}
	for _, sample := range []struct {
		value, prefix string
		expected      bool
	}{
		{"/a/b", "/a", true}, {"/a", "/a", true}, {"/ab", "/a", false}, {"/a/b", "/", true}, {"a/b", "a", true}, {"a", "a/b", false},
	} {
		if actual := pathStartsWith(sample.value, sample.prefix); actual != sample.expected {
			test.Errorf("%q starts with %q = %v", sample.value, sample.prefix, actual)
		}
	}
}

func TestCheckDevBuildDistributionLink(test *testing.T) {
	for _, valid := range [][2]string{{"a/b/link", "../c"}, {"link", "."}, {"a/link", ".."}, {"a/link", "./x/../y"}} {
		if err := checkDevBuildDistributionLink(valid[0], valid[1]); err != nil {
			test.Errorf("rejected %q: %v", valid, err)
		}
	}
	for _, invalid := range [][2]string{
		{"link", ".."}, {"a/link", "../.."}, {"link", ""}, {"link", "/abs"}, {"link", `a\b`}, {"link", "c:d"}, {"a//link", "b"}, {"../link", "b"},
	} {
		if err := checkDevBuildDistributionLink(invalid[0], invalid[1]); err == nil {
			test.Errorf("accepted %q", invalid)
		}
	}
}

func TestValidateDevBuildDirectorySpellings(test *testing.T) {
	if err := validateDevBuildDirectorySpellings([]string{"lib/a.jar", "lib/b.jar", "plugins/x/lib/c.jar"}); err != nil {
		test.Fatal(err)
	}
	err := validateDevBuildDirectorySpellings([]string{"lib/a.jar", "Lib/b.jar"})
	requireError(test, err, "Conflicting destination spellings 'lib' and 'Lib'")
}

func TestValidateDevBuildLinks(test *testing.T) {
	err := validateDevBuildLinks([]distributionLink{{"a", "b"}, {"A", "c"}})
	requireError(test, err, "Duplicate distribution link 'A'")
	err = validateDevBuildLinks([]distributionLink{{"dir/a", "b"}, {"dir/b", "a"}})
	requireError(test, err, "Distribution link cycle at 'dir/a'")
	if err := validateDevBuildLinks([]distributionLink{{"lib/current", "versions/A"}, {"lib/latest", "current"}}); err != nil {
		test.Fatal(err)
	}
}
