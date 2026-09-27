// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package main

import (
	"fmt"
	"os"
	"strings"

	"jetbrains.com/plugin-descriptor-writer/internal/descriptorxml"
)

const applicationInfoNamespace = "http://jetbrains.org/intellij/schema/application-info"

// applicationInfoRequest contains the declared inputs of dev_dist_frontend_application_info.
type applicationInfoRequest struct {
	output                 string
	clientApplicationInfo  string
	productApplicationInfo string
	buildNumber            string
	overrides              applicationInfoOverrides
}

// applicationInfoOverrides are the values that `computeAppInfoXml` in ApplicationInfoPropertiesImpl.kt reads from
// system properties and from the build. An empty string is an absent value.
type applicationInfoOverrides struct {
	eap           string
	versionSuffix string
	nightly       bool
	branchName    string
}

// replacement is one marker of the application info. The text holds it as `__<key>__`.
type replacement struct {
	key   string
	value string
}

func runApplicationInfo(lines []string) int {
	parsed, err := parseApplicationInfoRequest(lines)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: %v\n", err)
		return 2
	}
	content, err := resolveApplicationInfo(parsed)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: could not produce the frontend application info: %v\n", err)
		return 1
	}
	if err := writeOutput(parsed.output, content); err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: %v\n", err)
		return 1
	}
	return 0
}

// resolveApplicationInfo applies the client build markers, then follows applyApplicationInfoOverrides in ApplicationInfoPropertiesImpl.kt.
func resolveApplicationInfo(parsed applicationInfoRequest) (string, error) {
	buildNumber, err := readBuildNumber(parsed.buildNumber, "frontend")
	if err != nil {
		return "", err
	}
	clientContent, err := os.ReadFile(parsed.clientApplicationInfo)
	if err != nil {
		return "", err
	}
	replaced := replaceMarkers(string(clientContent), applicationInfoReplacements(nil, "JBC", buildNumber))
	productContent, err := os.ReadFile(parsed.productApplicationInfo)
	if err != nil {
		return "", err
	}
	product, err := parseApplicationInfo(string(productContent), parsed.productApplicationInfo)
	if err != nil {
		return "", err
	}
	productName, present := product.names.Attribute("fullname")
	if !present {
		productName, present = product.names.Attribute("product")
	}
	if !present {
		return "", fmt.Errorf("the product application info has no product name: %s", parsed.productApplicationInfo)
	}
	client, err := parseApplicationInfo(replaced, parsed.clientApplicationInfo)
	if err != nil {
		return "", err
	}
	client.names.SetAttribute("fullname", productName)
	client.names.RemoveAttribute("edition")
	copyApplicationInfoAttribute(client.names, product.names, "motto")
	for _, name := range []string{"eap", "major", "minor", "micro", "patch", "full", "suffix"} {
		copyApplicationInfoAttribute(client.version, product.version, name)
	}
	copyApplicationInfoAttribute(client.build, product.build, "majorReleaseDate")
	parsed.overrides.applyVersion(client.version)
	if parsed.overrides.stampsBranchName(buildNumber) {
		client.build.SetAttribute("branchName", parsed.overrides.branchName)
	}
	return descriptorxml.Write(client.root), nil
}

func readBuildNumber(file string, owner string) (string, error) {
	content, err := os.ReadFile(file)
	if err != nil {
		return "", err
	}
	buildNumber := strings.TrimSpace(string(content))
	if buildNumber == "" {
		return "", fmt.Errorf("the %s build number is empty: %s", owner, file)
	}
	return buildNumber, nil
}

// applicationInfoReplacements is the replacement map of `computeAppInfoXml` for a dev distribution.
//
// The product replacements come first. A base key that a product replacement also states keeps that position and takes
// the base value, as Kotlin's `Map.plus` does. A dev distribution stamps no `BUILD_DATE`, and it has no artifact server,
// so `BUILTIN_PLUGINS_URL` is empty.
func applicationInfoReplacements(product []replacement, productCode string, buildNumber string) []replacement {
	result := append([]replacement(nil), product...)
	for _, base := range []replacement{
		{"BUILD_NUMBER", productCode + "-" + buildNumber},
		{"BUILD", buildNumber},
		{"BUILTIN_PLUGINS_URL", ""},
	} {
		stated := false
		for i := range result {
			if result[i].key == base.key {
				result[i].value = base.value
				stated = true
			}
		}
		if !stated {
			result = append(result, base)
		}
	}
	return result
}

