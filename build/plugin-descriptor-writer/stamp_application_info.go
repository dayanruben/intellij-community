// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package main

import (
	"fmt"
	"os"
	"strings"

	"jetbrains.com/plugin-descriptor-writer/internal/descriptorxml"
)

// stampApplicationInfoRequest contains the declared inputs of the stamped application info of a product. The
// application-info module ships it as `idea/<prefix>ApplicationInfo.xml`.
type stampApplicationInfoRequest struct {
	output      string
	source      string
	buildNumber string
	productCode string
	// replacements are `ProductProperties.appInfoXmlReplacements`, in their order.
	replacements []replacement
	overrides    applicationInfoOverrides
}

func runStampApplicationInfo(lines []string) int {
	parsed, err := parseStampApplicationInfoRequest(lines)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: %v\n", err)
		return 2
	}
	content, err := stampApplicationInfo(parsed)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: could not stamp the application info (%s): %v\n", parsed.source, err)
		return 1
	}
	if err := writeOutput(parsed.output, content); err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: %v\n", err)
		return 1
	}
	return 0
}

// stampApplicationInfo is `computeAppInfoXml` (`ApplicationInfoPropertiesImpl.kt`) for a dev distribution.
//
// The markers are replaced in the text. The text keeps its comments and its layout, unless an override applies. Then
// `applyApplicationInfoOverrides` loads the text and writes it again. `ApplicationInfoOverrides` of a JetBrains Client
// product is not ported, because `--application-info` produces the application info of a frontend.
func stampApplicationInfo(parsed stampApplicationInfoRequest) (string, error) {
	buildNumber, err := readBuildNumber(parsed.buildNumber, "product")
	if err != nil {
		return "", err
	}
	source, err := os.ReadFile(parsed.source)
	if err != nil {
		return "", err
	}
	text := replaceMarkers(string(source), applicationInfoReplacements(parsed.replacements, parsed.productCode, buildNumber))

	overrides := parsed.overrides
	stampsVersion := overrides.eap != "" || overrides.versionSuffix != ""
	stampsBranch := overrides.stampsBranchName(buildNumber)
	if !stampsVersion && !stampsBranch {
		return text, nil
	}
	root, err := descriptorxml.Read(text)
	if err != nil {
		return "", err
	}
	if stampsVersion {
		version, found := singleChild(root, "version", applicationInfoNamespace)
		if !found {
			return "", fmt.Errorf("the application info has no unique version element")
		}
		overrides.applyVersion(version)
	}
	if stampsBranch {
		// The build element may also have no namespace, for `<component>` without one.
		build, found := singleChild(root, "build", applicationInfoNamespace)
		if !found {
			build, found = singleChild(root, "build", "")
		}
		if !found {
			return "", fmt.Errorf("the application info has no unique build element")
		}
		build.SetAttribute("branchName", overrides.branchName)
	}
	return descriptorxml.Write(root), nil
}

func parseStampApplicationInfoRequest(lines []string) (stampApplicationInfoRequest, error) {
	var parsed stampApplicationInfoRequest
	if err := requireMode(lines, stampApplicationInfoMode); err != nil {
		return parsed, err
	}
	stated := map[string]bool{}
	for _, line := range lines {
		if line == "" || line == stampApplicationInfoMode {
			continue
		}
		option, value, _ := strings.Cut(line, "=")
		handled, err := parsed.overrides.parseOption(option, value)
		if err != nil {
			return parsed, err
		}
		if handled {
			continue
		}
		switch option {
		case "--out":
			parsed.output = value
		case "--source":
			parsed.source = value
		case "--build-number":
			parsed.buildNumber = value
		case "--product-code":
			parsed.productCode = value
		case "--replacement":
			key, text, found := strings.Cut(value, "=")
			if !found || key == "" {
				return parsed, fmt.Errorf("a replacement is '<key>=<value>', and '%s' is not", value)
			}
			if stated[key] {
				return parsed, fmt.Errorf("the replacement '%s' is stated more than once", key)
			}
			stated[key] = true
			parsed.replacements = append(parsed.replacements, replacement{key: key, value: text})
		default:
			return parsed, fmt.Errorf("unknown application info stamp option '%s'", option)
		}
	}
	return parsed, requireOptions([]struct{ option, value string }{
		{"--out", parsed.output},
		{"--source", parsed.source},
		{"--build-number", parsed.buildNumber},
		{"--product-code", parsed.productCode},
	})
}