// replaceMarkers is `BuildUtils.replaceAll` with the marker `__`. It replaces the markers one after another, in order.
func replaceMarkers(text string, replacements []replacement) string {
	for _, r := range replacements {
		text = strings.ReplaceAll(text, "__"+r.key+"__", r.value)
	}
	return text
}

// applyVersion replaces the `eap` and `suffix` attributes when one of the two overrides is set.
func (o applicationInfoOverrides) applyVersion(version *descriptorxml.Element) {
	if o.eap == "" && o.versionSuffix == "" {
		return
	}
	replaceApplicationInfoAttribute(version, "eap", o.eap, o.eap != "")
	replaceApplicationInfoAttribute(version, "suffix", o.versionSuffix, o.versionSuffix != "")
}

// stampsBranchName follows `isNightlyBuild` of BuildContextImpl.kt: the stated flag, or a build number with at most one
// dot. The rule states the branch name only for a build outside the default branch.
func (o applicationInfoOverrides) stampsBranchName(buildNumber string) bool {
	return o.branchName != "" && (o.nightly || strings.Count(buildNumber, ".") <= 1)
}

type applicationInfoElements struct {
	root    *descriptorxml.Element
	names   *descriptorxml.Element
	version *descriptorxml.Element
	build   *descriptorxml.Element
}

func parseApplicationInfo(content string, file string) (applicationInfoElements, error) {
	var elements applicationInfoElements
	root, err := descriptorxml.Read(content)
	if err != nil {
		return elements, fmt.Errorf("%s: %w", file, err)
	}
	elements.root = root
	for _, child := range []struct {
		name   string
		target **descriptorxml.Element
	}{
		{"names", &elements.names},
		{"version", &elements.version},
		{"build", &elements.build},
	} {
		element, found := singleChild(root, child.name, applicationInfoNamespace)
		if !found {
			return elements, fmt.Errorf("the application info has no unique %s element: %s", child.name, file)
		}
		*child.target = element
	}
	return elements, nil
}

// singleChild is `getChildren(name, namespace).singleOrNull()`: the one child element with this name in this namespace
// URI.
func singleChild(root *descriptorxml.Element, name string, uri string) (*descriptorxml.Element, bool) {
	var result *descriptorxml.Element
	count := 0
	for _, element := range root.ChildElements() {
		if element.Name == name && element.URI == uri {
			result = element
			count++
		}
	}
	return result, count == 1
}

func copyApplicationInfoAttribute(target *descriptorxml.Element, source *descriptorxml.Element, name string) {
	value, present := source.Attribute(name)
	replaceApplicationInfoAttribute(target, name, value, present)
}

func replaceApplicationInfoAttribute(element *descriptorxml.Element, name string, value string, present bool) {
	if present {
		element.SetAttribute(name, value)
	} else {
		element.RemoveAttribute(name)
	}
}

func parseApplicationInfoRequest(lines []string) (applicationInfoRequest, error) {
	var parsed applicationInfoRequest
	if err := requireMode(lines, applicationInfoMode); err != nil {
		return parsed, err
	}
	for _, line := range lines {
		if line == "" || line == applicationInfoMode {
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
		case "--client-application-info":
			parsed.clientApplicationInfo = value
		case "--product-application-info":
			parsed.productApplicationInfo = value
		case "--build-number":
			parsed.buildNumber = value
		default:
			return parsed, fmt.Errorf("unknown frontend application info option '%s'", option)
		}
	}
	return parsed, requireOptions([]struct{ option, value string }{
		{"--out", parsed.output},
		{"--client-application-info", parsed.clientApplicationInfo},
		{"--product-application-info", parsed.productApplicationInfo},
		{"--build-number", parsed.buildNumber},
	})
}

// parseOption reads an override option that both application info modes accept. It returns false for another option.
func (o *applicationInfoOverrides) parseOption(option string, value string) (bool, error) {
	switch option {
	case "--eap-override":
		o.eap = value
	case "--version-suffix-override":
		o.versionSuffix = value
	case "--nightly":
		if value != "" {
			return true, fmt.Errorf("--nightly is a flag")
		}
		o.nightly = true
	case "--branch-name":
		o.branchName = value
	default:
		return false, nil
	}
	return true, nil
}

func requireOptions(required []struct{ option, value string }) error {
	for _, r := range required {
		if r.value == "" {
			return fmt.Errorf("%s is required", r.option)
		}
	}
	return nil
}
